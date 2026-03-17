/**
 * taxonomy-workspace-sync.js — Workspace Synchronisation
 *
 * Provides sync-state polling, dirty-state indicator, and local-changes panel.
 * Uses i18n keys for all user-visible labels. Integrates with the Context Bar
 * to display sync status prominently.
 *
 * @module TaxonomyWorkspaceSync
 */
window.TaxonomyWorkspaceSync = (function () {
    'use strict';

    var t = TaxonomyI18n.t;
    var escapeHtml = TaxonomyUtils.escapeHtml;

    var syncState = null;
    var POLL_INTERVAL = 15000; // 15 seconds
    var pollTimer = null;

    // ── Initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        pollSyncState();
        pollTimer = setInterval(pollSyncState, POLL_INTERVAL);
    });

    // ── Polling ─────────────────────────────────────────────────────

    function pollSyncState() {
        fetch('/api/workspace/sync-state')
            .then(function (r) {
                if (!r.ok) return null;
                return r.json();
            })
            .then(function (state) {
                if (!state) return;
                syncState = state;
                renderSyncIndicator(state);
                renderDirtyIndicator();
                renderSyncStatePanel(state);
            })
            .catch(function () {
                // silently ignore — non-critical
            });
    }

    // ── Sync Indicator (appended to Git Status Bar) ─────────────────

    function renderSyncIndicator(state) {
        var bar = document.getElementById('gitStatusBar');
        if (!bar) return;

        var existing = document.getElementById('syncStatusIndicator');
        if (existing) existing.remove();

        var status = state.syncStatus || 'UP_TO_DATE';
        var dot = 'fresh';
        var label = t('workspace.sync.status.synced');
        if (status === 'BEHIND') {
            dot = 'stale';
            label = t('workspace.sync.status.behind');
        } else if (status === 'AHEAD') {
            dot = 'ahead';
            label = t('workspace.sync.status.ahead', state.unpublishedCommitCount);
        } else if (status === 'DIVERGED') {
            dot = 'error';
            label = t('workspace.sync.status.diverged');
        }

        var span = document.createElement('span');
        span.id = 'syncStatusIndicator';
        span.innerHTML = '<span class="git-sep">\u2502</span>' +
            '<span class="git-indicator">' +
            '<span class="dot ' + dot + '"></span> Sync: ' + escapeHtml(label) +
            '</span>';
        bar.appendChild(span);
    }

    // ── Dirty State Indicator (in navbar) ───────────────────────────

    function renderDirtyIndicator() {
        var badge = document.getElementById('workspaceUserBadge');
        if (!badge || badge.classList.contains('d-none')) return;

        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        if (ctx && ctx.dirty) {
            if (!badge.textContent.includes('\u25CF')) {
                badge.textContent = '\u25CF ' + badge.textContent;
                badge.classList.remove('bg-info');
                badge.classList.add('bg-warning');
                badge.title = t('workspace.dirty.title');
            }
        } else {
            badge.textContent = badge.textContent.replace('\u25CF ', '');
            badge.classList.remove('bg-warning');
            badge.classList.add('bg-info');
            badge.title = t('workspace.clean.title');
        }
    }

    // ── Sync State Panel (inside Versions → Sync tab) ─────────────

    function renderSyncStatePanel(state) {
        var panel = document.getElementById('syncStatePanel');
        if (!panel) return;

        var status = state.syncStatus || 'UP_TO_DATE';
        var badgeClass = 'bg-success';
        var statusLabel = t('workspace.sync.panel.uptodate');
        if (status === 'BEHIND') {
            badgeClass = 'bg-warning text-dark';
            statusLabel = t('workspace.sync.panel.behind');
        } else if (status === 'AHEAD') {
            badgeClass = 'bg-info text-dark';
            statusLabel = t('workspace.sync.panel.ahead', state.unpublishedCommitCount);
        } else if (status === 'DIVERGED') {
            badgeClass = 'bg-danger';
            statusLabel = t('workspace.sync.panel.diverged');
        }

        var html = '<div class="d-flex align-items-center gap-2 mb-2">';
        html += '<span class="badge ' + badgeClass + '">' + escapeHtml(statusLabel) + '</span>';
        if (status === 'DIVERGED') {
            html += '<button class="btn btn-sm btn-outline-danger ms-2" '
                + 'onclick="var el = document.getElementById(\'syncDivergedModal\'); if (el &amp;&amp; typeof bootstrap !== \'undefined\') { new bootstrap.Modal(el).show(); }" '
                + 'title="' + escapeHtml(t('workspace.sync.panel.resolve.title')) + '">' + escapeHtml(t('workspace.sync.panel.resolve')) + '</button>';
        }
        html += '</div>';

        html += '<table class="table table-sm table-borderless mb-0" style="max-width:400px;">';
        if (state.lastSyncTimestamp) {
            html += '<tr><td class="text-muted small">' + escapeHtml(t('workspace.sync.panel.last.synced')) + '</td><td class="small">' +
                escapeHtml(formatTimestamp(state.lastSyncTimestamp)) + '</td></tr>';
        }
        if (state.lastPublishTimestamp) {
            html += '<tr><td class="text-muted small">' + escapeHtml(t('workspace.sync.panel.last.published')) + '</td><td class="small">' +
                escapeHtml(formatTimestamp(state.lastPublishTimestamp)) + '</td></tr>';
        }
        if (state.lastSyncedCommitId) {
            var commitAbbrev = state.lastSyncedCommitId.length >= 7
                ? state.lastSyncedCommitId.substring(0, 7)
                : state.lastSyncedCommitId;
            html += '<tr><td class="text-muted small">' + escapeHtml(t('workspace.sync.panel.synced.commit')) + '</td><td class="small"><code>' +
                escapeHtml(commitAbbrev) + '</code></td></tr>';
        }
        if (state.unpublishedCommitCount > 0) {
            html += '<tr><td class="text-muted small">' + escapeHtml(t('workspace.sync.panel.unpublished')) + '</td><td class="small">' +
                escapeHtml(t('workspace.sync.panel.changes', state.unpublishedCommitCount)) + '</td></tr>';
        }
        html += '</table>';

        panel.innerHTML = html;
    }

    function formatTimestamp(ts) {
        if (!ts) return '\u2014';
        try {
            var d = new Date(ts);
            return d.toLocaleString();
        } catch (e) {
            return String(ts);
        }
    }

    // ── Local Changes Panel ─────────────────────────────────────────

    function showLocalChangesPanel() {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = (ctx && ctx.branch) ? ctx.branch : 'draft';

        fetch('/api/workspace/local-changes?branch=' + encodeURIComponent(branch))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var html = '<div class="card shadow-sm mb-3">';
                html += '<div class="card-header fw-semibold d-flex justify-content-between align-items-center">';
                html += '<span>\uD83D\uDCC4 ' + escapeHtml(t('workspace.local.changes')) + '</span>';
                html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyWorkspaceSync.refresh()">\uD83D\uDD04 ' + escapeHtml(t('workspace.local.refresh')) + '</button>';
                html += '</div>';
                html += '<div class="card-body">';

                if (data.changeCount === 0) {
                    html += '<p class="text-muted mb-0">' + escapeHtml(t('workspace.local.none')) + '</p>';
                } else {
                    var branchDisplay = TaxonomyI18n.formatBranch(branch);
                    html += '<p class="mb-2">' + t('workspace.local.count', data.changeCount, escapeHtml(branchDisplay)) + '</p>';
                    html += '<div class="d-flex gap-2">';
                    html += '<button class="btn btn-sm btn-primary" onclick="TaxonomyWorkspaceSync.publish()">\uD83D\uDCE4 ' + escapeHtml(t('workspace.local.publish')) + '</button>';
                    html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyWorkspaceSync.syncFromShared()">\uD83D\uDCE5 ' + escapeHtml(t('workspace.local.sync')) + '</button>';
                    html += '</div>';
                }

                html += '</div></div>';

                var container = document.getElementById('localChangesPanel');
                if (container) {
                    container.innerHTML = html;
                    container.classList.remove('d-none');
                }
            });
    }

    // ── Sync Actions ────────────────────────────────────────────────

    function syncFromShared() {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = (ctx && ctx.branch) ? ctx.branch : 'draft';

        fetch('/api/workspace/sync-from-shared?userBranch=' + encodeURIComponent(branch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('workspace.sync.failed'), result.message || result.error);
                    } else {
                        alert(t('workspace.sync.failed') + ': ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('workspace.sync.from.success'),
                            t('workspace.sync.from.success.detail'));
                    }
                    pollSyncState();
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    showLocalChangesPanel();
                }
            });
    }

    function publish() {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = (ctx && ctx.branch) ? ctx.branch : 'draft';

        fetch('/api/workspace/publish?userBranch=' + encodeURIComponent(branch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('workspace.sync.publish.failed'), result.message || result.error);
                    } else {
                        alert(t('workspace.sync.publish.failed') + ': ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('workspace.sync.publish.success'),
                            t('workspace.sync.publish.success.detail'));
                    }
                    pollSyncState();
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    showLocalChangesPanel();
                }
            });
    }

    // ── Helpers ──────────────────────────────────────────────────────

    function refresh() {
        pollSyncState();
    }

    function getSyncState() {
        return syncState;
    }

    return {
        refresh: refresh,
        getSyncState: getSyncState,
        showLocalChangesPanel: showLocalChangesPanel,
        syncFromShared: syncFromShared,
        publish: publish
    };
}());
