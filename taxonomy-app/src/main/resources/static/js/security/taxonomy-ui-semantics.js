/** CSP-safe state and ARIA synchronization for dynamic Taxonomy UI surfaces. */
window.TaxonomyUiSemantics = (function () {
    'use strict';

    var resultPanelIds = [
        'docCandidateReviewPanel',
        'docAiResultPanel',
        'docRegMapResultPanel'
    ];
    var observers = [];

    function inlineDisplayMarker(panel) {
        return panel.getAttribute('style') || '';
    }

    function synchronizeResultPanel(panel) {
        if (!panel) return;
        var hidden = /(?:^|;)\s*display\s*:\s*none\s*;?/i.test(inlineDisplayMarker(panel));
        panel.classList.toggle('d-none', hidden);
        panel.classList.toggle('d-block', !hidden);
        panel.setAttribute('aria-hidden', hidden ? 'true' : 'false');
    }

    function synchronizeResultPanels() {
        resultPanelIds.forEach(function (id) {
            synchronizeResultPanel(document.getElementById(id));
        });
    }

    function scheduleResultPanelSync() {
        window.requestAnimationFrame(synchronizeResultPanels);
    }

    function installResultPanelBridge(panel) {
        if (!panel || panel.dataset.cspVisibilityObserved === 'true') return;
        panel.dataset.cspVisibilityObserved = 'true';
        synchronizeResultPanel(panel);
        var observer = new MutationObserver(function () {
            synchronizeResultPanel(panel);
        });
        observer.observe(panel, { attributes: true, attributeFilter: ['style'] });
        observers.push(observer);
    }

    function installDocumentImportStatusBridge() {
        var status = document.getElementById('docImportStatus');
        if (!status || status.dataset.resultPanelObserved === 'true') return;
        status.dataset.resultPanelObserved = 'true';
        var observer = new MutationObserver(scheduleResultPanelSync);
        observer.observe(status, {
            childList: true,
            subtree: true,
            characterData: true
        });
        observers.push(observer);
    }

    function synchronizeTaxonomyContainer(tree) {
        if (!tree) return;
        var containsTreeItems = Boolean(tree.querySelector('[role="treeitem"]'));
        var expectedRole = containsTreeItems ? 'tree' : 'region';
        if (tree.getAttribute('role') !== expectedRole) tree.setAttribute('role', expectedRole);
        if (containsTreeItems) tree.removeAttribute('aria-busy');
        else tree.setAttribute('aria-busy', 'true');
    }

    function installTaxonomyContainerBridge() {
        var tree = document.getElementById('taxonomyTree');
        if (!tree || tree.dataset.semanticRoleObserved === 'true') return;
        tree.dataset.semanticRoleObserved = 'true';
        synchronizeTaxonomyContainer(tree);
        var observer = new MutationObserver(function () {
            synchronizeTaxonomyContainer(tree);
        });
        observer.observe(tree, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['role']
        });
        observers.push(observer);
    }

    function initialize() {
        resultPanelIds.forEach(function (id) {
            installResultPanelBridge(document.getElementById(id));
        });
        installDocumentImportStatusBridge();
        installTaxonomyContainerBridge();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize, { once: true });
    } else {
        initialize();
    }

    return {
        initialize: initialize,
        synchronizeResultPanel: synchronizeResultPanel,
        synchronizeResultPanels: synchronizeResultPanels,
        synchronizeTaxonomyContainer: synchronizeTaxonomyContainer
    };
}());
