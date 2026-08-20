/* taxonomy-analysis-session-api-routing.js – tab-stable context for named API clients */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    var client = window.TaxonomyApiClient;
    if (!C || !client || client.__taxonomyWorkspaceRouting === true) return;

    var runtime = C.runtime;
    var WORKSPACE_QUERY_PARAMETER = 'workspaceId';

    function workspaceScopedUrl(input) {
        if (!runtime.workspaceId || typeof input !== 'string') return input;
        var resolved;
        try {
            resolved = new URL(input, window.location.href);
        } catch (error) {
            return input;
        }
        if (resolved.origin !== window.location.origin
                || resolved.pathname.indexOf('/api/') !== 0) {
            return input;
        }
        resolved.searchParams.set(WORKSPACE_QUERY_PARAMETER, runtime.workspaceId);
        return /^[a-z][a-z0-9+.-]*:/i.test(input)
            ? resolved.href
            : resolved.pathname + resolved.search + resolved.hash;
    }

    [
        'request',
        'getJson',
        'sendJson',
        'sendFormData',
        'deleteJson'
    ].forEach(function (methodName) {
        var original = client[methodName];
        if (typeof original !== 'function') return;
        client[methodName] = function () {
            var args = Array.prototype.slice.call(arguments);
            args[0] = workspaceScopedUrl(args[0]);
            return original.apply(client, args);
        };
    });

    Object.defineProperty(client, '__taxonomyWorkspaceRouting', {
        configurable: false,
        enumerable: false,
        value: true,
        writable: false
    });

    C.workspaceScopedApiUrl = workspaceScopedUrl;
}());
