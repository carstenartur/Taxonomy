/**
 * Shared utility functions for the Taxonomy UI.
 *
 * Include this script BEFORE all other taxonomy-*.js files so that
 * TaxonomyUtils.escapeHtml (and any future shared helpers) are
 * available globally.
 */
window.TaxonomyUtils = (function () {
    'use strict';

    /**
     * Escapes HTML special characters to prevent XSS.
     * Handles &, <, >, ", and ' (single quote).
     *
     * @param {*} s - value to escape (converted to string; falsy returns '')
     * @returns {string} the escaped string
     */
    function escapeHtml(s) {
        if (!s) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    return { escapeHtml: escapeHtml };
})();
