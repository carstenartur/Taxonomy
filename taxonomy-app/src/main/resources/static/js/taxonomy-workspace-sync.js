/**
 * taxonomy-workspace-sync.js — Workspace Sync Status & Local Changes
 *
 * Provides sync-state polling, dirty-state indicator, and local-changes panel.
 * Integrates with the Git Status Bar and Context Bar to display workspace
 * synchronisation information.
 *
 * @module TaxonomyWorkspaceSync
 */
window.TaxonomyWorkspaceSync = (function () {
    'use strict';

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

        // Remove previous sync indicator
        var existing = document.getElementById('syncStatusIndicator');
        if (existing) existing.remove();

        var status = state.syncStatus || 'UP_TO_DATE';
        var dot = 'fresh';
        var label = 'synced';
        if (status === 'BEHIND') {
            dot = 'stale';
            label = 'behind shared';
        } else if (status === 'AHEAD') {
            dot = 'ahead';
            label = state.unpublishedCommitCount + ' unpublished';
        } else if (status === 'DIVERGED') {
            dot = 'error';
            label = 'diverged';
        }

        var span = document.createElement('span');
        span.id = 'syncStatusIndicator';
        span.innerHTML = '<span class="git-sep">│</span>' +
            '<span class="git-indicator">' +
            '<span class="dot ' + dot + '"></span> Sync: ' + escapeHtml(label) +
            '</span>';
        bar.appendChild(span);
    }

    // ── Dirty State Indicator (in navbar) ───────────────────────────

    function renderDirtyIndicator() {
        var badge = document.getElementById('workspaceUserBadge');
        if (!badge || badge.classList.contains('d-none')) return;

        // Check dirty state from context bar
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        if (ctx && ctx.dirty) {
            if (!badge.textContent.includes('●')) {
                badge.textContent = '● ' + badge.textContent;
                badge.classList.remove('bg-info');
                badge.classList.add('bg-warning');
                badge.title = 'Workspace has unsaved changes';
            }
        } else {
            badge.textContent = badge.textContent.replace('● ', '');
            badge.classList.remove('bg-warning');
            badge.classList.add('bg-info');
            badge.title = 'Current workspace / user';
        }
    }

    // ── Sync State Panel (inside Versions → Sync tab) ─────────────

    function renderSyncStatePanel(state) {
        var panel = document.getElementById('syncStatePanel');
        if (!panel) return;

        var status = state.syncStatus || 'UP_TO_DATE';
        var badgeClass = 'bg-success';
        var statusLabel = 'Up to date';
        if (status === 'BEHIND') {
            badgeClass = 'bg-warning text-dark';
            statusLabel = 'Behind shared repository';
        } else if (status === 'AHEAD') {
            badgeClass = 'bg-info text-dark';
            statusLabel = state.unpublishedCommitCount + ' unpublished commit' +
                (state.unpublishedCommitCount !== 1 ? 's' : '');
        } else if (status === 'DIVERGED') {
            badgeClass = 'bg-danger';
            statusLabel = 'Diverged — both sides have changes';
        }

        var html = '<div class="d-flex align-items-center gap-2 mb-2">';
        html += '<span class="badge ' + badgeClass + '">' + escapeHtml(statusLabel) + '</span>';
        if (status === 'DIVERGED') {
            html += '<button class="btn btn-sm btn-outline-danger ms-2" '
                + 'onclick="var el = document.getElementById(\'syncDivergedModal\'); if (el &amp;&amp; typeof bootstrap !== \'undefined\') { new bootstrap.Modal(el).show(); }" '
                + 'title="Open diverged resolution dialog">Resolve…</button>';
        }
        html += '</div>';

        html += '<table class="table table-sm table-borderless mb-0" style="max-width:400px;">';
        if (state.lastSyncTimestamp) {
            html += '<tr><td class="text-muted small">Last synced</td><td class="small">' +
                escapeHtml(formatTimestamp(state.lastSyncTimestamp)) + '</td></tr>';
        }
        if (state.lastPublishTimestamp) {
            html += '<tr><td class="text-muted small">Last published</td><td class="small">' +
                escapeHtml(formatTimestamp(state.lastPublishTimestamp)) + '</td></tr>';
        }
        if (state.lastSyncedCommitId) {
            var commitAbbrev = state.lastSyncedCommitId.length >= 7
                ? state.lastSyncedCommitId.substring(0, 7)
                : state.lastSyncedCommitId;
            html += '<tr><td class="text-muted small">Synced commit</td><td class="small"><code>' +
                escapeHtml(commitAbbrev) + '</code></td></tr>';
        }
        if (state.unpublishedCommitCount > 0) {
            html += '<tr><td class="text-muted small">Unpublished</td><td class="small">' +
                state.unpublishedCommitCount + ' commit' +
                (state.unpublishedCommitCount !== 1 ? 's' : '') + '</td></tr>';
        }
        html += '</table>';

        panel.innerHTML = html;
    }

    function formatTimestamp(ts) {
        if (!ts) return '—';
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
                html += '<span>&#128196; Local Changes</span>';
                html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyWorkspaceSync.refresh()">&#128260; Refresh</button>';
                html += '</div>';
                html += '<div class="card-body">';

                if (data.changeCount === 0) {
                    html += '<p class="text-muted mb-0">No unpublished changes. Your workspace is in sync with the shared repository.</p>';
                } else {
                    html += '<p class="mb-2">' + data.changeCount + ' unpublished commit' + (data.changeCount !== 1 ? 's' : '') + ' on branch <strong>' + escapeHtml(branch) + '</strong>.</p>';
                    html += '<div class="d-flex gap-2">';
                    html += '<button class="btn btn-sm btn-primary" onclick="TaxonomyWorkspaceSync.publish()">&#128228; Publish to Shared</button>';
                    html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyWorkspaceSync.syncFromShared()">&#128229; Sync from Shared</button>';
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
                        window.TaxonomyOperationResult.showError('Sync Failed', result.message || result.error);
                    } else {
                        alert('Sync failed: ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Sync Complete',
                            'Synced from shared repository: ' + (result.mergeCommit || '').substring(0, 7));
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
                        window.TaxonomyOperationResult.showError('Publish Failed', result.message || result.error);
                    } else {
                        alert('Publish failed: ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Publish Complete',
                            'Published to shared repository: ' + (result.mergeCommit || '').substring(0, 7));
                    }
                    pollSyncState();
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    showLocalChangesPanel();
                }
            });
    }

    // ── Helpers ──────────────────────────────────────────────────────

    function escapeHtml(s) {
        if (!s) return '';
        return s.replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

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
