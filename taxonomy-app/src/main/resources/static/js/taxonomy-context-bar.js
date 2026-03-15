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
        html += '<span class="ms-auto d-flex align-items-center gap-1">';
        if (ctx.originContextId) {
            html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyContextBar.back()" title="Go back">&#8592; Back</button>';
            html += '<button class="btn btn-sm btn-outline-primary" onclick="TaxonomyContextBar.returnToOrigin()" title="Return to origin">&#8634; Origin</button>';
        }
        if (ctx.mode === 'READ_ONLY') {
            html += '<button class="btn btn-sm btn-outline-warning" onclick="TaxonomyContextBar.showTransferDialog()" title="Copy elements back to your editable context">&#128228; Copy Back</button>';
        }
        html += '<button class="btn btn-sm btn-outline-success" onclick="TaxonomyContextBar.showVariantDialog()" title="Create variant">&#43; Variant</button>';
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
     * Show the variant creation dialog using a proper Bootstrap modal.
     */
    function showVariantDialog() {
        var modal = document.getElementById('createVariantModal');
        if (!modal) return;
        var nameInput = document.getElementById('variantNameInput');
        if (nameInput) nameInput.value = '';
        var statusEl = document.getElementById('variantCreateStatus');
        if (statusEl) { statusEl.textContent = ''; statusEl.className = ''; }
        var bsModal = new bootstrap.Modal(modal);
        bsModal.show();
        if (nameInput) setTimeout(function () { nameInput.focus(); }, 300);
    }

    /**
     * Create a variant (called from the modal).
     */
    function createVariant() {
        var nameInput = document.getElementById('variantNameInput');
        var statusEl = document.getElementById('variantCreateStatus');
        var name = nameInput ? nameInput.value.trim() : '';
        if (!name) {
            if (statusEl) {
                statusEl.textContent = 'Please enter a variant name.';
                statusEl.className = 'small text-danger mt-2';
            }
            return;
        }
        fetch('/api/context/variant?name=' + encodeURIComponent(name), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (statusEl) {
                        statusEl.textContent = 'Error: ' + result.error;
                        statusEl.className = 'small text-danger mt-2';
                    }
                } else {
                    var modal = bootstrap.Modal.getInstance(document.getElementById('createVariantModal'));
                    if (modal) modal.hide();
                    fetchAndRender('contextBar');
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
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
     * Show the selective transfer dialog, pre-filled from current context.
     */
    function showTransferDialog() {
        if (window.TaxonomyContextTransfer) {
            var sourceCommit = currentContext ? currentContext.commitId : '';
            var targetCommit = '';
            // If we have an origin, use it as the target
            if (currentContext && currentContext.originCommitId) {
                targetCommit = currentContext.originCommitId;
            }
            window.TaxonomyContextTransfer.showDialog(sourceCommit, targetCommit);
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
        createVariant: createVariant,
        showCompareDialog: showCompareDialog,
        showTransferDialog: showTransferDialog,
        getCurrentContext: getCurrentContext
    };
}());
