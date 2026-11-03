/* Download adapters for immutable architecture snapshot formats. */
(function () {
    'use strict';

    function source() {
        const workspaceSource = window.TaxonomyArchitectureWorkspace?.getSource?.();
        if (workspaceSource) return workspaceSource.kind === 'snapshot' ? workspaceSource : null;
        return document.body.__architectureSnapshot?.getSource?.()
            || Object.assign({}, window.TaxonomyArchitectureWorkflow?.getScope?.(), document.body.dataset);
    }

    function bind(buttonId, urlFactory) {
        const button = document.getElementById(buttonId);
        if (!button) return;
        button.addEventListener('click', function () {
            const selected=source();
            if (button.disabled || !selected) return;
            window.location.assign(urlFactory(selected.projectId, selected.snapshotId, selected));
        });
    }

    bind('downloadArchitectureWord', ArchitectureWorkbenchApi.architectureWordUrl);
    const decision = document.getElementById('downloadDecisionWord');
    if (decision) decision.addEventListener('click', function () {
        const selected=source();
        if (decision.disabled || !selected) return;
        window.TaxonomyDecisionExport.openSaved({projectId:selected.projectId, snapshotId:selected.snapshotId, language: document.documentElement.lang,
            scope: Object.assign({}, selected), api: window.TaxonomyPortfolioApi});
    });

    bind(
        'downloadArchitectureArchiMate',
        ArchitectureWorkbenchApi.archiMateBundleUrl);
    bind(
        'downloadArchitectureVisio',
        ArchitectureWorkbenchApi.visioBundleUrl);
}());
