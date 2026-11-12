/* taxonomy-api-client.js – Canonical HTTP transport for the Taxonomy UI.
 *
 * Named api/*.js feature clients use this transport for JSON and FormData.
 * requestBlob() keeps cancellation and timeout active until download bodies finish.
 * Streaming adapters remain deliberately separate.
 * The legacy global interceptor keeps direct fetch() debt CSRF-safe until those
 * callers are migrated, while named helpers use request() directly.
 */
window.TaxonomyApiClient = (function () {
    'use strict';

    // taxonomy-i18n.js installs the external base-path wrapper before this file.
    // Capture that wrapper once, then apply the remaining transport policy here.
    var transportFetch = window.fetch.bind(window);
    var transportAdapters = new Set();
    var activeScopes = new Set();
    var authenticationGeneration = 0;
    var pageGeneration = 0;
    var accountContextPromise = null;
    var responseContexts = new WeakMap();
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

    // A successful requirement save can admit Autopilot work. Capture the
    // personal choice with that write; later passes use the persisted job value.
    function requirementAnalysisLanguage(input, method) {
        if (!['POST', 'PUT', 'PATCH'].includes(method) || !isSameOrigin(input)
                || !/\/api\/projects\/\d+\/requirements(?:\/|$)/.test(requestPath(input))) return null;
        var language = window.TaxonomyContentLanguage?.analysis();
        return language === 'de' || language === 'en' ? language : null;
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

    function workspacePin(input, init, options) {
        if (!isSameOrigin(input) || !/\/api\//.test(requestPath(input))) return undefined;
        var headers = new Headers(init?.headers || (input instanceof Request ? input.headers : {}));
        if (headers.has('X-Taxonomy-Workspace-Id')) return headers.get('X-Taxonomy-Workspace-Id');
        if (options.pinnedWorkspaceId !== undefined) return options.pinnedWorkspaceId == null ? '' : options.pinnedWorkspaceId;
        var target = new URL(inputUrl(input), window.location.href);
        if (target.searchParams.has('workspaceId')) return target.searchParams.get('workspaceId');
        // A retained workspace may already be unavailable while repairing installation settings.
        if (options.workspaceScoped === false || /\/api\/admin\/database-setup(?:\/|$)/.test(target.pathname)) return undefined;
        var workspace = window.__TaxonomyAnalysisSessionContext?.runtime?.workspaceId;
        if (workspace == null && (target.pathname.endsWith('/api/git/state')
                || target.pathname.endsWith('/api/context/current'))) {
            // Named status consumers capture this transport before the legacy
            // workspace fetch wrapper is installed. On a cold reload retain
            // the URL's unambiguous, explicitly selected original scope.
            var entry = new URL(window.location.href).searchParams;
            var original = entry.get('workspaceId');
            if (original && entry.get('workspaceScopeKey') === original) return original;
        }
        return workspace == null ? undefined : workspace;
    }

    function applicationInput(input, init, options) {
        if (typeof input !== 'string') return input;
        var path = window.TaxonomyI18n?.resolveUrl?.(input) || input;
        var pin = workspacePin(path, init, options);
        if (pin === undefined) return path;
        var target = new URL(path, window.location.href);
        target.searchParams.set('workspaceId', pin);
        return /^[a-z][a-z0-9+.-]*:/i.test(path) ? target.href : target.pathname + target.search + target.hash;
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

    function dispatchAuthFailure(error, owningScope) {
        if (error.status !== 401 && error.status !== 403) return;
        authenticationGeneration++;
        accountContextPromise = null;
        activeScopes.forEach(function (scope) {
            if (scope !== owningScope) scope.abort('authentication');
        });
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
        if (response.ok || (response.status !== 401 && response.status !== 403
                && context.acceptedStatuses.indexOf(response.status) >= 0)) {
            responseContexts.set(response, context);
            return Promise.resolve(response);
        }
        // Authority is known at headers. A slow problem body must never keep
        // another interactive result authorized or turn this into a timeout.
        if (response.status === 401 || response.status === 403) {
            context.authError = new ApiError('HTTP ' + response.status, response.status,
                response.url || context.url, null, {
                    requestId: responseRequestId(response, context.requestId), code: 'HTTP_ERROR'
                });
            dispatchAuthFailure(context.authError, context.scope);
        }
        return parseResponseBody(response).then(function (body) {
            var problem = body && typeof body === 'object' ? body : {};
            var detail = [problem.detail, problem.message, problem.error, body].find(function (value) {
                return typeof value === 'string' && value.trim();
            }) || null;
            var title = typeof problem.title === 'string' && problem.title.trim() ? problem.title : null;
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
            throw error;
        });
    }

    function parseJson(response) {
        if (response.status === 204) return null;
        var context = responseContexts.get(response) || {};
        return response.json().catch(function (error) {
            if (error instanceof ApiError) throw error;
            throw new ApiError('Invalid JSON response from server', response.status,
                response.url || context.url, null, {
                    requestId: responseRequestId(response, context.requestId || null),
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
        var invalidated = false;
        var timer = null;
        var callerSignals = options.signals || (options.signal ? [options.signal] : []);
        var listeners = [];
        callerSignals.forEach(function (signal) {
            var abort = function () { callerAborted = true; controller.abort(signal.reason); };
            if (signal.aborted) abort();
            else { signal.addEventListener('abort', abort, {once: true}); listeners.push([signal, abort]); }
        });
        if (validated.timeoutMillis > 0) {
            timer = setTimeout(function () {
                timedOut = true;
                controller.abort();
            }, validated.timeoutMillis);
        }
        var scope = {
            signal: controller.signal,
            idle: function () { if (typeof timer?.unref === 'function') timer.unref(); },
            abort: function (reason) { invalidated = true; controller.abort(reason); },
            timedOut: function () { return timedOut; },
            callerAborted: function () { return callerAborted || invalidated; },
            cleanup: function () {
                if (timer !== null) clearTimeout(timer);
                listeners.forEach(function (entry) { entry[0].removeEventListener('abort', entry[1]); });
                activeScopes.delete(this);
            }
        };
        controller.signal.addEventListener('abort', function () { scope.cleanup(); }, {once: true});
        return scope;
    }

    function installTransportAdapter(name, factory) {
        if (transportAdapters.has(name)) return false;
        if (typeof name !== 'string' || !name || typeof factory !== 'function') {
            throw new TypeError('A named transport adapter factory is required');
        }
        var adapted = factory(transportFetch);
        if (typeof adapted !== 'function') throw new TypeError('A transport adapter must return a fetch function');
        transportFetch = adapted;
        transportAdapters.add(name);
        return true;
    }

    function navigationIdentity(ignoreModelStand) {
        var value = window.TaxonomyContextBar?.getCurrentContext?.();
        var chrome = window.TaxonomyAppChrome?.getContext?.() || {};
        var source = window.TaxonomyArchitectureWorkflow?.getScope?.() || {};
        return JSON.stringify([value?.contextId, value?.branch, value?.commitId,
            window.TaxonomyContextBar?.getSelectionGeneration?.(),
            chrome.project?.id, chrome.requirement?.id, chrome.requirement?.version,
            chrome.architecture?.repositoryId, chrome.architecture?.workspaceScopeKey,
            chrome.architecture?.branch, ignoreModelStand ? null : chrome.architecture?.modelStand,
            chrome.source?.repositoryId, chrome.source?.workspaceScopeKey, chrome.source?.branch,
            chrome.source?.modelStand, chrome.source?.snapshotId, chrome.source?.projectId,
            chrome.source?.requirementId, chrome.source?.requirementVersionId, chrome.source?.commit,
            chrome.source?.revision, source.workspaceId, source.repositoryId, source.branch, source.commitId]);
    }

    function captureContext(options, init, ignoreModelStand) {
        var runtime = window.__TaxonomyAnalysisSessionContext?.runtime;
        var workspace = runtime?.workspaceId;
        var analysis = runtime?.analysisGeneration;
        var navigation = navigationIdentity(ignoreModelStand);
        var authentication = authenticationGeneration;
        var page = pageGeneration;
        return function () {
            return authentication === authenticationGeneration
                && (init?.keepalive === true || page === pageGeneration)
                && (options.contextSensitive === false
                    || (runtime === window.__TaxonomyAnalysisSessionContext?.runtime
                        && workspace === runtime?.workspaceId && analysis === runtime?.analysisGeneration
                        && navigation === navigationIdentity(ignoreModelStand)));
        };
    }

    function cancelled(context) {
        return new ApiError('Request context was superseded', 0, context.url, null,
            {code: 'ABORTED', requestId: context.requestId});
    }

    function assertCurrent(context) {
        if (!context.current()) throw cancelled(context);
    }

    function abortable(promise, signal) {
        return new Promise(function (resolve, reject) {
            var abort = function () { reject(new DOMException('Aborted', 'AbortError')); };
            if (signal.aborted) abort();
            else signal.addEventListener('abort', abort, {once: true});
            Promise.resolve(promise).then(resolve, reject).finally(function () {
                signal.removeEventListener('abort', abort);
            });
        });
    }

    // Every raw body path uses the original request deadline, caller signal and
    // immutable context. Stream reads stay incremental; no private bytes are
    // buffered into a JSON convenience response.
    function guardedResponse(response, context, options, validated, fetchScope, deadline) {
        var readers = new Set(['json', 'text', 'blob', 'arrayBuffer', 'bytes', 'formData']);
        var body;
        function consume(action) {
            return Promise.resolve().then(function () {
                assertCurrent(context);
                if (deadline !== null && Date.now() >= deadline) {
                    fetchScope.abort('deadline');
                    throw new ApiError('Request timed out after ' + validated.timeoutMillis + ' ms',
                        0, context.url, null, {code: 'TIMEOUT', requestId: context.requestId});
                }
                var remaining = deadline === null ? 0 : Math.max(1, deadline - Date.now());
                var scope = createRequestScope(options, {timeoutMillis: remaining});
                scope.contextSensitive = options.contextSensitive !== false;
                scope.current = context.current;
                scope.keepalive = context.keepalive;
                activeScopes.add(scope);
                scope.signal.addEventListener('abort', function () { fetchScope.abort(scope.signal.reason); }, {once: true});
                if (scope.signal.aborted) fetchScope.abort(scope.signal.reason);
                return abortable(Promise.resolve().then(action), scope.signal).then(function (value) {
                    assertCurrent(context);
                    return value;
                }).catch(function (error) {
                    if (!context.current()) throw cancelled(context);
                    throw normalizeTransportError(error, context, fetchScope.timedOut() ? fetchScope : scope, validated);
                }).finally(function () { scope.cleanup(); });
            });
        }
        function streamBody(stream) {
            var safeStream;
            // Pipe/tee/async-iteration use a real stream whose pull operation has
            // the same fence as getReader().read(), including an original timer.
            function delegatedStream() {
                if (!safeStream) {
                    var reader;
                    safeStream = new ReadableStream({
                        pull: function (controller) {
                            if (!reader) reader = stream.getReader();
                            return consume(function () { return reader.read(); }).then(function (part) {
                                if (part.done) { controller.close(); reader.releaseLock(); fetchScope.cleanup(); }
                                else controller.enqueue(part.value);
                            }).catch(function (error) {
                                controller.error(error);
                                reader.cancel(error).catch(function () {});
                            });
                        },
                        cancel: function (reason) { fetchScope.cleanup(); return reader ? reader.cancel(reason) : stream.cancel(reason); }
                    }, {highWaterMark: 0});
                }
                return safeStream;
            }
            return new Proxy(stream, {
                get: function (target, property) {
                    if (property === 'cancel') return function (reason) { fetchScope.cleanup(); return target.cancel(reason); };
                    if (property === 'getReader') return function (readerOptions) {
                        var reader = target.getReader(readerOptions);
                        return new Proxy(reader, {
                            get: function (readerTarget, readerProperty) {
                                if (readerProperty === 'read') return function () {
                                    var args = arguments;
                                    return consume(function () { return readerTarget.read.apply(readerTarget, args); }).then(function (part) {
                                        if (part.done) fetchScope.cleanup();
                                        return part;
                                    });
                                };
                                if (readerProperty === 'closed') return consume(function () { return readerTarget.closed; }).finally(function () { fetchScope.cleanup(); });
                                if (readerProperty === 'cancel') return function (reason) { fetchScope.cleanup(); return readerTarget.cancel(reason); };
                                var value = Reflect.get(readerTarget, readerProperty, readerTarget);
                                return typeof value === 'function' ? value.bind(readerTarget) : value;
                            }
                        });
                    };
                    if (property === 'tee') return function () {
                        return delegatedStream().tee().map(streamBody);
                    };
                    if (property === 'pipeThrough') return function (transform, pipeOptions) {
                        return streamBody(delegatedStream().pipeThrough(transform, pipeOptions));
                    };
                    if (property === 'values' || property === Symbol.asyncIterator) return function (iteratorOptions) {
                        var iterator = delegatedStream().values(iteratorOptions);
                        return {
                            next: function () { return consume(function () { return iterator.next(); }); },
                            return: function (value) { return iterator.return(value); },
                            [Symbol.asyncIterator]: function () { return this; }
                        };
                    };
                    if (property === 'pipeTo') return function (destination, pipeOptions) {
                        var writer = destination.getWriter();
                        var sink = new WritableStream({
                            write: function (chunk) { return consume(function () { return writer.write(chunk); }); },
                            close: function () { return writer.close().finally(function () { writer.releaseLock(); }); },
                            abort: function (reason) { return writer.abort(reason).finally(function () { writer.releaseLock(); }); }
                        });
                        return delegatedStream().pipeTo(sink, pipeOptions);
                    };
                    var value = Reflect.get(target, property, target);
                    return typeof value === 'function' ? value.bind(target) : value;
                }
            });
        }
        var guarded = new Proxy(response, {
            get: function (target, property) {
                if (property === 'clone') return function () {
                    return guardedResponse(target.clone(), context, options, validated, fetchScope, deadline);
                };
                if (property === 'body' && target.body) {
                    if (!body) body = streamBody(target.body);
                    return body;
                }
                if (readers.has(property) && typeof target[property] === 'function') return function () {
                    var args = arguments;
                    return consume(function () { return target[property].apply(target, args); }).finally(function () { fetchScope.cleanup(); });
                };
                var value = Reflect.get(target, property, target);
                return typeof value === 'function' ? value.bind(target) : value;
            }
        });
        responseContexts.set(guarded, context);
        return guarded;
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
        var pin = workspacePin(input, prepared, options);
        if (pin !== undefined && !headers.has('X-Taxonomy-Workspace-Id')) {
            headers.set('X-Taxonomy-Workspace-Id', pin);
        }
        if (metadata && sameOrigin && method !== 'GET' && method !== 'HEAD'
                && !headers.has(metadata.name)) {
            headers.set(metadata.name, metadata.value);
        }
        var analysisLanguage = requirementAnalysisLanguage(input, method);
        if (analysisLanguage && !headers.has('X-Taxonomy-Analysis-Language')) {
            headers.set('X-Taxonomy-Analysis-Language', analysisLanguage);
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
        if (error && error.code && error.name !== 'AbortError') {
            return new ApiError(error.message, error.status || 0, context.url, error.responseBody || null,
                {code: error.code, requestId: context.requestId, cause: error});
        }
        return new ApiError('Network request failed', 0, context.url, null, {
            requestId: context.requestId,
            code: 'NETWORK_ERROR',
            retryable: true,
            cause: error
        });
    }

    function performRequest(url, init, options, consumeResponse) {
        var requestOptions = Object.assign({}, options || {});
        requestOptions.signals = Array.from(new Set([requestOptions.signal, init?.signal,
            url instanceof Request ? url.signal : undefined].filter(Boolean)));
        if (requestOptions.signal === undefined) requestOptions.signal = requestOptions.signals[0];
        url = applicationInput(url, init, requestOptions);
        var validated = validateOptions(requestOptions);
        var suppliedHeaders = new Headers(init?.headers || (url instanceof Request ? url.headers : {}));
        var requestId = suppliedHeaders.get(REQUEST_ID_HEADER) || requestOptions.requestId || createRequestId();
        var context = {url: inputUrl(url), requestId: requestId,
            acceptedStatuses: Array.isArray(requestOptions.acceptedStatuses) ? requestOptions.acceptedStatuses : [],
            current: captureContext(requestOptions, init), keepalive: init?.keepalive === true};

        // An exact editor command can receive a revision conflict after only the
        // displayed revision advanced. Authentication, page, workspace, branch
        // and source selection still have to belong to the original operation.
        var conflictContext = requestOptions.preservePreconditionFailure === true
            ? captureContext(requestOptions, init, true) : null;

        function attempt(number) {
            var scope = createRequestScope(requestOptions, validated);
            var deadline = validated.timeoutMillis > 0 ? Date.now() + validated.timeoutMillis : null;
            scope.contextSensitive = requestOptions.contextSensitive !== false;
            scope.current = conflictContext || context.current;
            scope.keepalive = context.keepalive;
            context.scope = scope;
            activeScopes.add(scope);
            var prepared = prepareRequest(
                url, init, requestOptions, requestId, scope.signal);
            var pending;
            var rawDelivered = false;
            try {
                assertCurrent(context);
                pending = transportFetch(url, prepared);
            } catch (error) { pending = Promise.reject(error); }
            return abortable(Promise.resolve(pending)
                .then(function (response) { return checkStatus(response, context); })
                .then(function (response) {
                    assertCurrent(context);
                    if (consumeResponse) return consumeResponse(response);
                    rawDelivered = true;
                    if (response.body === null) scope.cleanup(); else scope.idle();
                    return guardedResponse(response, context, requestOptions, validated, scope, deadline);
                }), scope.signal)
                .then(function (result) { assertCurrent(context); return result; })
                .catch(function (error) {
                    if (context.authError && !(error instanceof ApiError && [401, 403].includes(error.status))) throw context.authError;
                    // A server-rejected exact-revision write must remain a genuine 412
                    // even when an independent accepted writer changes the live model-stand.
                    // This exception never publishes a successful stale response or
                    // suppresses cancellation of a different response/error.
                    var keepRevisionConflict = conflictContext && conflictContext()
                        && error instanceof ApiError && error.status === 412 && error.code === 'HTTP_ERROR';
                    if (!context.current() && !(error instanceof ApiError && [401, 403].includes(error.status))
                            && !keepRevisionConflict) {
                        throw cancelled(context);
                    }
                    throw normalizeTransportError(error, context, scope, validated);
                })
                .finally(function () { if (!rawDelivered) scope.cleanup(); })
                .catch(function (error) {
                    var callerCancelled = requestOptions.signals.some(function (signal) { return signal.aborted; });
                    if (!callerCancelled && number < validated.retries
                            && error.retryable && requestOptions.idempotent === true) {
                        return attempt(number + 1);
                    }
                    throw error;
                });
        }
        return attempt(0);
    }

    function request(url, init, options) {
        if (!options?.observeResponse) return performRequest(url, init, options);
        var current = captureContext(options, init);
        var first = options.observeAdmission ? options.observeAdmission(performRequest, url, init || {}, options, current)
            : performRequest(url, init, options);
        return first.then(function (response) {
            return options.observeResponse(response, url, init || {}, options, current);
        }).catch(function (error) { throw options.operationError ? options.operationError(error) : error; });
    }

    function requestJson(url, init, options) {
        return performRequest(url, init, options, parseJson);
    }

    // Unlike raw request(), this owns the complete response body lifecycle.
    // Return headers alongside the blob so feature code can validate provenance.
    function requestBlob(url, init, options) {
        return performRequest(url, init, options, function (response) {
            return response.blob().then(function (blob) {
                return { response: response, blob: blob };
            });
        });
    }

    function getAccountContext() {
        if (!accountContextPromise) {
            accountContextPromise = getJson('/api/account/me', {
                idempotent: true,
                timeoutMillis: 10000,
                contextSensitive: false,
                workspaceScoped: false
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
            var analysisLanguage = requirementAnalysisLanguage(input, method);
            if ((metadata && isSameOrigin(input) && method !== 'GET' && method !== 'HEAD') || analysisLanguage) {
                var inheritedHeaders = input instanceof Request ? input.headers : undefined;
                requestInit.headers = new Headers(requestInit.headers || inheritedHeaders || {});
                if (metadata && !requestInit.headers.has(metadata.name)) {
                    requestInit.headers.set(metadata.name, metadata.value);
                }
                if (analysisLanguage && !requestInit.headers.has('X-Taxonomy-Analysis-Language')) {
                    requestInit.headers.set('X-Taxonomy-Analysis-Language', analysisLanguage);
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
        return requestJson(url, { method: 'GET' }, requestOptions);
    }

    function sendJson(url, body, method, options) {
        var actualMethod = method;
        var requestOptions = options;
        if (actualMethod && typeof actualMethod === 'object') {
            requestOptions = actualMethod;
            actualMethod = null;
        }
        return requestJson(url, {
            method: actualMethod || 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }, requestOptions);
    }

    function sendFormData(url, formData, method, options) {
        var actualMethod = method;
        var requestOptions = options;
        if (actualMethod && typeof actualMethod === 'object') {
            requestOptions = actualMethod;
            actualMethod = null;
        }
        return requestJson(url, {
            method: actualMethod || 'POST',
            body: formData
        }, requestOptions);
    }

    function deleteJson(url, options) {
        return requestJson(url, { method: 'DELETE' }, options);
    }

    if (typeof window.addEventListener === 'function') window.addEventListener('pagehide', function () {
        pageGeneration++;
        activeScopes.forEach(function (scope) { if (!scope.keepalive) scope.abort('pagehide'); });
    });
    if (typeof document.addEventListener === 'function') document.addEventListener('taxonomy:analysis-invalidated', function () {
        activeScopes.forEach(function (scope) { if (scope.contextSensitive) scope.abort('analysis'); });
    });

    if (typeof document.addEventListener === 'function') {
        ['taxonomy:entity-context-changed', 'taxonomy:portfolio-project-selecting', 'taxonomy:repository-context-changed'].forEach(function (event) {
            document.addEventListener(event, function () {
                activeScopes.forEach(function (scope) {
                    if (scope.contextSensitive && scope.current && !scope.current()) scope.abort('navigation');
                });
            });
        });
    }

    installGlobalCsrfInterceptor();

    return {
        ApiError: ApiError,
        request: request,
        requestJson: requestJson,
        installTransportAdapter: installTransportAdapter,
        captureContext: function (options) { return captureContext(options || {}, null); },
        requestBlob: requestBlob,
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

    function resolveSurfaceUrl(source) {
        if (window.TaxonomyI18n && typeof window.TaxonomyI18n.resolveUrl === 'function') {
            return window.TaxonomyI18n.resolveUrl(source);
        }
        // Script elements bypass the fetch wrapper. If i18n has not bootstrapped,
        // derive the application context from this API client's own script URL.
        var bootstrap = document.currentScript;
        if (bootstrap && bootstrap.src) {
            try {
                var url = new URL(bootstrap.src, window.location.href);
                var suffix = '/js/api/taxonomy-api-client.js';
                if (url.origin === window.location.origin && url.pathname.endsWith(suffix)) {
                    var prefix = url.pathname.slice(0, -suffix.length);
                    if (!prefix.startsWith('//')) return prefix + source;
                }
            } catch (ignored) { /* Preserve the root-hosted fallback. */ }
        }
        return source;
    }

    function loadSurface(globalName, marker, source) {
        if (window[globalName] || document.querySelector('script[' + marker + ']')) return;
        var script = document.createElement('script');
        script.src = resolveSurfaceUrl(source);
        script.async = false;
        script.setAttribute(marker, 'true');
        document.head.appendChild(script);
    }

    loadSurface('TaxonomyRoleSurface', 'data-taxonomy-role-surface',
        '/js/security/taxonomy-role-surface.js');
    loadSurface('TaxonomyUiSemantics', 'data-taxonomy-ui-semantics',
        '/js/security/taxonomy-ui-semantics.js');
}());
