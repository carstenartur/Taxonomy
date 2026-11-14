/* One display preference; ownership data always belongs to the source being viewed. */
window.TaxonomyResponsibilityDisplay = (function () {
    'use strict';
    const preference = 'taxonomy.showResponsibilities';
    const palette = ['#0072B2', '#D55E00', '#00875A', '#8E579A', '#896A00', '#007E87', '#94534B', '#5264A2'];
    let enabled = false;
    try { enabled = window.sessionStorage.getItem(preference) === 'true'; } catch (_) { /* optional preference */ }
    let analysis = null;
    const t = (...args) => window.TaxonomyI18n.t(...args);

    function unit(id, name, sourceId) {
        // Same UTF-16 hash and palette as the report renderer.
        let hash = 0;
        for (let i = 0; i < id.length; i++) hash = (Math.imul(31, hash) + id.charCodeAt(i)) | 0;
        return {unitId:id, name, sourceId, color:palette[(hash % palette.length + palette.length) % palette.length]};
    }

    function fromEditor(view) {
        const names = new Map((view.model.organizations || []).map(org => [org.id, org.title]));
        const result = new Map();
        for (const [code, row] of Object.entries(view.hierarchy || {})) {
            const name = names.get(row.organizationId) || row.legacyOrganization;
            if (name && row.sourceElementId) {
                result.set(code, unit(row.organizationId || 'legacy:' + name, name, row.sourceElementId));
            }
        }
        return result;
    }

    function selection() {
        const state = window.TaxonomyState, session = window.TaxonomyAnalysisSession?.state();
        if (!state || !session?.ready) return null;
        const workspace = session.workspaceId || '__shared__', b = state.currentModelBinding;
        const chrome = window.TaxonomyAppChrome?.getContext()?.architecture || {};
        const navigation = window.TaxonomyContextBar;
        const generation = navigation?.getSelectionGeneration?.() || 0;
        if (b) {
            if (b.workspaceScopeKey !== workspace || !b.repositoryId || !b.branch || !b.modelSha256) return null;
            if ((chrome.repositoryId && chrome.repositoryId !== b.repositoryId)
                    || (chrome.branch && chrome.branch !== b.branch)
                    || (chrome.workspaceScopeKey && chrome.workspaceScopeKey !== workspace)) return null;
            const pin = {expectedCommit:b.commit, expectedSemanticRevision:b.semanticRevision, modelSha256:b.modelSha256,
                catalogueRevision:b.executionCatalogueRevision || b.catalogueRevision};
            return {key:JSON.stringify([b.repositoryId, workspace, b.branch, generation, pin]), scope:b, pin};
        }
        // Imported and historical results without evidence cannot use the current workspace's units.
        if (state.lastAnalyzedText != null || state.lastAnalysisStatus || state.analysisOperationId
                || Object.keys(state.currentScores || {}).length) return null;
        return {key:JSON.stringify(['live', workspace, generation, chrome.repositoryId, chrome.branch, chrome.revision, chrome.commit]),
            workspace, pin:{live:true}};
    }

    function updateControls() {
        for (const control of document.querySelectorAll('[data-responsibility-control]')) {
            control.querySelector('input').checked = enabled;
            const status = control.querySelector('small');
            status.textContent = enabled && control.dataset.responsibilityControl === 'analysis' && analysis?.message
                ? t(analysis.message) : '';
        }
    }

    function announce() {
        updateControls();
        document.dispatchEvent(new CustomEvent('taxonomy:responsibility-display-changed'));
    }

    document.addEventListener?.('taxonomy:repository-context-changed', function (event) {
        if (!enabled || !event.detail?.selectionChanged) return;
        analysis = null;
        announce();
    });

    function setEnabled(value) {
        if (enabled === Boolean(value)) return;
        enabled = Boolean(value);
        analysis = null;
        try { window.sessionStorage.setItem(preference, String(enabled)); } catch (_) { /* optional preference */ }
        announce();
    }

    function control(forEditor) {
        const label = document.createElement('label'), box = document.createElement('input');
        label.className = 'responsibility-display-control';
        label.dataset.responsibilityControl = forEditor === true ? 'editor' : 'analysis';
        box.type = 'checkbox'; box.checked = enabled;
        box.className = 'responsibility-display-toggle';
        box.addEventListener('change', () => setEnabled(box.checked));
        const title = document.createElement('span'); title.textContent = t('responsibility.show');
        const status = document.createElement('small'); status.setAttribute('role', 'status');
        if (enabled && !forEditor && analysis?.message) status.textContent = t(analysis.message);
        label.append(box, title, status);
        return label;
    }

    function bindAnalysis() {
        if (!enabled) return;
        const selected = selection();
        if (analysis && analysis.key === (selected?.key || '')) return analysis.request;
        const current = {key:selected?.key || '', entries:new Map(), message:selected ? 'responsibility.loading' : 'responsibility.unbound'};
        analysis = current;
        updateControls();
        if (!selected) return;
        current.request = (async () => {
            try {
                const scope = selected.scope || await window.TaxonomyArchitectureWorkspace.analysisScope();
                if (analysis !== current) return;
                if (!selected.scope && scope.workspaceScopeKey !== selected.workspace) {
                    throw new Error('Workspace changed');
                }
                const values = await window.ArchitectureEditorApi.analysisResponsibilities(scope, selected.pin);
                if (analysis !== current) return;
                for (const [code, value] of Object.entries(values)) {
                    if (value?.unitId && value.name && value.sourceId) {
                        current.entries.set(code, unit(value.unitId, value.name, value.sourceId));
                    }
                }
                current.message = current.entries.size ? null : 'responsibility.none';
            } catch (_) {
                if (analysis !== current) return;
                current.message = 'responsibility.unavailable';
            }
            if (selection()?.key !== current.key) {
                bindAnalysis();
            }
            announce();
        })();
        return current.request;
    }

    return {enabled:() => enabled, setEnabled, control, bindAnalysis, fromEditor,
        entry:code => enabled && analysis?.entries.get(code) || null};
}());
