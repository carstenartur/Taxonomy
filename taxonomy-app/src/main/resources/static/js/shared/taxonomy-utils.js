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

    /**
     * Strips all HTML tags from a string, returning only the text content.
     * Uses the browser's DOMParser for safe, complete tag removal that
     * cannot be bypassed by nested/overlapping tag patterns.
     *
     * @param {*} s - value to strip (converted to string; falsy returns '')
     * @returns {string} the plain-text content
     */
    function stripHtml(s) {
        if (!s) return '';
        var doc = new DOMParser().parseFromString(String(s), 'text/html');
        return doc.body.textContent || '';
    }

    // ── Bootstrap lifecycle attributes ────────────────────────────────────
    // Capture-phase listeners set data-* attributes on every Bootstrap modal
    // and toast when its show/hide transition completes.  This provides a
    // reliable, timing-independent signal that Selenium tests can wait for
    // instead of using Thread.sleep().
    document.addEventListener('shown.bs.modal', function (e) {
        e.target.setAttribute('data-modal-visible', 'true');
    }, true);
    document.addEventListener('hidden.bs.modal', function (e) {
        e.target.setAttribute('data-modal-visible', 'false');
    }, true);
    document.addEventListener('shown.bs.toast', function (e) {
        e.target.setAttribute('data-toast-visible', 'true');
    }, true);
    document.addEventListener('hidden.bs.toast', function (e) {
        e.target.setAttribute('data-toast-visible', 'false');
    }, true);

    return { escapeHtml: escapeHtml, stripHtml: stripHtml };
})();
