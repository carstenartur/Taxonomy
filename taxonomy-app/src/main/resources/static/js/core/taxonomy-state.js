/* taxonomy-state.js – shared state and operation arbitration for the Taxonomy Browser modules */

(function () {
    'use strict';

    var currentScores = null;
    var renderConsistencyCheck = 0;

    var state = {
        taxonomyData: [],
        currentReasons: {},   // code → reason string
        currentDiscrepancies: [], // TaxonomyDiscrepancy list from analysis
        currentArchView: null, // latest architecture view from analysis
        currentView: 'list', // 'list' | 'tabs' | 'sunburst' | 'tree' | 'decision' | 'summary'
        currentTreeRoot: 'BP', // code of the taxonomy shown in tree view

        // ── Interactive mode state ─────────────────────────────────────────────
        interactiveMode: true,       // ON by default
        storedBusinessText: null,    // stored when user clicks Analyze in interactive mode
        evaluatedNodes: new Set(),   // track which parent nodes have been evaluated
        lastAnalyzedText: null,      // text that was most recently analyzed successfully

        // ── Proposal state ────────────────────────────────────────────────────
        currentProposalFilter: 'PENDING',
        pendingProposalNodeCode: null // node code for propose modal
    };

    /**
     * A streaming analysis updates the current score object incrementally and
     * finally replaces it with the server's authoritative total score map. If an
     * incremental browser event or DOM update was missed, the state could then be
     * complete while the list/tabs view still contained no score badges.
     *
     * Defer one frame so normal callers can render synchronously. Only when the
     * rendered nodes still disagree with the replacement map do we rebuild the
     * current list/tabs view from the authoritative state.
     */
    function scheduleScoreRenderConsistencyCheck(scores) {
        var check = ++renderConsistencyCheck;
        if (!scores || typeof scores !== 'object') return;

        var positiveCodes = Object.keys(scores).filter(function (code) {
            return Number(scores[code]) > 0;
        });
        if (positiveCodes.length === 0) return;

        var schedule = typeof window.requestAnimationFrame === 'function'
            ? function (callback) { window.requestAnimationFrame(callback); }
            : function (callback) { window.setTimeout(callback, 0); };

        schedule(function () {
            if (check !== renderConsistencyCheck || currentScores !== scores) return;
            if (state.currentView !== 'list' && state.currentView !== 'tabs') return;
            if (!Array.isArray(state.taxonomyData) || state.taxonomyData.length === 0) return;
            if (!window.TaxonomyBrowse || typeof window.TaxonomyBrowse.renderView !== 'function') return;

            var nodesByCode = new Map();
            document.querySelectorAll('#taxonomyTree .tax-node[data-code]').forEach(function (node) {
                if (node.dataset.code) nodesByCode.set(node.dataset.code, node);
            });

            var renderableCodes = positiveCodes.filter(function (code) {
                return nodesByCode.has(code);
            });
            var missingBadge = renderableCodes.some(function (code) {
                return !nodesByCode.get(code)
                    .querySelector(':scope > .tax-node-header > .tax-pct');
            });

            if (renderableCodes.length > 0 && missingBadge) {
                window.TaxonomyBrowse.renderView(state.taxonomyData, scores);
            }
        });
    }

    Object.defineProperty(state, 'currentScores', {
        enumerable: true,
        get: function () { return currentScores; },
        set: function (scores) {
            currentScores = scores;
            scheduleScoreRenderConsistencyCheck(scores);
        }
    });

    function createAnalysisOperationController() {
        var generation = 0;
        var active = null;

        function closeTransport(record) {
            if (!record || record.transportClosed) return;
            record.transportClosed = true;
            var close = record.closeTransport;
            record.closeTransport = null;
            if (!close) return;
            try {
                close();
            } catch (error) {
                if (window.console && typeof window.console.warn === 'function') {
                    window.console.warn(
                        '[Taxonomy] Failed to close superseded analysis transport', error);
                }
            }
        }

        function isCurrent(record) {
            return active === record && record.superseded === false;
        }

        function snapshotOf(record) {
            return Object.freeze({
                generation: record.generation,
                operationId: record.operationId,
                highestSequence: record.highestSequence,
                terminal: record.terminal,
                superseded: record.superseded,
                transportClosed: record.transportClosed,
                active: isCurrent(record) && !record.terminal
            });
        }

        function begin(closeCurrentTransport) {
            if (active) {
                active.superseded = true;
                closeTransport(active);
            }

            var record = {
                generation: ++generation,
                operationId: null,
                highestSequence: 0,
                terminal: false,
                superseded: false,
                transportClosed: false,
                closeTransport: typeof closeCurrentTransport === 'function'
                    ? closeCurrentTransport : null
            };
            active = record;

            return Object.freeze({
                accept: function (envelope, options) {
                    var terminal = Boolean(options && options.terminal);
                    if (!isCurrent(record) || record.terminal) return false;
                    if (!envelope || typeof envelope !== 'object') return false;

                    var operationId = typeof envelope.operationId === 'string'
                        ? envelope.operationId.trim() : '';
                    var sequence = Number(envelope.sequence);
                    if (!operationId || !Number.isSafeInteger(sequence) || sequence <= 0) {
                        return false;
                    }
                    if (record.operationId === null) {
                        record.operationId = operationId;
                    } else if (record.operationId !== operationId) {
                        return false;
                    }
                    if (sequence <= record.highestSequence) return false;

                    record.highestSequence = sequence;
                    if (terminal) {
                        record.terminal = true;
                        closeTransport(record);
                    }
                    return true;
                },

                failTransport: function () {
                    if (!isCurrent(record) || record.terminal) return false;
                    record.terminal = true;
                    closeTransport(record);
                    return true;
                },

                cancel: function () {
                    if (!isCurrent(record)) return false;
                    record.terminal = true;
                    closeTransport(record);
                    active = null;
                    return true;
                },

                isActive: function () {
                    return isCurrent(record) && !record.terminal;
                },

                snapshot: function () {
                    return snapshotOf(record);
                }
            });
        }

        function cancelActive() {
            if (!active) return false;
            active.superseded = true;
            active.terminal = true;
            closeTransport(active);
            active = null;
            return true;
        }

        return Object.freeze({
            begin: begin,
            cancelActive: cancelActive,
            snapshot: function () {
                return active ? snapshotOf(active) : null;
            }
        });
    }

    function parseOperationEnvelope(event) {
        if (!event || typeof event.data !== 'string' || !event.data) return null;
        try {
            return JSON.parse(event.data);
        } catch (error) {
            if (window.console && typeof window.console.warn === 'function') {
                window.console.warn('[Taxonomy] Ignoring malformed analysis event', error);
            }
            return null;
        }
    }

    function invokeEventListener(listener, receiver, event) {
        if (typeof listener === 'function') {
            listener.call(receiver, event);
        } else if (listener && typeof listener.handleEvent === 'function') {
            listener.handleEvent(event);
        }
    }

    function guardAnalysisEventSource(nativeSource, session) {
        var guardedTypes = new Set(['phase', 'scores', 'expanding', 'complete', 'error']);
        var terminalTypes = new Set(['complete', 'error']);
        var listenerMaps = new Map();
        var transportErrorListener = null;
        var proxy;

        function wrappersFor(type) {
            if (!listenerMaps.has(type)) listenerMaps.set(type, new Map());
            return listenerMaps.get(type);
        }

        proxy = new Proxy(nativeSource, {
            get: function (target, property) {
                if (property === 'addEventListener') {
                    return function (type, listener, options) {
                        if (!guardedTypes.has(type)) {
                            target.addEventListener(type, listener, options);
                            return;
                        }
                        var wrapped = function (event) {
                            var envelope = parseOperationEnvelope(event);
                            if (!session.accept(envelope, {
                                terminal: terminalTypes.has(type)
                            })) return;
                            invokeEventListener(listener, proxy, event);
                        };
                        wrappersFor(type).set(listener, wrapped);
                        target.addEventListener(type, wrapped, options);
                    };
                }
                if (property === 'removeEventListener') {
                    return function (type, listener, options) {
                        var wrapped = listenerMaps.has(type)
                            ? listenerMaps.get(type).get(listener) : null;
                        target.removeEventListener(type, wrapped || listener, options);
                        if (wrapped) listenerMaps.get(type).delete(listener);
                    };
                }
                if (property === 'close') {
                    return function () {
                        session.cancel();
                        target.close();
                    };
                }
                if (property === 'onerror') return transportErrorListener;

                var value = Reflect.get(target, property, target);
                return typeof value === 'function' ? value.bind(target) : value;
            },

            set: function (target, property, value) {
                if (property === 'onerror') {
                    transportErrorListener = value;
                    target.onerror = function (event) {
                        // A typed server event named "error" is accepted by the
                        // registered MessageEvent listener first and makes the
                        // session terminal. Its subsequent onerror callback is
                        // therefore ignored here. Only a live transport failure
                        // can pass failTransport().
                        if (!session.failTransport()) return;
                        invokeEventListener(value, proxy, event);
                    };
                    return true;
                }
                return Reflect.set(target, property, value, target);
            }
        });

        return proxy;
    }

    function installAnalysisStreamGuard() {
        var scoring = window.TaxonomyScoring;
        var NativeEventSource = window.EventSource;
        if (!scoring || typeof scoring.runStreamingAnalysis !== 'function'
                || typeof NativeEventSource !== 'function'
                || scoring.__analysisOperationGuardInstalled === true) {
            return;
        }

        var controller = createAnalysisOperationController();
        var original = scoring.runStreamingAnalysis;

        function GuardedEventSource(url, configuration) {
            var nativeSource = new NativeEventSource(url, configuration);
            var session = controller.begin(function () { nativeSource.close(); });
            return guardAnalysisEventSource(nativeSource, session);
        }
        GuardedEventSource.prototype = NativeEventSource.prototype;
        ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function (constant) {
            if (constant in NativeEventSource) {
                GuardedEventSource[constant] = NativeEventSource[constant];
            }
        });

        scoring.runStreamingAnalysis = function () {
            window.EventSource = GuardedEventSource;
            try {
                return original.apply(this, arguments);
            } catch (error) {
                controller.cancelActive();
                throw error;
            } finally {
                window.EventSource = NativeEventSource;
            }
        };
        scoring.cancelStreamingAnalysis = controller.cancelActive;
        scoring.getStreamingOperationSnapshot = controller.snapshot;
        Object.defineProperty(scoring, '__analysisOperationGuardInstalled', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
    }

    window.TaxonomyState = state;
    window.TaxonomyAnalysisOperationController = Object.freeze({
        create: createAnalysisOperationController
    });

    if (typeof document.addEventListener === 'function') {
        document.addEventListener('DOMContentLoaded', installAnalysisStreamGuard, { once: true });
    }

})();
