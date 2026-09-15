/* analysis-session-api.js – HTTP boundary for resumable ad-hoc analysis state */
(function () {
    'use strict';

    function request(url, options, pinnedWorkspaceId) {
        var client = window.TaxonomyApiClient;
        if (!client || typeof client.request !== 'function') {
            return Promise.reject(new Error('Taxonomy API client is not available'));
        }
        var method = String((options && options.method) || 'GET').toUpperCase();
        return client.request(url, options, {
            idempotent: method === 'GET' || method === 'HEAD',
            signal: options && options.signal,
            pinnedWorkspaceId: pinnedWorkspaceId
        });
    }

    // Return the raw Response: an initial 202 has no JSON body. The canonical
    // client still owns CSRF, same-origin credentials, request IDs and errors.
    // Scope is captured by the monitor; never resolve the newly active workspace.
    function runRequest(operationId, suffix, scope, method) {
        scope = scope || {};
        var url = '/api/analysis-runs/' + encodeURIComponent(operationId) + suffix;
        var headers = {};
        var query = [];
        if (scope.workspaceId) {
            query.push('workspaceId=' + encodeURIComponent(scope.workspaceId));
            headers['X-Taxonomy-Workspace-Id'] = scope.workspaceId;
        }
        if (!suffix && scope.waitForRegistration) query.push('waitForRegistration=true');
        if (query.length) url += '?' + query.join('&');
        var options = { method: method, headers: headers, cache: 'no-store', signal: scope.signal };
        if (method === 'POST') options.keepalive = true;
        // The existing client captured the external base-path wrapper at startup.
        // No automatic retries: polling owns observation retries, never writes.
        return request(url, options, scope.workspaceId);
    }

    function getRunStatus(operationId, scope) {
        return runRequest(operationId, '', scope, 'GET');
    }

    function cancelRun(operationId, scope) {
        return runRequest(operationId, '/cancel', scope, 'POST');
    }

    function getRunCallDetail(operationId, callId, scope) {
        return runRequest(operationId, '/calls/' + encodeURIComponent(callId), scope, 'GET');
    }

    window.TaxonomyAnalysisSessionApi = Object.freeze({
        request: request,
        getRunStatus: getRunStatus,
        cancelRun: cancelRun,
        getRunCallDetail: getRunCallDetail
    });
}());
