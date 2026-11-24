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
    var pollTimer = window.TaxonomyApiClient.createPollTimer(15000, pollSyncState);
    var pendingRead = null;
    var ownsPolledSource = null;
    var planRead = null;
    var planTag = null;
    var planCheck = null;
    var pageActive = true;

    // ── Initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', pollSyncState);
    document.addEventListener('click', function (event) {
        if (event.target.closest?.('#workspaceRebasePlanButton')) showRebasePlan();
    });
    document.addEventListener('taxonomy-api-auth-failure', clearRebasePlan);
    window.addEventListener('pagehide', function () {
        clearRebasePlan();
        pageActive = false;
        pollTimer.cancel();
        if (pendingRead) pendingRead.abort();
        pendingRead = null;
    });
    window.addEventListener('pageshow', function (event) {
        if (!event.persisted || pageActive) return;
        pageActive = true;
        pollSyncState();
    });

    function selectedContext() {
        var bar = window.TaxonomyContextBar;
        return bar?.getContextForWorkspaceSource ? bar.getContextForWorkspaceSource() : bar?.getCurrentContext?.();
    }

    function editable() {
        return selectedContext()?.mode !== 'READ_ONLY';
    }

    document.addEventListener('taxonomy:repository-context-changed', function (event) {
        if (!pageActive) return;
        if (event.detail?.selectionChanged || (ownsPolledSource && !ownsPolledSource())) {
            pollSyncState(event.detail);
        } else {
            updateWorkspaceActions();
        }
    });
    document.addEventListener('taxonomy:architecture-source-changed', function () {
        if (ownsPolledSource && !ownsPolledSource()) pollSyncState();
    });

    function updateWorkspaceActions() {
        var actions = document.getElementById('workspaceSyncActions');
        if (actions) {
            var context = selectedContext();
            var disabled = !context || context.mode === 'READ_ONLY';
            var moveFocus = disabled && actions.contains(document.activeElement);
            actions.disabled = disabled;
            if (moveFocus) document.getElementById('contextBarBranch')?.focus({preventScroll: true});
        }
    }

    function clearSyncPresentation() {
        updateWorkspaceActions();
        clearRebasePlan();
        var planButton = document.getElementById('workspaceRebasePlanButton');
        if (planButton) planButton.hidden = true;
        syncState = null;
        window.TaxonomyBranchAdoption?.clear();
        document.getElementById('syncStatusIndicator')?.remove();
        document.getElementById('syncStatePanel')?.replaceChildren();
        var local = document.getElementById('localChangesPanel');
        if (local) {
            local.replaceChildren();
            local.classList.add('d-none');
        }
        renderDirtyIndicator();
        window.TaxonomyContextBar?.updateSyncInfoInBar?.();
    }

    // ── Polling ─────────────────────────────────────────────────────

    function pollSyncState(options) {
        if (!pageActive) return;
        if (options?.selectionChanged || (ownsPolledSource && !ownsPolledSource())) {
            clearSyncPresentation();
            pollTimer.reset();
        }
        if (pollTimer.isStopped()) return;
        pollTimer.cancel();
        if (pendingRead) pendingRead.abort();
        var controller = new AbortController();
        pendingRead = controller;
        var ownsWorkspace = window.TaxonomyApiClient.captureWorkspaceSource();
        ownsPolledSource = ownsWorkspace;
        var ownsSource = window.TaxonomyApiClient.captureContext();
        var pending = false;
        var error;
        function current() {
            return pageActive && pendingRead === controller && !controller.signal.aborted && ownsWorkspace();
        }
        return window.TaxonomyWorkspaceApi.syncState({ signal: controller.signal })
            .then(function (state) {
                if (!current() || !ownsSource() || !state) return;
                var context = selectedContext();
                var scope = window.TaxonomyArchitectureWorkflow?.getScope?.();
                var workspace = window.__TaxonomyAnalysisSessionContext?.runtime?.workspaceId;
                var branch = context?.branch || scope?.branch;
                if ((state.branch && branch && state.branch !== branch)
                        || (state.repositoryId && scope?.repositoryId && state.repositoryId !== scope.repositoryId)
                        || (state.workspaceId && workspace && state.workspaceId !== workspace)) return;
                // A later observation may advance Git refs without changing the selected branch.
                // Treat any changed sync observation as a new plan authority; do not show a stale plan.
                if (syncState && JSON.stringify(state) !== JSON.stringify(syncState)) clearRebasePlan();
                syncState = state;
                pending = Object.hasOwn(lifecycleLabels, state.syncStatus);
                window.TaxonomyBranchAdoption?.select(state, context, pending);
                checkVisibleRebasePlan(state);
                var planButton = document.getElementById('workspaceRebasePlanButton');
                if (planButton) planButton.hidden = !state.repositoryId || !state.branch
                    || (context?.mode === 'READ_ONLY' && context.commitId != null) || pending;
                renderSyncIndicator(state);
                renderDirtyIndicator();
                renderSyncStatePanel(state);
                window.TaxonomyContextBar?.updateSyncInfoInBar?.();
            })
            .catch(function (err) {
                error = err;
                if (current() && ownsSource() && err.status === 409) renderSyncConflict(err);
            }).finally(function () {
                if (pendingRead !== controller) return;
                pendingRead = null;
                if (pageActive) pollTimer.schedule(ownsWorkspace() ? {error, pending} : {});
            });
    }

    // ── Sync Indicator (appended to Git Status Bar) ─────────────────

    var lifecycleLabels = {
        NOT_PROVISIONED: 'workspace.sync.lifecycle.not.provisioned',
        PROVISIONING: 'workspace.sync.lifecycle.provisioning',
        FAILED: 'workspace.sync.lifecycle.failed'
    };

    function renderSyncConflict(error) {
        syncState = null;
        clearRebasePlan();
        window.TaxonomyBranchAdoption?.clear();
        var planButton = document.getElementById('workspaceRebasePlanButton');
        if (planButton) planButton.hidden = true;
        var panel = document.getElementById('syncStatePanel');
        var message = error.detail || error.title || error.message;
        if (panel) panel.textContent = message;
        renderSyncIndicator(null, message);
        window.TaxonomyContextBar?.updateSyncInfoInBar?.();
    }

    function renderSyncIndicator(state, conflict) {
        var bar = document.getElementById('gitStatusBar');
        if (!bar) return;

        var existing = document.getElementById('syncStatusIndicator');
        if (existing) existing.remove();

        var status = state?.syncStatus || 'UP_TO_DATE';
        var dot = 'fresh';
        var label = t('workspace.sync.status.synced');
        if (conflict) {
            dot = 'stale';
            label = conflict;
        } else if (Object.hasOwn(lifecycleLabels, status)) {
            dot = status === 'FAILED' ? 'error' : 'stale';
            label = t(lifecycleLabels[status]);
        } else if (status === 'BEHIND') {
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
        if (Object.hasOwn(lifecycleLabels, status)) {
            badgeClass = status === 'FAILED' ? 'bg-danger' : 'bg-secondary';
            statusLabel = t(lifecycleLabels[status]);
        } else if (status === 'BEHIND') {
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
        if (status === 'DIVERGED' && editable()) {
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

    function clearRebasePlan() {
        if (planRead) planRead.abort();
        if (planCheck) planCheck.abort();
        planRead = null;
        planCheck = null;
        planTag = null;
        document.getElementById('workspaceRebasePlan')?.replaceChildren();
    }

    async function checkVisibleRebasePlan(source) {
        if (planTag === null) return;
        if (planCheck) planCheck.abort();
        var check = new AbortController(), expected = planTag;
        planCheck = check;
        try {
            var live = await window.TaxonomyWorkspaceApi.rebasePlanRefTag(source, {signal:check.signal});
            if (planCheck === check && live !== expected) clearRebasePlan();
        } catch (error) {
            if (planCheck === check) clearRebasePlan();
        } finally {
            if (planCheck === check) planCheck = null;
        }
    }

    function rebaseButton(key, action) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn-sm btn-outline-secondary me-2';
        button.textContent = t('workspace.rebase.' + key);
        button.addEventListener('click', action);
        return button;
    }

    function showRebaseConflict(panel, result, resume) {
        var summary = document.createElement('p'), files = document.createElement('ul');
        summary.textContent = t('workspace.rebase.conflict', result.conflictCommit, result.originalHead);
        result.conflictingPaths.forEach(function (path) {
            var item = document.createElement('li'); item.textContent = path; files.append(item);
        });
        panel.replaceChildren(summary, files);
        var conflict = result.dslConflict;
        if (conflict) {
            for (var side of ['base', 'target', 'source']) {
                var detail = document.createElement('details'), heading = document.createElement('summary');
                var code = document.createElement('pre');
                heading.textContent = t('workspace.rebase.version.' + side);
                code.textContent = conflict[side].text;
                detail.append(heading, code);
                panel.append(detail);
            }
            var label = document.createElement('label'), editor = document.createElement('textarea');
            label.htmlFor = editor.id = 'workspaceRebaseResolution';
            label.textContent = t('workspace.rebase.resolution');
            editor.className = 'form-control mb-2';
            editor.rows = 8;
            editor.value = conflict.target.text;
            var retry = rebaseButton('resolve', () => resume(result.conflictCommit, conflict.target.blobId, editor.value));
            panel.append(label, editor, retry);
        }
        panel.append(rebaseButton('refresh', showRebasePlan), rebaseButton('abort', clearRebasePlan));
    }

    async function showRebasePlan() {
        clearRebasePlan();
        var panel = document.getElementById('workspaceRebasePlan'), source = syncState;
        if (!panel || !source?.repositoryId || !source.branch) return;
        var observedSource = JSON.stringify(source), contextId = selectedContext()?.contextId;
        var controller = new AbortController();
        planRead = controller;
        panel.textContent = t('workspace.rebase.loading');
        function current() {
            return !controller.signal.aborted && planRead === controller && JSON.stringify(syncState) === observedSource;
        }
        try {
            var {plan, etag} = await window.TaxonomyWorkspaceApi.rebasePlan(
                source, {signal:controller.signal, includeETag:true});
            if (!current()) return;
            if (!etag || plan.repositoryId !== source.repositoryId || plan.branch !== source.branch
                    || plan.workspaceId !== (source.workspaceId || null)) throw new Error();
            var liveTag = await window.TaxonomyWorkspaceApi.rebasePlanRefTag(source, {signal:controller.signal});
            if (!current()) return;
            if (liveTag !== etag) {
                clearRebasePlan();
                return;
            }
            var details = document.createElement('dl');
            details.className = 'small mb-2';
            function field(key, label, commit) {
                if (!commit) return;
                var term = document.createElement('dt'), value = document.createElement('dd'), id = document.createElement('code');
                term.textContent = t('workspace.rebase.' + key);
                value.textContent = label ? label + ' ' : '';
                id.textContent = commit;
                value.append(id);
                details.append(term, value);
            }
            field('local', plan.branch, plan.localCommit);
            field('live', plan.upstream.sourceRepositoryId + '/' + plan.upstream.sourceBranch, plan.upstream.sourceCommit);
            field('tracked', plan.upstream.sourceBranch, plan.upstream.trackedCommit);
            field('base', null, plan.mergeBase);
            if (plan.recoverableCommit) field('retained', null, plan.recoverableCommit);
            var status = document.createElement('p');
            status.textContent = t('workspace.rebase.state.' + plan.status);
            panel.replaceChildren(details, status);
            if (plan.upstream.sourceCommit !== plan.upstream.trackedCommit && plan.upstream.trackedCommit) {
                var changed = document.createElement('p');
                changed.textContent = t('workspace.rebase.upstream.changed');
                panel.append(changed);
            }
            if (plan.commits.length) {
                var list = document.createElement('ol');
                plan.commits.forEach(function (commit) {
                    var row = document.createElement('li'), id = document.createElement('code');
                    id.textContent = commit.id;
                    row.append(id, ' ' + commit.subject);
                    list.append(row);
                });
                var conflicts = document.createElement('p');
                conflicts.textContent = t('workspace.rebase.conflicts.unchecked');
                panel.append(list, conflicts);
            }
            var changedRecovery = plan.recoverableCommit && plan.recoverableCommit !== plan.localCommit;
            if (changedRecovery) {
                var warning = document.createElement('p');
                warning.textContent = t('workspace.rebase.recovery.changed');
                panel.append(warning);
            }
            planTag = etag;
            if ((!source.workspaceId || (contextId && selectedContext()?.branch === source.branch)) && selectedContext()?.mode === 'EDITABLE'
                    && !['main', 'accepted', 'sync-base'].includes(source.branch)
                    && (plan.recoverableCommit || (plan.upstream.sourceCommit === plan.upstream.trackedCommit
                    && plan.status === 'REPLAY' && plan.commits.length))) {
                var apply = document.createElement('button');
                apply.id = 'workspaceApplyRebase';
                apply.type = 'button';
                apply.className = 'btn btn-sm btn-primary';
                apply.textContent = t(changedRecovery ? 'workspace.rebase.adopt' : plan.recoverableCommit ? 'workspace.rebase.resume' : 'workspace.rebase.apply');
                var resolutions = Object.create(null);
                async function submit(commit, targetBlobId, text) {
                    if (!current() || selectedContext()?.mode !== 'EDITABLE' || selectedContext()?.contextId !== contextId || planTag !== etag) return;
                    if (commit) resolutions[commit] = {targetBlobId:targetBlobId, text:text};
                    apply.disabled = true;
                    planTag = null;
                    if (planCheck) planCheck.abort();
                    planCheck = null;
                    status.textContent = t('workspace.rebase.applying');
                    if (commit) panel.replaceChildren(status);
                    try {
                        var result = await window.TaxonomyWorkspaceApi.applyRebase(source, plan, etag, {signal:controller.signal, expectedContextId:contextId}, resolutions);
                        if (!current()) return;
                        if (plan.recoverableCommit ? result.newHead !== plan.localCommit : result.originalHead !== plan.localCommit) throw new Error('Unexpected rebase receipt');
                        var outcome = document.createElement('p');
                        if (result.conflictCommit) {
                            planTag = etag;
                            showRebaseConflict(panel, result, submit);
                            return;
                        }
                        outcome.textContent = t('workspace.rebase.applied', result.newHead);
                        var replacements = document.createElement('ol');
                        result.rewritten.forEach(function (entry) {
                            var item = document.createElement('li');
                            item.textContent = entry.originalCommit + ' → ' + entry.replacementCommit;
                            replacements.append(item);
                        });
                        panel.replaceChildren(outcome, replacements);
                        window.TaxonomyOperationResult?.showSuccess?.(t('workspace.rebase.done'), outcome.textContent);
                        window.TaxonomyGitStatus?.refresh?.();
                        window.TaxonomyContextBar?.fetchAndRender?.();
                    } catch (error) {
                        if (current()) {
                            panel.textContent = t(error.status === 409 ? 'workspace.rebase.expired' : 'workspace.rebase.failed');
                            panel.append(rebaseButton('refresh', showRebasePlan));
                        }
                    }
                }
                apply.addEventListener('click', () => submit());
                panel.append(apply);
            }
        } catch (error) {
            if (current()) panel.textContent = t('workspace.rebase.unavailable');
        }
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

        var current = window.TaxonomyApiClient.captureContext();
        window.TaxonomyWorkspaceApi.localChanges(branch)
            .then(function (data) {
                if (!current()) return;
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
                    if (editable()) {
                        html += '<div class="d-flex gap-2">';
                        html += '<button class="btn btn-sm btn-primary" onclick="TaxonomyWorkspaceSync.publish()">\uD83D\uDCE4 ' + escapeHtml(t('workspace.local.publish')) + '</button>';
                        html += '<button class="btn btn-sm btn-outline-secondary" onclick="TaxonomyWorkspaceSync.syncFromShared()">\uD83D\uDCE5 ' + escapeHtml(t('workspace.local.sync')) + '</button>';
                        html += '</div>';
                    }
                }

                html += '</div></div>';

                var container = document.getElementById('localChangesPanel');
                if (container) {
                    container.innerHTML = html;
                    container.classList.remove('d-none');
                }
            }).catch(function (error) { if (current()) showError(error); });
    }

    // ── Sync Actions ────────────────────────────────────────────────

    var operationKeys = new Map();

    function synchronize(pull) {
        if (!editable()) return;
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = ctx?.branch || 'draft';
        var workspace = window.__TaxonomyAnalysisSessionContext?.runtime?.workspaceId || '';
        var repository = window.TaxonomyArchitectureWorkflow?.getScope?.()?.repositoryId || '';
        var scope = (pull ? 'taxonomy-pull:' : 'taxonomy-publish:') + JSON.stringify([workspace, repository, branch]);
        var key = operationKeys.get(scope);
        try { key = key || window.sessionStorage?.getItem(scope); } catch (ignored) { }
        if (!key) key = window.crypto?.randomUUID?.() || 'sync-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
        operationKeys.set(scope, key);
        try { window.sessionStorage?.setItem(scope, key); } catch (ignored) { }
        function settled() {
            if (operationKeys.get(scope) !== key) return;
            operationKeys.delete(scope);
            try { if (window.sessionStorage?.getItem(scope) === key) window.sessionStorage.removeItem(scope); } catch (ignored) { }
        }

        var current = window.TaxonomyApiClient.captureContext();
        var send = pull ? window.TaxonomyWorkspaceApi.syncFromShared : window.TaxonomyWorkspaceApi.publish;
        var message = pull ? 'workspace.sync.from.' : 'workspace.sync.publish.';
        return send(branch, {idempotencyKey:key})
            .then(function (result) {
                var pending = result.operationId && !result.success && !result.error && result.status !== 'SUPERSEDED';
                if (!pending) settled();
                if (!current()) return;
                if (pending) {
                    window.TaxonomyOperationResult?.showWarning(t(message + 'pending'),
                        t('workspace.sync.publish.receipt', result.operationId, result.status));
                    return;
                }
                if (result.status === 'SUPERSEDED') {
                    window.TaxonomyOperationResult?.showWarning(t(message + 'superseded'),
                        t('workspace.sync.publish.receipt', result.operationId, result.status));
                    pollSyncState();
                    return;
                }
                if (result.error) {
                    var title=t(pull ? 'workspace.sync.failed' : 'workspace.sync.publish.failed');
                    if (window.TaxonomyOperationResult) window.TaxonomyOperationResult.showError(title, result.message || result.error);
                    else alert(title + ': ' + result.error);
                    return;
                }
                window.TaxonomyOperationResult?.showSuccess(t(message + 'success'),t(message + 'success.detail'));
                pollSyncState();
                if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                showLocalChangesPanel();
            }).catch(function (error) {
                if (error.status === 409 || error.status === 410 || error.status === 403) settled();
                if (current()) showError(error);
            });
    }

    function syncFromShared() { return synchronize(true); }
    function publish() { return synchronize(false); }

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
        if (!pageActive) return;
        if (pollTimer.isStopped()) pollTimer.reset();
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
