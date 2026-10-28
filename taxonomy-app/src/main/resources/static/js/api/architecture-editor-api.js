/* Exact-context feature client. The canonical transport owns CSRF, base paths and errors. */
window.ArchitectureEditorApi = (function () {
    'use strict';
    var base = '/api/architecture/editor';
    function query(context, revision, layout, layoutRevision) {
        var params = new URLSearchParams();
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) {
            if (context && context[key]) params.set(key, context[key]);
        });
        if (context && context.workspaceScopeKey) {
            params.set('workspaceId', context.workspaceScopeKey === '__shared__' ? '' : context.workspaceScopeKey);
        }
        if (revision !== null && revision !== undefined) {
            params.set(/^[a-f0-9]{40}$/i.test(String(revision)) ? 'commit' : 'revision', revision);
        }
        if (layout) params.set('layout', layout);
        if (layoutRevision !== null && layoutRevision !== undefined) params.set('layoutRevision', layoutRevision);
        return params.toString();
    }
    function write(path, body, context, policy) {
        var headers = { 'Content-Type': 'application/json' };
        headers['If-Match'] = '"workspace-revision-' + context.revision + '"';
        return window.TaxonomyApiClient.request(base + path + '?' + query(context), {
            method: 'POST', headers: headers, body: JSON.stringify(body)
        }, Object.assign({ retries: 0 }, policy)).then(function (response) { return response.json(); });
    }
    return {
        load: function (context, revision, signal, layout, layoutRevision) {
            return window.TaxonomyApiClient.getJson(base + '?' + query(context, revision, layout, layoutRevision), { signal: signal });
        },
        suggest: function (request) { return write('/focus/suggestions', request, request.context, { timeoutMillis: 120000 }); },
        preview: function (command) { return write('/preview', command, command.context); },
        execute: function (command) { return write('/commands', command, command.context); },
        checkpoint: function (command) { return write('/checkpoints', command, command.context); },
        resumeCheckpoint: function (context) { return write('/checkpoints/resume', {}, context); },
        recoverVersion: function (context) { return write('/versions/recover', {}, context); },
        rebuild: function (context) { return write('/rebuild', context, context); },
        exportUrl: function (context, format, source, layout, layoutRevision) {
            if (['svg', 'pdf'].indexOf(format) < 0) throw new Error('Unknown export format');
            return window.TaxonomyI18n.resolveUrl(base + '.' + format + '?' + query(context, source === 'GIT_CHECKPOINT' ? context.commit : context.revision, layout, layoutRevision));
        },
        createWorkspace: function (context) {
            return window.TaxonomyApiClient.sendJson('/api/repositories/' + encodeURIComponent(context.repositoryId) + '/workspaces', {
                displayName: 'Architecture editor', description: '', sourceBranch: context.branch
            });
        }
    };
}());
