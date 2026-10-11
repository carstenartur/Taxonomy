/* product-demo-api.js – Product-demo API module for the Taxonomy UI (issue #1184).
 *
 * Wraps the /api/product-demo endpoints on the shared TaxonomyApiClient (CSRF,
 * request IDs, timeouts and problem details). The product-demo UI must call
 * these named functions instead of constructing fetch calls directly.
 *
 * See docs/dev/03-ui-task-map.md for the UI API-client convention.
 */
window.TaxonomyProductDemoApi = (function () {
    'use strict';

    const base = '/api/product-demo';
    const client = () => window.TaxonomyApiClient;
    const runPath = runId => base + '/runs/' + encodeURIComponent(runId);

    return {
        /** Starts an isolated demo; the default scenario is used without a scenario ID. */
        startRun: scenarioId => client().sendJson(base + '/runs', scenarioId ? { scenarioId: scenarioId } : {}),
        /** The demo run of a project, or null for an ordinary project. */
        runForProject: projectId => client().getJson(base + '/runs?projectId=' + encodeURIComponent(projectId))
            .then(runs => (Array.isArray(runs) && runs.length ? runs[0] : null)),
        comparison: runId => client().getJson(runPath(runId) + '/comparison'),
        closeRun: runId => client().sendJson(runPath(runId) + '/close', {})
    };
}());
