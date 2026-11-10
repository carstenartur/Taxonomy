/* Named workspace, context and preference operations. Load after taxonomy-api-client.js.
 * Capture the tab's workspace when starting work; a late response must not repaint another workspace.
 */
(function () {
    'use strict';

    function request(path, init, options) {
        var client = window.TaxonomyApiClient;
        return client.requestJson(path, init || {}, Object.assign({}, options || {}, { retries: 0 }));
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
        listBranches: function (options) { return get('/api/git/branches', options); },
        compare: function (selection, options) {
            var query = new URLSearchParams({ leftBranch: selection.leftBranch, rightBranch: selection.rightBranch });
            if (selection.leftCommit) query.set('leftCommit', selection.leftCommit);
            if (selection.rightCommit) query.set('rightCommit', selection.rightCommit);
            return get('/api/context/compare?' + query, options);
        },
        searchHistory: function (query, branch, options) {
            var params = new URLSearchParams({ query: query, currentBranch: branch });
            return get('/api/dsl/history/search-versioned?' + params, options);
        }
    });

    // Response-returning operations retain domain conflict handling in their UI.
    // The canonical client still owns status/authentication and guarded body readers.
    function versionRequest(path, init, options) {
        return window.TaxonomyApiClient.request(path, init || {}, Object.assign({
            acceptedStatuses: [400, 404, 409, 412, 422, 423]
        }, options || {}, { retries: 0 }));
    }
    function query(params) { var value = new URLSearchParams(params).toString(); return value ? '?' + value : ''; }
    function versionPost(path, params, body, contentType) {
        var init = { method: 'POST' };
        if (body !== undefined) { init.body = body; init.headers = { 'Content-Type': contentType }; }
        return versionRequest(path + query(params), init);
    }
    window.TaxonomyVersioningApi = Object.freeze({
        branches: function () { return versionRequest('/api/git/branches'); },
        state: function (branch, options) { return versionRequest('/api/git/state' + query({branch: branch}), {cache:'no-store'}, options); },
        history: function (branch) { return versionRequest('/api/dsl/history' + query({branch: branch})); },
        commitById: function (id) { return versionRequest('/api/dsl/git/commit/' + encodeURIComponent(id)); },
        head: function (branch) { return versionRequest('/api/dsl/git/head' + query({branch: branch})); },
        diff: function (left, right) { return versionRequest('/api/dsl/diff/' + encodeURIComponent(left) + '/' + encodeURIComponent(right)); },
        restore: function (id, branch) { return versionPost('/api/dsl/restore', {commitId:id,branch:branch}); },
        revert: function (id, branch) { return versionPost('/api/dsl/revert', {commitId:id,branch:branch}); },
        undo: function (branch) { return versionPost('/api/dsl/undo', {branch:branch}); },
        commit: function (branch, author, message, text) { return versionPost('/api/dsl/commit', {branch:branch,author:author,message:message}, text, 'text/plain'); },
        compare: function (left, right) { return versionRequest('/api/context/compare' + query({leftBranch:left,rightBranch:right})); },
        merge: function (from, into) { return versionPost('/api/dsl/merge', {from:from,into:into}); },
        deleteBranch: function (name) { return versionRequest('/api/dsl/branch' + query({name:name}), {method:'DELETE'}); },
        mergeConflicts: function (from, into) { return versionRequest('/api/dsl/merge/conflicts' + query({from:from,into:into})); },
        cherryPickConflicts: function (id, target) { return versionRequest('/api/dsl/cherry-pick/conflicts' + query({commitId:id,targetBranch:target})); },
        resolveMerge: function (from, into, text) { return versionPost('/api/dsl/merge/resolve', {fromBranch:from,intoBranch:into}, text, 'text/plain'); },
        resolveCherryPick: function (id, target, text) { return versionPost('/api/dsl/cherry-pick/resolve', {commitId:id,targetBranch:target}, text, 'text/plain'); },
        resolveDiverged: function (strategy, branch) { return versionPost('/api/workspace/resolve-diverged', {strategy:strategy,userBranch:branch}); },
        copyBackPreview: function (selection) { return versionPost('/api/context/copy-back/preview', {}, JSON.stringify(selection), 'application/json'); },
        copyBackApply: function (selection, contextId) { return versionPost('/api/context/copy-back/apply', contextId ? {expectedContextId:contextId} : {}, JSON.stringify(selection), 'application/json'); }
    });

    window.TaxonomyPreferencesApi = Object.freeze({
        current: function (options) { return get('/api/preferences', options); },
        save: function (changes, options) { return json('/api/preferences', changes, 'PUT', options); },
        reset: function (options) { return post('/api/preferences/reset', options); },
        history: function (options) { return get('/api/preferences/history', options); }
    });
}());
