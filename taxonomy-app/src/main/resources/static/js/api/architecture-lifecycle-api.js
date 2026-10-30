/* Lifecycle requests retain the caller's exact repository scope and command identity. */
window.TaxonomyArchitectureLifecycleApi = (function () {
    'use strict';
    var base = '/api/architecture/lifecycle';
    function query(context) {
        var scope = context || window.TaxonomyArchitectureWorkflow?.getScope() || {};
        var params = new URLSearchParams();
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) {
            if (scope[key] !== undefined && scope[key] !== null) params.set(key, scope[key]);
        });
        if (scope.workspaceScopeKey !== undefined) params.set('workspaceId', scope.workspaceScopeKey === '__shared__' ? '' : scope.workspaceScopeKey);
        else if (scope.workspaceId !== undefined) params.set('workspaceId', scope.workspaceId);
        return params;
    }
    function read(path, context, values) {
        var params = query(context);
        Object.keys(values || {}).forEach(function (key) {
            if (values[key] !== undefined && values[key] !== null && values[key] !== '') params.set(key, values[key]);
        });
        return window.TaxonomyApiClient.getJson(path + '?' + params);
    }
    function write(path, body, exact) {
        var headers = {'Content-Type': 'application/json'};
        if (exact) headers['If-Match'] = '"workspace-revision-' + body.context.revision + '"';
        return window.TaxonomyApiClient.request(path + '?' + query(body.context), {
            method:'POST', headers:headers, body:JSON.stringify(body)
        }, {retries:0}).then(function (response) { return response.json(); });
    }
    return {
        // Selection is explicit. A command Context.revision is never a historical selection.
        overview:function (context, commit, revision) { return read(base, context, {commit:commit, revision:revision}); },
        history:function (context, filters) { return read(base + '/history', context, filters); },
        projects:function (context) { return read('/api/projects', context); },
        requirement:function (context, projectId, requirementId) { return read('/api/projects/' + projectId + '/requirements/' + requirementId, context); },
        previewReview:function (request) { return write(base + '/realizations/preview', request, true); },
        acceptReview:function (request) { return write(base + '/realizations', request, true); },
        saveRequirement:function (request) { return write('/api/architecture/editor/requirements', request, true); },
        reviewRequirementIntake:function (request) { return write(base + '/intake-review', request, false); },
        previewReconciliation:function (request) { return write('/api/architecture/editor/versions/reconciliation/preview', request, true); },
        acceptReconciliation:function (request) { return write('/api/architecture/editor/versions/reconciliation/accept', request, true); }
    };
}());
