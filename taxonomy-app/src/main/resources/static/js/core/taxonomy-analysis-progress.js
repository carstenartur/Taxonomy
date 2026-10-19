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
    function refreshNames() {
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
    }
    function nodeLabel(code) {
        if (!code) return '';
        refreshNames();
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
        if (r) return relationLabel(r);
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
    function percent(value) {
        return typeof value === 'number' && Number.isFinite(value) && value >= 0
            ? value.toLocaleString(german() ? 'de-DE' : 'en-GB', { maximumSignificantDigits: 6 }) + '%' : '—';
    }
    function scoreCoverage(part) {
        return text('Nichtnull: ', 'Non-zero: ') + (Number.isSafeInteger(part.positive) ? part.positive : text('nicht aufgezeichnet', 'not recorded'))
            + text(' · Verteilt: ', ' · Allocated: ') + percent(part.rootPercent == null ? null : part.allocatedPercent)
            + ' / ' + percent(part.rootPercent);
    }
    function sourceCoverage(part) {
        if (part.total === 0) return text('Keine konkreten Relations-Ausgangsknoten', 'No concrete relationship source nodes');
        return part.completed + text(' von ', ' of ') + part.total + text(' Ausgangsknoten erledigt · ', ' source nodes complete · ')
            + part.unresolved + text(' ungeklärt', ' unresolved');
    }
    function taxonomyCounts(part) {
        var list = node('dl', undefined, 'analysis-taxonomy-counts');
        list.id = 'analysis-taxonomy-counts-' + part.root;
        [[text('Gesamt', 'Total'), part.total], [text('Verarbeitet', 'Processed'), part.assessed + part.excluded],
            [text('Nichtnull', 'Non-zero'), Number.isSafeInteger(part.positive) ? part.positive : '—'],
            [text('Verteilt', 'Allocated'), percent(part.rootPercent == null ? null : part.allocatedPercent) + ' / ' + percent(part.rootPercent)]].forEach(function (metric, index) {
            var field = node('div');
            field.append(node('dt', metric[0]), node('dd', metric[1]));
            if (index === 1) field.setAttribute('title', nodeCoverage(part));
            if (index === 3) field.setAttribute('title', text(
                'Bis zu Blättern bzw. Produktfamilien verteiltes Bewertungsgewicht / Gewicht der Teiltaxonomie. Produkteignungen werden nicht addiert.',
                'Assessment weight allocated to leaves or product families / taxonomy weight. Product suitability scores are not added.'));
            list.append(field);
        });
        return list;
    }
    function belongsToRoot(code, root) {
        if (typeof code !== 'string') return false;
        refreshNames();
        return code === root || code.startsWith(root + '-') || names.get(code)?.taxonomyRoot === root;
    }
    function assessmentLabel(status, terminal) {
        var labels = {
            NOT_STARTED: terminal ? ['Nicht bewertet', 'Not assessed'] : ['Noch nicht begonnen', 'Not started'],
            RUNNING: ['Wird bewertet', 'Assessing'],
            COMPLETED: ['Bewertung abgeschlossen', 'Assessment completed'],
            COMPLETED_ZERO: ['Abgeschlossen · 0% Relevanz', 'Completed · 0% relevance'],
            PARTIAL: ['Bewertung unvollständig', 'Incomplete assessment'],
            FAILED: ['Bewertung fehlgeschlagen', 'Assessment failed']
        };
        return text(labels[status][0], labels[status][1]);
    }
    function sourceRoot(r, root) {
        var current = r?.current;
        if (!current) return false;
        refreshNames();
        return (current.candidates && r.step === 'SOURCES' ? current.candidates : [current.sourceId]).some(function (id) {
            return id === root || id.startsWith(root + '-') || names.get(id)?.taxonomyRoot === root;
        });
    }
    function colourNodeBar(bar, part, sources, relations) {
        var positive = part.positive, covered = part.assessed + part.excluded;
        var scoreKnown = Number.isSafeInteger(positive) && positive >= 0 && positive <= part.assessed;
        var sourceKnown = !relations || Boolean(sources);
        if (scoreKnown) bar.setAttribute('data-positive', positive);
        if (sourceKnown) {
            bar.setAttribute('data-relations-completed', sources ? sources.completed : 0);
            bar.setAttribute('data-relations-unresolved', sources ? sources.unresolved : 0);
        }
        var detail = bar.getAttribute('aria-valuetext') + ' · ' + (part.root ? scoreCoverage(part)
            : text('Nichtnull: ', 'Non-zero: ') + (scoreKnown ? positive : text('nicht aufgezeichnet', 'not recorded')));
        if (relations) detail += ' · ' + (sources ? sourceCoverage(sources)
            : text('Relations-Ausgangsknoten nicht aufgezeichnet', 'Relationship source nodes not recorded'));
        bar.setAttribute('aria-valuetext', detail);
        bar.setAttribute('data-score-state', scoreKnown ? 'known' : 'unknown');
        if (covered <= 0) return;
        var completed = Math.min(scoreKnown ? positive : 0, sources ? sources.completed : 0);
        var unresolved = Math.min(positive - completed, sources ? sources.unresolved : 0);
        // The current-work pointer does not say whether that source is already
        // counted as unresolved/completed. Highlight its row without counting it twice.
        var segments = scoreKnown ? [['relation-complete', completed], ['relation-open', unresolved],
            ['positive', positive - completed - unresolved], ['zero', part.assessed - positive], ['excluded', part.excluded]]
            : [['unknown', part.assessed], ['excluded', part.excluded]];
        var position = 0, stops = [];
        segments.forEach(function (segment) {
            var end = position + segment[1] / covered * 100;
            if (segment[1] > 0) stops.push('var(--analysis-segment-' + segment[0] + ') ' + position + '% ' + end + '%');
            position = end;
        });
        // Only the covered portion is painted by the native progress element;
        // every segment therefore remains a count of catalogue nodes.
        bar.setAttribute('style', '--analysis-work-segments:linear-gradient(to right,' + stops.join(',') + ')');
    }
    function workLegend(container) {
        var legend = node('div', undefined, 'analysis-work-legend');
        legend.setAttribute('aria-label', text('Bedeutung der Balkenfarben', 'Bar colour legend'));
        legend.append(node('strong', text('Knotenbalken:', 'Node bars:')));
        [['open', 'Offen', 'Unassessed'], ['excluded', 'Ausgeschlossen', 'Excluded'], ['zero', 'Bewertet: 0', 'Assessed: 0'],
            ['positive', 'Bewertet: > 0', 'Assessed: > 0'], ['unknown', 'Trefferzahl unbekannt', 'Positive count unknown'],
            ['active', 'Aktueller Suchbereich (Rand)', 'Current search scope (border)'],
            ['relation-complete', 'Relationssuche erledigt', 'Relationship search complete'],
            ['relation-open', 'Relationssuche ungeklärt', 'Relationship search unresolved']].forEach(function (entry) {
            var item = node('span', undefined, 'analysis-work-legend-item');
            var swatch = node('span', undefined, 'analysis-work-swatch analysis-segment-' + entry[0]);
            swatch.setAttribute('aria-hidden', 'true'); item.append(swatch, node('span', text(entry[1], entry[2]))); legend.append(item);
        });
        container.append(legend);
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
        return { bar: progress, row: row, header: header };
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
        var taxonomyOrder = [];
        var lastNodeProgress = null;
        var startedTaxonomies = new Set(), failedTaxonomies = new Set();
        function assessmentState(part, snapshot) {
            var terminal = ['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(snapshot.status) >= 0;
            var task = (snapshot.cluster?.tasks || []).find(function (entry) {
                return entry.taskType === 'SUBTAXONOMY_ANALYSIS'
                    && (typeof entry.root === 'string' ? entry.root : entry.root?.code) === part.root;
            });
            var calls = (snapshot.calls || []).filter(function (call) { return belongsToRoot(call.node, part.root); });
            if (task?.failed > 0 || calls.some(function (call) { return call.status === 'FAILED'; })) failedTaxonomies.add(part.root);
            var active = !terminal && !snapshot.relationProgress && snapshot.phase !== 'RELATIONS' && snapshot.phase !== 'FINALIZING'
                && (task ? task.running > 0 : calls.some(function (call) { return call.status === 'STARTED'; })
                    || belongsToRoot(snapshot.node, part.root) && snapshot.phase !== 'LLM_FAILED');
            if (active || !task && calls.length || task?.completed > 0 || task?.failed > 0) startedTaxonomies.add(part.root);
            // Durable failures outrank provisional diagnostics left by a crashed worker.
            if (task?.failed > 0) return part.assessed + part.excluded > 0 ? 'PARTIAL' : 'FAILED';
            if (part.total > 0 && part.open === 0) return part.rootPercent === 0 ? 'COMPLETED_ZERO' : 'COMPLETED';
            if (active) return 'RUNNING';
            if (failedTaxonomies.has(part.root)) return part.assessed + part.excluded > 0 ? 'PARTIAL' : 'FAILED';
            if (part.assessed + part.excluded > 0 || startedTaxonomies.has(part.root)) return 'PARTIAL';
            return 'NOT_STARTED';
        }
        function renderWork(snapshot) {
            var signature = JSON.stringify([snapshot.nodeProgress, snapshot.relationProgress, snapshot.cluster,
                snapshot.phase, snapshot.status, snapshot.node,
                (snapshot.calls || []).map(function (call) { return [call.node, call.status]; }), german()]);
            if (signature === lastWorkSignature) return;
            lastWorkSignature = signature;
            var oldDetails = document.getElementById('analysisTaxonomyProgressDetails');
            if (oldDetails) expandedTaxonomies = oldDetails.open;
            var focusedSummary = document.activeElement?.id === 'analysisTaxonomyProgressToggle';
            work.replaceChildren();
            work.setAttribute('data-run-status', snapshot.status || '');
            if (snapshot.nodeProgress) lastNodeProgress = snapshot.nodeProgress;
            var n = lastNodeProgress, r = snapshot.relationProgress;
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
                var overall = workMeter(work, 'nodes', '', text('Knotenbewertung', 'Node assessment'), n.assessed + n.excluded, n.total,
                    nodeCoverage(n), text('Keine Katalogknoten vorhanden', 'No catalogue nodes available'));
                var positiveKnown = n.taxonomies?.length && n.taxonomies.every(function (part) { return Number.isSafeInteger(part.positive); });
                var positive = positiveKnown ? n.taxonomies.reduce(function (sum, part) { return sum + part.positive; }, 0) : null;
                var sourceTotals = r && ((r.sourceTaxonomies || []).length || r.totalSources === 0 && r.step === 'FINISHED')
                    ? (r.sourceTaxonomies || []).reduce(function (sum, part) {
                        sum.total += part.total; sum.completed += part.completed; sum.unresolved += part.unresolved; return sum;
                    }, { total: 0, completed: 0, unresolved: 0 }) : null;
                colourNodeBar(overall.bar, Object.assign({}, n, { positive: positive }), sourceTotals, r);
                var details = node('details'); details.id = 'analysisTaxonomyProgressDetails'; details.open = expandedTaxonomies;
                var summary = node('summary', text('Fortschritt je Teiltaxonomie', 'Progress by taxonomy'));
                summary.id = 'analysisTaxonomyProgressToggle';
                details.append(summary);
                var parts = node('div', undefined, 'analysis-taxonomy-progress');
                if (!taxonomyOrder.length) taxonomyOrder = (window.TaxonomyState?.taxonomyData || []).map(function (root) { return root.code; });
                (n.taxonomies || []).forEach(function (part) { if (taxonomyOrder.indexOf(part.root) < 0) taxonomyOrder.push(part.root); });
                (n.taxonomies || []).slice().sort(function (a, b) { return taxonomyOrder.indexOf(a.root) - taxonomyOrder.indexOf(b.root); }).forEach(function (part) {
                    var name = german() ? part.nameDe || part.nameEn : part.nameEn || part.nameDe;
                    var assessment = assessmentState(part, snapshot);
                    var assessmentText = assessmentLabel(assessment, ['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED'].indexOf(snapshot.status) >= 0);
                    var meter = workMeter(parts, 'nodes', part.root, name && name !== part.root ? name + ' (' + part.root + ')' : nodeLabel(part.root),
                        part.assessed + part.excluded, part.total, nodeCoverage(part),
                        text('Keine Katalogknoten vorhanden', 'No catalogue nodes available'), null,
                        nodeCoverage(part, true) + ' · ' + scoreCoverage(part));
                    var source = (r?.sourceTaxonomies || []).find(function (entry) { return entry.root === part.root; });
                    if (!source && r && ((r.sourceTaxonomies || []).length || r.totalSources === 0 && r.step === 'FINISHED'))
                        source = { root: part.root, total: 0, completed: 0, unresolved: 0 };
                    colourNodeBar(meter.bar, part, source, r);
                    meter.row.setAttribute('data-assessment-state', assessment);
                    meter.header.children[0].append(node('span', assessmentText, 'analysis-taxonomy-state'));
                    meter.bar.setAttribute('aria-valuetext', assessmentText + ' · ' + meter.bar.getAttribute('aria-valuetext'));
                    meter.row.children[2].replaceChildren(taxonomyCounts(part));
                    if (r && source) meter.row.append(node('div', sourceCoverage(source), 'analysis-work-meter-detail analysis-source-progress'));
                    if (assessment === 'RUNNING' || sourceRoot(r, part.root)) meter.row.setAttribute('data-active', 'true');
                });
                details.append(parts);
                details.addEventListener('toggle', function () { expandedTaxonomies = details.open; });
                work.append(details);
                workLegend(work);
            }
            if (r) {
                var empty = r.step === 'FINISHED' ? text('Keine Suchaufträge erforderlich', 'No search tasks required') : null;
                workMeter(work, 'relations', '', text('Relationsprüfung – Suchaufträge', 'Relationship search – tasks'),
                    r.completedSearches, r.totalSearches, relationRemaining({ unresolved: r.unresolvedSearches, pending: r.pendingSearches }),
                    empty, text('Gesamter Suchumfang noch nicht bekannt', 'Total search work not yet known'));
                work.append(node('div', text('Fortschritt zählt Suchaufträge, nicht gefundene Beziehungen.',
                    'Progress counts search tasks, not relationships found.'), 'analysis-work-note'));
                work.append(node('div', r.assessedSources + text(' von ', ' of ') + r.totalSources
                    + text(' Quellenbeiträge bewertet · ', ' source contributions assessed · ')
                    + r.verifiedRelations + text(' geprüfte Relationsvorschläge', ' relationship proposals checked')));
                if (r.current) {
                    var current = node('div', undefined, 'analysis-current-work');
                    current.append(node('strong', text('Aktuell: ', 'Current: ')), node('span', currentRelation(r)));
                    work.append(current);
                }
                // Older runs without node coverage retain their truthful target-task
                // meters. Modern runs keep exactly one catalogue row per taxonomy.
                if (!n) {
                    var targets = node('div', undefined, 'analysis-taxonomy-progress');
                    (r.taxonomies || []).forEach(function (part) {
                        workMeter(targets, 'relations', part.root, text('Ziel: ', 'Target: ') + nodeLabel(part.root),
                            part.completed, part.total, relationRemaining(part), empty,
                            text('Gesamter Suchumfang noch nicht bekannt', 'Total search work not yet known'));
                    });
                    work.append(targets);
                }
                var b = r.budget;
                var hasBudget = b && [b.callsPerSlice, b.maxTotalCalls, b.newCalls, b.spentCalls]
                    .every(function (value) { return Number.isSafeInteger(value) && value >= 0; });
                work.append(node('div', hasBudget
                    ? text('Gesamtbudget der Relationssuche (letzter gespeicherter Stand): ',
                        'Total budget for relationship search (last saved state): ') + b.spentCalls + ' / ' + b.maxTotalCalls
                        + ' · ' + text('Neue Prüfungen dieses Durchlaufs: ', 'New evaluations in this execution: ') + b.newCalls
                        + ' · ' + text('Startbudget: ', 'Initial budget: ') + b.callsPerSlice
                    : text('Aufrufbudget dieses Durchlaufs: ', 'Call budget for this execution: ') + r.calls + ' / ' + r.maxCalls, 'small'));
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
        var diagnostics = node('details', undefined, 'analysis-progress-diagnostics');
        diagnostics.id = 'analysisProgressDiagnostics'; diagnostics.open = false;
        diagnostics.setAttribute('aria-live', 'off');
        diagnostics.append(node('summary', text('Technische Details', 'Technical details')), resources);
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
        panel.append(title, state, work, queueDuration, duration, diagnostics, warning, button, retryResult);
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
                title.textContent = phase ? text(phase[0], phase[1]) : text('Analyse läuft', 'Analysis running');
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
                var statuses = { RUNNING: ['Läuft', 'Running'], COMPLETED: ['Abgeschlossen', 'Completed'],
                    PARTIAL: ['Unvollständig', 'Incomplete'], ERROR: ['Fehlgeschlagen', 'Failed'],
                    CANCELLED: ['Abgebrochen', 'Cancelled'], CANCELLING: ['Abbruch angefordert', 'Cancellation requested'] };
                var status = statuses[snapshot.status];
                state.textContent = snapshot.status === 'QUEUED'
                    ? text('Auftrag angenommen; wartet auf freien Analyseplatz. Noch keine LLM-Anfrage gestartet.',
                        'Request accepted; waiting for an analysis slot. No LLM request started yet.')
                    : (status ? text(status[0], status[1]) : text('Status wird geprüft', 'Checking status')) + ' · '
                    + (snapshot.cluster || snapshot.nodeProgress || snapshot.relationProgress ? workSummary(snapshot)
                        : snapshot.evaluatedNodes + text(' Knoten bewertet', ' nodes evaluated'))
                    + (quiet >= 15 ? text(' · Letzte Aktivität vor ', ' · Last activity ') + quiet + ' s' : '');
                var memory = snapshot.memory || {};
                resources.textContent = snapshot.status + ' · Heap: ' + (memory.percent >= 0 ? memory.percent + '%' : text('unbekannt', 'unknown'))
                    + ' · DB: ' + snapshot.databaseStorage + ' · Index: ' + snapshot.indexStorage
                    + ' · ' + elapsed + ' s · ' + (snapshot.node || '')
                    + (snapshot.stopReason ? ' · ' + snapshot.stopReason : '')
                    + text(' · Server-Lebenszeichen empfangen', ' · server heartbeat received');
                var stopMessages = {
                    CALL_BUDGET: ['Aufrufbudget erreicht. Erledigte Arbeit bleibt erhalten; die Relationssuche ist noch unvollständig.',
                        'Call budget reached. Completed work is retained; relationship search is incomplete.'],
                    TOTAL_CALL_BUDGET: ['Gesamtbudget der Relationssuche erreicht. Gespeicherte Antworten bleiben erhalten; weitere Sucharbeit ist offen. Für neue Prüfungen muss das Gesamtbudget erhöht werden.',
                        'Total relationship search budget reached. Saved answers are retained; further search work remains open. Increase the total budget before new evaluations can proceed.'],
                    SOURCE_LIMIT: ['Quellengrenze dieses Abschnitts erreicht. Erledigte Arbeit bleibt erhalten; weitere Quellen sind noch offen.',
                        'Source limit for this section reached. Completed work is retained; further sources remain open.'],
                    WORK_LIMIT: ['Arbeitsgrenze dieses Abschnitts erreicht. Erledigte Arbeit bleibt erhalten; weitere Prüfungen sind noch offen.',
                        'Work limit for this section reached. Completed work is retained; further assessments remain open.'],
                    TIME_LIMIT: ['Zeitlimit erreicht. Erledigte Arbeit bleibt erhalten; offene Prüfungen sind noch nicht abgeschlossen.',
                        'Time limit reached. Completed work is retained; open assessments have not completed.'],
                    MEMORY_PRESSURE: ['Zum Schutz vor Speichermangel angehalten. Erledigte Arbeit bleibt erhalten.',
                        'Stopped to prevent memory exhaustion. Completed work is retained.'],
                    CANCELLED: ['Abgebrochen. Erledigte Arbeit bleibt erhalten.', 'Cancelled. Completed work is retained.']
                };
                var stopMessage = stopMessages[snapshot.stopReason];
                warning.textContent = snapshot.stopReason ? (stopMessage ? text(stopMessage[0], stopMessage[1]) : text(
                    'Analyse angehalten. Erledigte Arbeit bleibt erhalten; Details und offene Prüfungen stehen im Analysestatus.',
                    'Analysis stopped. Completed work is retained; details and open assessments are available in the analysis status.')) : (memory.warning
                    ? text('Speicherwarnung: ', 'Memory warning: ') + memory.percent + text(
                        '% belegt. Weitere Schritte werden bei anhaltendem Druck gestoppt.',
                        '% used. Further steps will stop if pressure persists.')
                    : snapshot.databaseStorage === 'IN_MEMORY' || snapshot.indexStorage === 'IN_MEMORY'
                        ? text('Ergebnisse liegen in flüchtigem Speicher und können bei einem Serverneustart verloren gehen.',
                            'Results use volatile storage and may be lost when the server restarts.') : '');
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
                if (name === 'taxonomy:analysis-invalidated') {
                    document.getElementById('analysisLiveProgress')?.remove();
                }
            });
        });
        document.addEventListener('taxonomy:analysis-draft-restored', function () {
            window.setTimeout(function () { refreshRecent(); }, 0);
        });
    }
}());
