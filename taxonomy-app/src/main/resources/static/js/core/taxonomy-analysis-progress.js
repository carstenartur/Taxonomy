/* taxonomy-analysis-progress.js – bounded observation of existing analysis operations */
(function () {
    'use strict';
    var active = null;

    function createMonitor(options) {
        var initial = options.context();
        var stopped = false, timer = null, request = null, sequence = 0, cancelling = false;
        var seen = false, cancelPending = false, cancelInFlight = false, cancelAcknowledged = false, cancelUncertain = false;
        var cleanupStarted = false, observedTerminal = false, finalRead = null, finalRequest = null;
        var id = options.id;
        function sameContext() {
            var now = options.context();
            return now.workspaceId === initial.workspaceId
                && now.generation === initial.generation && !now.invalidating;
        }
        function current() { return !stopped && sameContext(); }
        function stop() {
            stopped = true;
            if (timer !== null) options.clearTimeout(timer);
            timer = null;
            if (request) request.abort();
            if (finalRequest) finalRequest.abort();
            request = null;
        }
        async function cancel() {
            if (stopped || cancelling || cancelPending) return false;
            cancelling = true;
            cancelInFlight = true;
            cancelUncertain = false;
            try {
                await options.api.cancelRun(id, { workspaceId: initial.workspaceId });
                cancelAcknowledged = true;
                if (current() && options.onCancelling) options.onCancelling();
            } catch (error) {
                cancelling = false;
                if (error.status === 404 && !seen) {
                    // Registration can follow the first cancel request. Keep the intent;
                    // only a later observed RUNNING state permits this rejected write to retry.
                    cancelPending = true;
                    if (current() && options.onCancelling) options.onCancelling();
                } else {
                    // A lost write response cannot safely be retried by automatic cleanup.
                    // A later explicit user click may retry, but observation never does.
                    cancelUncertain = true;
                    if (current()) options.onUnavailable(error.message);
                }
            } finally { cancelInFlight = false; }
        }

        // UI invalidation must not erase a pending cancellation. A separate, bounded
        // observer retains the original identity without touching old or new UI state.
        function cancelAndStop() {
            if (cleanupStarted || stopped) return false;
            cleanupStarted = true;
            stop();
            var done = false, cleanupTimer = null, cleanupRequest = null, readTimeout = null;
            var cleanupSeen = seen;
            var deadline = options.setTimeout(endCleanup, 30000);
            function endCleanup() {
                if (done) return;
                done = true;
                options.clearTimeout(deadline);
                if (cleanupTimer !== null) options.clearTimeout(cleanupTimer);
                if (readTimeout !== null) options.clearTimeout(readTimeout);
                if (cleanupRequest) cleanupRequest.abort();
                cleanupTimer = readTimeout = cleanupRequest = null;
            }
            async function cleanupPoll() {
                if (done) return;
                if (cancelAcknowledged || cancelUncertain) { endCleanup(); return; }
                if (cancelInFlight) {
                    cleanupTimer = options.setTimeout(cleanupPoll, 1000);
                    return;
                }
                var writing = false;
                cleanupRequest = new AbortController();
                readTimeout = options.setTimeout(function () {
                    if (cleanupRequest) cleanupRequest.abort();
                }, 5000);
                try {
                    var response = await options.api.getRunStatus(id, {
                        workspaceId: initial.workspaceId, waitForRegistration: !cleanupSeen,
                        signal: cleanupRequest.signal
                    });
                    if (done) return;
                    if (response.status === 202 && !cleanupSeen) return;
                    var data = await response.json();
                    if (done || data.operationId !== id) return;
                    cleanupSeen = true;
                    if (['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED', 'CANCELLING'].indexOf(data.status) >= 0) {
                        endCleanup(); return;
                    }
                    if (data.status !== 'RUNNING') return;
                    writing = true;
                    await options.api.cancelRun(id, {
                        workspaceId: initial.workspaceId, signal: cleanupRequest.signal
                    });
                    cancelAcknowledged = true;
                    endCleanup();
                } catch (error) {
                    // Never retry a write whose delivery is ambiguous. The bounded server
                    // operation deadline remains the final safeguard when transport is lost.
                    if (writing || error.status === 400 || error.status === 401 || error.status === 403
                            || (error.status === 404 && cleanupSeen)) endCleanup();
                } finally {
                    if (readTimeout !== null) options.clearTimeout(readTimeout);
                    readTimeout = null;
                    cleanupRequest = null;
                    if (!done) cleanupTimer = options.setTimeout(cleanupPoll, 1000);
                }
            }
            cleanupTimer = options.setTimeout(cleanupPoll, 0);
            return true;
        }
        async function poll() {
            if (!current()) { if (!stopped) cancelAndStop(); return; }
            request = new AbortController();
            var timeout = options.setTimeout(function () { if (request) request.abort(); }, 5000);
            try {
                var response = await options.api.getRunStatus(id, {
                    workspaceId: initial.workspaceId, waitForRegistration: !seen, signal: request.signal
                });
                if (response.status === 202 && !seen) {
                    if (current()) options.onUnavailable('WAITING_FOR_RUN');
                    return;
                }
                var data = await response.json();
                if (!current()) return;
                if (data.operationId !== id || !Number.isSafeInteger(data.sequence)
                        || data.sequence < sequence) return;
                var changed = data.sequence > sequence;
                sequence = data.sequence;
                seen = true;
                observedTerminal = ['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(data.status) >= 0;
                options.onSnapshot(data, changed);
                if (observedTerminal) {
                    cancelPending = false;
                    stop();
                } else if (cancelPending && data.status === 'RUNNING') {
                    cancelPending = false;
                    await cancel();
                }
            } catch (error) {
                if (current()) {
                    var reason = error.status === 404 && !seen ? 'WAITING_FOR_RUN'
                        : error.name === 'AbortError' || error.code === 'ABORTED' || error.code === 'TIMEOUT'
                            ? 'CONNECTION_TIMEOUT' : error.message;
                    var terminal = error.status === 400 || error.status === 401 || error.status === 403
                        || (error.status === 404 && seen);
                    options.onUnavailable(reason, terminal);
                    if (terminal) { cancelPending = false; stop(); }
                }
            } finally {
                options.clearTimeout(timeout);
                request = null;
                if (current()) timer = options.setTimeout(poll, 1000);
            }
        }
        // The authoritative HTTP result stops score polling immediately. Retrieve the
        // final diagnostic metadata once, without feeding bounded preview scores back
        // into the already complete analysis envelope or restarting observation/work.
        function finalSnapshot() {
            if (finalRead) return finalRead;
            stop();
            if (observedTerminal || !sameContext()) return Promise.resolve(null);
            var controller = new AbortController();
            finalRequest = controller;
            var deadline = options.setTimeout(function () { controller.abort(); }, 5000);
            finalRead = (async function () {
                try {
                    var response = await options.api.getRunStatus(id, {
                        workspaceId: initial.workspaceId, signal: controller.signal
                    });
                    var data = await response.json();
                    if (!sameContext()) return null;
                    if (data.operationId !== id || !Number.isSafeInteger(data.sequence)
                            || data.sequence < sequence
                            || ['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(data.status) < 0) {
                        throw new Error('FINAL_STATUS_UNAVAILABLE');
                    }
                    observedTerminal = true;
                    return data;
                } finally {
                    options.clearTimeout(deadline);
                    if (finalRequest === controller) finalRequest = null;
                }
            }());
            return finalRead;
        }
        async function detail(callId) {
            if (!current() && !stopped) throw new Error('STALE_ANALYSIS');
            var now = options.context();
            if (now.workspaceId !== initial.workspaceId || now.generation !== initial.generation) {
                throw new Error('STALE_ANALYSIS');
            }
            var response = await options.api.getRunCallDetail(id, callId, { workspaceId: initial.workspaceId });
            var data = await response.json();
            now = options.context();
            if (now.workspaceId !== initial.workspaceId || now.generation !== initial.generation || now.invalidating) {
                throw new Error('STALE_ANALYSIS');
            }
            return data;
        }
        timer = options.setTimeout(poll, 0);
        return { stop: stop, cancel: cancel, cancelAndStop: cancelAndStop, detail: detail, isCurrent: current, finalSnapshot: finalSnapshot, isInScope: sameContext };
    }

    function context() {
        var shared = window.__TaxonomyAnalysisSessionContext;
        var runtime = shared && shared.runtime || {};
        return { workspaceId: runtime.workspaceId || '', generation: runtime.analysisGeneration || 0,
            invalidating: Boolean(runtime.invalidating) };
    }
    function german() {
        var i18n = window.TaxonomyI18n;
        return String(i18n && i18n.getLocale ? i18n.getLocale() : document.documentElement.lang || '').startsWith('de');
    }
    function text(de, en) { return german() ? de : en; }
    function node(tag, value, className) {
        var element = document.createElement(tag);
        if (value !== undefined) element.textContent = value;
        if (className) element.className = className;
        return element;
    }
    function presentation() {
        var previous = document.getElementById('analysisLiveProgress');
        if (previous) previous.remove();
        var panel = node('section', undefined, 'alert alert-info mt-2');
        panel.id = 'analysisLiveProgress';
        panel.setAttribute('role', 'status');
        panel.setAttribute('aria-live', 'polite');
        var title = node('strong', text('Analyse wird gestartet', 'Starting analysis'));
        var state = node('div', text('Warte auf den Server …', 'Waiting for the server …'));
        var resources = node('div');
        var warning = node('div', '', 'fw-bold');
        var button = node('button', text('Analyse abbrechen', 'Cancel analysis'), 'btn btn-sm btn-danger mt-2');
        button.type = 'button';
        panel.append(title, state, resources, warning, button);
        var anchor = document.getElementById('statusArea') || document.getElementById('analyzeBtn');
        if (anchor) anchor.insertAdjacentElement('afterend', panel);
        var log = document.getElementById('llmCommLogContent');
        if (log) log.replaceChildren();
        var entries = new Map();
        var omitted = node('div', '', 'text-muted p-2');
        if (log) log.append(omitted);
        async function loadDetail(entry, monitor) {
            if (!entry.details.open || entry.loaded || entry.loading || entry.call.status === 'STARTED') return;
            entry.loading = true;
            try {
                var detail = await monitor.detail(entry.call.id);
                if (!entry.details.isConnected) return;
                entry.body.textContent = 'Prompt\n' + detail.prompt + '\n\nResponse\n' + detail.response
                    + (detail.truncated ? '\n\n' + text('Diagnosevorschau gekürzt; Bewertungen bleiben vollständig.',
                        'Diagnostic preview truncated; scores remain complete.') : '');
                entry.loaded = true;
            } catch (error) {
                if (entry.details.isConnected) {
                    entry.body.textContent = text('Details nicht verfügbar: ', 'Details unavailable: ') + error.message;
                }
            } finally { entry.loading = false; }
        }
        var phases = {
            PREPARING: ['Analyse vorbereiten', 'Preparing analysis'],
            LLM_PREPARING: ['LLM-Anfrage vorbereiten', 'Preparing LLM request'],
            WAITING_RATE_LIMIT: ['Warte auf das LLM-Ratenlimit', 'Waiting for LLM rate limit'],
            RETRY_WAIT: ['Warte vor erneutem Versuch', 'Waiting before retry'],
            LLM_REQUEST: ['Warte auf LLM-Antwort', 'Waiting for LLM response'],
            SCORING: ['Bewertungen auswerten', 'Processing scores'],
            RELATIONS: ['Relationshypothesen erstellen', 'Generating relation hypotheses'],
            ARCHITECTURE: ['Architekturansicht erstellen', 'Building architecture view'],
            STOPPING: ['Analyse wird kontrolliert gestoppt', 'Stopping analysis cooperatively'],
            FINISHED: ['Analyse beendet', 'Analysis finished']
        };
        return {
            button: button,
            finalDiagnosticsUnavailable: function () {
                omitted.textContent += text(' Abschlussprotokoll nicht verfügbar; angezeigt bleibt der zuletzt beobachtete Stand.',
                    ' Final diagnostic status unavailable; showing the last observed state.');
            },
            finished: function (status) {
                title.textContent = text('Analyse beendet', 'Analysis finished');
                state.textContent = status === 'SUCCESS'
                    ? text('Vollständiges Ergebnis empfangen.', 'Complete result received.')
                    : text('Teilergebnis oder Fehler empfangen; Einzelheiten stehen im Analysestatus.',
                        'Partial result or error received; see the analysis status for details.');
                button.disabled = true;
            },
            unavailable: function (reason, terminal) {
                if (terminal) button.disabled = true;
                state.textContent = reason === 'WAITING_FOR_RUN'
                    ? text('Warte auf Aufnahme des Laufs; noch keine LLM-Anfrage bestätigt.', 'Waiting for admission; no LLM request confirmed yet.')
                    : text('Statusverbindung unterbrochen; letzter Stand bleibt sichtbar. ', 'Status connection interrupted; retaining the last state. ') + reason;
            },
            cancelling: function () {
                button.disabled = true;
                state.textContent = text('Abbruch angefordert. Ein laufender HTTP-Aufruf kann noch bis zu seinem Timeout dauern.',
                    'Cancellation requested. An in-flight HTTP call may continue until its timeout.');
            },
            render: function (snapshot, monitor) {
                var phase = phases[snapshot.phase];
                title.textContent = phase ? text(phase[0], phase[1]) : snapshot.phase;
                var elapsed = Math.max(0, Math.floor((snapshot.serverTime - snapshot.startedAt) / 1000));
                var quiet = Math.max(0, Math.floor((snapshot.serverTime - snapshot.lastActivityAt) / 1000));
                state.textContent = snapshot.status + ' · ' + snapshot.evaluatedNodes
                    + text(' Knoten bewertet', ' nodes evaluated') + ' · ' + elapsed + ' s · '
                    + text('letzter Arbeitsschritt vor ', 'last activity ') + quiet + ' s'
                    + (snapshot.node ? ' · ' + snapshot.node : '')
                    + text(' · Server-Lebenszeichen empfangen', ' · server heartbeat received');
                var memory = snapshot.memory || {};
                resources.textContent = 'Heap: ' + (memory.percent >= 0 ? memory.percent + '%' : text('unbekannt', 'unknown'))
                    + ' · DB: ' + snapshot.databaseStorage + ' · Index: ' + snapshot.indexStorage;
                warning.textContent = snapshot.stopReason || (memory.warning
                    ? text('Speicherwarnung: Weitere Schritte werden bei anhaltendem Druck gestoppt.',
                        'Memory warning: further steps will stop if pressure persists.')
                    : snapshot.databaseStorage === 'IN_MEMORY' || snapshot.indexStorage === 'IN_MEMORY'
                        ? text('Flüchtiger Speicher: Für große Analysen das Profil hsqldb-file verwenden.',
                            'Volatile storage: use the hsqldb-file profile for large analyses.') : '');
                panel.className = 'alert mt-2 ' + (memory.warning || snapshot.stopReason ? 'alert-warning' : 'alert-info');
                button.disabled = ['RUNNING'].indexOf(snapshot.status) < 0;
                var kept = new Set();
                (snapshot.calls || []).forEach(function (call) {
                    kept.add(call.id);
                    var entry = entries.get(call.id);
                    if (!entry && log) {
                        var details = node('details', undefined, 'llm-log-entry');
                        var summary = node('summary');
                        var body = node('pre', text('Details werden beim Öffnen geladen.', 'Details are loaded when opened.'), 'small text-wrap');
                        details.append(summary, body);
                        entry = { details: details, summary: summary, body: body, loaded: false, loading: false, call: call };
                        entries.set(call.id, entry);
                        log.append(details);
                        details.addEventListener('toggle', function () { loadDetail(entry, monitor); });
                    }
                    if (entry) {
                        var becameReady = entry.call.status === 'STARTED' && call.status !== 'STARTED';
                        entry.call = call;
                        var seconds = call.status === 'STARTED'
                            ? Math.max(0, Math.floor((snapshot.serverTime - call.startedAt) / 1000))
                            : Math.floor(call.durationMillis / 1000);
                        entry.summary.textContent = call.provider + ' · ' + call.node + ' · ' + call.status + ' · ' + seconds + ' s';
                        // An open pending row needs no second toggle when its response arrives.
                        if (becameReady) loadDetail(entry, monitor);
                    }
                });
                entries.forEach(function (entry, key) { if (!kept.has(key)) { entry.details.remove(); entries.delete(key); } });
                omitted.textContent = (snapshot.omittedCalls
                    ? snapshot.omittedCalls + text(' ältere Diagnoseeinträge nicht mehr im Live-Puffer.', ' older diagnostic entries no longer retained.') : '')
                    + (snapshot.scoresTruncated ? text(' Live-Bewertungen gekürzt; das Endergebnis bleibt vollständig.',
                        ' Live score preview truncated; the final result remains complete.') : '');
            }
        };
    }
    function start(id, onScores) {
        if (active) active.cancelAndStop();
        var view = presentation();
        var monitor = createMonitor({
            id: id, context: context, api: window.TaxonomyAnalysisSessionApi,
            setTimeout: window.setTimeout.bind(window), clearTimeout: window.clearTimeout.bind(window),
            onSnapshot: function (snapshot, changed) {
                view.render(snapshot, monitor);
                if (changed && onScores) onScores(snapshot);
            },
            onUnavailable: view.unavailable, onCancelling: view.cancelling
        });
        monitor.transportFailed = function () {
            monitor.cancelAndStop();
            view.unavailable('CONNECTION_LOST', true);
        };
        var finished = false;
        monitor.finish = function (status, refreshDiagnostics) {
            if (finished || active !== monitor) return;
            finished = true;
            monitor.stop();
            view.finished(status);
            if (refreshDiagnostics === false) return;
            monitor.finalSnapshot().then(function (snapshot) {
                if (!snapshot || active !== monitor || !monitor.isInScope()) return;
                view.render(snapshot, monitor);
                view.finished(status);
            }).catch(function () {
                if (active === monitor && monitor.isInScope()) view.finalDiagnosticsUnavailable();
            });
        };
        view.button.addEventListener('click', monitor.cancel);
        active = monitor;
        return monitor;
    }
    window.TaxonomyAnalysisProgress = { createMonitor: createMonitor, start: start };
    if (typeof document !== 'undefined' && document.addEventListener) {
        ['taxonomy:analysis-invalidated', 'taxonomy:analysis-cancelled'].forEach(function (name) {
            document.addEventListener(name, function () {
                if (active) { active.cancelAndStop(); active = null; }
            });
        });
    }
}());
