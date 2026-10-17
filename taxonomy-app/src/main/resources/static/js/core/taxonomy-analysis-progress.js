/* taxonomy-analysis-progress.js – bounded observation of existing analysis operations */
(function () {
    'use strict';
    var active = null;

    function createMonitor(options) {
        var initial = options.context();
        var stopped = false, timer = null, request = null, sequence = 0, cancelling = false;
        var seen = false, cancelPending = false, cancelInFlight = false, cancelAcknowledged = false, cancelUncertain = false;
        var cleanupStarted = false, observedTerminal = false, finalRead = null, finalRequest = null;
        var stream = null, clusterScope = null, resultRead = null, resultRequest = null;
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
            if (resultRequest) resultRequest.abort();
            if (stream) stream.close();
            stream = null;
            request = null;
        }

        function matchesAuthority(scope) {
            if (!scope || typeof scope.repositoryId !== 'string' || !scope.repositoryId
                    || !Object.prototype.hasOwnProperty.call(scope, 'branch')
                    || !Object.prototype.hasOwnProperty.call(scope, 'sourceCommit')
                    || (scope.workspaceId || '') !== (initial.workspaceId || '')) return false;
            return ['workspaceId', 'repositoryId', 'branch', 'sourceCommit'].every(function (key) {
                if (clusterScope) return scope[key] === clusterScope[key];
                return initial[key] === undefined || key === 'workspaceId' || scope[key] === initial[key];
            });
        }

        function recoverResult() {
            if (resultRead) return resultRead;
            if (!clusterScope || !observedTerminal || !sameContext()) return Promise.resolve(null);
            var controller = new AbortController();
            resultRequest = controller;
            var deadline = options.setTimeout(function () { controller.abort(); }, 5000);
            resultRead = (async function () {
                try {
                    var response = await options.api.getRunResult(id, {
                        workspaceId: initial.workspaceId, signal: controller.signal
                    });
                    var result = await response.json();
                    if (!sameContext() || controller.signal.aborted) return null;
                    if (options.onResult) options.onResult(result);
                    return result;
                } finally {
                    options.clearTimeout(deadline);
                    if (resultRequest === controller) resultRequest = null;
                }
            }()).catch(function (error) {
                // Retrying this explicit read is safe; it never starts another run.
                resultRead = null;
                throw error;
            });
            return resultRead;
        }

        async function acceptSnapshot(data, eventId, fromStream) {
            if (!current()) { if (!stopped) cancelAndStop(); return false; }
            if (!data || data.operationId !== id || !Number.isSafeInteger(data.sequence)
                    || data.sequence < sequence || (fromStream && data.sequence === sequence)) return false;
            if (fromStream && (data.transport !== 'artemis'
                    || !/^[0-9]+$/.test(eventId) || Number(eventId) !== data.sequence)) return false;
            if (data.transport === 'artemis') {
                if (!matchesAuthority(data.scope)) return false;
                if (!clusterScope) clusterScope = Object.assign({}, data.scope);
            } else if (clusterScope) return false;
            var changed = data.sequence > sequence;
            sequence = data.sequence;
            seen = true;
            observedTerminal = ['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(data.status) >= 0;
            options.onSnapshot(data, changed);
            if (observedTerminal) {
                cancelPending = false;
                stop();
                if (clusterScope && options.onResult) {
                    try { await recoverResult(); }
                    catch (error) { if (sameContext()) options.onUnavailable('RESULT_UNAVAILABLE'); }
                }
            } else {
                if (clusterScope && !stream) openStream();
                if (cancelPending && (data.status === 'RUNNING' || data.status === 'QUEUED')) {
                    cancelPending = false;
                    await cancel();
                }
            }
            return true;
        }

        function openStream() {
            if (timer !== null) options.clearTimeout(timer);
            timer = null;
            stream = options.api.openRunEvents(id, { workspaceId: initial.workspaceId, afterSequence: sequence });
            function receive(event) {
                if (!current()) { if (!stopped) cancelAndStop(); return; }
                var data;
                try { data = JSON.parse(event.data); }
                catch (_) { return; }
                acceptSnapshot(data, event.lastEventId, true).catch(function () {
                    if (current()) options.onUnavailable('CONNECTION_LOST');
                });
            }
            stream.addEventListener('snapshot', receive);
            stream.addEventListener('progress', receive);
            stream.addEventListener('error', function () {
                if (!current()) { if (!stopped) cancelAndStop(); return; }
                // The native EventSource reconnects with Last-Event-ID. A closed
                // connection remains explicit; neither state resumes DB polling.
                options.onUnavailable('CONNECTION_LOST', stream.readyState === 2);
                if (stream.readyState === 2) stop();
            });
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
                    // only a later observed QUEUED/RUNNING state permits this rejected write to retry.
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
            if (clusterScope) {
                // Durable registration is already known. Departure needs one pinned
                // cancel write, never a second status-polling lifecycle.
                if (!cancelAcknowledged && !cancelUncertain && !cancelInFlight) {
                    cancelInFlight = true;
                    var clusterCancel = new AbortController();
                    var clusterDeadline = options.setTimeout(function () { clusterCancel.abort(); }, 5000);
                    options.api.cancelRun(id, { workspaceId: initial.workspaceId, signal: clusterCancel.signal })
                        .then(function () { cancelAcknowledged = true; })
                        .catch(function () { cancelUncertain = true; })
                        .finally(function () {
                            cancelInFlight = false;
                            options.clearTimeout(clusterDeadline);
                        });
                }
                return true;
            }
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
                    if (data.status !== 'RUNNING' && data.status !== 'QUEUED') return;
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
                await acceptSnapshot(data, null, false);
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
                if (current() && !clusterScope) timer = options.setTimeout(poll, 1000);
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
                            || (clusterScope && !matchesAuthority(data.scope))
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
        return { stop: stop, cancel: cancel, cancelAndStop: cancelAndStop, detail: detail,
            isCurrent: current, finalSnapshot: finalSnapshot, recoverResult: recoverResult, isInScope: sameContext };
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
    function countLabel(done, total) {
        return done + text(' von ', ' of ') + total + text(' erledigt', ' complete');
    }
    function relationLabel(p) {
        return text('Relationsprüfung – Suchaufträge: ', 'Relationship search – tasks: ')
            + (p.totalSearches > 0 ? countLabel(p.completedSearches, p.totalSearches)
                : p.step === 'FINISHED' ? text('Keine Suchaufträge erforderlich', 'No search tasks required')
                    : text('Gesamter Suchumfang noch nicht bekannt', 'Total search work not yet known'))
            + ' · ' + p.unresolvedSearches + text(' ungeklärt', ' unresolved')
            + ' · ' + p.pendingSearches + text(' offen', ' pending');
    }
    var namedTaxonomy = null, names = new Map();
    function nodeLabel(code) {
        if (!code) return '';
        var roots = window.TaxonomyState?.taxonomyData;
        if (roots !== namedTaxonomy) {
            namedTaxonomy = roots;
            names = new Map();
            var pending = Array.isArray(roots) ? roots.slice() : [];
            var seen = new Set();
            while (pending.length && seen.size < 100000) {
                var item = pending.pop();
                if (!item || typeof item !== 'object' || seen.has(item)) continue;
                seen.add(item);
                if (typeof item.code === 'string') names.set(item.code, item);
                if (Array.isArray(item.children)) item.children.forEach(function (child) { pending.push(child); });
            }
        }
        var entry = names.get(code);
        var name = entry && (german() ? entry.nameDe || entry.name || entry.nameEn : entry.nameEn || entry.name);
        return name && name !== code ? name + ' (' + code + ')' : code;
    }
    function clusterRelationCounts(snapshot) {
        var tasks = (snapshot.cluster?.tasks || []).filter(function (task) { return task.taskType === 'RELATION_ANALYSIS'; });
        if (!tasks.length && snapshot.phase !== 'RELATIONS') return null;
        return tasks.reduce(function (total, task) {
            ['queued', 'running', 'completed', 'failed'].forEach(function (key) {
                total[key] += Number.isSafeInteger(task[key]) && task[key] >= 0 ? task[key] : 0;
            });
            return total;
        }, { queued: 0, running: 0, completed: 0, failed: 0 });
    }
    function currentRelation(p) {
        var c = p.current;
        if (!c) return '';
        var steps = { SOURCES: ['Anforderungsbeitrag bestimmen', 'Identify requested contribution'],
            NAVIGATE: ['Hierarchie durchsuchen', 'Search hierarchy'], VERIFY: ['Relationsvorschlag prüfen', 'Verify relationship proposal'] };
        var phase = steps[p.step];
        return nodeLabel(c.sourceId) + (c.targetRoot ? ' → ' + nodeLabel(c.targetRoot) : '')
            + (c.type ? ' · ' + c.type : '')
            + (c.direction ? ' · ' + (c.direction === 'INCOMING'
                ? text('eingehend', 'incoming') : text('ausgehend', 'outgoing')) : '')
            + (phase ? ' · ' + text(phase[0], phase[1]) : '')
            + (p.step !== 'SOURCES' ? text(' · Tiefe ', ' · depth ') + c.depth : '');
    }
    function workSummary(snapshot) {
        var r = snapshot.relationProgress, n = snapshot.nodeProgress;
        if (r) return relationLabel(r) + (r.current ? ' · ' + currentRelation(r) : '');
        var relations = clusterRelationCounts(snapshot);
        if (relations) return text('Relationsprüfung – Suchaufträge: ', 'Relationship search – tasks: ')
            + relations.completed + text(' erledigt · ', ' complete · ')
            + relations.running + text(' aktiv · ', ' active · ')
            + relations.queued + text(' wartend · ', ' queued · ')
            + relations.failed + text(' fehlgeschlagen', ' failed');
        if (snapshot.cluster) return text('Knotenbewertung (Teiltaxonomien): ', 'Node assessment (taxonomy roots): ')
            + countLabel(snapshot.cluster.completedRoots, snapshot.cluster.totalRoots);
        return n ? text('Knotenbewertung: ', 'Node assessment: ') + countLabel(n.assessed + n.excluded, n.total) : '';
    }
    function node(tag, value, className) {
        var element = document.createElement(tag);
        if (value !== undefined) element.textContent = value;
        if (className) element.className = className;
        return element;
    }
    function nodeCoverage(p, compact) {
        return p.assessed + (compact ? text(' bewertet · ', ' assessed · ') : text(' direkt bewertet · ', ' directly assessed · '))
            + p.excluded + (compact ? text(' ausgeschlossen · ', ' excluded · ')
                : text(' durch übergeordnete Entscheidung ausgeschlossen · ', ' excluded by parent decision · '))
            + p.open + text(' offen', ' open');
    }
    function relationRemaining(p) {
        return p.unresolved + text(' ungeklärt · ', ' unresolved · ')
            + p.pending + text(' offen', ' pending');
    }
    function taskCounts(p) {
        return p.queued + text(' wartend · ', ' queued · ')
            + p.running + text(' aktiv · ', ' running · ')
            + p.completed + text(' erledigt · ', ' complete · ')
            + p.failed + text(' fehlgeschlagen', ' failed');
    }
    // Native progress semantics remain available independently of the visual theme.
    // An absent value means the denominator is unknown, never a fabricated 100%.
    function workMeter(container, kind, root, label, done, total, detail, empty, unknown, visibleDetail) {
        var id = 'analysisWork-' + kind + (root ? '-' + encodeURIComponent(root) : '');
        var known = Number.isSafeInteger(total) && total > 0
            && Number.isSafeInteger(done) && done >= 0 && done <= total;
        var count = known ? countLabel(done, total) : total === 0 && empty ? empty
            : unknown || text('Gesamtumfang noch nicht bekannt', 'Total work not yet known');
        var row = node('div', undefined, 'analysis-work-meter' + (known ? ' analysis-work-meter-known' : ''));
        var header = node('div', undefined, 'analysis-work-meter-heading');
        var caption = node('label', label, 'analysis-work-meter-label');
        caption.id = id + '-label';
        caption.setAttribute('for', id);
        header.append(caption, node('span', known && root ? done + '/' + total : count, 'analysis-work-meter-count'));
        var progress = node('progress', count, 'analysis-work-bar');
        progress.id = id;
        progress.setAttribute('data-progress-kind', kind);
        if (root) progress.setAttribute('data-taxonomy-root', root);
        progress.setAttribute('data-progress-state', known ? 'known' : total === 0 && empty ? 'empty' : 'unknown');
        if (known) {
            progress.setAttribute('max', String(total));
            progress.setAttribute('value', String(done));
        }
        progress.setAttribute('aria-labelledby', caption.id);
        progress.setAttribute('aria-valuetext', count + (detail ? ' · ' + detail : ''));
        row.append(header, progress);
        if (detail) row.append(node('div', visibleDetail === undefined ? detail : visibleDetail, 'analysis-work-meter-detail'));
        container.append(row);
    }
    function diagnosticCopyButton(title, raw) {
        var button = node('button', text('Kopieren', 'Copy'),
            'btn btn-sm btn-outline-secondary ms-2 llm-log-copy');
        button.type = 'button';
        button.disabled = !raw;
        button.setAttribute('aria-label', text('Kopieren: ', 'Copy: ') + title);
        button.addEventListener('click', async function () {
            if (!raw) return;
            try {
                if (!window.TaxonomyUtils || typeof window.TaxonomyUtils.copyText !== 'function') {
                    throw new Error('clipboard unavailable');
                }
                await window.TaxonomyUtils.copyText(raw);
                button.textContent = text('Kopiert', 'Copied');
            } catch (_) {
                button.textContent = text('Kopieren fehlgeschlagen', 'Copy failed');
            }
        });
        return button;
    }
    function diagnosticField(container, title, value, className, originalLength, emptyMessage, formatJson) {
        var raw = typeof value === 'string' ? value : '';
        var section = node('div', undefined, 'mb-2');
        var heading = node('div', undefined, 'd-flex align-items-center justify-content-between gap-2');
        heading.append(node('strong', title), diagnosticCopyButton(title, raw));
        section.append(heading);
        if (Number.isSafeInteger(originalLength) && originalLength > raw.length) {
            section.append(node('div', text('Gekürzte Vorschau; ursprüngliche Länge: ',
                'Truncated preview; original length: ') + originalLength + text(' Zeichen.', ' characters.'), 'text-muted'));
        }
        var formatted = raw, parsedJson = false;
        if (formatJson && raw) {
            try {
                var parsed = JSON.parse(raw);
                if (parsed !== null && typeof parsed === 'object') {
                    formatted = JSON.stringify(parsed, null, 2);
                    parsedJson = true;
                }
            } catch (_) { /* Non-JSON and truncated replies remain readable, unchanged evidence. */ }
        }
        section.append(node('pre', raw ? formatted : emptyMessage, className));
        if (parsedJson) {
            var original = node('details');
            original.append(node('summary', text('Unveränderte Rohantwort', 'Unchanged raw response')),
                node('pre', raw, className));
            section.append(original);
        }
        container.append(section);
    }
    function renderDiagnostic(body, detail) {
        body.replaceChildren();
        if (detail.error) {
            body.append(node('strong', text('Fehler', 'Error')),
                node('div', detail.error, 'llm-log-error-detail mb-2'));
        }
        if (detail.taskId) {
            body.append(node('div', text('Worker-Zustellung: ', 'Worker delivery: ') + detail.deliveryAttempt + ' · ' + detail.taskId, 'text-muted'));
        }
        if (Array.isArray(detail.attempts) && detail.attempts.length) {
            var attempts = node('section', undefined, 'mb-2');
            attempts.append(node('strong', text('Transportversuche', 'Transport attempts')));
            detail.attempts.forEach(function (attempt) {
                var usage = attempt.usage || {};
                function tokens(value) { return Number.isSafeInteger(value) && value >= 0 ? String(value) : 'UNKNOWN'; }
                var caption = attempt.source + ' · #' + attempt.retryIndex + ' · ' + attempt.state;
                if (attempt.httpStatus != null) caption += ' · HTTP ' + attempt.httpStatus;
                if (attempt.outcome) caption += ' · ' + attempt.outcome;
                if (attempt.source === 'RECORDING_REPLAY') {
                    caption += text(' · Replay, keine neue Provider-Nutzung', ' · Replay, no new provider usage');
                } else {
                    caption += ' · input=' + tokens(usage.inputTokens) + ' · output=' + tokens(usage.outputTokens) + ' · total=' + tokens(usage.totalTokens);
                    if (usage.invalid) caption += text(' · ungültige Nutzungsangaben', ' · invalid usage report');
                }
                attempts.append(node('div', caption, 'small'));
            });
            body.append(attempts);
        }
        // Keep the answer visible independently of a potentially much longer prompt.
        // Preserve pre-wrap formatting without creating nested scrollports.
        diagnosticField(body, text('LLM-Antwort', 'LLM response'), detail.response, 'llm-log-response',
            detail.responseLength, text('Keine Antwort im Diagnosepuffer vorhanden.',
                'No response retained in the diagnostic buffer.'), true);
        diagnosticField(body, 'Prompt', detail.prompt, 'llm-log-prompt', detail.promptLength,
            text('Kein Prompt im Diagnosepuffer vorhanden.', 'No prompt retained in the diagnostic buffer.'), false);
        if (detail.truncated) {
            body.append(node('div', text('Die Diagnosevorschau ist gekürzt; sie enthält nicht den vollständigen Austausch.',
                'This diagnostic preview is truncated; it does not contain the complete exchange.'), 'text-muted'));
        }
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
        var work = node('div', undefined, 'mt-2');
        work.id = 'analysisWorkProgress';
        // The concise state above announces changes; individual bars remain readable
        // without announcing the entire catalogue again on every progress event.
        work.setAttribute('aria-live', 'off');
        var expandedTaxonomies = true;
        var lastWorkSignature = null;
        function renderWork(snapshot) {
            var signature = JSON.stringify([snapshot.nodeProgress, snapshot.relationProgress, snapshot.cluster,
                snapshot.phase, snapshot.status, german()]);
            if (signature === lastWorkSignature) return;
            lastWorkSignature = signature;
            var oldDetails = document.getElementById('analysisTaxonomyProgressDetails');
            if (oldDetails) expandedTaxonomies = oldDetails.open;
            var focusedSummary = document.activeElement?.id === 'analysisTaxonomyProgressToggle';
            work.replaceChildren();
            work.setAttribute('data-run-status', snapshot.status || '');
            var n = snapshot.nodeProgress, r = snapshot.relationProgress;
            var outcome = { ERROR: ['Analyse fehlgeschlagen; unvollständiger Fortschritt bleibt erhalten.',
                'Analysis failed; incomplete progress is retained.'],
                CANCELLED: ['Analyse abgebrochen; unvollständiger Fortschritt bleibt erhalten.',
                    'Analysis cancelled; incomplete progress is retained.'],
                PARTIAL: ['Analyse unvollständig; erledigte und offene Arbeit bleibt sichtbar.',
                    'Analysis incomplete; completed and open work remain visible.'] }[snapshot.status];
            if (outcome) work.append(node('div', text(outcome[0], outcome[1]), 'analysis-work-outcome'));
            if (snapshot.cluster) {
                if (!n) {
                    workMeter(work, 'cluster-nodes', '', text('Knotenbewertung (Teiltaxonomien)', 'Node assessment (taxonomy roots)'),
                        snapshot.cluster.completedRoots, snapshot.cluster.totalRoots, '', null);
                    work.append(node('div', text('Detaillierte Knotenabdeckung nicht verfügbar; angezeigt werden verteilte Auftragszustände.',
                        'Detailed node coverage unavailable; showing distributed task states.'), 'analysis-work-note'));
                }
                var relations = clusterRelationCounts(snapshot);
                if (relations && !r) {
                    workMeter(work, 'cluster-relations', '', text('Relationsprüfung – Suchaufträge', 'Relationship search – tasks'),
                        null, null, taskCounts(relations));
                    var terminal = ['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(snapshot.status) >= 0;
                    work.append(node('div', terminal ? text(
                        'Suchgesamtumfang nicht verfügbar. Auftragszustände ergeben keinen festen Suchgesamtumfang; sie zählen keine gefundenen Beziehungen.',
                        'Search totals unavailable. Task states do not establish a fixed search total; they do not count relationships found.') : text(
                        'Gezählt werden verteilte Suchaufträge, nicht gefundene Beziehungen. Weitere Suchaufträge können hinzukommen; ein Gesamtprozentwert wäre daher irreführend.',
                        'These are distributed search tasks, not relationships found. More tasks may be added; an overall percentage would therefore be misleading.'), 'analysis-work-note'));
                }
                var clusterParts = node('div', undefined, 'analysis-taxonomy-progress');
                (snapshot.cluster.tasks || []).forEach(function (task) {
                    var relation = task.taskType === 'RELATION_ANALYSIS';
                    if (relation ? r : n) return;
                    var family = relation
                        ? text('Relationsprüfung', 'Relationship search') : text('Knotenbewertung', 'Node assessment');
                    var root = typeof task.root === 'string' ? task.root : task.root?.code;
                    workMeter(clusterParts, relation ? 'cluster-relations' : 'cluster-nodes', root || 'preparation',
                        family + ' · ' + (root ? nodeLabel(root) : text('Vorbereitung', 'Preparation')), null, null, taskCounts(task));
                });
                work.append(clusterParts);
            }
            if (n) {
                workMeter(work, 'nodes', '', text('Knotenbewertung', 'Node assessment'), n.assessed + n.excluded, n.total,
                    nodeCoverage(n), text('Keine Katalogknoten vorhanden', 'No catalogue nodes available'));
                var details = node('details'); details.id = 'analysisTaxonomyProgressDetails'; details.open = expandedTaxonomies;
                var summary = node('summary', text('Fortschritt je Teiltaxonomie', 'Progress by taxonomy'));
                summary.id = 'analysisTaxonomyProgressToggle';
                details.append(summary);
                var parts = node('div', undefined, 'analysis-taxonomy-progress');
                (n.taxonomies || []).forEach(function (part) {
                    var name = german() ? part.nameDe || part.nameEn : part.nameEn || part.nameDe;
                    workMeter(parts, 'nodes', part.root, name && name !== part.root ? name + ' (' + part.root + ')' : nodeLabel(part.root),
                        part.assessed + part.excluded, part.total, nodeCoverage(part),
                        text('Keine Katalogknoten vorhanden', 'No catalogue nodes available'), null, nodeCoverage(part, true));
                });
                details.append(parts);
                details.addEventListener('toggle', function () { expandedTaxonomies = details.open; });
                work.append(details);
            }
            if (r) {
                var empty = r.step === 'FINISHED' ? text('Keine Suchaufträge erforderlich', 'No search tasks required') : null;
                workMeter(work, 'relations', '', text('Relationsprüfung – Suchaufträge', 'Relationship search – tasks'),
                    r.completedSearches, r.totalSearches, relationRemaining({ unresolved: r.unresolvedSearches, pending: r.pendingSearches }),
                    empty, text('Gesamter Suchumfang noch nicht bekannt', 'Total search work not yet known'));
                work.append(node('div', text('Fortschritt zählt Suchaufträge, nicht gefundene Beziehungen.',
                    'Progress counts search tasks, not relationships found.'), 'analysis-work-note'));
                work.append(node('div', r.assessedSources + text(' von ', ' of ') + r.totalSources
                    + text(' Ausgangsknoten geprüft · ', ' source contributions assessed · ')
                    + r.verifiedRelations + text(' geprüfte Relationsvorschläge', ' relationship proposals checked')));
                if (r.current) work.append(node('div', currentRelation(r)));
                var targets = node('div', undefined, 'analysis-taxonomy-progress');
                (r.taxonomies || []).forEach(function (part) {
                    workMeter(targets, 'relations', part.root, text('Ziel: ', 'Target: ') + nodeLabel(part.root),
                        part.completed, part.total, relationRemaining(part), empty,
                        text('Gesamter Suchumfang noch nicht bekannt', 'Total search work not yet known'));
                });
                work.append(targets);
                work.append(node('div', text('Aufrufbudget dieses Durchlaufs: ', 'Call budget for this execution: ')
                    + r.calls + ' / ' + r.maxCalls, 'small'));
                if (r.step === 'PAUSED') work.append(node('div', text(
                    'Relationssuche unvollständig. Offene Aufträge und Abbruchgründe bleiben im Bericht erhalten.',
                    'Relationship search incomplete. Open tasks and stop reasons remain recorded in the report.'), 'fw-bold'));
            }
            if (focusedSummary) document.getElementById('analysisTaxonomyProgressToggle')?.focus({ preventScroll: true });
        }
        var duration = node('div', text('Analysedauer: noch nicht verfügbar', 'Analysis duration: not yet available'));
        duration.id = 'analysisElapsed';
        var queueDuration = node('div');
        queueDuration.id = 'analysisQueueWait';
        queueDuration.hidden = true;
        var terminalDuration = null;
        function showDuration(millis) {
            var value = Number.isSafeInteger(millis) && millis >= 0 ? millis : null;
            duration.textContent = text('Analysedauer: ', 'Analysis duration: ') + (value === null
                ? text('Nicht aufgezeichnet', 'Not recorded')
                : Math.floor(value / 60000) + ' min ' + String(Math.floor(value / 1000) % 60).padStart(2, '0') + ' s');
        }
        var resources = node('div');
        var warning = node('div', '', 'fw-bold');
        var button = node('button', text('Analyse abbrechen', 'Cancel analysis'), 'btn btn-sm btn-danger mt-2');
        button.type = 'button';
        var retryResult = node('button', text('Ergebnis erneut abrufen', 'Retry result'),
            'btn btn-sm btn-outline-primary mt-2 ms-2');
        retryResult.type = 'button';
        retryResult.hidden = true;
        function cancelState(disabled, label) {
            button.disabled = disabled;
            button.className = 'btn btn-sm mt-2 ' + (disabled ? 'btn-outline-secondary' : 'btn-danger');
            button.textContent = label;
        }
        panel.append(title, state, work, queueDuration, duration, resources, warning, button, retryResult);
        var anchor = document.getElementById('statusArea') || document.getElementById('analyzeBtn');
        if (anchor) anchor.insertAdjacentElement('afterend', panel);
        var log = document.getElementById('llmCommLogContent');
        if (log) log.replaceChildren();
        var entries = new Map();
        var omitted = node('div', '', 'text-muted p-2');
        var operation = node('div', '', 'small p-2');
        if (log) log.append(operation, omitted);
        async function loadDetail(entry, monitor) {
            if (!entry.details.open || entry.loaded || entry.loading) return;
            var requestedSequence = entry.detailSequence;
            entry.loading = true;
            try {
                var detail = await monitor.detail(entry.call.id);
                if (!entry.details.isConnected) return;
                renderDiagnostic(entry.body, detail);
                entry.loaded = requestedSequence === entry.detailSequence;
            } catch (error) {
                if (entry.details.isConnected) {
                    entry.body.textContent = text('Details nicht verfügbar: ', 'Details unavailable: ') + error.message;
                }
            } finally {
                entry.loading = false;
                // A newer durable hint can arrive during the explicit detail read.
                if (entry.details.open && requestedSequence !== entry.detailSequence) loadDetail(entry, monitor);
            }
        }
        var phases = {
            QUEUED: ['Wartet auf einen freien Analyseplatz', 'Waiting for an analysis slot'],
            WAITING_PROVIDER_CAPACITY: ['Warte auf freie LLM-Kapazität', 'Waiting for provider capacity'],
            PREPARING: ['Analyse vorbereiten', 'Preparing analysis'],
            LLM_PREPARING: ['LLM-Anfrage vorbereiten', 'Preparing LLM request'],
            WAITING_RATE_LIMIT: ['Warte auf das LLM-Ratenlimit', 'Waiting for LLM rate limit'],
            RETRY_WAIT: ['Warte vor erneutem Versuch', 'Waiting before retry'],
            LLM_REQUEST: ['Warte auf LLM-Antwort', 'Waiting for LLM response'],
            SCORING: ['Bewertungen auswerten', 'Processing scores'],
            RELATIONS: ['Relationshypothesen erstellen', 'Generating relation hypotheses'],
            ARCHITECTURE: ['Architekturansicht erstellen', 'Building architecture view'],
            FINALIZING: ['Analyse abschließen', 'Finalizing analysis'],
            STOPPING: ['Analyse wird kontrolliert gestoppt', 'Stopping analysis cooperatively'],
            FINISHED: ['Analyse beendet', 'Analysis finished']
        };
        return {
            button: button,
            retryResult: retryResult,
            finalDiagnosticsUnavailable: function () {
                omitted.textContent += text(' Abschlussprotokoll nicht verfügbar; angezeigt bleibt der zuletzt beobachtete Stand.',
                    ' Final diagnostic status unavailable; showing the last observed state.');
            },
            finished: function (status, measuredDuration) {
                retryResult.hidden = true;
                if (Number.isSafeInteger(measuredDuration) && measuredDuration >= 0) terminalDuration = measuredDuration;
                // A running estimate may include admission/connection time. Only an
                // authoritative result or measured terminal snapshot can finalize it.
                showDuration(terminalDuration);
                title.textContent = text('Analyse beendet', 'Analysis finished');
                state.textContent = status === 'SUCCESS'
                    ? text('Vollständiges Ergebnis empfangen.', 'Complete result received.')
                    : text('Teilergebnis oder Fehler empfangen; Einzelheiten stehen im Analysestatus.',
                        'Partial result or error received; see the analysis status for details.');
                cancelState(true, text('Analyse beendet', 'Analysis finished'));
            },
            unavailable: function (reason, terminal) {
                if (reason === 'RESULT_UNAVAILABLE') {
                    retryResult.hidden = false;
                    retryResult.disabled = false;
                    state.textContent = text('Gespeichertes Ergebnis konnte nicht abgerufen werden. Erneut versuchen.',
                        'The saved result could not be retrieved. Try again.');
                    return;
                }
                if (terminal) {
                    cancelState(true, text('Nicht verfügbar', 'Unavailable'));
                    showDuration(terminalDuration);
                }
                state.textContent = reason === 'WAITING_FOR_RUN'
                    ? text('Warte auf Aufnahme des Laufs; noch keine LLM-Anfrage bestätigt.', 'Waiting for admission; no LLM request confirmed yet.')
                    : text('Statusverbindung unterbrochen; letzter Stand bleibt sichtbar. ', 'Status connection interrupted; retaining the last state. ') + reason;
            },
            cancelling: function () {
                cancelState(true, text('Abbruch angefordert', 'Cancellation requested'));
                state.textContent = text('Abbruch angefordert. Ein laufender HTTP-Aufruf kann noch bis zu seinem Timeout dauern.',
                    'Cancellation requested. An in-flight HTTP call may continue until its timeout.');
            },
            render: function (snapshot, monitor) {
                renderWork(snapshot);
                var phase = phases[snapshot.phase];
                if (snapshot.phase === 'RELATIONS' && snapshot.relationProgress && ['SOURCES', 'NAVIGATE', 'VERIFY'].indexOf(snapshot.relationProgress.step) >= 0)
                    phase = ['Relationen bewerten', 'Assessing relationships'];
                title.textContent = phase ? text(phase[0], phase[1]) : snapshot.phase;
                var isTerminal = ['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(snapshot.status) >= 0;
                var measuredExecution = Number.isSafeInteger(snapshot.executionMillis) && snapshot.executionMillis >= 0;
                var elapsedMillis = measuredExecution ? snapshot.executionMillis : Number.isSafeInteger(snapshot.elapsedMillis) && snapshot.elapsedMillis >= 0
                    ? snapshot.elapsedMillis : !isTerminal && Number.isFinite(snapshot.startedAt) && Number.isFinite(snapshot.serverTime)
                        ? Math.max(0, snapshot.serverTime - snapshot.startedAt) : null;
                if (isTerminal) terminalDuration = elapsedMillis;
                showDuration(elapsedMillis);
                var hasQueueTime = Number.isSafeInteger(snapshot.queueWaitMillis) && snapshot.queueWaitMillis >= 0;
                queueDuration.hidden = !hasQueueTime;
                queueDuration.textContent = hasQueueTime ? text('Wartezeit vor Analysebeginn: ', 'Queue time before analysis: ')
                    + Math.floor(snapshot.queueWaitMillis / 1000) + ' s' : '';
                var elapsed = elapsedMillis === null ? '?' : Math.floor(elapsedMillis / 1000);
                var quiet = Math.max(0, Math.floor((snapshot.serverTime - snapshot.lastActivityAt) / 1000));
                state.textContent = snapshot.status === 'QUEUED'
                    ? text('Auftrag angenommen; wartet auf freien Analyseplatz. Noch keine LLM-Anfrage gestartet.',
                        'Request accepted; waiting for an analysis slot. No LLM request started yet.')
                    : snapshot.status + ' · ' + (snapshot.cluster || snapshot.nodeProgress || snapshot.relationProgress ? workSummary(snapshot)
                        : snapshot.evaluatedNodes + text(' Knoten bewertet', ' nodes evaluated')) + ' · ' + elapsed + ' s · '
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
                var cancellable = snapshot.status === 'RUNNING' || snapshot.status === 'QUEUED';
                cancelState(!cancellable, cancellable
                    ? text('Analyse abbrechen', 'Cancel analysis')
                    : snapshot.status === 'CANCELLING'
                        ? text('Abbruch angefordert', 'Cancellation requested')
                        : text('Analyse beendet', 'Analysis finished'));
                operation.textContent = text('Vorgang: ', 'Operation: ') + snapshot.operationId;
                var kept = new Set();
                (snapshot.calls || []).forEach(function (call) {
                    kept.add(call.id);
                    var entry = entries.get(call.id);
                    if (!entry && log) {
                        var details = node('details', undefined, 'llm-log-entry');
                        var summary = node('summary');
                        var body = node('div', text('Details werden beim Öffnen geladen.', 'Details are loaded when opened.'), 'small');
                        details.append(summary, body);
                        entry = { details: details, summary: summary, body: body, loaded: false, loading: false, call: call };
                        entries.set(call.id, entry);
                        log.append(details);
                        details.addEventListener('toggle', function () {
                            // A deliberate reopening may inspect late durable completion after live observation ended.
                            if (details.open) entry.loaded = false;
                            loadDetail(entry, monitor);
                        });
                    }
                    if (entry) {
                        var becameReady = entry.call.status === 'STARTED' && call.status !== 'STARTED';
                        var changed = entry.detailSequence !== snapshot.sequence;
                        entry.detailSequence = snapshot.sequence;
                        if (changed || becameReady) entry.loaded = false;
                        entry.call = call;
                        entry.details.className = 'llm-log-entry' + (call.status === 'FAILED' ? ' llm-log-error' : '');
                        var seconds = call.status === 'STARTED'
                            ? Math.max(0, Math.floor((snapshot.serverTime - call.startedAt) / 1000))
                            : Math.floor(call.durationMillis / 1000);
                        entry.summary.replaceChildren(node('span', text('Aufruf #', 'Call #') + call.id
                            + ' · ' + call.provider + ' · ' + call.node + ' · '));
                        // Display the recorded server start, never a polling/browser reception time.
                        var started = Number.isSafeInteger(call.startedAt) ? new Date(call.startedAt) : null;
                        if (started && Number.isFinite(started.getTime())) {
                            var iso = started.toISOString();
                            var timestamp = node('time', iso.replace('T', ' ').replace('Z', ' UTC'));
                            timestamp.setAttribute('datetime', iso);
                            entry.summary.append(node('span', text('Start: ', 'Started: ')), timestamp);
                        } else {
                            entry.summary.append(node('span', text('Startzeit unbekannt', 'Start time unknown')));
                        }
                        entry.summary.append(node('span', ' · ' + call.status + ' · '
                            + (Number.isFinite(seconds) ? seconds + ' s' : '—')));
                        // An open pending row needs no second toggle when its response arrives.
                        if (changed || becameReady) loadDetail(entry, monitor);
                    }
                });
                entries.forEach(function (entry, key) { if (!kept.has(key)) { entry.details.remove(); entries.delete(key); } });
                omitted.textContent = (snapshot.omittedCalls
                    ? snapshot.omittedCalls + text(' ältere Diagnoseeinträge in dieser Ansicht ausgeblendet.', ' older diagnostic entries omitted from this view.') : '')
                    + (snapshot.scoresTruncated ? text(' Live-Bewertungen gekürzt; das Endergebnis bleibt vollständig.',
                        ' Live score preview truncated; the final result remains complete.') : '');
            }
        };
    }
    function start(id, onScores, onResult, expectedScope) {
        if (active) active.cancelAndStop();
        clearRecent();
        var view = presentation();
        var monitor = createMonitor({
            id: id, context: function () { return Object.assign({}, expectedScope || {}, context()); },
            api: window.TaxonomyAnalysisSessionApi,
            setTimeout: window.setTimeout.bind(window), clearTimeout: window.clearTimeout.bind(window),
            onSnapshot: function (snapshot, changed) {
                view.render(snapshot, monitor);
                document.dispatchEvent(new CustomEvent('taxonomy:analysis-progress', { detail: snapshot }));
                if (changed && onScores) onScores(snapshot);
            },
            onUnavailable: view.unavailable, onCancelling: view.cancelling,
            onResult: onResult ? function (result) {
                if (active !== monitor || !monitor.isInScope()) return;
                onResult(result);
            } : null
        });
        // Result ownership outlives polling: a terminal snapshot may arrive before the
        // complete HTTP envelope, but a replacement operation must never accept it.
        monitor.acceptsResult = function () { return active === monitor && monitor.isInScope(); };
        monitor.transportFailed = function () {
            monitor.cancelAndStop();
            view.unavailable('CONNECTION_LOST', true);
        };
        var finished = false;
        monitor.finish = function (status, refreshDiagnostics, measuredDuration) {
            if (finished || active !== monitor) return;
            finished = true;
            monitor.stop();
            view.finished(status, measuredDuration);
            if (refreshDiagnostics === false) return;
            monitor.finalSnapshot().then(function (snapshot) {
                if (!snapshot || active !== monitor || !monitor.isInScope()) return;
                view.render(snapshot, monitor);
                view.finished(status, measuredDuration);
            }).catch(function () {
                if (active === monitor && monitor.isInScope()) view.finalDiagnosticsUnavailable();
            });
        };
        view.button.addEventListener('click', monitor.cancel);
        view.retryResult.addEventListener('click', async function () {
            if (active !== monitor || !monitor.isInScope() || view.retryResult.disabled) return;
            view.retryResult.disabled = true;
            try {
                await monitor.recoverResult();
                if (active === monitor && monitor.isInScope()) view.retryResult.hidden = true;
            } catch (_) {
                if (active === monitor && monitor.isInScope()) view.unavailable('RESULT_UNAVAILABLE');
            }
        });
        active = monitor;
        return monitor;
    }
    var recentLoaded = null, recentRead = null, recentEpoch = 0;
    function clearRecent() {
        recentEpoch++; recentLoaded = null; recentRead = null;
        var panel = document.getElementById('analysisRecentRuns');
        if (panel) panel.remove();
    }
    function refreshRecent(force) {
        var session = window.TaxonomyAnalysisSession;
        if (!session || !session.state().ready || active && active.isCurrent()) return Promise.resolve(false);
        var initial = context(), epoch = recentEpoch;
        var key = JSON.stringify([initial.workspaceId, initial.generation]);
        if (recentRead) return recentRead;
        if (!force && recentLoaded === key) return Promise.resolve(false);
        var controller = new AbortController();
        var deadline = window.setTimeout(function () { controller.abort(); }, 5000);
        function valid() {
            var now = context();
            return recentEpoch === epoch && now.workspaceId === initial.workspaceId
                && now.generation === initial.generation && !now.invalidating && session.state().ready;
        }
        recentRead = window.TaxonomyAnalysisSessionApi.getRecentRuns({
            workspaceId: initial.workspaceId, signal: controller.signal
        }).then(function (response) { return response.json(); }).then(function (runs) {
            if (!valid() || active && active.isCurrent()) return false;
            recentLoaded = key;
            var previous = document.getElementById('analysisRecentRuns');
            if (previous) previous.remove();
            runs = (Array.isArray(runs) ? runs : []).filter(function (run) {
                return run.transport === 'artemis' && run.scope
                    && (run.scope.workspaceId || '') === (initial.workspaceId || '');
            }).sort(function (left, right) {
                function running(run) { return ['QUEUED', 'RUNNING', 'CANCELLING'].indexOf(run.status) >= 0 ? 1 : 0; }
                return running(right) - running(left) || right.startedAt - left.startedAt;
            }).slice(0, 10);
            if (!runs.length) return false;
            var panel = node('section', undefined, 'alert alert-secondary mt-2'); panel.id = 'analysisRecentRuns';
            panel.append(node('strong', text('Gespeicherte Analyseläufe', 'Saved analysis runs')),
                node('div', text('Auswählen stellt die ursprüngliche Anforderung wieder her und ersetzt den aktuellen Arbeitsentwurf.',
                    'Choosing a run restores its original requirement and replaces the current working draft.'), 'small'));
            runs.forEach(function (run) {
                var terminal = ['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(run.status) >= 0;
                var row = node('div', undefined, 'd-flex flex-wrap align-items-center gap-2 mt-2');
                row.append(node('span', run.status + ' · ' + workSummary(run) + ' · ' + run.operationId, 'small'));
                var button = node('button', terminal ? text('Ergebnis abrufen', 'Retrieve result')
                    : text('Beobachtung fortsetzen', 'Resume observation'), 'btn btn-sm btn-outline-primary');
                button.type = 'button';
                button.addEventListener('click', async function () {
                    if (!valid() || !window.TaxonomyScoring || button.disabled) return;
                    button.disabled = true;
                    try {
                        if (await window.TaxonomyScoring.resumeAnalysis(run.operationId, run.scope)) panel.remove();
                    } catch (_) {
                        if (valid()) panel.append(node('div', text('Wiederherstellung nicht verfügbar. Erneut versuchen.',
                            'Recovery unavailable. Try again.'), 'small text-danger'));
                    } finally { button.disabled = false; }
                });
                row.append(button); panel.append(row);
            });
            var anchor = document.getElementById('statusArea') || document.getElementById('analyzeBtn');
            if (anchor) anchor.insertAdjacentElement('afterend', panel);
            return true;
        }).catch(function () { return false; }).finally(function () {
            window.clearTimeout(deadline);
            if (epoch === recentEpoch) recentRead = null;
        });
        return recentRead;
    }
    window.TaxonomyAnalysisProgress = { createMonitor: createMonitor, start: start,
        workSummary: workSummary, refreshRecent: refreshRecent };
    if (window.TaxonomyAnalysisSessionReady && typeof window.TaxonomyAnalysisSessionReady.then === 'function') {
        window.TaxonomyAnalysisSessionReady.then(function (ready) { if (ready) refreshRecent(); });
    }
    if (typeof document !== 'undefined' && document.addEventListener) {
        ['taxonomy:analysis-invalidated', 'taxonomy:analysis-cancelled'].forEach(function (name) {
            document.addEventListener(name, function () {
                if (active) { active.cancelAndStop(); active = null; }
                clearRecent();
            });
        });
        document.addEventListener('taxonomy:analysis-draft-restored', function () {
            window.setTimeout(function () { refreshRecent(); }, 0);
        });
    }
}());
