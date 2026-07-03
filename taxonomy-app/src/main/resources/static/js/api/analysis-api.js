/* analysis-api.js – Analysis and graph API module for the Taxonomy UI.
 *
 * Wraps the LLM-driven analysis endpoints (/api/gap/analyze,
 * /api/patterns/detect, /api/recommend) and the graph traversal endpoints
 * (/api/graph/node/*, /api/graph/apqc-hierarchy).
 *
 * Feature modules should call these named functions instead of constructing
 * fetch('/api/gap/…') or fetch('/api/graph/…') directly.
 *
 * Depends on: TaxonomyApiClient (api/taxonomy-api-client.js)
 */
window.AnalysisApi = (function () {
    'use strict';

    var client = window.TaxonomyApiClient;

    // ── LLM analysis ──────────────────────────────────────────────────────────

    /**
     * Run a gap analysis.
     * POST /api/gap/analyze
     * @param {Object} payload
     * @returns {Promise<any>}
     */
    function analyzeGap(payload) {
        return client.sendJson('/api/gap/analyze', payload);
    }

    /**
     * Detect patterns.
     * POST /api/patterns/detect
     * @param {Object} payload
     * @returns {Promise<any>}
     */
    function detectPatterns(payload) {
        return client.sendJson('/api/patterns/detect', payload);
    }

    /**
     * Run recommendations.
     * POST /api/recommend
     * @param {Object} payload
     * @returns {Promise<any>}
     */
    function recommend(payload) {
        return client.sendJson('/api/recommend', payload);
    }

    /**
     * Fetch enriched failure-impact data for a single node.
     * GET /api/graph/node/{code}/enriched-failure-impact?maxHops={maxHops}
     * @param {string} code
     * @param {number} maxHops
     * @returns {Promise<any>}
     */
    function enrichedFailureImpact(code, maxHops) {
        return client.getJson(
            '/api/graph/node/' + encodeURIComponent(code) +
                '/enriched-failure-impact?maxHops=' + maxHops
        );
    }

    // ── Graph traversal ───────────────────────────────────────────────────────

    /**
     * Fetch upstream graph data for a node.
     * GET /api/graph/node/{nodeCode}/upstream?maxHops={maxHops}
     * @param {string} nodeCode
     * @param {number} maxHops
     * @returns {Promise<any>}
     */
    function graphUpstream(nodeCode, maxHops) {
        return client.getJson(
            '/api/graph/node/' + encodeURIComponent(nodeCode) +
                '/upstream?maxHops=' + maxHops
        );
    }

    /**
     * Fetch downstream graph data for a node.
     * GET /api/graph/node/{nodeCode}/downstream?maxHops={maxHops}
     * @param {string} nodeCode
     * @param {number} maxHops
     * @returns {Promise<any>}
     */
    function graphDownstream(nodeCode, maxHops) {
        return client.getJson(
            '/api/graph/node/' + encodeURIComponent(nodeCode) +
                '/downstream?maxHops=' + maxHops
        );
    }

    /**
     * Fetch failure-impact graph data for a node.
     * GET /api/graph/node/{nodeCode}/failure-impact?maxHops={maxHops}
     * @param {string} nodeCode
     * @param {number} maxHops
     * @returns {Promise<any>}
     */
    function graphFailureImpact(nodeCode, maxHops) {
        return client.getJson(
            '/api/graph/node/' + encodeURIComponent(nodeCode) +
                '/failure-impact?maxHops=' + maxHops
        );
    }

    /**
     * Fetch the APQC process hierarchy.
     * GET /api/graph/apqc-hierarchy
     * @returns {Promise<any>}
     */
    function apqcHierarchy() {
        return client.getJson('/api/graph/apqc-hierarchy');
    }

    return {
        analyzeGap:            analyzeGap,
        detectPatterns:        detectPatterns,
        recommend:             recommend,
        enrichedFailureImpact: enrichedFailureImpact,
        graphUpstream:         graphUpstream,
        graphDownstream:       graphDownstream,
        graphFailureImpact:    graphFailureImpact,
        apqcHierarchy:         apqcHierarchy
    };
})();
