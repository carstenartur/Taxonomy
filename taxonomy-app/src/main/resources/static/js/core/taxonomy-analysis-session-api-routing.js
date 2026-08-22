/* taxonomy-analysis-session-api-routing.js – tab-stable API and SSE context */
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

    function resolveApplicationUrl(input) {
        var i18n = window.TaxonomyI18n;
        return i18n && typeof i18n.resolveUrl === 'function'
            ? i18n.resolveUrl(input) : input;
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

    function workspaceScopedUrl(input) {
        if (!runtime.workspaceId || typeof input !== 'string') return input;
        var resolved;
        try {
            resolved = new URL(input, window.location.href);
        } catch (error) {
            return input;
        }
        if (!isSameApplicationApi(resolved)) return input;

        resolved.searchParams.set(WORKSPACE_QUERY_PARAMETER, runtime.workspaceId);
        return /^[a-z][a-z0-9+.-]*:/i.test(input)
            ? resolved.href
            : resolved.pathname + resolved.search + resolved.hash;
    }

    function installNamedClientRouting() {
        if (!client || client.__taxonomyWorkspaceRouting === true) return;
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
    }

    function installWorkspaceEventSourceRouting() {
        var NativeEventSource = window.EventSource;
        if (typeof NativeEventSource !== 'function'
                || NativeEventSource.__taxonomyWorkspaceRouting === true) return;

        function RoutedEventSource(url, configuration) {
            var target = resolveApplicationUrl(url);
            var resolved;
            try {
                resolved = new URL(target, window.location.href);
            } catch (error) {
                return new NativeEventSource(target, configuration);
            }
            if (runtime.workspaceId && isSameApplicationApi(resolved)) {
                resolved.searchParams.set(WORKSPACE_QUERY_PARAMETER, runtime.workspaceId);
                target = resolved.href;
            }
            return new NativeEventSource(target, configuration);
        }

        RoutedEventSource.prototype = NativeEventSource.prototype;
        ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function (constant) {
            if (constant in NativeEventSource) {
                RoutedEventSource[constant] = NativeEventSource[constant];
            }
        });
        Object.defineProperty(RoutedEventSource, '__taxonomyWorkspaceRouting', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
        window.EventSource = RoutedEventSource;
    }

    installNamedClientRouting();
    C.workspaceScopedApiUrl = workspaceScopedUrl;
    C.installWorkspaceEventSourceRouting = installWorkspaceEventSourceRouting;
}());
