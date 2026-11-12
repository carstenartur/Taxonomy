/* Read-only exact-source boundary. The canonical client owns CSRF, base paths and errors. */
window.ArchitectureSourceFocusApi = Object.freeze({
    suggest: function (request, options) {
        'use strict';
        var source = request.source;
        var query = new URLSearchParams();
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) { query.set(key, source[key]); });
        var workspace = source.workspaceScopeKey === '__shared__' ? '' : source.workspaceScopeKey;
        query.set('workspaceId', workspace);
        // Deliberately no If-Match or editor context. The server resolves the pinned durable source.
        var body = { source: source, sourceId: request.sourceId, candidateLimit: request.candidateLimit,
            useAi: request.useAi === true, language: request.language };
        return window.TaxonomyApiClient.request('/api/architecture/focus/suggestions?' + query, {
            method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Taxonomy-Workspace-Id': workspace },
            body: JSON.stringify(body), cache: 'no-store', signal: options && options.signal
        }, { retries: 0, timeoutMillis: 120000, signal: options && options.signal, pinnedWorkspaceId: workspace,
            isCurrent: options?.isCurrent, onPending: options?.onPending,
            operationError: function (error) { return window.AnalysisAdapterApi?.describe(error) || error; },
            observeAdmission: window.AnalysisAdapterApi?.admission, observeResponse: function (response, url, init, policy, current) {
                return response.status === 202 ? window.AnalysisAdapterApi.follow(response, url, init, policy, current) : response;
            } })
            .then(function (response) { return response.json(); });
    }
});
