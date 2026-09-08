/* Exact-context feature client. The canonical transport owns CSRF, base paths and errors. */
window.ArchitectureEditorApi = (function () {
    'use strict';
    var base = '/api/architecture/editor';
    function query(context, commit) {
        var params = new URLSearchParams();
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) {
            if (context && context[key]) params.set(key, context[key]);
        });
        if (context && context.workspaceScopeKey && context.workspaceScopeKey !== '__shared__') params.set('workspaceId', context.workspaceScopeKey);
        if (commit) params.set('commit', commit);
        return params.toString();
    }
    function write(path, body, context) {
        var headers = { 'Content-Type': 'application/json' };
        if (context.commit) headers['If-Match'] = '"' + context.commit + '"';
        else headers['If-None-Match'] = '*';
        return window.TaxonomyApiClient.request(base + path + '?' + query(context), {
            method: 'POST', headers: headers, body: JSON.stringify(body)
        }, { retries: 0 }).then(function (response) { return response.json(); });
    }
    return {
        load: function (context, commit, signal) {
            return window.TaxonomyApiClient.getJson(base + '?' + query(context, commit), { signal: signal });
        },
        preview: function (command) { return write('/preview', command, command.context); },
        execute: function (command) { return write('/commands', command, command.context); },
        rebuild: function (context) { return write('/rebuild', context, context); },
        exportUrl: function (context, format) {
            if (['svg', 'pdf'].indexOf(format) < 0) throw new Error('Unknown export format');
            return window.TaxonomyI18n.resolveUrl(base + '.' + format + '?' + query(context, context.commit));
        },
        createWorkspace: function (context) {
            return window.TaxonomyApiClient.sendJson('/api/repositories/' + encodeURIComponent(context.repositoryId) + '/workspaces', {
                displayName: 'Architecture editor', description: '', sourceBranch: context.branch
            });
        }
    };
}());
