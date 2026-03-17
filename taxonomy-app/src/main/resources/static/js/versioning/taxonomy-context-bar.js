/**
 * taxonomy-context-bar.js — Workspace Navigation Bar
 *
 * Renders the current architecture context (branch, commit, mode) with
 * user-friendly German labels and navigation controls.
 * Provides breadcrumb navigation, relative timestamps, and sync status.
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
     * Map mode to user-friendly German label and CSS class.
     */
    function getModeInfo(mode) {
        if (mode === 'READ_ONLY') {
            return { cls: 'bg-warning text-dark', label: '\uD83D\uDFE1 Nur ansehen', icon: '\uD83D\uDFE1' };
        }
        if (mode === 'TEMPORARY') {
            return { cls: 'bg-secondary', label: '\u26AA Entwurf', icon: '\u26AA' };
        }
        return { cls: 'bg-success', label: '\uD83D\uDFE2 Arbeitsversion', icon: '\uD83D\uDFE2' };
    }

    /**
     * Format branch name for display.
     * 'draft' → 'Hauptversion', otherwise use the branch name.
     */
    function formatBranchName(branch) {
        if (!branch || branch === 'draft') return 'Hauptversion';
        return branch;
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
            if (diff < 60) return 'Gerade eben gespeichert';
            if (diff < 3600) return 'Gespeichert vor ' + Math.floor(diff / 60) + ' Min.';
            if (diff < 86400) return 'Gespeichert vor ' + Math.floor(diff / 3600) + ' Std.';
            return 'Gespeichert vor ' + Math.floor(diff / 86400) + ' Tag(en)';
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
        var branchDisplay = formatBranchName(ctx.branch);
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
            html += '<a onclick="TaxonomyContextBar.returnToOrigin()" title="Zur\u00FCck zum Ursprung">' + escapeHtml(formatBranchName(ctx.originBranch)) + '</a>';
            html += '<span class="wb-sep">\u2192</span>';
            html += '<span>' + escapeHtml(branchDisplay) + '</span>';
            html += '</span>';
        }

        if (ctx.openedFromSearch) {
            html += '<span class="badge bg-info text-dark">Suche: ' + escapeHtml(ctx.openedFromSearch) + '</span>';
        }

        // Dirty indicator — pulsing
        if (ctx.dirty) {
            html += '<span class="badge bg-danger wb-dirty">\uD83D\uDD34 Ungespeicherte \u00C4nderungen</span>';
        }

        // Sync status inline (AP 6)
        html += '<span class="wb-sync-info" id="contextBarSyncInfo"></span>';

        // Action buttons
        html += '<span class="wb-actions">';
        if (ctx.originContextId) {
            html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyContextBar.back()" title="Zur\u00FCck zum vorherigen Kontext">\u21A9 Zur\u00FCck</button>';
            html += '<button class="btn btn-sm btn-outline-primary" onclick="TaxonomyContextBar.returnToOrigin()" title="Zur\u00FCck zum Ursprung">\uD83C\uDFE0 Zum Ursprung</button>';
        }
        if (ctx.mode === 'READ_ONLY') {
            html += '<button class="btn btn-sm btn-outline-warning" onclick="TaxonomyContextBar.showTransferDialog()" title="Elemente zur\u00FCck in die Arbeitsversion kopieren">\uD83D\uDCE4 Zur\u00FCckkopieren</button>';
        }
        html += '<button class="btn btn-sm btn-outline-success" onclick="TaxonomyContextBar.showVariantDialog()" title="Neue Variante erstellen">\uD83C\uDF3F Neue Variante</button>';
        html += '<button class="btn btn-sm btn-outline-info" onclick="TaxonomyContextBar.showCompareDialog()" title="Versionen vergleichen">\uD83D\uDD0D Vergleichen</button>';
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
                + 'onclick="TaxonomyWorkspaceSync.publish()" title="Jetzt ver\u00F6ffentlichen">'
                + '\uD83D\uDD04 ' + state.unpublishedCommitCount + ' \u00C4nderung(en) zum Teilen</span>';
        } else if (status === 'BEHIND') {
            el.innerHTML = '<span class="badge bg-warning text-dark" style="font-size:0.72rem;cursor:pointer;" '
                + 'onclick="TaxonomyWorkspaceSync.syncFromShared()" title="Jetzt aktualisieren">'
                + '\u2B07 Aktualisierungen verf\u00FCgbar</span>';
        } else if (status === 'DIVERGED') {
            el.innerHTML = '<span class="badge bg-danger" style="font-size:0.72rem;">'
                + '\u26A0 Versionen weichen ab</span>';
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
                statusEl.textContent = 'Bitte geben Sie einen Variantennamen ein.';
                statusEl.className = 'small text-danger mt-2';
            }
            return;
        }
        fetch('/api/context/variant?name=' + encodeURIComponent(name), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (statusEl) {
                        statusEl.textContent = 'Fehler: ' + result.error;
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
            alert('Vergleichsfunktion wird geladen\u2026');
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
