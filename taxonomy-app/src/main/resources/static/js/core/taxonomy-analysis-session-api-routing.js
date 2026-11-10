/* taxonomy-analysis-session-api-routing.js – tab-stable named API clients */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) return;

    var client = window.TaxonomyApiClient;
    var runtime = C.runtime;
    var WORKSPACE_QUERY_PARAMETER = 'workspaceId';

    function applicationBasePath() {
        var i18n = window.TaxonomyI18n;
        return i18n && typeof i18n.getBasePath === 'function'
            ? String(i18n.getBasePath() || '') : '';
    }

    function applicationApiPath(resolved) {
        var pathname = resolved.pathname;
        var basePath = applicationBasePath();
        if (basePath && pathname.indexOf(basePath + '/') === 0) {
            pathname = pathname.substring(basePath.length);
        }
        return pathname;
    }

    function isSameApplicationApi(resolved) {
        return resolved
            && resolved.origin === window.location.origin
            && applicationApiPath(resolved).indexOf('/api/') === 0;
    }

    function workspaceScopedUrl(input, pinnedWorkspaceId) {
        // An explicit null/empty pin means central scope, not the active tab.
        var explicitPin = pinnedWorkspaceId !== undefined;
        var workspaceId = explicitPin ? pinnedWorkspaceId : runtime.workspaceId;
        if ((!explicitPin && !workspaceId) || typeof input !== 'string') return input;
        var resolved;
        try {
            resolved = new URL(input, window.location.href);
        } catch (error) {
            return input;
        }
        if (!isSameApplicationApi(resolved)) return input;
        // A URL pin is already an immutable source selection. Only an explicit
        // policy may replace it; implicit tab routing must preserve central too.
        if (!explicitPin && resolved.searchParams.has(WORKSPACE_QUERY_PARAMETER)) return input;

        // Database setup manages installation-wide preferences and connection
        // guidance, so it must remain available when the active workspace is
        // unavailable. Retain any explicit caller pin for server validation.
        var path = applicationApiPath(resolved);
        if (!explicitPin && (path === '/api/admin/database-setup'
                || path.indexOf('/api/admin/database-setup/') === 0)) return input;

        resolved.searchParams.set(WORKSPACE_QUERY_PARAMETER, workspaceId == null ? '' : workspaceId);
        return /^[a-z][a-z0-9+.-]*:/i.test(input)
            ? resolved.href
            : resolved.pathname + resolved.search + resolved.hash;
    }

    function installNamedClientRouting() {
        if (!client || client.__taxonomyWorkspaceRouting === true) return;
        [
            'request',
            'requestJson',
            'requestBlob',
            'getJson',
            'sendJson',
            'sendFormData',
            'deleteJson'
        ].forEach(function (methodName) {
            var original = client[methodName];
            if (typeof original !== 'function') return;
            client[methodName] = function () {
                var args = Array.prototype.slice.call(arguments);
                // Explicitly scoped operation calls (including cancellation after a
                // tab switch) must retain the same workspace in URL and header.
                var policy = methodName === 'request' || methodName === 'requestJson' || methodName === 'requestBlob' ? args[2]
                    : methodName === 'getJson' || methodName === 'deleteJson' ? args[1]
                    : typeof args[2] === 'object' ? args[2] : args[3];
                if (!policy || policy.workspaceScoped !== false || policy.pinnedWorkspaceId !== undefined) {
                    args[0] = workspaceScopedUrl(args[0], policy ? policy.pinnedWorkspaceId : undefined);
                }
                return original.apply(client, args);
            };
        });

        Object.defineProperty(client, '__taxonomyWorkspaceRouting', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
    }

    installNamedClientRouting();
    C.workspaceScopedApiUrl = workspaceScopedUrl;
}());
