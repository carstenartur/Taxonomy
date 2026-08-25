/* Context-path adapter for analysis-session workspace fetch transport. */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before context-path routing');

    var runtime = C.runtime;
    var WORKSPACE_HEADER = 'X-Taxonomy-Workspace-Id';
    var ROOT_MARKER = '__taxonomyWorkspaceRouting';
    var CONTEXT_PATH_MARKER = '__taxonomyContextPathWorkspaceRouting';
    var installRootFetchRouting = C.installWorkspaceFetchRouting;

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
        // Preserve the core route for ordinary /api/** callers. This additional
        // wrapper covers callers that already resolved the servlet prefix before
        // invoking fetch, for example Request objects created with /taxonomy/api/**.
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

    // SSE remains owned by taxonomy-analysis-session-api-routing.js, which loads
    // next and already resolves the base path plus the workspace query parameter.
}());
