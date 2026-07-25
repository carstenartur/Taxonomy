/* taxonomy-api-client.js – Base HTTP client for the Taxonomy UI.
 *
 * Provides named helpers for JSON GET/POST/PUT/DELETE and FormData uploads.
 * All helpers read CSRF metadata from <meta name="_csrf"> and
 * <meta name="_csrf_header"> when present and inject the header automatically.
 *
 * The global interceptor keeps legacy direct fetch() calls CSRF-safe while they
 * are incrementally migrated to named api/*.js functions.
 *
 * Convention: new UI code must call named functions from an api/*.js module
 * instead of constructing fetch('/api/…') calls directly. See
 * docs/dev/03-ui-task-map.md for details.
 */
window.TaxonomyApiClient = (function () {
    'use strict';

    var nativeFetch = window.fetch.bind(window);
    var accountContextPromise = null;

    function csrfMetadata() {
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !token.content) return null;
        return {
            name: (header && header.content) || 'X-CSRF-TOKEN',
            value: token.content
        };
    }

    function csrfHeaders() {
        var metadata = csrfMetadata();
        if (!metadata) return {};
        var headers = {};
        headers[metadata.name] = metadata.value;
        return headers;
    }

    function inputUrl(input) {
        if (typeof input === 'string' || input instanceof URL) return String(input);
        return input && input.url ? input.url : '';
    }

    function isSameOrigin(input) {
        try {
            return new URL(inputUrl(input), window.location.href).origin === window.location.origin;
        } catch (ignored) {
            return true;
        }
    }

    function requestPath(input) {
        try {
            return new URL(inputUrl(input), window.location.href).pathname;
        } catch (ignored) {
            return '';
        }
    }

    function ApiError(message, status, url, responseBody) {
        this.name = 'ApiError';
        this.message = message;
        this.status = status;
        this.url = url;
        this.responseBody = responseBody;
        if (Error.captureStackTrace) Error.captureStackTrace(this, ApiError);
    }
    ApiError.prototype = Object.create(Error.prototype);
    ApiError.prototype.constructor = ApiError;

    function checkStatus(response) {
        if (response.ok) return response;
        return response.clone().json()
            .catch(function () {
                return response.clone().text().catch(function () { return ''; });
            })
            .then(function (body) {
                var serverMessage = body && typeof body === 'object'
                    ? (body.detail || body.message || body.error) : body;
                var message = serverMessage
                    ? 'HTTP ' + response.status + ': ' + serverMessage
                    : 'HTTP ' + response.status;
                throw new ApiError(message, response.status, response.url, body);
            });
    }

    function parseJson(response) {
        if (response.status === 204) return null;
        return response.json().catch(function () {
            throw new ApiError('Invalid JSON response from server', response.status, response.url, null);
        });
    }

    function getAccountContext() {
        if (!accountContextPromise) {
            accountContextPromise = nativeFetch('/api/account/me')
                .then(checkStatus)
                .then(parseJson)
                .catch(function (error) {
                    accountContextPromise = null;
                    throw error;
                });
        }
        return accountContextPromise;
    }

    function isAdminPromptBootstrap(input, method) {
        if (method !== 'GET' || !isSameOrigin(input)) return false;
        var path = requestPath(input);
        return path === '/api/prompts' || path === '/api/prompts/categories';
    }

    function emptyPromptBootstrapResponse(input) {
        var body = requestPath(input) === '/api/prompts/categories' ? '{}' : '[]';
        return new Response(body, {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
        });
    }

    function installGlobalCsrfInterceptor() {
        if (window.fetch.__taxonomyCsrfInterceptor) return;
        var originalFetch = window.fetch.bind(window);

        function csrfAwareFetch(input, init) {
            var requestInit = Object.assign({}, init || {});
            var method = (requestInit.method || (input instanceof Request ? input.method : 'GET'))
                .toUpperCase();
            var metadata = csrfMetadata();
            if (metadata && isSameOrigin(input) && method !== 'GET' && method !== 'HEAD') {
                var inheritedHeaders = input instanceof Request ? input.headers : undefined;
                requestInit.headers = new Headers(requestInit.headers || inheritedHeaders || {});
                if (!requestInit.headers.has(metadata.name)) {
                    requestInit.headers.set(metadata.name, metadata.value);
                }
            }

            // The prompt editor lives inside the hidden ADMIN tab but legacy UI
            // initialization starts it for every role. Resolve the cached account
            // context first: admins use the real endpoints, while non-admins receive
            // an empty bootstrap model without issuing forbidden background requests.
            if (isAdminPromptBootstrap(input, method)) {
                return getAccountContext().then(function (account) {
                    return account && account.administrator
                        ? originalFetch(input, requestInit)
                        : emptyPromptBootstrapResponse(input);
                });
            }
            return originalFetch(input, requestInit);
        }

        csrfAwareFetch.__taxonomyCsrfInterceptor = true;
        csrfAwareFetch.__taxonomyOriginalFetch = originalFetch;
        window.fetch = csrfAwareFetch;
    }

    function getJson(url) {
        return fetch(url).then(checkStatus).then(parseJson);
    }

    function sendJson(url, body, method) {
        return fetch(url, {
            method: method || 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, csrfHeaders()),
            body: JSON.stringify(body)
        }).then(checkStatus).then(parseJson);
    }

    function sendFormData(url, formData, method) {
        return fetch(url, {
            method: method || 'POST',
            headers: csrfHeaders(),
            body: formData
        }).then(checkStatus).then(parseJson);
    }

    function deleteJson(url) {
        return fetch(url, {
            method: 'DELETE',
            headers: csrfHeaders()
        }).then(checkStatus).then(parseJson);
    }

    installGlobalCsrfInterceptor();

    return {
        ApiError: ApiError,
        getJson: getJson,
        getAccountContext: getAccountContext,
        sendJson: sendJson,
        sendFormData: sendFormData,
        deleteJson: deleteJson,
        csrfHeaders: csrfHeaders
    };
}());

(function loadAuthenticatedUiSurfaces() {
    'use strict';

    function ensureDocumentImportSpinner() {
        var button = document.getElementById('docImportUploadBtn');
        if (!button || document.getElementById('docImportSpinner')) return;
        // Thymeleaf's legacy th:text on the button replaces its nested spinner.
        // Restore the control synchronously before taxonomy-document-import.js
        // captures its element references and installs the click handler.
        var spinner = document.createElement('span');
        spinner.id = 'docImportSpinner';
        spinner.className = 'spinner-border spinner-border-sm d-none me-1';
        spinner.setAttribute('role', 'status');
        spinner.setAttribute('aria-hidden', 'true');
        button.prepend(spinner);
    }

    function loadSurface(globalName, marker, source) {
        if (window[globalName] || document.querySelector('script[' + marker + ']')) return;
        var script = document.createElement('script');
        script.src = source;
        script.async = false;
        script.setAttribute(marker, 'true');
        document.head.appendChild(script);
    }

    ensureDocumentImportSpinner();
    loadSurface('TaxonomyRoleSurface', 'data-taxonomy-role-surface',
        '/js/security/taxonomy-role-surface.js');
    loadSurface('TaxonomyUiSemantics', 'data-taxonomy-ui-semantics',
        '/js/security/taxonomy-ui-semantics.js');
}());
