/* Read-only catalogue preview. The full analysis and its saved scores have their own lifecycle. */
(function () {
    'use strict';
    const byId = id => document.getElementById(id);
    const panel = byId('analysisPreview');
    if (!panel) return;
    const input = byId('businessText');
    const status = byId('analysisPreviewStatus');
    const results = byId('analysisPreviewResults');
    const tree = byId('analysisPreviewTree');
    const associations = byId('analysisPreviewAssociations');
    const retry = byId('analysisPreviewRetry');
    const t = (key, ...args) => window.TaxonomyI18n.t('analysis.preview.' + key, ...args);
    let page = window.location.hash.slice(1) || 'analyze';
    let timer, controller, requestKey;
    let generation = 0, basis, owner, confirmed, localStatus, accountAllowed = false;
    const action = byId('analysisPreviewRefine'), refinement = byId('analysisPreviewRefinement');
    const architectureLink = byId('analysisPreviewArchitectureLink');
    const components = byId('analysisPreviewComponents');
    const assessedLabels = new Map();
    const componentGroups = new Map();
    let layoutSource;
    const decimal = new Intl.NumberFormat(document.documentElement.lang, {maximumFractionDigits:1});

    function assessmentLabel(code) {
        const span = element('span', '', 'analysis-preview-assessed ms-2');
        if (!assessedLabels.has(code)) assessedLabels.set(code, []);
        assessedLabels.get(code).push(span);
        return span;
    }

    function position(parent, child, index) {
        if (parent.children[index] === child) return;
        const before = parent.children[index];
        if (before) parent.insertBefore(child, before);
        else parent.append(child);
    }

    function componentLayout(nodes, byCode, source) {
        if (!components) return;
        const groups = new Map();
        const candidates = nodes.filter(node => node.architectureCandidate === true && node.score != null);
        candidates.sort((a,b) => a.taxonomyRoot.localeCompare(b.taxonomyRoot)
            || (a.sourceOrder ?? Infinity) - (b.sourceOrder ?? Infinity) || a.code.localeCompare(b.code));
        for (const node of candidates) {
            if (!groups.has(node.taxonomyRoot)) groups.set(node.taxonomyRoot, []);
            groups.get(node.taxonomyRoot).push(node);
        }
        const selected = JSON.stringify([window.TaxonomyArchitectureWorkflow?.getScope?.(),
            source.catalogueRevision, source.commit, source.semanticRevision]);
        if (selected !== layoutSource) {
            components.replaceChildren();
            componentGroups.clear();
            layoutSource = selected;
        }
        for (const [root, group] of componentGroups) {
            if (!groups.has(root)) { group.section.remove(); componentGroups.delete(root); }
        }
        if (!groups.size) { components.textContent = t('noComponents'); return; }
        if (!componentGroups.size) components.replaceChildren();
        let column = 0;
        for (const [root, entries] of groups) {
            let group = componentGroups.get(root);
            if (!group) {
                group = {section:element('section'), list:element('ul'), cards:new Map()};
                group.section.append(element('h3', byCode.has(root) ? label(byCode.get(root)) : root, 'h6'), group.list);
                componentGroups.set(root, group);
            }
            position(components, group.section, column++);
            const retained = new Set(entries.map(node => node.code));
            for (const [code, card] of group.cards) {
                if (!retained.has(code)) { card.row.remove(); group.cards.delete(code); }
            }
            entries.forEach((node, index) => {
                let card = group.cards.get(node.code);
                if (!card) {
                    card = {row:element('li'), local:element('small'), assessed:element('small', '', 'analysis-preview-assessed')};
                    card.row.dataset.code = node.code;
                    card.row.append(element('span',label(node),'d-block'), card.local, card.assessed);
                    group.cards.set(node.code,card);
                }
                card.local.textContent = t('localScore', decimal.format(node.score));
                card.assessed.textContent = '';
                assessedLabels.get(node.code).push(card.assessed);
                position(group.list, card.row, index);
            });
        }
    }

    function currentBasis() {
        const account = window.TaxonomyRoleSurface?.getContext?.();
        return JSON.stringify([input.value.trim(), window.TaxonomyAnalysisScope.read(),
            window.TaxonomyAnalysisSession?.state?.().workspaceId, account?.username, account?.analysisExecutionAllowed,
            window.TaxonomyArchitectureWorkflow?.getScope?.(), window.location.search,
            window.TaxonomyArchitectureLifecycle?.selectedAnalysisModel?.(), byId('providerSelect')?.value,
            Boolean(byId('interactiveMode')?.checked), Boolean(byId('includeArchitectureView')?.checked),
            window.TaxonomyContentLanguage?.analysis?.()]);
    }

    function clearDetail() {
        const prior = owner; owner = null; prior?.abort();
        confirmed = null;
        assessedLabels.clear();
        if (action) { action.disabled = true; action.hidden = true; }
        if (architectureLink) architectureLink.hidden = true;
        if (refinement) refinement.textContent = '';
    }

    function providerLabel() {
        const picker = byId('providerSelect');
        return picker?.selectedOptions?.[0]?.textContent?.trim() || picker?.value || t('serverDefault');
    }

    function actionStatus() {
        if (!action || !confirmed || owner) return;
        const session = window.TaxonomyAnalysisSession?.state?.();
        const standard = byId('analyzeBtn'), recovery = window.TaxonomyAnalysisRecovery;
        const manual = byId('providerSelect')?.value === 'MANUAL' || byId('interactiveMode')?.checked;
        const available = accountAllowed && session?.ready && session.workspaceId
            && !session.conflict && !session.restoring && recovery?.startManagedAnalysis && standard && !standard.disabled
            && window.TaxonomyStartupProgress?.analysisReady?.() !== false;
        const plan = available && !manual ? recovery.startPlan(true) : null;
        const oldScores = window.TaxonomyState?.currentScores;
        // A new run clears the previous display. Keep that choice in the ordinary review workflow.
        const replaces = plan?.kind === 'NEW' && oldScores && Object.keys(oldScores).length > 0;
        const admitted = plan && ['NEW','EXTEND','RELATIONS'].includes(plan.kind) && !replaces;
        action.hidden = false;
        action.disabled = !admitted;
        refinement.textContent = t(manual ? 'manual' : !available ? 'unavailableRun' : replaces ? 'retained'
            : admitted ? 'ready' : 'review', providerLabel());
    }

    function beginDetail() {
        actionStatus();
        if (action.disabled || owner || !confirmed || currentBasis() !== basis) return;
        const source = confirmed, selected = basis, lifetime = new AbortController();
        const candidates = source.nodes.map(node => ({node, labels:assessedLabels.get(node.code) || []}));
        let operation, cancelRun, finishRecovered, canonicalView, accepted = false, terminal = false, awaitingReview = false;
        const job = {
            signal:lifetime.signal,
            pins:{expectedCommit:source.commit, expectedSemanticRevision:source.semanticRevision,
                expectedCatalogueRevision:source.catalogueRevision},
            current() {
                if (lifetime.signal.aborted || owner !== job || selected !== basis) return false;
                try { return selected === currentBasis(); } catch (_) { return false; }
            },
            canOpenArchitecture() { return job.current() && terminal && canonicalView
                    && canonicalView === window.TaxonomyState?.currentArchView; },
            bind(id, cancel, finish) {
                if (!job.current() || operation) return false;
                operation = id; cancelRun = cancel; finishRecovered = finish;
                return true;
            },
            hasAcceptedResult() { return accepted; },
            rejected() {
                if (!job.current() || terminal) return;
                terminal = true;
                refinement.textContent = t('failed');
                finishRecovered?.('REJECTED');
            },
            retainLocalEvidence(status) {
                if (!job.current() || terminal || awaitingReview) return;
                awaitingReview = true;
                finishRecovered?.(status);
                refinement.textContent = t('review');
                architectureLink.hidden = true;
            },
            acceptsRecovered(result) {
                return job.current() && Boolean(operation) && (!result?.analysisOperationId || result.analysisOperationId === operation);
            },
            reconcile(result) {
                if (!job.acceptsRecovered(result) || terminal) return;
                // The authenticated continuation journal has already admitted and hydrated this result.
                job.present(operation, {status:result.status, scores:result.effectiveScores || result.scores,
                    architecture:Boolean(result.architectureView)});
                finishRecovered?.(result.status);
            },
            // A scoped presentation callback. Only the managed scorer that bound this operation may publish.
            present(id, update) {
                if (!job.current() || id !== operation || terminal) return;
                if (awaitingReview && ['IN_PROGRESS','CHECKING'].includes(update.status)) return;
                if (update.status === 'CHECKING') { refinement.textContent = t('checking'); return; }
                if (update.status === 'IN_PROGRESS') refinement.textContent = t('running', providerLabel());
                for (const {node, labels} of candidates) {
                    let score = update.scores?.[node.code];
                    if (update.status === 'IN_PROGRESS') {
                        const detail = update.details?.[node.code];
                        const kind = node.analysisRole === 'PRODUCT' ? 'PRODUCT_SUITABILITY'
                            : node.parentCode ? 'HIERARCHICAL_RELEVANCE' : 'ROOT_RELEVANCE';
                        // A previous draft can have a different catalogue. Only present live interpretations
                        // matching the role and direct parent of this exact preview; final scores are server-typed.
                        if (detail?.kind !== kind || (detail.parentCode ?? null) !== (node.parentCode ?? null)
                            || detail.effectiveRelevance !== score) score = null;
                    }
                    for (const span of labels) span.textContent = Number.isFinite(score) ? t('assessed',decimal.format(score)) : '';
                }
                if (update.status === 'IN_PROGRESS') return;
                terminal = true; accepted = true;
                canonicalView = update.architecture ? window.TaxonomyState?.currentArchView : null;
                const statusKey = {SUCCESS:'success', PARTIAL:'partial', CANCELLED:'cancelled'}[update.status] || 'failed';
                refinement.textContent = t(statusKey) + (update.architecture ? ' ' + t('relationships') : '');
                architectureLink.hidden = !canonicalView;
            },
            abort() {
                lifetime.abort();
                if (!terminal && !awaitingReview) cancelRun?.();
            }
        };
        owner = job; action.disabled = true; retry.hidden = true; refinement.textContent = t('running', providerLabel());
        const start = window.TaxonomyAnalysisRecovery.startManagedAnalysis(job);
        function settled() {
            if (!job.current() || terminal) return;
            // A bound operation may still be running after a lost START response.
            if (operation) return;
            owner = null;
            actionStatus();
            refinement.textContent = t('review');
        }
        Promise.resolve(start).then(settled, settled);
    }

    function element(tag, text, className) {
        const node = document.createElement(tag);
        if (text != null) node.textContent = text;
        if (className) node.className = className;
        return node;
    }

    function label(node) {
        const german = document.documentElement.lang.toLowerCase().startsWith('de');
        const name = german ? node.nameDe || node.nameEn : node.nameEn || node.nameDe;
        return name && name !== node.code ? name + ' (' + node.code + ')' : node.code;
    }

    function renderTree(nodes) {
        const points = new Intl.NumberFormat(document.documentElement.lang, {maximumFractionDigits:1});
        const byCode = new Map(nodes.map(node => [node.code, node]));
        const children = new Map();
        for (const node of nodes) {
            const parent = byCode.has(node.parentCode) ? node.parentCode : null;
            if (!children.has(parent)) children.set(parent, []);
            children.get(parent).push(node);
        }
        // Put the path to the strongest match first at every level of a wide catalogue.
        const strongest = new Map();
        for (const hit of nodes) {
            let node = hit;
            while (node && hit.score != null && (strongest.get(node.code) ?? -1) < hit.score) {
                strongest.set(node.code, hit.score);
                node = byCode.get(node.parentCode);
            }
        }
        for (const group of children.values()) group.sort((a,b) => strongest.get(b.code) - strongest.get(a.code));
        function list(parent) {
            const group = element('ul');
            for (const node of children.get(parent) || []) {
                const item = element('li');
                item.append(element('span', label(node)));
                if (node.score != null) {
                    const score = node.score > 0 && node.score < 0.1 ? '<' + points.format(0.1) : points.format(node.score);
                    item.append(element('span', ' ' + t('localScore',score), 'analysis-preview-score'));
                }
                item.append(assessmentLabel(node.code));
                if (children.has(node.code)) item.append(list(node.code));
                group.append(item);
            }
            return group;
        }
        tree.replaceChildren(list(null));
        return byCode;
    }

    function render(data, roots) {
        if (!['CATALOGUE_LEXICAL', 'CATALOGUE_ONNX'].includes(data.method) || !Array.isArray(data.nodes) || !Array.isArray(data.associations)) {
            throw new Error('Unsupported catalogue preview');
        }
        const count = data.nodes.filter(node => node.score != null).length;
        retry.hidden = data.method !== 'CATALOGUE_LEXICAL';
        status.textContent = t(data.method === 'CATALOGUE_ONNX' ? 'semantic' : 'lexical') + ' '
            + (count ? t('matched', count) : t('noMatches'));
        localStatus = status.textContent;
        results.hidden = !count;
        confirmed = data;
        actionStatus();
        assessedLabels.clear();
        if (!count) return;
        const byCode = renderTree(data.nodes);
        componentLayout(data.nodes, byCode, data);
        associations.replaceChildren();
        if (data.associations.length) {
            const list = element('ul', null, 'ps-3 mb-0');
            for (const pair of data.associations) {
                const source = byCode.get(pair.sourceCode), target = byCode.get(pair.targetCode);
                const item = element('li', t('pair', label(source), label(target)));
                item.append(element('small', t('sharedTerms', pair.sharedTerms.join(', ')), 'd-block text-body-secondary'));
                list.append(item);
            }
            associations.append(list);
        } else {
            associations.textContent = t(roots.length === 1 ? 'singleRoot' : 'noAssociations');
        }
        const revision = data.catalogueRevision;
        const source = [t('catalogue', /^[0-9a-f]{64}$/.test(revision) ? revision.slice(0,12) : revision)];
        if (data.commit) source.push(t('commit', data.commit.slice(0, 12)));
        if (data.semanticRevision != null) source.push(t('revision', data.semanticRevision));
        byId('analysisPreviewSource').textContent = source.join(' · ');
        byId('analysisPreviewSource').title = [revision,data.commit].filter(Boolean).join(' · ');
    }

    function cancel() {
        if (!owner) clearDetail();
        generation++;
        clearTimeout(timer);
        controller?.abort();
        controller = null;
        requestKey = null;
        results.hidden = true;
        retry.hidden = true;
        status.textContent = '';
    }

    function refresh(force) {
        const text = input.value.trim();
        let nextBasis;
        try { nextBasis = currentBasis(); } catch (_) { nextBasis = null; }
        if (nextBasis !== basis) { clearDetail(); basis = nextBasis; }
        if (!panel.open || page !== 'analyze') {
            cancel();
            return;
        }
        if (owner && confirmed && accountAllowed) {
            status.textContent = localStatus;
            results.hidden = !confirmed.nodes.some(node => node.score != null);
            return;
        }
        let scope;
        try {
            scope = window.TaxonomyAnalysisScope.read();
        } catch (failure) {
            cancel();
            status.textContent = failure.message;
            return;
        }
        const lifecycle = window.TaxonomyArchitectureLifecycle;
        const selection = () => JSON.stringify([window.TaxonomyArchitectureWorkflow?.getScope?.(),
            window.location.search, lifecycle?.selectedAnalysisModel?.()]);
        const selectedQuery = window.location.search;
        const requestedModel = lifecycle?.selectedAnalysisModel?.() || {};
        const key = JSON.stringify([basis, selection()]);
        if (key === requestKey && !force) return;
        cancel();
        requestKey = key;
        const ticket = generation;
        status.textContent = t(text ? 'loading' : 'empty');
        timer = setTimeout(async function () {
            controller = new AbortController();
            try {
                const roles = window.TaxonomyRoleSurface;
                if (roles) await roles.ready;
                const account = roles ? roles.getContext() : await window.TaxonomyApiClient.getAccountContext();
                if (ticket !== generation) return;
                accountAllowed = account?.analysisExecutionAllowed === true;
                if (!accountAllowed) {
                    clearDetail();
                    status.textContent = t('permission');
                    return;
                }
                if (!lifecycle?.ready || !window.TaxonomyArchitectureWorkspace?.ready || !window.TaxonomyArchitectureWorkflow?.getScope) {
                    throw Object.assign(new Error('Catalogue selection is initializing'), {status:409});
                }
                const overview = await lifecycle.ready();
                if (ticket !== generation) return;
                const source = selection();
                const model = lifecycle?.selectedAnalysisModel?.() || {};
                const pin = new URLSearchParams(selectedQuery);
                if (pin.has('commit') && pin.get('commit') !== model.expectedCommit
                    || pin.has('revision') && pin.get('revision') !== String(model.expectedSemanticRevision)
                    || requestedModel.expectedCommit && requestedModel.expectedCommit !== model.expectedCommit
                    || requestedModel.expectedSemanticRevision != null && requestedModel.expectedSemanticRevision !== model.expectedSemanticRevision) {
                    throw Object.assign(new Error('Selected model unavailable'), {status:409});
                }
                if (!overview && !model.expectedCommit && model.expectedSemanticRevision == null) {
                    // The Analyze tab suspends the editor lifecycle. Only its own confirmed
                    // repository, workspace and branch may then select the current catalogue.
                    const workspace = window.TaxonomyArchitectureWorkspace;
                    const ready = await workspace?.ready?.();
                    const retained = ready && await workspace.analysisScope();
                    if (ticket !== generation) return;
                    const visible = window.TaxonomyArchitectureWorkflow?.getScope?.();
                    if (!retained?.repositoryId || retained.workspaceId == null || !retained.branch
                        || ['repositoryId','workspaceId','branch'].some(key => retained[key] !== visible?.[key])
                        || source !== selection()) {
                        throw Object.assign(new Error('Analysis selection unavailable'), {status:409});
                    }
                }
                const payload = Object.assign({taxonomyRoots:scope.taxonomyRoots}, model);
                if (!text) {
                    // An empty, open preview can prepare catalogue vectors while the user writes.
                    await window.AnalysisApi.preparePreview(payload, controller.signal);
                    return;
                }
                payload.businessText = text;
                const data = await window.AnalysisApi.preview(payload, controller.signal);
                if (ticket !== generation) return;
                if (source !== selection() || text !== input.value.trim()) { refresh(true); return; }
                if (model.expectedCommit && model.expectedCommit !== data.commit
                    || model.expectedSemanticRevision != null && model.expectedSemanticRevision !== data.semanticRevision) {
                    throw Object.assign(new Error('Preview source changed'), {status:409});
                }
                render(data, scope.taxonomyRoots);
            } catch (failure) {
                if (ticket !== generation || !text) return;
                clearDetail();
                results.hidden = true;
                const reason = failure.status === 400 ? 'invalid' : failure.status === 401 || failure.status === 403
                    ? (failure.status === 401 ? 'signedOut' : 'denied') : [404,409,503].includes(failure.status) ? 'unavailable' : 'error';
                status.textContent = t(reason);
                retry.hidden = false;
            } finally {
                if (ticket === generation) controller = null;
            }
        }, text ? 300 : 0);
    }

    action?.addEventListener('click', beginDetail);
    architectureLink?.addEventListener('click', () => {
        if (!owner?.canOpenArchitecture() || architectureLink.hidden) return;
        const scope = window.TaxonomyArchitectureWorkflow?.getScope?.();
        const workspace = window.TaxonomyArchitectureWorkspace;
        workspace?.open?.('analysis', {source:scope}).catch(() => {
            if (owner?.current()) refinement.textContent = t('openFailed');
        });
    });
    for (const id of ['providerSelect','interactiveMode','includeArchitectureView']) byId(id)?.addEventListener('change', () => refresh());
    if (action && byId('analyzeBtn') && typeof MutationObserver !== 'undefined')
        new MutationObserver(actionStatus).observe(byId('analyzeBtn'), {attributes:true, attributeFilter:['disabled']});
    document.addEventListener('taxonomy:content-language-changed', () => refresh());
    document.addEventListener('taxonomy:analysis-recovery-updated', () => {
        actionStatus();
        if (owner && architectureLink && !owner.canOpenArchitecture()) architectureLink.hidden = true;
    });
    for (const event of ['taxonomy:analysis-evidence-imported','taxonomy:analysis-invalidated','taxonomy:analysis-draft-reset',
        'taxonomy:analysis-manual-edit']) document.addEventListener(event, () => { clearDetail(); refresh(true); });
    input.addEventListener('input', () => refresh());
    panel.addEventListener('toggle', () => refresh());
    retry.addEventListener('click', () => refresh(true));
    document.addEventListener('taxonomy:page-activated', event => { page = event.detail.page; refresh(); });
    for (const name of ['taxonomy:analysis-selection-changed', 'taxonomy:analysis-draft-restored',
        'taxonomy:analysis-source-ready', 'taxonomy:repository-context-changed']) {
        document.addEventListener(name, () => refresh());
    }
    document.addEventListener('taxonomy:entity-context-changed', () => refresh());

    document.addEventListener('taxonomy:roles-ready', () => {
        if (window.TaxonomyRoleSurface?.getContext?.()?.analysisExecutionAllowed !== true) { accountAllowed = false; clearDetail(); }
        refresh(true);
    });
    document.addEventListener('DOMContentLoaded', () => {
        window.TaxonomyArchitectureLifecycle?.onStandChange?.(() => refresh());
        refresh();
    });
}());
