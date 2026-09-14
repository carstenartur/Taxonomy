/* taxonomy-analysis-progress.js – bounded observation of existing analysis operations */
(function () {
    'use strict';
    var active = null;

    function createMonitor(options) {
        var initial = options.context();
        var stopped = false, timer = null, request = null, sequence = 0, cancelling = false;
        var seen = false;
        var id = options.id;
        var base = '/api/analysis-runs/' + encodeURIComponent(id);
        function current() {
            var now = options.context();
            return !stopped && now.workspaceId === initial.workspaceId
                && now.generation === initial.generation && !now.invalidating;
        }
        function headers() {
            var result = Object.assign({}, options.headers ? options.headers() : {});
            if (initial.workspaceId) result['X-Taxonomy-Workspace-Id'] = initial.workspaceId;
            return result;
        }
        function url(suffix) {
            var target = base + (suffix || '');
            if (initial.workspaceId) target += '?workspaceId=' + encodeURIComponent(initial.workspaceId);
            return options.resolveUrl ? options.resolveUrl(target) : target;
        }
        function stop() {
            stopped = true;
            if (timer !== null) options.clearTimeout(timer);
            timer = null;
            if (request) request.abort();
            request = null;
        }
        async function cancel() {
            if (cancelling) return;
            cancelling = true;
            try {
                var response = await options.fetch(url('/cancel'), {
                    method: 'POST', headers: headers(), cache: 'no-store', keepalive: true
                });
                if (!response.ok) throw new Error('HTTP ' + response.status);
                if (current() && options.onCancelling) options.onCancelling();
            } catch (error) {
                cancelling = false;
                if (current()) options.onUnavailable(error.message);
            }
        }
        async function poll() {
            if (!current()) { if (!stopped) cancel(); stop(); return; }
            request = new AbortController();
            var timeout = options.setTimeout(function () { if (request) request.abort(); }, 5000);
            try {
                var response = await options.fetch(url(), {
                    headers: headers(), cache: 'no-store', signal: request.signal
                });
                if (response.status === 404 && !seen) {
                    if (current()) options.onUnavailable('WAITING_FOR_RUN');
                    return;
                }
                if (!response.ok) throw new Error('HTTP ' + response.status);
                var data = await response.json();
                if (!current()) return;
                if (data.operationId !== id || !Number.isSafeInteger(data.sequence)
                        || data.sequence < sequence) return;
                var changed = data.sequence > sequence;
                sequence = data.sequence;
                seen = true;
                options.onSnapshot(data, changed);
                if (['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(data.status) >= 0) stop();
            } catch (error) {
                if (current()) options.onUnavailable(error.name === 'AbortError' ? 'CONNECTION_TIMEOUT' : error.message);
            } finally {
                options.clearTimeout(timeout);
                request = null;
                if (current()) timer = options.setTimeout(poll, 1000);
            }
        }
        async function detail(callId) {
            if (!current() && !stopped) throw new Error('STALE_ANALYSIS');
            var now = options.context();
            if (now.workspaceId !== initial.workspaceId || now.generation !== initial.generation) {
                throw new Error('STALE_ANALYSIS');
            }
            var response = await options.fetch(url('/calls/' + encodeURIComponent(callId)), {
                headers: headers(), cache: 'no-store'
            });
            if (!response.ok) throw new Error('HTTP ' + response.status);
            var data = await response.json();
            now = options.context();
            if (now.workspaceId !== initial.workspaceId || now.generation !== initial.generation || now.invalidating) {
                throw new Error('STALE_ANALYSIS');
            }
            return data;
        }
        timer = options.setTimeout(poll, 0);
        return { stop: stop, cancel: cancel, detail: detail, isCurrent: current };
    }

    function context() {
        var shared = window.__TaxonomyAnalysisSessionContext;
        var runtime = shared && shared.runtime || {};
        return { workspaceId: runtime.workspaceId || '', generation: runtime.analysisGeneration || 0,
            invalidating: Boolean(runtime.invalidating) };
    }
    function csrfHeaders() {
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        var result = {};
        if (token && token.content) result[header && header.content || 'X-CSRF-TOKEN'] = token.content;
        return result;
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
    function resolveUrl(url) {
        return window.TaxonomyI18n && window.TaxonomyI18n.resolveUrl
            ? window.TaxonomyI18n.resolveUrl(url) : url;
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
        var button = node('button', text('Analyse abbrechen', 'Cancel analysis'), 'btn btn-sm btn-outline-danger mt-2');
        button.type = 'button';
        panel.append(title, state, resources, warning, button);
        var anchor = document.getElementById('statusArea') || document.getElementById('analyzeBtn');
        if (anchor) anchor.insertAdjacentElement('afterend', panel);
        var log = document.getElementById('llmCommLogContent');
        if (log) log.replaceChildren();
        var entries = new Map();
        var omitted = node('div', '', 'text-muted p-2');
        if (log) log.append(omitted);
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
            unavailable: function (reason) {
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
                        details.addEventListener('toggle', async function () {
                            if (!details.open || entry.loaded || entry.loading || entry.call.status === 'STARTED') return;
                            entry.loading = true;
                            try {
                                var detail = await monitor.detail(call.id);
                                if (!details.isConnected) return;
                                body.textContent = 'Prompt\n' + detail.prompt + '\n\nResponse\n' + detail.response
                                    + (detail.truncated ? '\n\n' + text('Diagnosevorschau gekürzt; Bewertungen bleiben vollständig.',
                                        'Diagnostic preview truncated; scores remain complete.') : '');
                                entry.loaded = true;
                            } catch (error) { body.textContent = text('Details nicht verfügbar: ', 'Details unavailable: ') + error.message; }
                            finally { entry.loading = false; }
                        });
                    }
                    if (entry) {
                        entry.call = call;
                        var seconds = call.status === 'STARTED'
                            ? Math.max(0, Math.floor((snapshot.serverTime - call.startedAt) / 1000))
                            : Math.floor(call.durationMillis / 1000);
                        entry.summary.textContent = call.provider + ' · ' + call.node + ' · ' + call.status + ' · ' + seconds + ' s';
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
        if (active) { active.cancel(); active.stop(); }
        var view = presentation();
        var monitor = createMonitor({
            id: id, context: context, fetch: window.fetch.bind(window), headers: csrfHeaders,
            resolveUrl: resolveUrl, setTimeout: window.setTimeout.bind(window), clearTimeout: window.clearTimeout.bind(window),
            onSnapshot: function (snapshot, changed) {
                view.render(snapshot, monitor);
                if (changed && onScores) onScores(snapshot);
            },
            onUnavailable: view.unavailable, onCancelling: view.cancelling
        });
        view.button.addEventListener('click', monitor.cancel);
        active = monitor;
        return monitor;
    }
    window.TaxonomyAnalysisProgress = { createMonitor: createMonitor, start: start };
    if (typeof document !== 'undefined' && document.addEventListener) {
        ['taxonomy:analysis-invalidated', 'taxonomy:analysis-cancelled'].forEach(function (name) {
            document.addEventListener(name, function () {
                if (active) { active.cancel(); active.stop(); active = null; }
            });
        });
    }
}());
