/* Context-path adapter for the analysis-session workspace transport. */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before context-path routing');

    var runtime = C.runtime;
    var WORKSPACE_HEADER = 'X-Taxonomy-Workspace-Id';
    var ROOT_MARKER = '__taxonomyWorkspaceRouting';
    var CONTEXT_PATH_MARKER = '__taxonomyContextPathWorkspaceRouting';
    var installRootFetchRouting = C.installWorkspaceFetchRouting;
    var installRootEventSourceRouting = C.installWorkspaceEventSourceRouting;

    function requestUrl(input) {
        try {
            if (typeof input === 'string') return new URL(input, window.location.href);
            if (input && typeof input.url === 'string') {
                return new URL(input.url, window.location.href);
            }
        } catch (error) {
            return null;
        }
        return null;
    }

    function applicationApiPrefix() {
        var resolved = requestUrl(window.TaxonomyI18n
                && typeof window.TaxonomyI18n.resolveUrl === 'function'
            ? window.TaxonomyI18n.resolveUrl('/api/')
            : '/api/');
        var prefix = resolved ? resolved.pathname : '/api/';
        return prefix.endsWith('/') ? prefix : prefix + '/';
    }

    function isPrefixedApplicationApiUrl(url) {
        if (!url || url.origin !== window.location.origin) return false;
        var prefix = applicationApiPrefix();
        if (prefix === '/api/') return false;
        var root = prefix.substring(0, prefix.length - 1);
        return url.pathname === root || url.pathname.indexOf(prefix) === 0;
    }

    function markRoutingInstalled(target) {
        [ROOT_MARKER, CONTEXT_PATH_MARKER].forEach(function (marker) {
            Object.defineProperty(target, marker, {
                configurable: false,
                enumerable: false,
                value: true,
                writable: false
            });
        });
    }

    C.installWorkspaceFetchRouting = function installWorkspaceFetchRouting() {
        installRootFetchRouting();
        if (window.fetch[CONTEXT_PATH_MARKER] === true) return;
        var previousFetch = window.fetch.bind(window);

        function routedFetch(input, init) {
            var url = requestUrl(input);
            var request = Object.assign({}, init || {});
            if (runtime.workspaceId && isPrefixedApplicationApiUrl(url)) {
                var headers = new Headers(input instanceof Request ? input.headers : undefined);
                new Headers(request.headers || {}).forEach(function (value, name) {
                    headers.set(name, value);
                });
                headers.set(WORKSPACE_HEADER, runtime.workspaceId);
                request.headers = headers;
            }
            return previousFetch(input, request);
        }

        markRoutingInstalled(routedFetch);
        window.fetch = routedFetch;
    };

    C.installWorkspaceEventSourceRouting = function installWorkspaceEventSourceRouting() {
        installRootEventSourceRouting();
        var PreviousEventSource = window.EventSource;
        if (typeof PreviousEventSource !== 'function'
                || PreviousEventSource[CONTEXT_PATH_MARKER] === true) return;

        function RoutedEventSource(url, configuration) {
            var resolved = requestUrl(url);
            var target = url;
            if (runtime.workspaceId && isPrefixedApplicationApiUrl(resolved)) {
                resolved.searchParams.set('workspaceId', runtime.workspaceId);
                target = resolved.href;
            }
            return new PreviousEventSource(target, configuration);
        }

        RoutedEventSource.prototype = PreviousEventSource.prototype;
        ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function (constant) {
            if (constant in PreviousEventSource) {
                RoutedEventSource[constant] = PreviousEventSource[constant];
            }
        });
        markRoutingInstalled(RoutedEventSource);
        window.EventSource = RoutedEventSource;
    };
}());
