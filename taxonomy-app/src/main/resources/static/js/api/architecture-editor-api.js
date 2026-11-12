/* Exact-context feature client. The canonical transport owns CSRF, base paths and errors. */
window.ArchitectureEditorApi = (function () {
    'use strict';
    var base = '/api/architecture/editor';
    function query(context, revision, layout, layoutRevision, viewId) {
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
        if (viewId) params.set('viewId', viewId);
        if (layout) params.set('layout', layout);
        if (layoutRevision !== null && layoutRevision !== undefined) params.set('layoutRevision', layoutRevision);
        return params.toString();
    }
    function admbwPath(context, projectCode, viewId, rootPackageGuid) {
        var params = new URLSearchParams(query(context, context.commit, null, null, viewId));
        params.set('projectCode', projectCode);
        if (rootPackageGuid != null) {
            params.set('rootPackageGuid', rootPackageGuid);
            return base + '.admbw.qea?' + params;
        }
        return base + '.admbw.json?' + params;
    }
    function exactPolicy(context) {
        return context && context.workspaceScopeKey !== undefined ? {pinnedWorkspaceId:context.workspaceScopeKey === '__shared__' ? '' : context.workspaceScopeKey} : {};
    }
    function write(path, body, context, policy) {
        var headers = { 'Content-Type': 'application/json' };
        headers['If-Match'] = '"workspace-revision-' + context.revision + '"';
        return window.TaxonomyApiClient.request(base + path + '?' + query(context), {
            method: 'POST', headers: headers, body: JSON.stringify(body)
        }, Object.assign({ retries: 0 }, exactPolicy(context), policy)).then(function (response) { return response.json(); });
    }
    return {
        load: function (context, revision, signal, layout, layoutRevision, viewId) {
            return window.TaxonomyApiClient.getJson(base + '?' + query(context, revision, layout, layoutRevision, viewId), Object.assign({ signal: signal }, exactPolicy(context)));
        },
        analysisBrief: function (context, model) {
            var params = new URLSearchParams(query(context));
            if (model?.expectedCommit) params.set('expectedCommit', model.expectedCommit);
            if (model?.expectedSemanticRevision != null) params.set('expectedSemanticRevision', String(model.expectedSemanticRevision));
            return window.TaxonomyApiClient.getJson(base + '/analysis-brief?' + params, exactPolicy(context));
        },
        suggest: function (request, options) { return write('/focus/suggestions', request, request.context, {
            isCurrent: options?.isCurrent, onPending: options?.onPending, signal: options?.signal,
            timeoutMillis: 120000, operationError: function (error) { return window.AnalysisAdapterApi?.describe(error) || error; },
            observeResponse: function (response, url, init, options, current) {
                return response.status === 202 ? window.AnalysisAdapterApi.follow(response, url, init, options, current) : response;
            }
        }); },
        previewAdoption: function (request) { return write('/snapshot-adoptions/preview', request, request.context); },
        adoptSnapshot: function (request) { return write('/snapshot-adoptions', request, request.context); },
        preview: function (command) { return write('/preview', command, command.context); },
        setSharing: function (context, enabled) { return write('/collaboration', {context:context, enabled:enabled}, context); },
        // Only the command receipt needs a 412 from the preceding revision.
        // Previews and suggestions always belong to the current displayed stand.
        execute: function (command) { return write('/commands', command, command.context, {preservePreconditionFailure:true}); },
        checkpoint: function (command) { return write('/checkpoints', command, command.context); },
        resumeCheckpoint: function (context) { return write('/checkpoints/resume', {}, context); },
        recoverVersion: function (context) { return write('/versions/recover', {}, context); },
        rebuild: function (context) { return write('/rebuild', context, context); },
        exportUrl: function (context, format, source, layout, layoutRevision, viewId) {
            if (['svg', 'pdf'].indexOf(format) < 0) throw new Error('Unknown export format');
            return window.TaxonomyI18n.resolveUrl(base + '.' + format + '?' + query(context, source === 'GIT_CHECKPOINT' ? context.commit : context.revision, layout, layoutRevision, viewId));
        },
        admbwUrl: function (context, projectCode, viewId) {
            return window.TaxonomyI18n.resolveUrl(admbwPath(context, projectCode, viewId));
        },
        admbwAssessment: function (context, projectCode, viewId) {
            return window.TaxonomyApiClient.getJson(admbwPath(context, projectCode, viewId), exactPolicy(context));
        },
        admbwQea: function (context, projectCode, viewId, rootPackageGuid, basis, signal) {
            var mediaType = 'application/vnd.sparx.qea';
            return window.TaxonomyApiClient.requestBlob(admbwPath(context, projectCode, viewId, rootPackageGuid), {
                method: 'POST', headers: { 'Content-Type': mediaType }, body: basis
            }, Object.assign({ retries: 0, timeoutMillis: 120000, signal: signal }, exactPolicy(context))).then(function (result) {
                var headers = result.response.headers;
                if (headers.get('X-Taxonomy-Commit') !== context.commit || headers.get('Content-Type') !== mediaType) {
                    throw new Error(window.TaxonomyI18n.t('editor.admbw.qeaUnexpected'));
                }
                return result.blob;
            });
        },
        createWorkspace: function (context) {
            return window.TaxonomyApiClient.sendJson(base + '/workspaces?' + query(context), {
                repositoryId: context.repositoryId, branch: context.branch,
                displayName: window.TaxonomyI18n.t('workflow.editor')
            }, exactPolicy(context));
        }
    };
}());
