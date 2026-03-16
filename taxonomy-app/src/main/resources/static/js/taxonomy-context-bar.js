/**
 * taxonomy-context-bar.js — Workspace Navigation Bar
 *
 * Renders the current architecture context (branch, commit, mode) with
 * user-friendly labels and navigation controls.
 * Provides breadcrumb navigation, relative timestamps, and sync status.
 *
 * @module TaxonomyContextBar
 */
window.TaxonomyContextBar = (function () {
    'use strict';

    var t = TaxonomyI18n.t;

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
     * Map mode to user-friendly label and CSS class.
     */
    function getModeInfo(mode) {
        if (mode === 'READ_ONLY') {
            return { cls: 'bg-warning text-dark', label: t('context.mode.readonly') };
        }
        if (mode === 'TEMPORARY') {
            return { cls: 'bg-secondary', label: t('context.mode.temporary') };
        }
        return { cls: 'bg-success', label: t('context.mode.editable') };
    }

    /**
     * Format a commit timestamp as a relative time string.
     */
    function formatRelativeTime(timestamp) {
        if (!timestamp) return '';
        try {
            var now = Date.now();
            var ts = new Date(timestamp).getTime();
            var diff = Math.floor((now - ts) / 1000);
            if (diff < 60) return t('context.time.just_now');
            if (diff < 3600) return t('context.time.minutes_ago', Math.floor(diff / 60));
            if (diff < 86400) return t('context.time.hours_ago', Math.floor(diff / 3600));
            return t('context.time.days_ago', Math.floor(diff / 86400));
        } catch (e) {
            return '';
        }
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

        var mode = getModeInfo(ctx.mode);
        var branchDisplay = TaxonomyI18n.formatBranch(ctx.branch);
        var timeInfo = formatRelativeTime(ctx.commitTimestamp || ctx.timestamp);
        var commitShort = ctx.commitId ? ctx.commitId.substring(0, 7) : '';

        var html = '<div class="workspace-bar">';

        // Row 1: Status + Branch name + Time info
        html += '<div class="wb-row">';
        html += '<span class="badge ' + mode.cls + '">' + escapeHtml(mode.label) + '</span>';
        html += '<strong>' + escapeHtml(branchDisplay) + '</strong>';

        if (timeInfo) {
            html += '<span class="text-muted small">' + escapeHtml(timeInfo) + '</span>';
        } else if (commitShort) {
            html += '<code class="text-muted small" title="' + escapeHtml(ctx.commitId || '') + '">' + escapeHtml(commitShort) + '</code>';
        }

        // Breadcrumb for origin
        if (ctx.originBranch) {
            html += '<span class="wb-breadcrumb">';
            html += '<a onclick="TaxonomyContextBar.returnToOrigin()" title="' + escapeHtml(t('context.btn.origin.title')) + '">' + escapeHtml(TaxonomyI18n.formatBranch(ctx.originBranch)) + '</a>';
            html += '<span class="wb-sep">\u2192</span>';
            html += '<span>' + escapeHtml(branchDisplay) + '</span>';
            html += '</span>';
        }

        if (ctx.openedFromSearch) {
            html += '<span class="badge bg-info text-dark">' + escapeHtml(t('context.search')) + escapeHtml(ctx.openedFromSearch) + '</span>';
        }

        // Dirty indicator — pulsing
        if (ctx.dirty) {
            html += '<span class="badge bg-danger wb-dirty">' + escapeHtml(t('context.dirty')) + '</span>';
        }

        // Sync status inline (AP 6)
        html += '<span class="wb-sync-info" id="contextBarSyncInfo"></span>';

        // Action buttons
        html += '<span class="wb-actions">';
        if (ctx.originContextId) {
            html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyContextBar.back()" title="' + escapeHtml(t('context.btn.back.title')) + '">' + escapeHtml(t('context.btn.back')) + '</button>';
            html += '<button class="btn btn-sm btn-outline-primary" onclick="TaxonomyContextBar.returnToOrigin()" title="' + escapeHtml(t('context.btn.origin.title')) + '">' + escapeHtml(t('context.btn.origin')) + '</button>';
        }
        if (ctx.mode === 'READ_ONLY') {
            html += '<button class="btn btn-sm btn-outline-warning" onclick="TaxonomyContextBar.showTransferDialog()" title="' + escapeHtml(t('context.btn.transfer.title')) + '">' + escapeHtml(t('context.btn.transfer')) + '</button>';
        }
        html += '<button class="btn btn-sm btn-outline-success" onclick="TaxonomyContextBar.showVariantDialog()" title="' + escapeHtml(t('context.btn.variant.title')) + '">' + escapeHtml(t('context.btn.variant')) + '</button>';
        html += '<button class="btn btn-sm btn-outline-info" onclick="TaxonomyContextBar.showCompareDialog()" title="' + escapeHtml(t('context.btn.compare.title')) + '">' + escapeHtml(t('context.btn.compare')) + '</button>';
        html += '</span>';

        html += '</div>'; // wb-row
        html += '</div>'; // workspace-bar
        container.innerHTML = html;
        container.classList.remove('d-none');

        // Update sync info in context bar (AP 6)
        updateSyncInfoInBar();
    }

    /**
     * Update sync status directly in the context bar (AP 6).
     */
    function updateSyncInfoInBar() {
        var el = document.getElementById('contextBarSyncInfo');
        if (!el) return;
        if (!window.TaxonomyWorkspaceSync) return;

        var state = window.TaxonomyWorkspaceSync.getSyncState();
        if (!state) return;

        var status = state.syncStatus || 'UP_TO_DATE';
        if (status === 'AHEAD' && state.unpublishedCommitCount > 0) {
            el.innerHTML = '<span class="badge bg-info text-dark" style="font-size:0.72rem;cursor:pointer;" '
                + 'onclick="TaxonomyWorkspaceSync.publish()" title="' + escapeHtml(t('sync.bar.changes_to_share.title')) + '">'
                + escapeHtml(t('sync.bar.changes_to_share', state.unpublishedCommitCount)) + '</span>';
        } else if (status === 'BEHIND') {
            el.innerHTML = '<span class="badge bg-warning text-dark" style="font-size:0.72rem;cursor:pointer;" '
                + 'onclick="TaxonomyWorkspaceSync.syncFromShared()" title="' + escapeHtml(t('sync.bar.updates_available.title')) + '">'
                + escapeHtml(t('sync.bar.updates_available')) + '</span>';
        } else if (status === 'DIVERGED') {
            el.innerHTML = '<span class="badge bg-danger" style="font-size:0.72rem;">'
                + escapeHtml(t('sync.bar.diverged')) + '</span>';
        } else {
            el.innerHTML = '';
        }
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
                statusEl.textContent = t('context.variant.error_empty');
                statusEl.className = 'small text-danger mt-2';
            }
            return;
        }
        fetch('/api/context/variant?name=' + encodeURIComponent(name), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (statusEl) {
                        statusEl.textContent = t('context.variant.error') + result.error;
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
     * Show the compare dialog.
     */
    function showCompareDialog() {
        if (window.TaxonomyContextCompare) {
            window.TaxonomyContextCompare.showDialog(currentContext);
        } else {
            alert(t('context.compare.loading'));
        }
    }

    /**
     * Show the selective transfer dialog, pre-filled from current context.
     */
    function showTransferDialog() {
        if (window.TaxonomyContextTransfer) {
            var sourceCommit = currentContext ? currentContext.commitId : '';
            var targetCommit = '';
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
