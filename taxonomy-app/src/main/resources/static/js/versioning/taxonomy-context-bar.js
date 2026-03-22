/**
 * taxonomy-context-bar.js — Workspace Navigation Bar
 *
 * Renders the current architecture context (branch, commit, mode) with
 * localised labels and navigation controls.
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
     * Map mode to localised label and CSS class.
     */
    function getModeInfo(mode) {
        if (mode === 'READ_ONLY') {
            return { cls: 'bg-warning text-dark', label: '\uD83D\uDFE1 ' + t('context.mode.readonly.label'), icon: '\uD83D\uDFE1' };
        }
        if (mode === 'TEMPORARY') {
            return { cls: 'bg-secondary', label: '\u26AA ' + t('context.mode.temporary'), icon: '\u26AA' };
        }
        return { cls: 'bg-success', label: '\uD83D\uDFE2 ' + t('context.mode.editable'), icon: '\uD83D\uDFE2' };
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
            if (diff < 60) return t('context.saved.just.now');
            if (diff < 3600) return t('context.saved.minutes', Math.floor(diff / 60));
            if (diff < 86400) return t('context.saved.hours', Math.floor(diff / 3600));
            return t('context.saved.days', Math.floor(diff / 86400));
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
            html += '<a onclick="TaxonomyContextBar.returnToOrigin()" title="' + escapeHtml(t('context.origin.title')) + '">' + escapeHtml(TaxonomyI18n.formatBranch(ctx.originBranch)) + '</a>';
            html += '<span class="wb-sep">\u2192</span>';
            html += '<span>' + escapeHtml(branchDisplay) + '</span>';
            html += '</span>';
        }

        if (ctx.openedFromSearch) {
            html += '<span class="badge bg-info text-dark">' + escapeHtml(t('context.search', ctx.openedFromSearch)) + '</span>';
        }

        // Dirty indicator — pulsing
        if (ctx.dirty) {
            html += '<span class="badge bg-danger wb-dirty">\uD83D\uDD34 ' + escapeHtml(t('context.unsaved')) + '</span>';
        }

        // Sync status inline (AP 6)
        html += '<span class="wb-sync-info" id="contextBarSyncInfo"></span>';

        // Action buttons
        html += '<span class="wb-actions">';
        if (ctx.originContextId) {
            html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyContextBar.back()" title="' + escapeHtml(t('context.back.title')) + '">\u21A9 ' + escapeHtml(t('context.back')) + '</button>';
            html += '<button class="btn btn-sm btn-outline-primary" onclick="TaxonomyContextBar.returnToOrigin()" title="' + escapeHtml(t('context.origin.title')) + '">\uD83C\uDFE0 ' + escapeHtml(t('context.origin')) + '</button>';
        }
        if (ctx.mode === 'READ_ONLY') {
            html += '<button class="btn btn-sm btn-outline-warning" onclick="TaxonomyContextBar.showTransferDialog()" title="' + escapeHtml(t('context.copyback.title')) + '">\uD83D\uDCE4 ' + escapeHtml(t('context.copyback')) + '</button>';
        }
        html += '<button class="btn btn-sm btn-outline-success" onclick="TaxonomyContextBar.showVariantDialog()" title="' + escapeHtml(t('context.new.variant.title')) + '">\uD83C\uDF3F ' + escapeHtml(t('context.new.variant')) + '</button>';
        html += '<button class="btn btn-sm btn-outline-info" onclick="TaxonomyContextBar.showCompareDialog()" title="' + escapeHtml(t('context.compare.title')) + '">\uD83D\uDD0D ' + escapeHtml(t('context.compare')) + '</button>';
        html += '</span>';

        html += '</div>'; // wb-row
        html += '</div>'; // workspace-bar
        container.innerHTML = html;
        container.classList.remove('d-none');
        container.setAttribute('data-context-rendered', 'true');

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
                + 'onclick="TaxonomyWorkspaceSync.publish()" title="' + escapeHtml(t('context.sync.unpublished.title')) + '">'
                + '\uD83D\uDD04 ' + escapeHtml(t('context.sync.unpublished', state.unpublishedCommitCount)) + '</span>';
        } else if (status === 'BEHIND') {
            el.innerHTML = '<span class="badge bg-warning text-dark" style="font-size:0.72rem;cursor:pointer;" '
                + 'onclick="TaxonomyWorkspaceSync.syncFromShared()" title="' + escapeHtml(t('context.sync.behind.title')) + '">'
                + '\u2B07 ' + escapeHtml(t('context.sync.behind')) + '</span>';
        } else if (status === 'DIVERGED') {
            el.innerHTML = '<span class="badge bg-danger" style="font-size:0.72rem;">'
                + '\u26A0 ' + escapeHtml(t('context.sync.diverged')) + '</span>';
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
     * Return to the origin context
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
                statusEl.textContent = t('variants.variant.name.required');
                statusEl.className = 'small text-danger mt-2';
            }
            return;
        }
        fetch('/api/context/variant?name=' + encodeURIComponent(name), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (statusEl) {
                        statusEl.textContent = t('variants.variant.error', result.error);
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

    var escapeHtml = TaxonomyUtils.escapeHtml;

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
