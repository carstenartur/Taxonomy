/**
 * taxonomy-workspace-sync.js — Arbeitsbereich-Synchronisierung
 *
 * Provides sync-state polling, dirty-state indicator, and local-changes panel.
 * Uses user-friendly German labels. Integrates with the Context Bar to display
 * sync status prominently (AP 6).
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

        var existing = document.getElementById('syncStatusIndicator');
        if (existing) existing.remove();

        var status = state.syncStatus || 'UP_TO_DATE';
        var dot = 'fresh';
        var label = 'synchron';
        if (status === 'BEHIND') {
            dot = 'stale';
            label = 'Aktualisierungen verf\u00FCgbar';
        } else if (status === 'AHEAD') {
            dot = 'ahead';
            label = state.unpublishedCommitCount + ' unver\u00F6ffentlicht';
        } else if (status === 'DIVERGED') {
            dot = 'error';
            label = 'abweichend';
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
                badge.title = 'Arbeitsbereich hat ungespeicherte \u00C4nderungen';
            }
        } else {
            badge.textContent = badge.textContent.replace('\u25CF ', '');
            badge.classList.remove('bg-warning');
            badge.classList.add('bg-info');
            badge.title = 'Aktueller Arbeitsbereich / Benutzer';
        }
    }

    // ── Sync State Panel (inside Versions → Sync tab) ─────────────

    function renderSyncStatePanel(state) {
        var panel = document.getElementById('syncStatePanel');
        if (!panel) return;

        var status = state.syncStatus || 'UP_TO_DATE';
        var badgeClass = 'bg-success';
        var statusLabel = 'Auf dem neuesten Stand';
        if (status === 'BEHIND') {
            badgeClass = 'bg-warning text-dark';
            statusLabel = 'Aktualisierungen vom Team verf\u00FCgbar';
        } else if (status === 'AHEAD') {
            badgeClass = 'bg-info text-dark';
            statusLabel = state.unpublishedCommitCount + ' unver\u00F6ffentlichte \u00C4nderung' +
                (state.unpublishedCommitCount !== 1 ? 'en' : '');
        } else if (status === 'DIVERGED') {
            badgeClass = 'bg-danger';
            statusLabel = 'Abweichend \u2014 beide Seiten haben \u00C4nderungen';
        }

        var html = '<div class="d-flex align-items-center gap-2 mb-2">';
        html += '<span class="badge ' + badgeClass + '">' + escapeHtml(statusLabel) + '</span>';
        if (status === 'DIVERGED') {
            html += '<button class="btn btn-sm btn-outline-danger ms-2" '
                + 'onclick="var el = document.getElementById(\'syncDivergedModal\'); if (el &amp;&amp; typeof bootstrap !== \'undefined\') { new bootstrap.Modal(el).show(); }" '
                + 'title="Dialog zur Aufl\u00F6sung \u00F6ffnen">Aufl\u00F6sen\u2026</button>';
        }
        html += '</div>';

        html += '<table class="table table-sm table-borderless mb-0" style="max-width:400px;">';
        if (state.lastSyncTimestamp) {
            html += '<tr><td class="text-muted small">Zuletzt synchronisiert</td><td class="small">' +
                escapeHtml(formatTimestamp(state.lastSyncTimestamp)) + '</td></tr>';
        }
        if (state.lastPublishTimestamp) {
            html += '<tr><td class="text-muted small">Zuletzt ver\u00F6ffentlicht</td><td class="small">' +
                escapeHtml(formatTimestamp(state.lastPublishTimestamp)) + '</td></tr>';
        }
        if (state.lastSyncedCommitId) {
            var commitAbbrev = state.lastSyncedCommitId.length >= 7
                ? state.lastSyncedCommitId.substring(0, 7)
                : state.lastSyncedCommitId;
            html += '<tr><td class="text-muted small">Synchronisierter Commit</td><td class="small"><code>' +
                escapeHtml(commitAbbrev) + '</code></td></tr>';
        }
        if (state.unpublishedCommitCount > 0) {
            html += '<tr><td class="text-muted small">Unver\u00F6ffentlicht</td><td class="small">' +
                state.unpublishedCommitCount + ' \u00C4nderung' +
                (state.unpublishedCommitCount !== 1 ? 'en' : '') + '</td></tr>';
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
                html += '<span>\uD83D\uDCC4 Lokale \u00C4nderungen</span>';
                html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyWorkspaceSync.refresh()">\uD83D\uDD04 Aktualisieren</button>';
                html += '</div>';
                html += '<div class="card-body">';

                if (data.changeCount === 0) {
                    html += '<p class="text-muted mb-0">Keine unver\u00F6ffentlichten \u00C4nderungen. Ihr Arbeitsbereich ist mit dem Team-Repository synchron.</p>';
                } else {
                    var branchDisplay = branch === 'draft' ? 'Hauptversion' : branch;
                    html += '<p class="mb-2">' + data.changeCount + ' unver\u00F6ffentlichte \u00C4nderung' + (data.changeCount !== 1 ? 'en' : '') + ' auf <strong>' + escapeHtml(branchDisplay) + '</strong>.</p>';
                    html += '<div class="d-flex gap-2">';
                    html += '<button class="btn btn-sm btn-primary" onclick="TaxonomyWorkspaceSync.publish()">\uD83D\uDCE4 F\u00FCr Team ver\u00F6ffentlichen</button>';
                    html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyWorkspaceSync.syncFromShared()">\uD83D\uDCE5 Vom Team aktualisieren</button>';
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
                        window.TaxonomyOperationResult.showError('Synchronisierung fehlgeschlagen', result.message || result.error);
                    } else {
                        alert('Synchronisierung fehlgeschlagen: ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Synchronisierung abgeschlossen',
                            'Vom Team-Repository aktualisiert.');
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
                        window.TaxonomyOperationResult.showError('Ver\u00F6ffentlichung fehlgeschlagen', result.message || result.error);
                    } else {
                        alert('Ver\u00F6ffentlichung fehlgeschlagen: ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Ver\u00F6ffentlichung abgeschlossen',
                            '\u00C4nderungen wurden mit dem Team-Repository geteilt.');
                    }
                    pollSyncState();
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    showLocalChangesPanel();
                }
            });
    }

    // ── Helpers ──────────────────────────────────────────────────────

    var escapeHtml = TaxonomyUtils.escapeHtml;

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
