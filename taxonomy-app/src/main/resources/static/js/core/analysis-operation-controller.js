(function (global) {
    'use strict';

    function normalizeCloseCallback(closeTransport) {
        return typeof closeTransport === 'function' ? closeTransport : null;
    }

    function create() {
        let generation = 0;
        let active = null;

        function closeTransport(record) {
            if (!record || record.transportClosed) return;
            record.transportClosed = true;
            const close = record.closeTransport;
            record.closeTransport = null;
            if (!close) return;
            try {
                close();
            } catch (error) {
                if (global.console && typeof global.console.warn === 'function') {
                    global.console.warn('[Taxonomy] Failed to close superseded analysis transport', error);
                }
            }
        }

        function isCurrent(record) {
            return active === record && record.superseded === false;
        }

        function begin(closeCurrentTransport) {
            if (active) {
                active.superseded = true;
                closeTransport(active);
            }

            const record = {
                generation: ++generation,
                operationId: null,
                highestSequence: 0,
                terminal: false,
                superseded: false,
                transportClosed: false,
                closeTransport: normalizeCloseCallback(closeCurrentTransport)
            };
            active = record;

            function snapshot() {
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

            return Object.freeze({
                accept(envelope, options) {
                    const terminal = Boolean(options && options.terminal);
                    if (!isCurrent(record) || record.terminal) return false;
                    if (!envelope || typeof envelope !== 'object') return false;

                    const operationId = typeof envelope.operationId === 'string'
                        ? envelope.operationId.trim()
                        : '';
                    const sequence = Number(envelope.sequence);
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

                failTransport() {
                    if (!isCurrent(record) || record.terminal) return false;
                    record.terminal = true;
                    closeTransport(record);
                    return true;
                },

                cancel() {
                    if (!isCurrent(record)) return false;
                    record.terminal = true;
                    closeTransport(record);
                    active = null;
                    return true;
                },

                isActive() {
                    return isCurrent(record) && !record.terminal;
                },

                snapshot
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

        function snapshot() {
            if (!active) return null;
            return Object.freeze({
                generation: active.generation,
                operationId: active.operationId,
                highestSequence: active.highestSequence,
                terminal: active.terminal,
                superseded: active.superseded,
                transportClosed: active.transportClosed,
                active: !active.terminal && !active.superseded
            });
        }

        return Object.freeze({ begin, cancelActive, snapshot });
    }

    global.TaxonomyAnalysisOperationController = Object.freeze({ create });
})(window);
