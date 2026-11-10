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
    function policy(params) { return params.has('workspaceId') ? {pinnedWorkspaceId:params.get('workspaceId')} : {}; }
    function read(path, context, values) {
        var params = query(context);
        Object.keys(values || {}).forEach(function (key) {
            if (values[key] !== undefined && values[key] !== null && values[key] !== '') params.set(key, values[key]);
        });
        return window.TaxonomyApiClient.getJson(path + '?' + params, policy(params));
    }
    function write(path, body, exact, context, preserveReceipt) {
        var headers = {'Content-Type': 'application/json'};
        if (exact) headers['If-Match'] = '"workspace-revision-' + body.context.revision + '"';
        var params = query(context || body.context);
        var options = Object.assign({retries:0},policy(params));
        // An admitted exact mutation may already be saved after source navigation.
        // Its domain fence decides whether the receipt can update the visible stand;
        // canonical authentication, page and body-deadline guards still apply.
        if (preserveReceipt) options.contextSensitive = false;
        return window.TaxonomyApiClient.request(path + '?' + params, {
            method:'POST', headers:headers, body:JSON.stringify(body)
        }, options).then(function (response) { return response.json(); });
    }
    return {
        architectures:function () { return read(base + '/architectures', {}); },
        createArchitecture:function (request) { return write(base + '/architectures', request, false); },
        workspaces:function (repositoryId) { return read(base + '/architectures/' + encodeURIComponent(repositoryId) + '/workspaces', {}); },
        createWorkspace:function (context, displayName) { return write('/api/architecture/editor/workspaces', {
            repositoryId:context.repositoryId, branch:context.branch, displayName:displayName
        }, false, context); },
        // Selection is explicit. A command Context.revision is never a historical selection.
        overview:function (context, commit, revision) { return read(base, context, {commit:commit, revision:revision}); },
        history:function (context, filters) { return read(base + '/history', context, filters); },
        projects:function (context) { return read('/api/projects', context); },
        requirement:function (context, projectId, requirementId) { return read('/api/projects/' + projectId + '/requirements/' + requirementId, context); },
        previewReview:function (request) { return write(base + '/realizations/preview', request, true); },
        acceptReview:function (request) { return write(base + '/realizations', request, true, null, true); },
        saveRequirement:function (request) { return write('/api/architecture/editor/requirements', request, true, null, true); },
        reviewRequirementIntake:function (request) { return write(base + '/intake-review', request, false); },
        previewReconciliation:function (request) { return write('/api/architecture/editor/versions/reconciliation/preview', request, true); },
        acceptReconciliation:function (request) { return write('/api/architecture/editor/versions/reconciliation/accept', request, true, null, true); }
    };
}());
