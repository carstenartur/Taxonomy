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
    var pendingRead = null;
    var pageActive = true;

    // ── Initialization ──────────────────────────────────────────────

    function startPolling() {
        if (!pageActive || pollTimer !== null) return;
        pollSyncState();
        pollTimer = setInterval(pollSyncState, POLL_INTERVAL);
    }
    document.addEventListener('DOMContentLoaded', startPolling);
    window.addEventListener('pagehide', function () {
        pageActive = false; clearInterval(pollTimer); pollTimer = null;
        if (pendingRead) pendingRead.abort();
    });
    window.addEventListener('pageshow', function (event) {
        if (!event.persisted || pageActive) return;
        pageActive = true; startPolling();
    });

    // ── Polling ─────────────────────────────────────────────────────

    function pollSyncState() {
        if (!pageActive) return;
        if (pendingRead) pendingRead.abort();
        var controller = new AbortController();
        pendingRead = controller;
        function current() {
            return pageActive && pendingRead === controller && !controller.signal.aborted;
        }
        window.TaxonomyWorkspaceApi.syncState({ signal: controller.signal })
            .then(function (state) {
                if (!current() || !state) return;
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
            '<span class="dot ' + dot + '"></span> ' + escapeHtml(t('workspace.sync.indicator')) + ' ' + escapeHtml(label) +
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

        window.TaxonomyWorkspaceApi.localChanges(branch)
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
            }).catch(showError);
    }

    // ── Sync Actions ────────────────────────────────────────────────

    function syncFromShared() {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = (ctx && ctx.branch) ? ctx.branch : 'draft';

        window.TaxonomyWorkspaceApi.syncFromShared(branch)
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
            }).catch(showError);
    }

    var publicationKeys = new Map();

    function publish() {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = (ctx && ctx.branch) ? ctx.branch : 'draft';

        var workspace = window.__TaxonomyAnalysisSessionContext?.runtime?.workspaceId || '';
        var scope = 'taxonomy-publish:' + JSON.stringify([workspace, ctx?.repositoryId || '', branch]);
        var key = publicationKeys.get(scope);
        try { key = key || window.sessionStorage?.getItem(scope); } catch (ignored) { }
        if (!key) key = window.crypto?.randomUUID?.() || 'publish-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
        publicationKeys.set(scope, key);
        try { window.sessionStorage?.setItem(scope, key); } catch (ignored) { }
        function settled() {
            if (publicationKeys.get(scope) !== key) return;
            publicationKeys.delete(scope);
            try { if (window.sessionStorage?.getItem(scope) === key) window.sessionStorage.removeItem(scope); } catch (ignored) { }
        }

        return window.TaxonomyWorkspaceApi.publish(branch, {idempotencyKey:key})
            .then(function (result) {
                if (result.operationId && !result.success && !result.error) {
                    window.TaxonomyOperationResult?.showWarning(t('workspace.sync.publish.pending'),
                        t('workspace.sync.publish.receipt', result.operationId, result.status));
                    return;
                }
                settled();
                if (result.status === 'SUPERSEDED') {
                    window.TaxonomyOperationResult?.showWarning(t('workspace.sync.publish.superseded'),
                        t('workspace.sync.publish.receipt', result.operationId, result.status));
                    pollSyncState();
                    return;
                }
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
            }).catch(function (error) {
                if (error.status === 409 || error.status === 410 || error.status === 403) settled();
                showError(error);
            });
    }

    function showError(error) {
        if (error.code === 'ABORTED') return;
        if (window.TaxonomyOperationResult) {
            window.TaxonomyOperationResult.showError(t('operation.failed', error.message), error.detail || '');
        } else {
            alert(t('operation.failed', error.message));
        }
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
