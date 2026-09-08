/* Exact-context feature client. The canonical transport owns CSRF, base paths and errors. */
window.ArchitectureEditorApi = (function () {
    'use strict';
    var base = '/api/architecture/editor';
    function query(context, revision) {
        var params = new URLSearchParams();
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) {
            if (context && context[key]) params.set(key, context[key]);
        });
        if (context && context.workspaceScopeKey && context.workspaceScopeKey !== '__shared__') params.set('workspaceId', context.workspaceScopeKey);
        if (revision !== null && revision !== undefined) {
            params.set(/^[a-f0-9]{40}$/i.test(String(revision)) ? 'commit' : 'revision', revision);
        }
        return params.toString();
    }
    function write(path, body, context) {
        var headers = { 'Content-Type': 'application/json' };
        headers['If-Match'] = '"workspace-revision-' + context.revision + '"';
        return window.TaxonomyApiClient.request(base + path + '?' + query(context), {
            method: 'POST', headers: headers, body: JSON.stringify(body)
        }, { retries: 0 }).then(function (response) { return response.json(); });
    }
    return {
        load: function (context, revision, signal) {
            return window.TaxonomyApiClient.getJson(base + '?' + query(context, revision), { signal: signal });
        },
        preview: function (command) { return write('/preview', command, command.context); },
        execute: function (command) { return write('/commands', command, command.context); },
        checkpoint: function (command) { return write('/checkpoints', command, command.context); },
        resumeCheckpoint: function (context) { return write('/checkpoints/resume', {}, context); },
        recoverVersion: function (context) { return write('/versions/recover', {}, context); },
        rebuild: function (context) { return write('/rebuild', context, context); },
        exportUrl: function (context, format, source) {
            if (['svg', 'pdf'].indexOf(format) < 0) throw new Error('Unknown export format');
            return window.TaxonomyI18n.resolveUrl(base + '.' + format + '?' + query(context, source === 'GIT_CHECKPOINT' ? context.commit : context.revision));
        },
        createWorkspace: function (context) {
            return window.TaxonomyApiClient.sendJson('/api/repositories/' + encodeURIComponent(context.repositoryId) + '/workspaces', {
                displayName: 'Architecture editor', description: '', sourceBranch: context.branch
            });
        }
    };
}());
