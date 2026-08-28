/* taxonomy-analysis-session-transport.js – stale request arbitration and draft write serialization */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before transport lifecycle');

    var runtime = C.runtime;
    var DRAFT_WRITE_HISTORY_LIMIT = 1;
    var DRAFT_TRACE_LIMIT = 48;
    runtime.analysisGeneration = Number(runtime.analysisGeneration) || 0;
    runtime.activeAnalysisControllers = runtime.activeAnalysisControllers || new Set();
    runtime.copilotIntervals = runtime.copilotIntervals || new Set();
    runtime.draftMutationQueue = runtime.draftMutationQueue || Promise.resolve();
    runtime.draftMutationSequence = Number(runtime.draftMutationSequence) || 0;
    runtime.acknowledgedDraftWrites = Array.isArray(runtime.acknowledgedDraftWrites)
        ? runtime.acknowledgedDraftWrites : [];
    runtime.draftMutationTrace = Array.isArray(runtime.draftMutationTrace)
        ? runtime.draftMutationTrace : [];

    function notifyAnalysisRunningChanged() {
        document.dispatchEvent(new CustomEvent('taxonomy:analysis-running-changed', {
            detail: {
                activeRequests: runtime.activeAnalysisControllers.size,
                activeCopilotTimers: runtime.copilotIntervals.size
            }
        }));
    }

    function methodOf(input, init) {
        return String((init && init.method)
            || (input && typeof input === 'object' && input.method)
            || 'GET').toUpperCase();
    }

    function derivedAnalysisRequest(input) {
        var url = C.requestUrl(input);
        if (!url || url.origin !== window.location.origin) return false;
        var path = url.pathname;
        return path === '/api/analyze'
            || path === '/api/analyze-node'
            || path === '/api/justify-leaf'
            || path === '/api/recommend'
            || path === '/api/graph/impact'
            || path.indexOf('/api/gap/') === 0
            || path.indexOf('/api/patterns/') === 0
            || path.indexOf('/api/explain/') === 0;
    }

    function neverSettles() {
        return new Promise(function () { /* intentionally superseded */ });
    }

    function isCurrent(generation) {
        return generation === runtime.analysisGeneration && !runtime.invalidating;
    }

    function guardedResponse(response, generation) {
        var bodyMethods = new Set([
            'arrayBuffer', 'blob', 'bytes', 'formData', 'json', 'text'
        ]);
        return new Proxy(response, {
            get: function (target, property) {
                if (bodyMethods.has(property) && typeof target[property] === 'function') {
                    return function () {
                        if (!isCurrent(generation)) return neverSettles();
                        return target[property]().then(function (value) {
                            return isCurrent(generation) ? value : neverSettles();
                        });
                    };
                }
                if (property === 'clone' && typeof target.clone === 'function') {
                    return function () {
                        return guardedResponse(target.clone(), generation);
                    };
                }
                var value = Reflect.get(target, property, target);
                return typeof value === 'function' ? value.bind(target) : value;
            }
        });
    }

    function installDerivedFetchGuard() {
        if (window.fetch.__taxonomyAnalysisGenerationGuard === true) return;
        var nativeFetch = window.fetch.bind(window);

        function guardedFetch(input, init) {
            if (!derivedAnalysisRequest(input)) {
                return nativeFetch(input, init);
            }

            var generation = runtime.analysisGeneration;
            var controller = new AbortController();
            var request = Object.assign({}, init || {});
            var suppliedSignal = request.signal
                || (input && typeof input === 'object' ? input.signal : null);
            if (suppliedSignal) {
                if (suppliedSignal.aborted) controller.abort(suppliedSignal.reason);
                else suppliedSignal.addEventListener('abort', function () {
                    controller.abort(suppliedSignal.reason);
                }, { once: true });
            }
            request.signal = controller.signal;
            runtime.activeAnalysisControllers.add(controller);
            notifyAnalysisRunningChanged();

            return nativeFetch(input, request).then(function (response) {
                runtime.activeAnalysisControllers.delete(controller);
                notifyAnalysisRunningChanged();
                return isCurrent(generation)
                    ? guardedResponse(response, generation)
                    : neverSettles();
            }, function (error) {
                runtime.activeAnalysisControllers.delete(controller);
                notifyAnalysisRunningChanged();
                if (!isCurrent(generation) || controller.signal.aborted) {
                    return neverSettles();
                }
                throw error;
            });
        }

        Object.defineProperty(guardedFetch, '__taxonomyAnalysisGenerationGuard', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
        window.fetch = guardedFetch;
    }

    function installCopilotIntervalGuard() {
        if (window.setInterval.__taxonomyCopilotGuard === true) return;
        var nativeSetInterval = window.setInterval.bind(window);
        var nativeClearInterval = window.clearInterval.bind(window);

        function guardedSetInterval(callback, delay) {
            var id = nativeSetInterval(callback, delay);
            var button = document.getElementById('copilotBtn');
            var spinner = document.getElementById('copilotSpinner');
            if (button && button.disabled && spinner
                    && !spinner.classList.contains('d-none')) {
                runtime.copilotIntervals.add(id);
                notifyAnalysisRunningChanged();
            }
            return id;
        }

        function guardedClearInterval(id) {
            runtime.copilotIntervals.delete(id);
            notifyAnalysisRunningChanged();
            return nativeClearInterval(id);
        }

        Object.defineProperty(guardedSetInterval, '__taxonomyCopilotGuard', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
        window.setInterval = guardedSetInterval;
        window.clearInterval = guardedClearInterval;
        runtime.nativeClearInterval = nativeClearInterval;
    }

    function draftEndpoint(url) {
        var resolved = C.requestUrl(url);
        if (!resolved || resolved.origin !== window.location.origin
                || !/\/api\/analysis-drafts\/[^/]+(?:\/reset)?$/.test(resolved.pathname)) {
            return null;
        }
        return resolved;
    }

    function isDraftReset(url) {
        var endpoint = draftEndpoint(url);
        return Boolean(endpoint && /\/reset$/.test(endpoint.pathname));
    }

    function isDraftOperation(url, options) {
        var endpoint = draftEndpoint(url);
        if (!endpoint) return false;
        var method = methodOf(url, options);
        return /\/reset$/.test(endpoint.pathname)
            ? method === 'POST'
            : method === 'GET' || method === 'PUT' || method === 'DELETE';
    }

    function numericVersion(value) {
        if (value === null || value === undefined || value === '') return null;
        var version = Number(value);
        return Number.isFinite(version) ? version : null;
    }

    function comparablePayload(payload) {
        return payload === null || payload === undefined ? null : C.comparable(payload);
    }

    function traceDraftMutation(mutationId, phase, details) {
        var entry = Object.assign({
            mutationId: mutationId,
            phase: phase,
            recordedAt: new Date().toISOString()
        }, details || {});
        runtime.draftMutationTrace.push(entry);
        if (runtime.draftMutationTrace.length > DRAFT_TRACE_LIMIT) {
            runtime.draftMutationTrace.splice(
                0, runtime.draftMutationTrace.length - DRAFT_TRACE_LIMIT);
        }
        document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-mutation', {
            detail: entry
        }));
    }

    function rememberAcknowledgedDraft(view) {
        if (!view || !view.payload) return;
        var version = numericVersion(view.version);
        if (version === null) return;
        var serialized = comparablePayload(view.payload);
        runtime.acknowledgedDraftWrites = runtime.acknowledgedDraftWrites.filter(
            function (entry) {
                return entry.version !== version || entry.serialized !== serialized;
            });
        runtime.acknowledgedDraftWrites.push({
            version: version,
            serialized: serialized
        });
        if (runtime.acknowledgedDraftWrites.length > DRAFT_WRITE_HISTORY_LIMIT) {
            runtime.acknowledgedDraftWrites.splice(
                0,
                runtime.acknowledgedDraftWrites.length - DRAFT_WRITE_HISTORY_LIMIT);
        }
    }

    function wasAcknowledgedLocally(view) {
        if (!view || !view.payload) return false;
        var version = numericVersion(view.version);
        var serialized = comparablePayload(view.payload);
        if (version === null) return false;
        return runtime.acknowledgedDraftWrites.some(function (entry) {
            return entry.version === version && entry.serialized === serialized;
        });
    }

    function dispatchDraftReconciled(mutationId, reason, expectedVersion, view, error) {
        document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-write-reconciled', {
            detail: {
                mutationId: mutationId,
                workspaceId: runtime.workspaceId,
                reason: reason,
                expectedVersion: expectedVersion,
                version: view && view.version !== undefined ? view.version : null,
                requestId: error && error.requestId ? error.requestId : null
            }
        }));
    }

    function reconcileDraftConflict(
            url, request, body, serializedPayload,
            expectedVersion, originalError, mutationId) {
        var endpoint = draftEndpoint(url);
        var readUrl = endpoint ? endpoint.pathname : url;
        traceDraftMutation(mutationId, 'conflict', {
            expectedVersion: expectedVersion,
            currentVersion: null,
            requestId: originalError.requestId || null
        });
        return C.nativeJsonRequest(readUrl, { method: 'GET' }).then(function (remote) {
            var remoteVersion = remote ? numericVersion(remote.version) : null;
            var remotePayload = remote && remote.payload
                ? comparablePayload(remote.payload) : null;
            traceDraftMutation(mutationId, 'conflict-read', {
                expectedVersion: expectedVersion,
                currentVersion: remoteVersion,
                requestId: originalError.requestId || null
            });

            if (remote && remotePayload === serializedPayload) {
                runtime.version = remoteVersion;
                rememberAcknowledgedDraft(remote);
                dispatchDraftReconciled(
                    mutationId, 'write-already-committed',
                    expectedVersion, remote, originalError);
                return remote;
            }

            if (!remote || remoteVersion === null
                    || remoteVersion === numericVersion(expectedVersion)
                    || !wasAcknowledgedLocally(remote)) {
                throw originalError;
            }

            runtime.version = remoteVersion;
            var retryBody = Object.assign({}, body, {
                expectedVersion: remoteVersion
            });
            var retryRequest = Object.assign({}, request, {
                body: JSON.stringify(retryBody)
            });
            traceDraftMutation(mutationId, 'retry', {
                expectedVersion: remoteVersion,
                currentVersion: remoteVersion,
                requestId: originalError.requestId || null
            });
            return C.nativeJsonRequest(url, retryRequest).then(function (view) {
                runtime.version = view && view.version !== undefined
                    ? numericVersion(view.version) : runtime.version;
                rememberAcknowledgedDraft(view);
                traceDraftMutation(mutationId, 'reconciled', {
                    expectedVersion: remoteVersion,
                    currentVersion: runtime.version,
                    requestId: originalError.requestId || null
                });
                dispatchDraftReconciled(
                    mutationId, 'stale-local-version',
                    expectedVersion, view, originalError);
                return view;
            });
        });
    }

    function currentDraftMutation(url, options) {
        var request = Object.assign({}, options || {});
        var method = methodOf(url, request);
        var target = draftEndpoint(url);
        var mutationId = ++runtime.draftMutationSequence;
        var expectedVersion = numericVersion(runtime.version);
        var body = null;
        var serializedPayload = null;
        if (method === 'PUT') {
            body = request.body ? JSON.parse(request.body) : {};
            body.expectedVersion = expectedVersion;
            serializedPayload = comparablePayload(body.payload);
            request.body = JSON.stringify(body);
        } else if (method === 'DELETE') {
            if (runtime.version === null) {
                runtime.acknowledgedDraftWrites = [];
                return Promise.resolve(null);
            }
            if (target) {
                target.searchParams.set('expectedVersion', expectedVersion);
                url = target.pathname + target.search;
            }
        }
        traceDraftMutation(mutationId, 'started', {
            method: method,
            expectedVersion: expectedVersion,
            currentVersion: expectedVersion,
            requestId: null
        });
        return C.nativeJsonRequest(url, request).then(function (view) {
            // The queue owns revision advancement. Callers may mirror this state,
            // but the next queued mutation cannot start before this update.
            if ((method === 'PUT' || method === 'POST')
                    && view && view.version !== undefined) {
                runtime.version = numericVersion(view.version);
                rememberAcknowledgedDraft(view);
            } else if (method === 'GET') {
                runtime.version = view && view.version !== undefined
                    ? numericVersion(view.version) : null;
                if (runtime.version === null) {
                    runtime.acknowledgedDraftWrites = [];
                }
            } else if (method === 'DELETE') {
                runtime.version = null;
                runtime.acknowledgedDraftWrites = [];
            }
            traceDraftMutation(mutationId, 'succeeded', {
                method: method,
                expectedVersion: expectedVersion,
                currentVersion: runtime.version,
                requestId: null
            });
            return view;
        }).catch(function (error) {
            if (method === 'PUT' && error && error.status === 409) {
                return reconcileDraftConflict(
                    url, request, body, serializedPayload,
                    expectedVersion, error, mutationId);
            }
            if (method === 'POST' && isDraftReset(url)
                    && error && error.status === 409) {
                traceDraftMutation(mutationId, 'reset-retry', {
                    method: method,
                    expectedVersion: null,
                    currentVersion: runtime.version,
                    requestId: error.requestId || null
                });
                return C.nativeJsonRequest(url, request).then(function (view) {
                    runtime.version = view && view.version !== undefined
                        ? numericVersion(view.version) : runtime.version;
                    rememberAcknowledgedDraft(view);
                    traceDraftMutation(mutationId, 'reconciled', {
                        method: method,
                        expectedVersion: null,
                        currentVersion: runtime.version,
                        requestId: error.requestId || null
                    });
                    dispatchDraftReconciled(
                        mutationId, 'concurrent-reset', null, view, error);
                    return view;
                });
            }
            traceDraftMutation(mutationId, 'failed', {
                method: method,
                expectedVersion: expectedVersion,
                currentVersion: runtime.version,
                requestId: error && error.requestId ? error.requestId : null
            });
            throw error;
        });
    }

    function installDraftMutationQueue() {
        if (C.nativeJsonRequest) return;
        C.nativeJsonRequest = C.jsonRequest;
        C.jsonRequest = function (url, options) {
            if (!isDraftOperation(url, options)) {
                return C.nativeJsonRequest(url, options);
            }
            var execute = function () {
                return currentDraftMutation(url, options);
            };
            var task = runtime.draftMutationQueue.then(execute, execute);
            runtime.draftMutationQueue = task.catch(function () { /* keep queue usable */ });
            return task;
        };
    }

    function analysisOptions() {
        var provider = document.getElementById('providerSelect');
        var interactive = document.getElementById('interactiveMode');
        var architecture = document.getElementById('includeArchitectureView');
        return {
            provider: provider ? provider.value : '',
            interactiveMode: interactive ? interactive.checked : true,
            includeArchitectureView: architecture ? architecture.checked : false
        };
    }

    function installPayloadOptions() {
        if (C.nativeCurrentPayload) return;
        C.nativeCurrentPayload = C.currentPayload;
        C.currentPayload = function () {
            var payload = C.nativeCurrentPayload();
            payload.analysisOptions = analysisOptions();
            return payload;
        };
    }

    function restoreOptions(payload, attempt) {
        var options = payload && payload.analysisOptions;
        if (!options) return;
        var interactive = document.getElementById('interactiveMode');
        var architecture = document.getElementById('includeArchitectureView');
        if (interactive && typeof options.interactiveMode === 'boolean') {
            interactive.checked = options.interactiveMode;
            C.S.interactiveMode = options.interactiveMode;
        }
        if (architecture && typeof options.includeArchitectureView === 'boolean') {
            architecture.checked = options.includeArchitectureView;
        }

        var provider = document.getElementById('providerSelect');
        var requested = options.provider || '';
        if (!provider) return;
        var available = Array.from(provider.options).some(function (option) {
            return option.value === requested;
        });
        if (available) {
            provider.value = requested;
        } else if ((attempt || 0) < 40) {
            window.setTimeout(function () {
                restoreOptions(payload, (attempt || 0) + 1);
            }, 250);
        }
    }

    function resetBusyControls() {
        if (window.TaxonomyScoring
                && typeof window.TaxonomyScoring.setAnalyzing === 'function') {
            window.TaxonomyScoring.setAnalyzing(false);
        }
        var copilotButton = document.getElementById('copilotBtn');
        var copilotSpinner = document.getElementById('copilotSpinner');
        if (copilotButton) copilotButton.disabled = false;
        if (copilotSpinner) copilotSpinner.classList.add('d-none');
        document.querySelectorAll('.tax-evaluating').forEach(function (element) {
            element.classList.remove('tax-evaluating');
        });
    }

    function cancelDerivedRequests(reason) {
        var cancellationReason = typeof reason === 'string' && reason
            ? reason : 'analysis-invalidated';
        runtime.analysisGeneration += 1;
        runtime.activeAnalysisControllers.forEach(function (controller) {
            try { controller.abort(cancellationReason); } catch (error) { /* no-op */ }
        });
        runtime.activeAnalysisControllers.clear();
        var clearInterval = runtime.nativeClearInterval || window.clearInterval.bind(window);
        runtime.copilotIntervals.forEach(function (id) { clearInterval(id); });
        runtime.copilotIntervals.clear();
        resetBusyControls();
        notifyAnalysisRunningChanged();
    }

    // Install workspace routing before the pre-existing streaming guard captures
    // EventSource on DOMContentLoaded. The wrappers consult runtime.workspaceId
    // at request creation time, so early installation remains safe.
    C.installWorkspaceFetchRouting();
    C.installWorkspaceEventSourceRouting();
    installDerivedFetchGuard();
    installCopilotIntervalGuard();
    installDraftMutationQueue();
    installPayloadOptions();

    document.addEventListener('taxonomy:analysis-invalidated', function (event) {
        cancelDerivedRequests(event && event.detail && event.detail.reason
            ? event.detail.reason : 'analysis-invalidated');
    });
    document.addEventListener('taxonomy:analysis-draft-restored', function () {
        restoreOptions(runtime.restoredPayload, 0);
    });
    document.addEventListener('change', function (event) {
        if (!event.target || ['providerSelect', 'interactiveMode', 'includeArchitectureView']
                .indexOf(event.target.id) < 0) return;
        if (typeof C.queueSave === 'function') C.queueSave();
    });

    Object.assign(C, {
        analysisOptions: analysisOptions,
        restoreOptions: restoreOptions,
        cancelDerivedRequests: cancelDerivedRequests,
        notifyAnalysisRunningChanged: notifyAnalysisRunningChanged,
        draftMutationDiagnostics: function () {
            return runtime.draftMutationTrace.slice();
        }
    });
}());
