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
    let generation = 0;

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
                    item.append(element('span', ' ' + score + '/100', 'analysis-preview-score'));
                }
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
        results.hidden = !count;
        if (!count) return;
        const byCode = renderTree(data.nodes);
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
        if (!panel.open || page !== 'analyze') {
            cancel();
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
        const key = JSON.stringify([text, scope.taxonomyRoots, selection()]);
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
                if (account?.analysisExecutionAllowed !== true) {
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

    input.addEventListener('input', () => refresh());
    panel.addEventListener('toggle', () => refresh());
    retry.addEventListener('click', () => refresh(true));
    document.addEventListener('taxonomy:page-activated', event => { page = event.detail.page; refresh(); });
    for (const name of ['taxonomy:analysis-selection-changed', 'taxonomy:analysis-draft-restored',
        'taxonomy:analysis-source-ready', 'taxonomy:repository-context-changed']) {
        document.addEventListener(name, () => refresh());
    }
    document.addEventListener('taxonomy:entity-context-changed', () => refresh(true));
    document.addEventListener('taxonomy:roles-ready', () => refresh(true));
    document.addEventListener('DOMContentLoaded', () => {
        window.TaxonomyArchitectureLifecycle?.onStandChange?.(() => refresh());
        refresh();
    });
}());
