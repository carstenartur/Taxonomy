/* Named DSL inspection, validation and command preflight boundary. */
window.TaxonomyDslApi = (function () {
    'use strict';
    var client = window.TaxonomyApiClient;
    function read(path, options) { return client.request('/api/dsl' + path, {}, options); }
    function text(path, value, options) {
        return client.request('/api/dsl' + path, {method:'POST', headers:{'Content-Type':'text/plain'}, body:value}, options);
    }
    function post(path) { return client.request('/api/dsl' + path, {method:'POST'}, {retries:0}); }
    return {
        projectText: function (scope, options) {
            var params = new URLSearchParams();
            ['repositoryId', 'branch', 'commit', 'revision'].forEach(function (key) {
                if (scope[key] !== undefined && scope[key] !== null) params.set(key, scope[key]);
            });
            if (scope.workspaceId !== undefined && scope.workspaceId !== null) {
                params.set('workspaceId', scope.workspaceId);
                options = Object.assign({}, options, {pinnedWorkspaceId:scope.workspaceId});
            }
            return read('/project?' + params, options);
        },
        compareCatalogue: function (value, options) { return text('/project/compare-current', value, options); },
        exportText: function (options) { return read('/export', options); },
        format: function (value, options) { return text('/format', value, options); },
        gitCommit: function (commit, options) { return read('/git/commit/' + encodeURIComponent(commit), options); },
        commit: function (branch, message, value) { return text('/commit?branch=' + encodeURIComponent(branch) + '&message=' + encodeURIComponent(message), value, {retries:0}); },
        materialize: function (branch, value) { return text('/materialize' + (branch ? '?branch=' + encodeURIComponent(branch) : ''), value, {retries:0}); },
        materializeIncremental: function (after, before) { return post('/materialize-incremental?afterDocId=' + encodeURIComponent(after) + '&beforeDocId=' + encodeURIComponent(before)); },
        createBranch: function (name, from) { return post('/branches?name=' + encodeURIComponent(name) + '&fromBranch=' + encodeURIComponent(from)); },
        cherryPick: function (commit, branch) { return post('/cherry-pick?commitId=' + encodeURIComponent(commit) + '&targetBranch=' + encodeURIComponent(branch)); },
        merge: function (from, into) { return post('/merge?fromBranch=' + encodeURIComponent(from) + '&intoBranch=' + encodeURIComponent(into)); },
        parse: function (value, options) { return text('/parse', value, options); },
        validate: function (value, options) { return text('/validate', value, options); },
        branches: function () { return read('/branches'); },
        history: function (branch) { return read('/history?branch=' + encodeURIComponent(branch || 'draft')); },
        diff: function (before, after) { return read('/diff/' + encodeURIComponent(before) + '/' + encodeURIComponent(after)); },
        textDiff: function (before, after) { return read('/diff/text/' + encodeURIComponent(before) + '/' + encodeURIComponent(after)); },
        operationCheck: function (branch, operation) { return read('/operation/check?branch=' + encodeURIComponent(branch) + '&operationType=' + encodeURIComponent(operation)); },
        mergePreview: function (from, into) { return read('/merge/preview?from=' + encodeURIComponent(from) + '&into=' + encodeURIComponent(into)); },
        cherryPickPreview: function (commit, branch) { return read('/cherry-pick/preview?commitId=' + encodeURIComponent(commit) + '&targetBranch=' + encodeURIComponent(branch)); }
    };
}());
