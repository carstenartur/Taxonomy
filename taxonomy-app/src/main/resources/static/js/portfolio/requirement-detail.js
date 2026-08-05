(function () {
    'use strict';

    const match = window.location.pathname.match(/^\/projects\/(\d+)\/requirements\/(\d+)$/);
    if (!match) return;
    const projectId = Number(match[1]);
    const requirementId = Number(match[2]);
    const locale = (new URLSearchParams(window.location.search).get('lang')
        || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const state = {
        project: null,
        requirement: null,
        versions: [],
        snapshots: [],
        selectedVersion: null,
        selectedSnapshot: null,
        snapshotDetail: null,
        portfolio: null,
        account: null,
        busy: 0
    };

    const text = {
        en: {
            loading: 'Loading…', portfolio: 'Portfolio', matrices: 'Matrices',
            detail: 'Requirement detail', analyze: 'Analyze current version',
            newVersion: 'New version', open: 'Open decisions', textSource: 'Text & source',
            versions: 'Versions', analyses: 'Analyses', architecture: 'Taxonomy & architecture',
            decisions: 'Decisions', solutions: 'Solutions & products', currentText: 'Current requirement text',
            source: 'Original source fragment', noSource: 'No source fragment is recorded.',
            noVersions: 'No versions are available.', noSnapshots: 'No analysis snapshots are available.',
            noMappings: 'No taxonomy mappings are available.', noRelations: 'No relation mappings are available.',
            noSolutions: 'No solution is currently linked to this requirement.',
            versionCreated: 'A new immutable version was created.', analysisQueued: 'Analysis was queued.',
            stale: 'The current requirement version has not been analysed.',
            unreviewed: 'Taxonomy mappings still require human review.',
            noConfirmedSolution: 'No confirmed solution covers this requirement.',
            sourceMissing: 'The current version has no source provenance.',
            allClear: 'No open decisions were derived from the current data.',
            provider: 'Provider', model: 'Model', created: 'Created', duration: 'Duration',
            baseline: 'Baseline', score: 'Score', relevance: 'Relevance', confidence: 'Confidence',
            origin: 'Origin', review: 'Review', action: 'Action', evidence: 'Evidence',
            node: 'Taxonomy node', relation: 'Relation', changeReason: 'Change reason',
            author: 'Author', page: 'Page', section: 'Section', sourceArtifact: 'Source artifact',
            snapshot: 'Snapshot', productCandidates: 'Product candidates', coverage: 'Coverage',
            selected: 'Selected', retry: 'Retry failed analysis', comparePrevious: 'Compare with previous version',
            added: 'Added', removed: 'Removed', unchanged: 'Unchanged', permission: 'Your role cannot change this requirement.',
            failed: 'The operation failed.'
        },
        de: {
            loading: 'Wird geladen…', portfolio: 'Portfolio', matrices: 'Matrizen',
            detail: 'Anforderungsdetails', analyze: 'Aktuelle Version analysieren',
            newVersion: 'Neue Version', open: 'Offene Entscheidungen', textSource: 'Text & Quelle',
            versions: 'Versionen', analyses: 'Analysen', architecture: 'Taxonomie & Architektur',
            decisions: 'Entscheidungen', solutions: 'Lösungen & Produkte', currentText: 'Aktueller Anforderungstext',
            source: 'Ursprüngliches Quellfragment', noSource: 'Es ist kein Quellfragment hinterlegt.',
            noVersions: 'Es sind keine Versionen verfügbar.', noSnapshots: 'Es sind keine Analyse-Snapshots verfügbar.',
            noMappings: 'Es sind keine Taxonomiezuordnungen verfügbar.', noRelations: 'Es sind keine Beziehungszuordnungen verfügbar.',
            noSolutions: 'Dieser Anforderung ist derzeit keine Lösung zugeordnet.',
            versionCreated: 'Eine neue unveränderliche Version wurde angelegt.', analysisQueued: 'Die Analyse wurde eingereiht.',
            stale: 'Die aktuelle Anforderungsversion wurde noch nicht analysiert.',
            unreviewed: 'Taxonomiezuordnungen benötigen noch eine menschliche Prüfung.',
            noConfirmedSolution: 'Keine bestätigte Lösung deckt diese Anforderung ab.',
            sourceMissing: 'Für die aktuelle Version fehlt die Quellenprovenienz.',
            allClear: 'Aus den aktuellen Daten wurden keine offenen Entscheidungen abgeleitet.',
            provider: 'Provider', model: 'Modell', created: 'Erstellt', duration: 'Laufzeit',
            baseline: 'Baseline', score: 'Score', relevance: 'Relevanz', confidence: 'Konfidenz',
            origin: 'Herkunft', review: 'Prüfung', action: 'Maßnahme', evidence: 'Evidenz',
            node: 'Taxonomieknoten', relation: 'Beziehung', changeReason: 'Änderungsgrund',
            author: 'Autor', page: 'Seite', section: 'Abschnitt', sourceArtifact: 'Quellartefakt',
            snapshot: 'Snapshot', productCandidates: 'Produktkandidaten', coverage: 'Abdeckung',
            selected: 'Ausgewählt', retry: 'Fehlgeschlagene Analyse wiederholen', comparePrevious: 'Mit vorheriger Version vergleichen',
            added: 'Hinzugefügt', removed: 'Entfernt', unchanged: 'Unverändert', permission: 'Ihre Rolle darf diese Anforderung nicht ändern.',
            failed: 'Die Operation ist fehlgeschlagen.'
        }
    };

    document.addEventListener('DOMContentLoaded', initialize);

    function t(key) { return (text[locale] && text[locale][key]) || text.en[key] || key; }

    async function initialize() {
        translateSurface();
        wireEvents();
        await loadAll();
        const requestedSnapshot = new URLSearchParams(window.location.search).get('snapshot');
        if (requestedSnapshot) await selectSnapshot(requestedSnapshot);
        else if (state.snapshots.length) await selectSnapshot(state.snapshots[0].id);
    }

    function translateSurface() {
        document.documentElement.lang = locale;
        document.title = t('detail') + ' — Taxonomy';
        document.querySelector('.skip-link').textContent = locale === 'de'
            ? 'Zu den Anforderungsdetails springen' : 'Skip to requirement detail';
        document.getElementById('pageTitle').textContent = t('detail');
        document.getElementById('portfolioBack').textContent = t('portfolio');
        document.getElementById('matrixLink').textContent = t('matrices');
        document.getElementById('reanalyzeRequirement').textContent = t('analyze');
        document.getElementById('newVersionButton').textContent = t('newVersion');
        document.getElementById('tasksHeading').textContent = t('open');
        setTabText('text-tab', t('textSource'));
        setTabText('versions-tab', t('versions'));
        setTabText('analyses-tab', t('analyses'));
        setTabText('architecture-tab', t('architecture'));
        setTabText('decisions-tab', t('decisions'));
        setTabText('solutions-tab', t('solutions'));
        document.querySelector('#textPane h2').textContent = t('currentText');
        document.querySelectorAll('#textPane h2')[1].textContent = t('source');
    }

    function setTabText(id, value) { document.getElementById(id).textContent = value; }

    function wireEvents() {
        document.getElementById('newVersionForm').addEventListener('submit', createVersion);
        document.getElementById('reanalyzeRequirement').addEventListener('click', analyzeRequirement);
        document.getElementById('versionList').addEventListener('click', event => {
            const button = event.target.closest('[data-version-id]');
            if (button) selectVersion(Number(button.dataset.versionId));
        });
        document.getElementById('snapshotList').addEventListener('click', event => {
            const button = event.target.closest('[data-snapshot-id]');
            if (button) selectSnapshot(button.dataset.snapshotId);
        });
    }

    async function loadAll() {
        setBusy(true);
        try {
            const [project, requirement, versions, snapshots, portfolio, account] = await Promise.all([
                api().getProject(projectId),
                api().getRequirement(projectId, requirementId),
                api().listRequirementVersions(projectId, requirementId),
                api().listRequirementSnapshots(projectId, requirementId),
                api().getProjectPortfolio(projectId),
                api().getAccount()
            ]);
            Object.assign(state, { project, requirement, versions, snapshots, portfolio, account });
            state.selectedVersion = requirement.currentVersion || versions[0] || null;
            renderAll();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function renderAll() {
        const requirement = state.requirement;
        document.getElementById('portfolioBack').href = `/projects?lang=${locale}`;
        document.getElementById('matrixLink').href = `/projects/${projectId}/matrices?lang=${locale}`;
        document.getElementById('requirementKey').textContent = requirement.requirementKey;
        document.getElementById('requirementStatus').textContent = humanize(requirement.status);
        document.getElementById('requirementReview').textContent = humanize(requirement.reviewStatus);
        document.getElementById('requirementHeading').textContent = requirement.title;
        document.getElementById('requirementMeta').textContent = [
            state.project.projectKey + ' — ' + state.project.title,
            humanize(requirement.requirementType),
            humanize(requirement.criticality),
            requirement.ownerUsername || '—'
        ].join(' · ');
        renderCurrentText();
        renderVersions();
        renderSnapshots();
        renderTasks();
        renderSolutions();
        applyCapabilities();
    }

    function renderCurrentText() {
        const version = state.requirement.currentVersion;
        document.getElementById('currentText').textContent = version ? version.text : '—';
        document.getElementById('versionText').value = version ? version.text : '';
        const source = version && version.source;
        const metadata = document.getElementById('sourceMetadata');
        metadata.textContent = '';
        if (!source) {
            document.getElementById('sourceText').textContent = t('noSource');
            return;
        }
        addDefinition(metadata, t('sourceArtifact'), source.sourceArtifactId || '—');
        addDefinition(metadata, t('section'), source.sectionReference || '—');
        addDefinition(metadata, t('page'), source.pageNumber || '—');
        document.getElementById('sourceText').textContent = source.originalText || t('noSource');
        document.getElementById('sourceSection').value = source.sectionReference || '';
        document.getElementById('sourcePage').value = source.pageNumber || '';
        document.getElementById('sourceOriginal').value = source.originalText || '';
    }

    function renderVersions() {
        const list = document.getElementById('versionList');
        list.textContent = '';
        if (!state.versions.length) return renderEmpty(list, t('noVersions'));
        state.versions.forEach(version => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'list-group-item list-group-item-action';
            button.dataset.versionId = version.id;
            button.innerHTML = `<div class="d-flex justify-content-between gap-2"><strong>v${version.versionNumber}</strong>`
                + `<span class="small">${escapeHtml(formatDate(version.createdAt))}</span></div>`
                + `<div class="small text-body-secondary">${escapeHtml(version.changeReason || '—')}</div>`;
            list.appendChild(button);
        });
        renderVersionDetail(state.selectedVersion);
    }

    function selectVersion(id) {
        state.selectedVersion = state.versions.find(version => version.id === id) || null;
        renderVersionDetail(state.selectedVersion);
    }

    function renderVersionDetail(version) {
        const target = document.getElementById('versionDetail');
        if (!version) return renderEmpty(target, t('noVersions'));
        const index = state.versions.findIndex(candidate => candidate.id === version.id);
        const previous = index >= 0 ? state.versions[index + 1] : null;
        target.innerHTML = `<div class="d-flex justify-content-between gap-2"><h2 class="h5">v${version.versionNumber}</h2>`
            + `<code>${escapeHtml(String(version.contentHash || '').slice(0, 16))}</code></div>`
            + `<dl class="portfolio-card-meta"><dt>${escapeHtml(t('author'))}</dt><dd>${escapeHtml(version.createdBy || '—')}</dd>`
            + `<dt>${escapeHtml(t('created'))}</dt><dd>${escapeHtml(formatDate(version.createdAt))}</dd>`
            + `<dt>${escapeHtml(t('changeReason'))}</dt><dd>${escapeHtml(version.changeReason || '—')}</dd></dl>`
            + `<pre class="border rounded p-3 bg-body-tertiary text-wrap mt-3">${escapeHtml(version.text)}</pre>`
            + (previous ? `<details class="mt-3"><summary>${escapeHtml(t('comparePrevious'))}</summary>`
                + `<div class="mt-2">${renderTextDiff(previous.text, version.text)}</div></details>` : '');
    }

    function renderTextDiff(older, newer) {
        const oldLines = new Set(String(older || '').split(/\r?\n/));
        const newLines = new Set(String(newer || '').split(/\r?\n/));
        const rows = [];
        oldLines.forEach(line => { if (!newLines.has(line)) rows.push(`<div class="text-danger">− ${escapeHtml(line)}</div>`); });
        newLines.forEach(line => rows.push(oldLines.has(line)
            ? `<div class="text-body-secondary">  ${escapeHtml(line)}</div>`
            : `<div class="text-success">+ ${escapeHtml(line)}</div>`));
        return `<div class="font-monospace small">${rows.join('')}</div>`;
    }

    function renderSnapshots() {
        const list = document.getElementById('snapshotList');
        list.textContent = '';
        if (!state.snapshots.length) {
            renderEmpty(list, t('noSnapshots'));
            renderEmpty(document.getElementById('snapshotDetail'), t('noSnapshots'));
            return;
        }
        state.snapshots.forEach(snapshot => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'list-group-item list-group-item-action';
            button.dataset.snapshotId = snapshot.id;
            button.innerHTML = `<div class="d-flex justify-content-between gap-2"><strong>v${snapshot.requirementVersionNumber}</strong>`
                + `<span class="badge ${statusClass(snapshot.status)}">${escapeHtml(humanize(snapshot.status))}</span></div>`
                + `<div class="small">${escapeHtml(formatDate(snapshot.createdAt))}</div>`;
            list.appendChild(button);
        });
    }

    async function selectSnapshot(id) {
        setBusy(true);
        try {
            state.selectedSnapshot = state.snapshots.find(snapshot => snapshot.id === id) || null;
            state.snapshotDetail = await api().getSnapshot(projectId, id);
            const url = new URL(window.location.href);
            url.searchParams.set('snapshot', id);
            url.searchParams.set('lang', locale);
            history.replaceState(null, '', url);
            renderSnapshotDetail();
            renderArchitecture();
            renderDecisions();
            renderTasks();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function renderSnapshotDetail() {
        const target = document.getElementById('snapshotDetail');
        const detail = state.snapshotDetail;
        if (!detail) return renderEmpty(target, t('noSnapshots'));
        const summary = detail.summary;
        target.innerHTML = `<div class="d-flex justify-content-between gap-2"><h2 class="h5">${escapeHtml(t('snapshot'))} v${summary.requirementVersionNumber}</h2>`
            + `<span class="badge ${statusClass(summary.status)}">${escapeHtml(humanize(summary.status))}</span></div>`
            + `<dl class="portfolio-card-meta"><dt>${escapeHtml(t('provider'))}</dt><dd>${escapeHtml(summary.provider || '—')}</dd>`
            + `<dt>${escapeHtml(t('model'))}</dt><dd>${escapeHtml(summary.modelName || '—')}</dd>`
            + `<dt>${escapeHtml(t('created'))}</dt><dd>${escapeHtml(formatDate(summary.createdAt))}</dd>`
            + `<dt>${escapeHtml(t('duration'))}</dt><dd>${escapeHtml(String(summary.durationMs))} ms</dd>`
            + `<dt>${escapeHtml(t('baseline'))}</dt><dd><code>${escapeHtml(String(summary.taxonomyFingerprint || '').slice(0, 16))}</code> / <code>${escapeHtml(String(summary.promptFingerprint || '').slice(0, 16))}</code></dd></dl>`
            + renderAnalysisSummary(detail);
    }

    function renderAnalysisSummary(detail) {
        const warnings = detail.analysis && detail.analysis.warnings || [];
        const gap = detail.gapAnalysis;
        const recommendation = detail.recommendation;
        return (warnings.length ? `<div class="alert alert-warning mt-3"><ul class="mb-0">${warnings.map(w => `<li>${escapeHtml(w)}</li>`).join('')}</ul></div>` : '')
            + (gap ? `<section class="mt-3"><h3 class="h6">Gap analysis</h3><p>${escapeHtml(gap.summary || gap.description || JSON.stringify(gap))}</p></section>` : '')
            + (recommendation ? `<section class="mt-3"><h3 class="h6">Recommendation</h3><p>${escapeHtml(recommendation.summary || recommendation.recommendation || JSON.stringify(recommendation))}</p></section>` : '');
    }

    function renderArchitecture() {
        const detail = state.snapshotDetail;
        const mappings = detail && detail.elementMappings || [];
        const relations = detail && detail.relationMappings || [];
        const summary = document.getElementById('architectureSummary');
        summary.innerHTML = `<div class="row row-cols-2 row-cols-md-4 g-2">`
            + metric(t('node'), mappings.length) + metric(t('relation'), relations.length)
            + metric(t('review'), mappings.filter(m => m.reviewStatus === 'CONFIRMED').length)
            + metric(t('open'), mappings.filter(m => m.reviewStatus === 'PROPOSED').length) + '</div>';
        renderMappings(mappings);
        renderRelations(relations);
    }

    function metric(label, value) {
        return `<div><div class="card h-100"><div class="card-body"><div class="h3">${value}</div><div class="small text-body-secondary">${escapeHtml(label)}</div></div></div></div>`;
    }

    function renderMappings(mappings) {
        const target = document.getElementById('mappingTable');
        if (!mappings.length) return renderEmpty(target, t('noMappings'));
        target.innerHTML = `<table class="table table-sm align-middle"><caption>${escapeHtml(t('architecture'))}</caption>`
            + `<thead><tr><th>${escapeHtml(t('node'))}</th><th>${escapeHtml(t('score'))}</th><th>${escapeHtml(t('relevance'))}</th>`
            + `<th>${escapeHtml(t('confidence'))}</th><th>${escapeHtml(t('origin'))}</th><th>${escapeHtml(t('review'))}</th><th>${escapeHtml(t('action'))}</th></tr></thead>`
            + `<tbody>${mappings.map(mapping => `<tr><td><code>${escapeHtml(mapping.nodeCode)}</code><div class="small">${escapeHtml(mapping.nodeTitle || '')}</div><div class="small text-body-secondary">${escapeHtml(mapping.hierarchyPath || '')}</div></td>`
                + `<td>${mapping.directScore}%</td><td>${Math.round(mapping.relevance * 100)}%</td><td>${Math.round(mapping.confidence * 100)}%</td>`
                + `<td>${escapeHtml(humanize(mapping.mappingOrigin))}</td><td>${escapeHtml(humanize(mapping.reviewStatus))}</td><td>${escapeHtml(humanize(mapping.actionStatus))}</td></tr>`).join('')}</tbody></table>`;
    }

    function renderRelations(relations) {
        const target = document.getElementById('relationTable');
        if (!relations.length) return renderEmpty(target, t('noRelations'));
        target.innerHTML = `<table class="table table-sm align-middle"><caption>${escapeHtml(t('relation'))}</caption>`
            + `<thead><tr><th>${escapeHtml(t('relation'))}</th><th>${escapeHtml(t('relevance'))}</th><th>${escapeHtml(t('confidence'))}</th><th>${escapeHtml(t('review'))}</th></tr></thead>`
            + `<tbody>${relations.map(relation => `<tr><td><code>${escapeHtml(relation.sourceCode)}</code> → <code>${escapeHtml(relation.targetCode)}</code><div class="small">${escapeHtml(relation.relationType)}</div></td>`
                + `<td>${Math.round(relation.relevance * 100)}%</td><td>${Math.round(relation.confidence * 100)}%</td><td>${escapeHtml(humanize(relation.reviewStatus))}</td></tr>`).join('')}</tbody></table>`;
    }

    function renderDecisions() {
        const target = document.getElementById('decisionList');
        target.textContent = '';
        const mappings = state.snapshotDetail && state.snapshotDetail.elementMappings || [];
        if (!mappings.length) return renderEmpty(target, t('noMappings'));
        mappings.forEach(mapping => {
            const card = document.createElement('article');
            card.className = 'card';
            card.innerHTML = `<div class="card-body"><div class="d-flex justify-content-between gap-2"><div><code>${escapeHtml(mapping.nodeCode)}</code> · <strong>${escapeHtml(mapping.nodeTitle || '')}</strong></div>`
                + `<span class="badge ${mapping.reviewStatus === 'CONFIRMED' ? 'text-bg-success' : 'text-bg-warning'}">${escapeHtml(humanize(mapping.reviewStatus))}</span></div>`
                + `<dl class="portfolio-card-meta mt-2"><dt>${escapeHtml(t('action'))}</dt><dd>${escapeHtml(humanize(mapping.actionStatus))}</dd>`
                + `<dt>${escapeHtml(t('evidence'))}</dt><dd>${escapeHtml(mapping.actionEvidence || '—')}</dd>`
                + `<dt>${escapeHtml(t('author'))}</dt><dd>${escapeHtml(mapping.decisionBy || '—')}</dd>`
                + `<dt>${escapeHtml(t('created'))}</dt><dd>${escapeHtml(formatDate(mapping.decisionAt))}</dd></dl></div>`;
            target.appendChild(card);
        });
    }

    function renderSolutions() {
        const target = document.getElementById('solutionList');
        target.textContent = '';
        const solutions = (state.portfolio && state.portfolio.solutions || []).filter(projectSolution =>
            (projectSolution.requirements || []).some(link => link.requirementId === requirementId));
        if (!solutions.length) return renderEmpty(target, t('noSolutions'));
        solutions.forEach(projectSolution => {
            const link = projectSolution.requirements.find(item => item.requirementId === requirementId);
            const column = document.createElement('div');
            column.className = 'col-12 col-xl-6';
            column.innerHTML = `<article class="card h-100"><div class="card-header d-flex justify-content-between gap-2"><strong>${escapeHtml(projectSolution.solution.title)}</strong>`
                + `<span class="badge ${projectSolution.status === 'SELECTED' ? 'text-bg-success' : 'text-bg-secondary'}">${escapeHtml(humanize(projectSolution.status))}</span></div>`
                + `<div class="card-body"><p>${escapeHtml(projectSolution.solution.description || '')}</p>`
                + `<dl class="portfolio-card-meta"><dt>${escapeHtml(t('coverage'))}</dt><dd>${link.coveragePercent}%</dd>`
                + `<dt>${escapeHtml(t('review'))}</dt><dd>${escapeHtml(humanize(link.reviewStatus))}</dd>`
                + `<dt>${escapeHtml(t('evidence'))}</dt><dd>${escapeHtml(link.evidence || '—')}</dd></dl>`
                + `<h3 class="h6 mt-3">${escapeHtml(t('productCandidates'))}</h3>`
                + `<ul class="list-group list-group-flush border rounded">${(projectSolution.productCandidates || []).map(candidate => `<li class="list-group-item"><strong>${escapeHtml(candidate.product.manufacturer + ' ' + candidate.product.productName)}</strong>`
                    + `<div class="small">${candidate.coveragePercent}% · ${escapeHtml(humanize(candidate.selectionStatus))}</div>`
                    + `<div class="small text-body-secondary">${escapeHtml(candidate.product.sourceReference)}</div></li>`).join('') || '<li class="list-group-item">—</li>'}</ul></div></article>`;
            target.appendChild(column);
        });
    }

    function renderTasks() {
        const target = document.getElementById('taskList');
        target.textContent = '';
        const tasks = [];
        if (!state.requirement.currentAnalysisSnapshotId) tasks.push(t('stale'));
        const mappings = state.snapshotDetail && state.snapshotDetail.elementMappings || [];
        if (mappings.some(mapping => mapping.reviewStatus !== 'CONFIRMED')) tasks.push(t('unreviewed'));
        const links = (state.portfolio && state.portfolio.solutions || []).flatMap(solution => solution.requirements || [])
            .filter(link => link.requirementId === requirementId && link.reviewStatus === 'CONFIRMED');
        if (!links.length) tasks.push(t('noConfirmedSolution'));
        if (!(state.requirement.currentVersion && state.requirement.currentVersion.source)) tasks.push(t('sourceMissing'));
        if (!tasks.length) tasks.push(t('allClear'));
        tasks.forEach(task => {
            const item = document.createElement('div');
            item.className = 'list-group-item';
            item.textContent = task;
            target.appendChild(item);
        });
    }

    async function createVersion(event) {
        event.preventDefault();
        setBusy(true);
        try {
            const sourcePage = document.getElementById('sourcePage').value;
            await api().createRequirementVersion(projectId, requirementId, {
                text: document.getElementById('versionText').value,
                changeReason: document.getElementById('changeReason').value,
                source: {
                    sourceArtifactId: state.requirement.currentVersion?.source?.sourceArtifactId || null,
                    sourceVersionId: state.requirement.currentVersion?.source?.sourceVersionId || null,
                    sourceFragmentIds: state.requirement.currentVersion?.source?.sourceFragmentIds || [],
                    sectionReference: document.getElementById('sourceSection').value || null,
                    pageNumber: sourcePage ? Number(sourcePage) : null,
                    originalText: document.getElementById('sourceOriginal').value || null
                }
            });
            bootstrap.Modal.getOrCreateInstance(document.getElementById('newVersionModal')).hide();
            showInfo(t('versionCreated'));
            await loadAll();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    async function analyzeRequirement() {
        setBusy(true);
        try {
            const response = await api().analyzeRequirement(projectId, requirementId, {
                provider: null,
                maxArchitectureNodes: 25,
                idempotencyKey: `detail:${requirementId}:${Date.now()}`
            });
            showInfo(t('analysisQueued'));
            const location = response.headers.get('Location');
            if (location) pollAnalysis(analysisJobId(location));
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function analysisJobId(location) {
        const match = new URL(location, window.location.href).pathname
            .match(/\/analysis-jobs\/([^/]+)$/);
        return match ? decodeURIComponent(match[1]) : null;
    }

    async function pollAnalysis(jobId) {
        if (!jobId) return;
        for (;;) {
            await new Promise(resolve => setTimeout(resolve, 1500));
            try {
                const job = await api().getAnalysisJob(projectId, jobId);
                document.getElementById('requirementLive').textContent = humanize(job.status);
                if (['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(job.status)) {
                    await loadAll();
                    if (job.items && job.items[0] && job.items[0].snapshotId) {
                        await selectSnapshot(job.items[0].snapshotId);
                    }
                    return;
                }
            } catch (error) {
                showError(error);
                return;
            }
        }
    }

    function applyCapabilities() {
        if (state.account && state.account.architectureMutationAllowed) return;
        const versionButton = document.getElementById('newVersionButton');
        versionButton.disabled = true;
        versionButton.title = t('permission');
    }

    function api() {
        if (!window.TaxonomyPortfolioApi) {
            throw new Error('Portfolio API boundary is not available');
        }
        return window.TaxonomyPortfolioApi;
    }

    function addDefinition(target, term, value) {
        const dt = document.createElement('dt'); dt.textContent = term;
        const dd = document.createElement('dd'); dd.textContent = value;
        target.append(dt, dd);
    }

    function renderEmpty(target, message) {
        target.textContent = '';
        const paragraph = document.createElement('p');
        paragraph.className = 'text-body-secondary mb-0 p-3';
        paragraph.textContent = message;
        target.appendChild(paragraph);
    }

    function setBusy(active) {
        state.busy += active ? 1 : -1;
        state.busy = Math.max(0, state.busy);
        document.getElementById('detailBusy').classList.toggle('d-none', state.busy === 0);
    }

    function showError(error) {
        const target = document.getElementById('detailError');
        target.textContent = error?.message || t('failed');
        target.classList.remove('d-none');
        target.focus();
    }

    function showInfo(message) {
        const target = document.getElementById('detailInfo');
        target.textContent = message;
        target.classList.remove('d-none');
        document.getElementById('requirementLive').textContent = message;
    }

    function humanize(value) {
        return String(value || '—').toLowerCase().replaceAll('_', ' ')
            .replace(/\b\w/g, character => character.toUpperCase());
    }

    function statusClass(status) {
        if (['SUCCESS', 'CONFIRMED', 'ACTIVE', 'SELECTED'].includes(status)) return 'text-bg-success';
        if (['FAILED', 'REJECTED', 'CANCELLED'].includes(status)) return 'text-bg-danger';
        if (['PARTIAL', 'PROPOSED', 'DRAFT', 'PENDING'].includes(status)) return 'text-bg-warning';
        return 'text-bg-secondary';
    }

    function formatDate(value) {
        if (!value) return '—';
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(locale);
    }

    function escapeHtml(value) { return window.TaxonomyUtils.escapeHtml(value); }
})();
