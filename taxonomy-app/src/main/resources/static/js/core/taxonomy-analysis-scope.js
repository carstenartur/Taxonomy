/* Explicit next-run controls; completed evidence retains its own frozen scope. */
(function () {
    'use strict';
    var S = window.TaxonomyState;
    var selection = null; // null = all; [] = explicitly none (invalid to run, valid to save)
    var mode = 'FULL';
    function t(key) { return TaxonomyI18n.t(key); }
    function inputs() {
        var container = document.getElementById('analysisTaxonomyRoots');
        return container ? Array.from(container.querySelectorAll('input[data-taxonomy-root]')) : [];
    }
    function syncMode() {
        var control = document.getElementById('analysisMode');
        if (control) mode = control.value || 'FULL';
        var architecture = document.getElementById('includeArchitectureView');
        if (architecture) architecture.disabled = mode === 'TAXONOMIES_ONLY';
    }
    function render(tree) {
        var container = document.getElementById('analysisTaxonomyRoots');
        if (!container) return;
        container.replaceChildren();
        var german = (document.documentElement.lang || '').startsWith('de');
        (tree || []).forEach(function (root) {
            var label = document.createElement('label');
            label.className = 'form-check form-check-inline me-3';
            var input = document.createElement('input');
            input.type = 'checkbox'; input.className = 'form-check-input';
            input.id = 'analysisRoot-' + root.code;
            input.dataset.taxonomyRoot = root.code;
            input.checked = selection === null || selection.includes(root.code);
            var caption = document.createElement('span');
            caption.className = 'form-check-label';
            caption.textContent = root.code + ' – ' + ((german ? root.nameDe || root.nameEn : root.nameEn || root.nameDe) || root.name || root.code);
            label.append(input, caption); container.append(label);
        });
        syncMode(); renderEvidence();
    }
    function captureSelection() {
        var choices = inputs();
        var chosen = choices.filter(function (input) { return input.checked; })
            .map(function (input) { return input.dataset.taxonomyRoot; }).sort();
        selection = choices.length && chosen.length === choices.length ? null : chosen;
    }
    function read() {
        syncMode();
        if (!document.getElementById('analysisTaxonomyRoots')) return {taxonomyRoots: [], mode: mode};
        captureSelection();
        if (selection !== null && !selection.length) throw new Error(t('analysis.scope.select.one'));
        return {taxonomyRoots: selection === null ? [] : selection.slice(), mode: mode};
    }
    function options() {
        syncMode();
        // Preserve an explicit empty selection through draft saves, independently of API defaults.
        return {taxonomySelection: selection === null ? null : selection.slice(), analysisMode: mode};
    }
    function restoreOptions(saved) {
        saved = saved || {};
        selection = Array.isArray(saved.taxonomySelection) ? saved.taxonomySelection.slice() : null;
        mode = saved.analysisMode === 'TAXONOMIES_ONLY' ? 'TAXONOMIES_ONLY' : 'FULL';
        var control = document.getElementById('analysisMode');
        if (control) control.value = mode;
        render(S.taxonomyData);
    }
    function restrictsGlobalAnalysis(scope) {
        if (!scope) return false; // legacy evidence retains its existing coverage contract
        if (scope.mode === 'TAXONOMIES_ONLY') return true;
        var selected = scope.taxonomyRoots || [];
        if (!selected.length) return false;
        var roots = S.taxonomyData || [];
        return !roots.length || roots.some(function (root) { return !selected.includes(root.code); });
    }
    function renderEvidence() {
        var label = document.getElementById('analysisScopeEvidence');
        if (!label) return;
        var scope = S.lastAnalysisScope;
        label.hidden = !scope;
        if (scope) label.textContent = t('analysis.scope.result') + ': '
            + (scope.taxonomyRoots?.length ? scope.taxonomyRoots.join(', ') : t('analysis.scope.all'))
            + ' · ' + t(scope.mode === 'TAXONOMIES_ONLY' ? 'analysis.scope.nodes.only' : 'analysis.scope.full');
    }
    function acceptResult(scope) {
        S.lastAnalysisScope = scope ? {taxonomyRoots: (scope.taxonomyRoots || []).slice(), mode: scope.mode || 'FULL'} : null;
        renderEvidence();
    }
    document.addEventListener('change', function (event) {
        if (event.target?.dataset?.taxonomyRoot) captureSelection();
        if (event.target?.id === 'analysisMode') syncMode();
    });
    document.addEventListener('click', function (event) {
        if (event.target?.id !== 'analysisSelectAll') return;
        selection = null; render(S.taxonomyData);
        window.__TaxonomyAnalysisSessionContext?.queueSave?.();
    });
    document.addEventListener('DOMContentLoaded', function () { render(S.taxonomyData); });
    document.addEventListener('taxonomy-i18n-loaded', function () { render(S.taxonomyData); });
    document.addEventListener('taxonomy:analysis-draft-restored', renderEvidence);
    window.TaxonomyAnalysisScope = {read: read, render: render, options: options,
        restoreOptions: restoreOptions, acceptResult: acceptResult, restrictsGlobalAnalysis: restrictsGlobalAnalysis};
}());
