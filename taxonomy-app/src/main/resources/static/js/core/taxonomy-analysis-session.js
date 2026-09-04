/* taxonomy-analysis-session.js – ordered loader for the analysis session lifecycle */
(function () {
    'use strict';
    if (window.TaxonomyAnalysisSession || window.__taxonomyAnalysisSessionLoading) return;
    window.__taxonomyAnalysisSessionLoading = true;

    var sources = [
        '/js/api/analysis-session-api.js',
        '/js/core/taxonomy-analysis-session-core.js',
        '/js/core/taxonomy-analysis-session-api-routing.js',
        '/js/core/taxonomy-analysis-session-transport.js',
        '/js/core/taxonomy-copilot-terminal-state.js',
        '/js/core/taxonomy-operation-coordinator.js',
        '/js/core/taxonomy-analysis-session-ui.js',
        '/js/core/taxonomy-analysis-session-draft.js',
        '/js/core/taxonomy-analysis-session-projects.js'
    ];
    var copilotSessionUiSettled = false;

    function applicationUrl(source) {
        return window.TaxonomyI18n
                && typeof window.TaxonomyI18n.resolveUrl === 'function'
            ? window.TaxonomyI18n.resolveUrl(source)
            : source;
    }

    function loadCopilotSessionUi(done) {
        if (!document.head || typeof document.head.insertBefore !== 'function') {
            done();
            return;
        }
        var script = document.createElement('script');
        script.src = applicationUrl('/js/core/taxonomy-copilot-session-ui.js');
        script.async = false;
        script.dataset.taxonomyAnalysisSessionPart = 'copilot-session-ui';
        script.addEventListener('load', done, { once: true });
        script.addEventListener('error', function () {
            if (window.console) {
                window.console.error(
                    '[Taxonomy] Could not load Copilot session UI module', script.src);
            }
            done();
        }, { once: true });
        document.head.insertBefore(script, document.head.firstChild || null);
    }

    function load(index) {
        if (index >= sources.length) {
            window.__taxonomyAnalysisSessionLoading = false;
            return;
        }
        if (!copilotSessionUiSettled
                && sources[index] === '/js/core/taxonomy-operation-coordinator.js') {
            copilotSessionUiSettled = true;
            loadCopilotSessionUi(function () { load(index); });
            return;
        }
        var script = document.createElement('script');
        script.src = applicationUrl(sources[index]);
        script.async = false;
        script.dataset.taxonomyAnalysisSessionPart = String(index + 1);
        script.addEventListener('load', function () { load(index + 1); }, { once: true });
        script.addEventListener('error', function () {
            window.__taxonomyAnalysisSessionLoading = false;
            if (window.console) {
                window.console.error(
                    '[Taxonomy] Could not load analysis session module', script.src);
            }
        }, { once: true });
        document.head.appendChild(script);
    }

    load(0);
}());
