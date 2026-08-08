/* taxonomy-api-client.js – Canonical HTTP transport for the Taxonomy UI.
 *
 * Named api/*.js feature clients use this transport for JSON and FormData.
 * Streaming and large-download adapters remain deliberately separate.
 * The legacy global interceptor keeps direct fetch() debt CSRF-safe until those
 * callers are migrated, while named helpers use request() directly.
 */
window.TaxonomyApiClient = (function () {
    'use strict';

    // taxonomy-i18n.js installs the external base-path wrapper before this file.
    // Capture that wrapper once, then apply the remaining transport policy here.
    var transportFetch = window.fetch.bind(window);
    var accountContextPromise = null;
    var DEFAULT_TIMEOUT_MILLIS = 30000;
    var REQUEST_ID_HEADER = 'X-Request-ID';

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

    function createRequestId() {
        if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
            return crypto.randomUUID();
        }
        return 'taxonomy-' + Date.now().toString(36) + '-' +
            Math.random().toString(36).slice(2);
    }

    function responseRequestId(response, fallback) {
        if (!response || !response.headers) return fallback;
        return response.headers.get(REQUEST_ID_HEADER)
            || response.headers.get('X-Correlation-ID')
            || fallback;
    }

    function ApiError(message, status, url, responseBody, metadata) {
        var details = metadata || {};
        this.name = 'ApiError';
        this.message = message;
        this.status = status || 0;
        this.url = url || '';
        this.responseBody = responseBody;
        this.type = details.type || null;
        this.title = details.title || null;
        this.detail = details.detail || null;
        this.instance = details.instance || null;
        this.requestId = details.requestId || null;
        this.code = details.code || 'HTTP_ERROR';
        this.retryable = Boolean(details.retryable);
        this.cause = details.cause || null;
        if (Error.captureStackTrace) Error.captureStackTrace(this, ApiError);
    }
    ApiError.prototype = Object.create(Error.prototype);
    ApiError.prototype.constructor = ApiError;

    function parseResponseBody(response) {
        return response.clone().text().then(function (text) {
            if (!text) return null;
            try {
                return JSON.parse(text);
            } catch (ignored) {
                return text;
            }
        }).catch(function () { return null; });
    }

    function dispatchAuthFailure(error) {
        if (error.status !== 401 && error.status !== 403) return;
        document.dispatchEvent(new CustomEvent('taxonomy-api-auth-failure', {
            detail: {
                status: error.status,
                url: error.url,
                requestId: error.requestId,
                code: error.code
            }
        }));
    }

    function checkStatus(response, context) {
        if (response.ok) return Promise.resolve(response);
        return parseResponseBody(response).then(function (body) {
            var problem = body && typeof body === 'object' ? body : {};
            var detail = problem.detail || problem.message || problem.error
                || (typeof body === 'string' ? body : null);
            var title = problem.title || null;
            var message = detail || title
                ? 'HTTP ' + response.status + ': ' + (detail || title)
                : 'HTTP ' + response.status;
            var error = new ApiError(message, response.status,
                response.url || context.url, body, {
                    type: problem.type,
                    title: title,
                    detail: detail,
                    instance: problem.instance,
                    requestId: responseRequestId(response, context.requestId),
                    code: 'HTTP_ERROR',
                    retryable: [429, 502, 503, 504].indexOf(response.status) >= 0
                });
            dispatchAuthFailure(error);
            throw error;
        });
    }

    function parseJson(response) {
        if (response.status === 204) return null;
        return response.json().catch(function (error) {
            throw new ApiError('Invalid JSON response from server', response.status,
                response.url, null, {
                    requestId: responseRequestId(response, null),
                    code: 'INVALID_JSON',
                    cause: error
                });
        });
    }

    function validateOptions(options) {
        var retries = options.retries === undefined ? 0 : Number(options.retries);
        if (!Number.isInteger(retries) || retries < 0) {
            throw new TypeError('API retry count must be a non-negative integer');
        }
        if (retries > 0 && options.idempotent !== true) {
            throw new TypeError('Automatic API retry requires idempotent: true');
        }
        var timeoutMillis = options.timeoutMillis === undefined
            ? DEFAULT_TIMEOUT_MILLIS : Number(options.timeoutMillis);
        if (!Number.isFinite(timeoutMillis) || timeoutMillis < 0) {
            throw new TypeError('API timeoutMillis must be a non-negative number');
        }
        return { retries: retries, timeoutMillis: timeoutMillis };
    }

    function createRequestScope(options, validated) {
        var controller = new AbortController();
        var timedOut = false;
        var callerAborted = false;
        var timer = null;
        var callerSignal = options.signal || null;
        var onCallerAbort = function () {
            callerAborted = true;
            controller.abort(callerSignal.reason);
        };

        if (callerSignal) {
            if (callerSignal.aborted) onCallerAbort();
            else callerSignal.addEventListener('abort', onCallerAbort, { once: true });
        }
        if (validated.timeoutMillis > 0) {
            timer = setTimeout(function () {
                timedOut = true;
                controller.abort();
            }, validated.timeoutMillis);
        }
        return {
            signal: controller.signal,
            timedOut: function () { return timedOut; },
            callerAborted: function () { return callerAborted; },
            cleanup: function () {
                if (timer !== null) clearTimeout(timer);
                if (callerSignal) callerSignal.removeEventListener('abort', onCallerAbort);
            }
        };
    }

    function prepareRequest(input, init, options, requestId, signal) {
        var prepared = Object.assign({}, init || {});
        var method = (prepared.method || (input instanceof Request ? input.method : 'GET'))
            .toUpperCase();
        var inheritedHeaders = input instanceof Request ? input.headers : undefined;
        var headers = new Headers(prepared.headers || inheritedHeaders || {});
        var sameOrigin = isSameOrigin(input);
        var metadata = csrfMetadata();

        if (sameOrigin && prepared.credentials === undefined) {
            prepared.credentials = 'same-origin';
        }
        if (sameOrigin && !headers.has(REQUEST_ID_HEADER)) {
            headers.set(REQUEST_ID_HEADER, requestId);
        }
        if (metadata && sameOrigin && method !== 'GET' && method !== 'HEAD'
                && !headers.has(metadata.name)) {
            headers.set(metadata.name, metadata.value);
        }
        prepared.method = method;
        prepared.headers = headers;
        prepared.signal = signal;
        return prepared;
    }

    function normalizeTransportError(error, context, scope, validated) {
        if (error instanceof ApiError) return error;
        if (scope.timedOut()) {
            return new ApiError(
                'Request timed out after ' + validated.timeoutMillis + ' ms',
                0, context.url, null, {
                    requestId: context.requestId,
                    code: 'TIMEOUT',
                    retryable: true,
                    cause: error
                });
        }
        if (scope.callerAborted()) {
            return new ApiError('Request was cancelled', 0, context.url, null, {
                requestId: context.requestId,
                code: 'ABORTED',
                retryable: false,
                cause: error
            });
        }
        return new ApiError('Network request failed', 0, context.url, null, {
            requestId: context.requestId,
            code: 'NETWORK_ERROR',
            retryable: true,
            cause: error
        });
    }

    function request(url, init, options) {
        var requestOptions = Object.assign({}, options || {});
        var validated = validateOptions(requestOptions);
        var requestId = requestOptions.requestId || createRequestId();
        var context = { url: inputUrl(url), requestId: requestId };

        function attempt(number) {
            var scope = createRequestScope(requestOptions, validated);
            var prepared = prepareRequest(
                url, init, requestOptions, requestId, scope.signal);
            return transportFetch(url, prepared)
                .then(function (response) { return checkStatus(response, context); })
                .catch(function (error) {
                    throw normalizeTransportError(error, context, scope, validated);
                })
                .finally(scope.cleanup)
                .catch(function (error) {
                    var callerCancelled = requestOptions.signal
                        && requestOptions.signal.aborted;
                    if (!callerCancelled && number < validated.retries
                            && error.retryable && requestOptions.idempotent === true) {
                        return attempt(number + 1);
                    }
                    throw error;
                });
        }
        return attempt(0);
    }

    function getAccountContext() {
        if (!accountContextPromise) {
            accountContextPromise = getJson('/api/account/me', {
                idempotent: true,
                timeoutMillis: 10000
            }).catch(function (error) {
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

    function getJson(url, options) {
        var requestOptions = Object.assign({ idempotent: true }, options || {});
        return request(url, { method: 'GET' }, requestOptions).then(parseJson);
    }

    function sendJson(url, body, method, options) {
        var actualMethod = method;
        var requestOptions = options;
        if (actualMethod && typeof actualMethod === 'object') {
            requestOptions = actualMethod;
            actualMethod = null;
        }
        return request(url, {
            method: actualMethod || 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }, requestOptions).then(parseJson);
    }

    function sendFormData(url, formData, method, options) {
        var actualMethod = method;
        var requestOptions = options;
        if (actualMethod && typeof actualMethod === 'object') {
            requestOptions = actualMethod;
            actualMethod = null;
        }
        return request(url, {
            method: actualMethod || 'POST',
            body: formData
        }, requestOptions).then(parseJson);
    }

    function deleteJson(url, options) {
        return request(url, { method: 'DELETE' }, options).then(parseJson);
    }

    installGlobalCsrfInterceptor();

    return {
        ApiError: ApiError,
        request: request,
        getJson: getJson,
        getAccountContext: getAccountContext,
        sendJson: sendJson,
        sendFormData: sendFormData,
        deleteJson: deleteJson,
        csrfHeaders: csrfHeaders,
        defaultTimeoutMillis: DEFAULT_TIMEOUT_MILLIS
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
