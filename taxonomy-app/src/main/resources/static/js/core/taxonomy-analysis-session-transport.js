/* taxonomy-analysis-session-transport.js – stale request arbitration and draft write serialization */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before transport lifecycle');

    var runtime = C.runtime;
    runtime.analysisGeneration = Number(runtime.analysisGeneration) || 0;
    runtime.activeAnalysisControllers = runtime.activeAnalysisControllers || new Set();
    runtime.copilotIntervals = runtime.copilotIntervals || new Set();
    runtime.draftMutationQueue = runtime.draftMutationQueue || Promise.resolve();

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

            return nativeFetch(input, request).then(function (response) {
                runtime.activeAnalysisControllers.delete(controller);
                return isCurrent(generation)
                    ? guardedResponse(response, generation)
                    : neverSettles();
            }, function (error) {
                runtime.activeAnalysisControllers.delete(controller);
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
            }
            return id;
        }

        function guardedClearInterval(id) {
            runtime.copilotIntervals.delete(id);
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

    function isDraftMutation(url, options) {
        var resolved = C.requestUrl(url);
        if (!resolved || resolved.origin !== window.location.origin
                || resolved.pathname.indexOf('/api/analysis-drafts/') !== 0) {
            return false;
        }
        var method = methodOf(url, options);
        return method === 'PUT' || method === 'DELETE';
    }

    function currentDraftMutation(url, options) {
        var request = Object.assign({}, options || {});
        var method = methodOf(url, request);
        var target = C.requestUrl(url);
        if (method === 'PUT') {
            var body = request.body ? JSON.parse(request.body) : {};
            body.expectedVersion = runtime.version;
            request.body = JSON.stringify(body);
        } else if (method === 'DELETE') {
            if (runtime.version === null) return Promise.resolve(null);
            if (target) {
                target.searchParams.set('expectedVersion', runtime.version);
                url = target.pathname + target.search;
            }
        }
        return C.nativeJsonRequest(url, request).then(function (view) {
            // The queue owns revision advancement. Callers may mirror this state,
            // but the next queued mutation cannot start before this update.
            if (method === 'PUT' && view && view.version !== undefined) {
                runtime.version = view.version;
            } else if (method === 'DELETE') {
                runtime.version = null;
            }
            return view;
        });
    }

    function installDraftMutationQueue() {
        if (C.nativeJsonRequest) return;
        C.nativeJsonRequest = C.jsonRequest;
        C.jsonRequest = function (url, options) {
            if (!isDraftMutation(url, options)) {
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

    function cancelDerivedRequests() {
        runtime.analysisGeneration += 1;
        runtime.activeAnalysisControllers.forEach(function (controller) {
            try { controller.abort('analysis-invalidated'); } catch (error) { /* no-op */ }
        });
        runtime.activeAnalysisControllers.clear();
        var clearInterval = runtime.nativeClearInterval || window.clearInterval.bind(window);
        runtime.copilotIntervals.forEach(function (id) { clearInterval(id); });
        runtime.copilotIntervals.clear();
        resetBusyControls();
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

    document.addEventListener('taxonomy:analysis-invalidated', cancelDerivedRequests);
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
        cancelDerivedRequests: cancelDerivedRequests
    });
}());
