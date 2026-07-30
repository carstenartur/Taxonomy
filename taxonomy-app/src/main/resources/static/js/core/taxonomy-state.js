/* taxonomy-state.js – shared state for the Taxonomy Browser modules */

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

    window.TaxonomyState = state;

})();
