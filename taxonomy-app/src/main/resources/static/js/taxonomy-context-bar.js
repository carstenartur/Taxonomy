/**
 * taxonomy-context-bar.js — Context Navigation Bar
 *
 * Renders the current architecture context (branch, commit, mode) and
 * provides navigation controls: Back, Return to Origin, Create Variant, Compare.
 *
 * @module TaxonomyContextBar
 */
window.TaxonomyContextBar = (function () {
    'use strict';

    var POLL_INTERVAL = 10000; // 10 seconds
    var pollTimer = null;
    var currentContext = null;

    /**
     * Initialise the context bar and start polling.
     *
     * @param {string} containerId — DOM ID for the context bar container
     */
    function init(containerId) {
        fetchAndRender(containerId);
        if (pollTimer) clearInterval(pollTimer);
        pollTimer = setInterval(function () {
            fetchAndRender(containerId);
        }, POLL_INTERVAL);
    }

    /**
     * Fetch the current context from the API and render the bar.
     *
     * @param {string} containerId — DOM ID
     */
    function fetchAndRender(containerId) {
        fetch('/api/context/current')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (ctx) {
                if (!ctx) return;
                currentContext = ctx;
                render(containerId, ctx);
            })
            .catch(function () {
                // silently ignore — the global git-status bar already shows errors
            });
    }

    /**
     * Render the context bar into the given container.
     *
     * @param {string} containerId — DOM ID
     * @param {object} ctx — ContextRef object from API
     */
    function render(containerId, ctx) {
        var container = document.getElementById(containerId);
        if (!container) return;

        var modeClass = 'bg-success';
        var modeLabel = 'EDITABLE';
        if (ctx.mode === 'READ_ONLY') {
            modeClass = 'bg-warning text-dark';
            modeLabel = 'READ-ONLY';
        } else if (ctx.mode === 'TEMPORARY') {
            modeClass = 'bg-secondary';
            modeLabel = 'TEMPORARY';
        }

        var commitShort = ctx.commitId ? ctx.commitId.substring(0, 7) : 'HEAD';

        var html = '<div class="context-bar d-flex align-items-center gap-2 px-3 py-1 border-bottom bg-light">';
        html += '<span class="badge ' + modeClass + '">' + escapeHtml(modeLabel) + '</span>';
        html += '<strong>' + escapeHtml(ctx.branch || 'draft') + '</strong>';
        html += '<code class="text-muted small">' + escapeHtml(commitShort) + '</code>';

        if (ctx.originBranch) {
            html += '<span class="text-muted small">from ' + escapeHtml(ctx.originBranch) + '</span>';
        }
        if (ctx.openedFromSearch) {
            html += '<span class="badge bg-info text-dark">search: ' + escapeHtml(ctx.openedFromSearch) + '</span>';
        }
        if (ctx.dirty) {
            html += '<span class="badge bg-danger">unsaved changes</span>';
        }

        // Navigation buttons
        html += '<span class="ms-auto">';
        if (ctx.originContextId) {
            html += '<button class="btn btn-sm btn-outline-secondary me-1" onclick="TaxonomyContextBar.back()" title="Go back">&#8592; Back</button>';
            html += '<button class="btn btn-sm btn-outline-primary me-1" onclick="TaxonomyContextBar.returnToOrigin()" title="Return to origin">&#8634; Origin</button>';
        }
        html += '<button class="btn btn-sm btn-outline-success me-1" onclick="TaxonomyContextBar.showVariantDialog()" title="Create variant">&#43; Variant</button>';
        html += '<button class="btn btn-sm btn-outline-info" onclick="TaxonomyContextBar.showCompareDialog()" title="Compare contexts">&#8596; Compare</button>';
        html += '</span>';

        html += '</div>';
        container.innerHTML = html;
        container.classList.remove('d-none');
    }

    /**
     * Navigate back one step.
     */
    function back() {
        fetch('/api/context/back', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (ctx) {
                currentContext = ctx;
                fetchAndRender('contextBar');
            });
    }

    /**
     * Return to the origin context.
     */
    function returnToOrigin() {
        fetch('/api/context/return-to-origin', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (ctx) {
                currentContext = ctx;
                fetchAndRender('contextBar');
            });
    }

    /**
     * Show the variant creation dialog.
     */
    function showVariantDialog() {
        var name = prompt('Enter variant name:');
        if (!name) return;
        fetch('/api/context/variant?name=' + encodeURIComponent(name), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    alert('Error: ' + result.error);
                } else {
                    fetchAndRender('contextBar');
                }
            });
    }

    /**
     * Show the compare dialog placeholder.
     */
    function showCompareDialog() {
        if (window.TaxonomyContextCompare) {
            window.TaxonomyContextCompare.showDialog(currentContext);
        } else {
            alert('Compare feature not yet loaded.');
        }
    }

    /**
     * Get the current context.
     *
     * @returns {object|null} the current ContextRef
     */
    function getCurrentContext() {
        return currentContext;
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    return {
        init: init,
        fetchAndRender: fetchAndRender,
        back: back,
        returnToOrigin: returnToOrigin,
        showVariantDialog: showVariantDialog,
        showCompareDialog: showCompareDialog,
        getCurrentContext: getCurrentContext
    };
}());
