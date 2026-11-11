/* copilot-api.js – persistent requirement Copilot/Autopilot API boundary. */
window.TaxonomyCopilotApi = (function () {
    'use strict';

    var client = window.TaxonomyApiClient;
    function sourceRequest(url) {
        const scoped = window.TaxonomyArchitectureWorkflow?.scopeUrl?.(url) || url;
        const target = new URL(scoped, window.location.href);
        const options = target.searchParams.has('workspaceId')
            ? {pinnedWorkspaceId: target.searchParams.get('workspaceId')} : {};
        return {url: scoped, options};
    }
    function getJson(url) {
        const source = sourceRequest(url);
        return client.requestJson(source.url, {method:'GET', cache:'no-store',
            headers:{Accept:'application/json'}}, source.options);
    }
    function postJson(url, body) {
        const source = sourceRequest(url);
        return client.sendJson(source.url, body === undefined ? {} : body, 'POST', source.options);
    }

    function positiveInteger(value, name) {
        const parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed < 1) {
            throw new TypeError(name + ' must be a positive integer');
        }
        return parsed;
    }

    function projectPath(projectId) {
        return '/api/projects/' + positiveInteger(projectId, 'projectId');
    }

    return {
        status: function () {
            return client.getJson('/api/ai-automation', {contextSensitive: false, workspaceScoped: false});
        },
        start: function (projectId, requirementId, request) {
            return postJson(projectPath(projectId) + '/requirements/'
                + positiveInteger(requirementId, 'requirementId') + '/copilot',
                Object.assign({analysisLanguage: window.TaxonomyContentLanguage?.analysis()}, request));
        },
        latest: function (projectId, requirementId) {
            return getJson(projectPath(projectId) + '/requirements/'
                + positiveInteger(requirementId, 'requirementId') + '/copilot/latest');
        },
        get: function (projectId, operationId) {
            return getJson(projectPath(projectId) + '/copilot-operations/'
                + encodeURIComponent(String(operationId)));
        },
        resume: function (projectId, operationId) {
            return postJson(projectPath(projectId) + '/copilot-operations/'
                + encodeURIComponent(String(operationId)) + '/resume', {});
        },
        cancel: function (projectId, operationId) {
            return postJson(projectPath(projectId) + '/copilot-operations/'
                + encodeURIComponent(String(operationId)) + '/cancel', {});
        }
    };
}());
