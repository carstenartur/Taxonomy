/* taxonomy-state.js – shared state for the Taxonomy Browser modules */

(function () {
    'use strict';

    window.TaxonomyState = {
        taxonomyData: [],
        currentScores: null,
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

})();
