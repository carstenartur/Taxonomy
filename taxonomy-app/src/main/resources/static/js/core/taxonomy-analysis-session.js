/* taxonomy-analysis-session.js – ordered loader for the analysis session lifecycle */
(function () {
    'use strict';
    if (window.TaxonomyAnalysisSession || window.__taxonomyAnalysisSessionLoading) return;
    window.__taxonomyAnalysisSessionLoading = true;

    var sources = [
        '/js/core/taxonomy-analysis-session-core.js',
        '/js/core/taxonomy-analysis-session-transport.js',
        '/js/core/taxonomy-analysis-session-ui.js',
        '/js/core/taxonomy-analysis-session-draft.js',
        '/js/core/taxonomy-analysis-session-projects.js'
    ];

    function load(index) {
        if (index >= sources.length) {
            window.__taxonomyAnalysisSessionLoading = false;
            return;
        }
        var script = document.createElement('script');
        script.src = sources[index];
        script.async = false;
        script.dataset.taxonomyAnalysisSessionPart = String(index + 1);
        script.addEventListener('load', function () { load(index + 1); }, { once: true });
        script.addEventListener('error', function () {
            window.__taxonomyAnalysisSessionLoading = false;
            if (window.console) {
                window.console.error('[Taxonomy] Could not load analysis session module', sources[index]);
            }
        }, { once: true });
        document.head.appendChild(script);
    }

    load(0);
}());
