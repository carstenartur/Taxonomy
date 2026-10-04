/* analysis-session-api.js – HTTP boundary for resumable ad-hoc analysis state */
(function () {
    'use strict';
    // Capture the native constructor before active-tab routing is installed. Every
    // operation stream below carries its original explicit workspace pin.
    var RunEventSource = window.EventSource;

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
        if (scope.workspaceId !== undefined) {
            var workspacePin = scope.workspaceId === null ? '' : scope.workspaceId;
            query.push('workspaceId=' + encodeURIComponent(workspacePin));
            headers['X-Taxonomy-Workspace-Id'] = workspacePin;
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

    function getRunResult(operationId, scope) {
        return runRequest(operationId, '/result', scope, 'GET');
    }

    function getRunInput(operationId, scope) {
        return runRequest(operationId, '/request', scope, 'GET');
    }

    function getRecentRuns(scope) {
        scope = scope || {};
        var workspace = scope.workspaceId == null ? '' : scope.workspaceId;
        return request('/api/analysis-runs?workspaceId=' + encodeURIComponent(workspace), {
            method: 'GET', cache: 'no-store', signal: scope.signal,
            headers: { 'X-Taxonomy-Workspace-Id': workspace }
        }, workspace);
    }

    function openRunEvents(operationId, scope) {
        scope = scope || {};
        if (typeof RunEventSource !== 'function') throw new Error('EVENT_STREAM_UNAVAILABLE');
        var cursor = scope.afterSequence === undefined ? 0 : scope.afterSequence;
        if (!Number.isSafeInteger(cursor) || cursor < 0) throw new Error('INVALID_REPLAY_CURSOR');
        var url = '/api/analysis-runs/' + encodeURIComponent(operationId) + '/events?workspaceId='
            + encodeURIComponent(scope.workspaceId == null ? '' : scope.workspaceId)
            + '&afterSequence=' + cursor;
        var i18n = window.TaxonomyI18n;
        if (i18n && typeof i18n.resolveUrl === 'function') url = i18n.resolveUrl(url);
        // Native reconnect retains Last-Event-ID and this exact URL; it never
        // resolves another active workspace or resubmits the analysis request.
        return new RunEventSource(url);
    }

    window.TaxonomyAnalysisSessionApi = Object.freeze({
        request: request,
        getRunStatus: getRunStatus,
        cancelRun: cancelRun,
        getRunCallDetail: getRunCallDetail,
        getRunResult: getRunResult,
        getRunInput: getRunInput,
        getRecentRuns: getRecentRuns,
        openRunEvents: openRunEvents
    });
}());
