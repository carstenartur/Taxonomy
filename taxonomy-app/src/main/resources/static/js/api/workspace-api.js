/* Named workspace, context and preference operations. Load after taxonomy-api-client.js.
 * Capture the tab's workspace when starting work; a late response must not repaint another workspace.
 */
(function () {
    'use strict';

    function request(path, init, options) {
        var client = window.TaxonomyApiClient;
        var context = window.__TaxonomyAnalysisSessionContext;
        var workspace = context && context.runtime.workspaceId;
        var policy = Object.assign({}, options || {}, { retries: 0 });
        // Before session hydration, the existing source-aware bootstrap owns
        // status scope. An unresolved workspace is not an explicit central pin.
        if (policy.pinnedWorkspaceId === undefined && workspace != null) policy.pinnedWorkspaceId = workspace;
        var headers = new Headers(init && init.headers || {});
        if (policy.pinnedWorkspaceId !== undefined) {
            var pin = policy.pinnedWorkspaceId == null ? '' : policy.pinnedWorkspaceId;
            path += (path.indexOf('?') < 0 ? '?' : '&') + 'workspaceId=' + encodeURIComponent(pin);
            headers.set('X-Taxonomy-Workspace-Id', pin);
        }
        function current() {
            if (policy.signal && policy.signal.aborted || context && context.runtime.workspaceId !== workspace) {
                throw new client.ApiError('Request was cancelled', 0, path, null, { code: 'ABORTED' });
            }
        }
        return client.request(path, Object.assign({}, init || {}, { headers: headers }), policy)
            .then(function (response) { current(); return response.json(); })
            .then(function (body) { current(); return body; })
            .catch(function (error) {
                if (error?.code !== 'ABORTED') current();
                throw error;
            });
    }

    function get(path, options) { return request(path, { method: 'GET' }, options); }
    function post(path, options) { return request(path, { method: 'POST' }, options); }
    function json(path, body, method, options) {
        return request(path, { method: method, headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body) }, options);
    }
    function branchQuery(branch) { return '?userBranch=' + encodeURIComponent(branch); }

    window.TaxonomyWorkspaceApi = Object.freeze({
        current: function (options) { return get('/api/workspace/current', options); },
        list: function (options) { return get('/api/workspace/list', options); },
        history: function (options) { return get('/api/workspace/history', options); },
        create: function (displayName, options) { return json('/api/workspace/create', { displayName: displayName }, 'POST', options); },
        archive: function (id, options) { return post('/api/workspace/' + encodeURIComponent(id) + '/archive', options); },
        switchWorkspace: function (id, options) {
            return post('/api/workspace/' + encodeURIComponent(id) + '/switch', options).then(function (workspace) {
                // Named requests bypass the legacy fetch wrapper. Keep its successful-switch contract here.
                var context = window.__TaxonomyAnalysisSessionContext;
                if (context) context.rememberWorkspaceId(id);
                else {
                    try { window.sessionStorage.setItem('taxonomy-analysis-workspace-id', id); }
                    catch (ignored) { /* Reload can still reconcile from the server's active workspace. */ }
                }
                window.setTimeout(function () { window.location.reload(); }, 0);
                return workspace;
            });
        },
        syncState: function (options) { return get('/api/workspace/sync-state', options); },
        localChanges: function (branch, options) { return get('/api/workspace/local-changes?branch=' + encodeURIComponent(branch), options); },
        syncFromShared: function (branch, options) { return post('/api/workspace/sync-from-shared' + branchQuery(branch), options); },
        publish: function (branch, options) { return post('/api/workspace/publish' + branchQuery(branch), options); }
    });

    window.TaxonomyContextApi = Object.freeze({
        current: function (options) { return get('/api/context/current', options); },
        open: function (branch, commitId, readOnly, options) {
            var query = new URLSearchParams({ branch: branch, readOnly: readOnly });
            if (commitId != null) query.set('commitId', commitId);
            return post('/api/context/open?' + query, options);
        },
        back: function (options) { return post('/api/context/back', options); },
        returnToOrigin: function (options) { return post('/api/context/return-to-origin', options); },
        createVariant: function (name, options) { return post('/api/context/variant?name=' + encodeURIComponent(name), options); },
        searchHistory: function (query, branch, options) {
            var params = new URLSearchParams({ query: query, currentBranch: branch });
            return get('/api/dsl/history/search-versioned?' + params, options);
        }
    });

    window.TaxonomyPreferencesApi = Object.freeze({
        current: function (options) { return get('/api/preferences', options); },
        save: function (changes, options) { return json('/api/preferences', changes, 'PUT', options); },
        reset: function (options) { return post('/api/preferences/reset', options); },
        history: function (options) { return get('/api/preferences/history', options); }
    });
}());
