/* taxonomy-operation-coordinator.js – typed browser lifecycle for long-running analysis work. */
(function () {
    'use strict';

    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before operation coordination');
    var runtime = C.runtime || (C.runtime = {});
    if (runtime.operationCoordinatorInstalled) return;
    runtime.operationCoordinatorInstalled = true;

    var sequence = 0;
    var activeAnalysisOperationId = null;
    var delegatedFetch = window.fetch.bind(window);
    var TERMINAL = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']);

    function operationId(prefix) {
        sequence += 1;
        return prefix + '-' + Date.now().toString(36) + '-' + sequence.toString(36);
    }

    function requestUrl(input) {
        try {
            return input instanceof Request
                ? new URL(input.url, window.location.href)
                : new URL(String(input), window.location.href);
        } catch (error) {
            return null;
        }
    }

    function requestMethod(input, init) {
        return String((init && init.method)
            || (input instanceof Request && input.method)
            || 'GET').toUpperCase();
    }

    function applicationApiPath(url) {
    var i18n = window.TaxonomyI18n;
    var basePath = i18n && typeof i18n.getBasePath === 'function'
        ? String(i18n.getBasePath() || '') : '';
    var pathname = url.pathname;
    if (basePath && pathname.indexOf(basePath + '/') === 0) {
        pathname = pathname.substring(basePath.length);
    }
    return pathname;
}

function isMainAnalysisRequest(input, init) {
    var url = requestUrl(input);
    return Boolean(url
        && url.origin === window.location.origin
        && applicationApiPath(url) === '/api/analyze'
        && requestMethod(input, init) === 'POST');
}

    function dispatch(detail) {
        var normalized = Object.assign({
            operationId: null,
            operationType: 'ANALYSIS',
            status: 'RUNNING',
            phase: 'ANALYSIS',
            determinate: false,
            updatedAt: new Date().toISOString(),
            message: ''
        }, detail || {});
        document.dispatchEvent(new CustomEvent('taxonomy:operation-state', {
            detail: normalized
        }));
        return normalized;
    }

    function boundedMessage(value, fallback) {
        var text = String(value || fallback || '').trim();
        return text.length > 1000 ? text.substring(0, 997) + '...' : text;
    }

    function problemFromResponse(response) {
        var fallback = 'HTTP ' + response.status;
        return response.clone().json().then(function (body) {
            return boundedMessage(body && (body.detail || body.message || body.error), fallback);
        }).catch(function () {
            return fallback;
        });
    }

    function classifyResult(result) {
        var status = String(result && result.status || '').toUpperCase();
        if (status === 'SUCCESS') return 'SUCCEEDED';
        if (status === 'PARTIAL') return 'PARTIAL';
        if (status === 'CANCELLED') return 'CANCELLED';
        if (status === 'ERROR' || status === 'FAILED') return 'FAILED';
        return result && result.scores ? 'SUCCEEDED' : 'FAILED';
    }

    function installFetchLifecycle() {
        if (window.fetch.__taxonomyOperationCoordinator === true) return;

        function coordinatedFetch(input, init) {
            if (!isMainAnalysisRequest(input, init)) {
                return delegatedFetch(input, init);
            }

            var id = operationId('analysis');
            activeAnalysisOperationId = id;
            dispatch({
                operationId: id,
                status: 'RUNNING',
                phase: 'SCORING',
                message: C.language() === 'de'
                    ? 'Die Anforderung wird vollständig bewertet.'
                    : 'The requirement is being evaluated completely.'
            });

            return delegatedFetch(input, init).then(function (response) {
                if (!response.ok) {
                    problemFromResponse(response).then(function (message) {
                        window.setTimeout(function () {
                            dispatch({
                                operationId: id,
                                status: 'FAILED',
                                phase: 'SCORING',
                                message: message,
                                error: message,
                                httpStatus: response.status
                            });
                        }, 0);
                    });
                    return response;
                }

                response.clone().json().then(function (result) {
                    var terminalStatus = classifyResult(result);
                    var message = result && result.errorMessage;
                    if (!message && result && Array.isArray(result.warnings)
                            && result.warnings.length > 0) {
                        message = result.warnings.join('; ');
                    }
                    window.setTimeout(function () {
                        dispatch({
                            operationId: id,
                            status: terminalStatus,
                            phase: 'SCORING',
                            determinate: true,
                            progressPercent: 100,
                            provider: result && result.provider,
                            message: boundedMessage(message,
                                terminalStatus === 'SUCCEEDED'
                                    ? (C.language() === 'de'
                                        ? 'Die Bewertung ist abgeschlossen.'
                                        : 'The evaluation is complete.')
                                    : (C.language() === 'de'
                                        ? 'Die Bewertung wurde nicht vollständig abgeschlossen.'
                                        : 'The evaluation did not complete fully.')),
                            result: result
                        });
                    }, 0);
                }).catch(function (error) {
                    window.setTimeout(function () {
                        dispatch({
                            operationId: id,
                            status: 'FAILED',
                            phase: 'SCORING',
                            message: boundedMessage(error && error.message,
                                'The analysis response could not be read.'),
                            error: boundedMessage(error && error.message)
                        });
                    }, 0);
                });
                return response;
            }, function (error) {
                var cancelled = error && (error.name === 'AbortError'
                    || /abort|cancel/i.test(String(error.message || '')));
                dispatch({
                    operationId: id,
                    status: cancelled ? 'CANCELLED' : 'FAILED',
                    phase: 'SCORING',
                    message: cancelled
                        ? (C.language() === 'de'
                            ? 'Die Analyse wurde abgebrochen.'
                            : 'The analysis was cancelled.')
                        : boundedMessage(error && error.message,
                            C.language() === 'de'
                                ? 'Die Analyseverbindung ist fehlgeschlagen.'
                                : 'The analysis connection failed.'),
                    error: cancelled ? null : boundedMessage(error && error.message)
                });
                throw error;
            });
        }

        Object.defineProperty(coordinatedFetch, '__taxonomyOperationCoordinator', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
        window.fetch = coordinatedFetch;
    }

    function setCopilotBusy(busy) {
        var button = document.getElementById('copilotBtn');
        var spinner = document.getElementById('copilotSpinner');
        if (button) {
            button.disabled = busy;
            button.setAttribute('aria-busy', busy ? 'true' : 'false');
            button.dataset.sessionControl = 'copilot';
            button.dataset.sessionTestOutcome = 'operation';
        }
        if (spinner) spinner.classList.toggle('d-none', !busy);
    }

    function renderCopilotOperation(state, actions) {
        var panel = document.getElementById('copilotPanel');
        var target = document.getElementById('copilotContent');
        if (panel) panel.style.display = '';
        if (!target) return;

        var status = String(state && state.status || 'RUNNING');
        var kind = status === 'FAILED' ? 'danger'
            : status === 'PARTIAL' || status === 'CANCELLED' ? 'warning'
            : status === 'SUCCEEDED' ? 'success' : 'info';
        var phase = boundedMessage(state && state.phase, 'ANALYSIS');
        var message = boundedMessage(state && state.message,
            C.language() === 'de' ? 'Analyse läuft.' : 'Analysis is running.');
        var id = boundedMessage(state && state.operationId, activeAnalysisOperationId || '');

        var wrapper = document.createElement('div');
        wrapper.className = 'vstack gap-2';
        wrapper.dataset.operationId = id;
        wrapper.dataset.operationStatus = status;
        wrapper.setAttribute('role', status === 'FAILED' ? 'alert' : 'status');
        wrapper.setAttribute('aria-live', status === 'FAILED' ? 'assertive' : 'polite');

        var summary = document.createElement('div');
        summary.className = 'alert alert-' + kind + ' py-2 px-3 mb-0';
        var strong = document.createElement('strong');
        strong.textContent = status;
        summary.appendChild(strong);
        summary.appendChild(document.createTextNode(' · ' + phase + ' — ' + message));
        wrapper.appendChild(summary);

        if (!TERMINAL.has(status)) {
            var progress = document.createElement('div');
            progress.className = 'progress';
            progress.setAttribute('role', 'progressbar');
            progress.setAttribute('aria-label', C.language() === 'de'
                ? 'Fortschritt der Copilot-Analyse'
                : 'Copilot analysis progress');
            progress.setAttribute('aria-valuetext', message);
            var bar = document.createElement('div');
            bar.className = 'progress-bar progress-bar-striped progress-bar-animated w-100';
            progress.appendChild(bar);
            wrapper.appendChild(progress);
        }

        if (id) {
            var metadata = document.createElement('div');
            metadata.className = 'small text-body-secondary';
            metadata.textContent = (C.language() === 'de' ? 'Vorgang: ' : 'Operation: ') + id;
            wrapper.appendChild(metadata);
        }

        if (Array.isArray(actions) && actions.length > 0) {
            var actionRow = document.createElement('div');
            actionRow.className = 'd-flex flex-wrap gap-2';
            actions.forEach(function (action) {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = action.className || 'btn btn-sm btn-outline-primary';
                button.textContent = action.label;
                button.dataset.sessionControl = action.id || 'operation-action';
                button.dataset.sessionTestOutcome = 'operation';
                button.addEventListener('click', action.handler);
                actionRow.appendChild(button);
            });
            wrapper.appendChild(actionRow);
        }

        target.replaceChildren(wrapper);
    }

    function currentScoresAreAuthoritative() {
        var scores = window._taxonomyCurrentScores;
        var status = String(C.S.lastAnalysisStatus || '').toUpperCase();
        return Boolean(scores && typeof scores === 'object'
            && Object.keys(scores).length > 0
            && (status === 'SUCCESS' || status === 'IMPORTED'));
    }

    function retryCopilot() {
        var button = document.getElementById('copilotBtn');
        if (button) button.click();
    }

    function waitForMainAnalysis() {
        return new Promise(function (resolve) {
            var selectedOperationId = null;
            function listener(event) {
                var detail = event && event.detail;
                if (!detail || detail.operationType !== 'ANALYSIS') return;
                if (!selectedOperationId && detail.status === 'RUNNING') {
                    selectedOperationId = detail.operationId;
                    activeAnalysisOperationId = selectedOperationId;
                    renderCopilotOperation(detail);
                    return;
                }
                if (!selectedOperationId || detail.operationId !== selectedOperationId) return;
                renderCopilotOperation(detail);
                if (!TERMINAL.has(detail.status)) return;
                document.removeEventListener('taxonomy:operation-state', listener);
                resolve(detail);
            }
            document.addEventListener('taxonomy:operation-state', listener);

            var analyze = document.getElementById('analyzeBtn');
            if (!analyze || analyze.disabled || analyze.getAttribute('aria-disabled') === 'true') {
                document.removeEventListener('taxonomy:operation-state', listener);
                resolve({
                    operationId: null,
                    operationType: 'ANALYSIS',
                    status: 'FAILED',
                    phase: 'VALIDATING',
                    message: C.language() === 'de'
                        ? 'Die vollständige Analyse kann in diesem Zustand nicht gestartet werden.'
                        : 'The complete analysis cannot be started in the current state.'
                });
                return;
            }
            analyze.click();
        });
    }

    function startAuthoritativeCopilot(event) {
        var target = event.target && typeof event.target.closest === 'function'
            ? event.target.closest('#copilotBtn') : null;
        if (!target) return;
        target.dataset.sessionControl = 'copilot';
        target.dataset.sessionTestOutcome = 'operation';

        if (currentScoresAreAuthoritative()) return;

        event.preventDefault();
        event.stopImmediatePropagation();
        setCopilotBusy(true);
        renderCopilotOperation({
            operationId: null,
            status: 'RUNNING',
            phase: 'VALIDATING',
            message: C.language() === 'de'
                ? 'Die vollständige Analyse wird vorbereitet.'
                : 'The complete analysis is being prepared.'
        });

        waitForMainAnalysis().then(function (terminal) {
            if (terminal.status === 'SUCCEEDED') {
                if (window.TaxonomyAnalysis
                        && typeof window.TaxonomyAnalysis.runCopilotFlow === 'function') {
                    window.TaxonomyAnalysis.runCopilotFlow();
                    return;
                }
                setCopilotBusy(false);
                renderCopilotOperation({
                    operationId: terminal.operationId,
                    status: 'FAILED',
                    phase: 'ENRICHING',
                    message: C.language() === 'de'
                        ? 'Die Copilot-Folgeschritte sind nicht verfügbar.'
                        : 'The Copilot follow-up steps are not available.'
                }, [{
                    id: 'retry',
                    label: C.language() === 'de' ? 'Erneut versuchen' : 'Retry',
                    handler: retryCopilot
                }]);
                return;
            }

            setCopilotBusy(false);
            renderCopilotOperation(terminal, [{
                id: 'retry',
                label: C.language() === 'de' ? 'Analyse erneut starten' : 'Restart analysis',
                handler: retryCopilot
            }]);
        });
    }

    installFetchLifecycle();
    document.addEventListener('click', startAuthoritativeCopilot, true);

    ['analyzeBtn', 'copilotBtn', 'cancelAnalysisBtn'].forEach(function (id) {
        var control = document.getElementById(id);
        if (!control) return;
        control.dataset.sessionControl = id;
        control.dataset.sessionTestOutcome = id === 'cancelAnalysisBtn' ? 'cancel' : 'operation';
    });

    window.TaxonomyOperationCoordinator = {
        activeAnalysisOperationId: function () { return activeAnalysisOperationId; },
        dispatch: dispatch
    };
}());
