/**
 * Git Status Bar — polls /api/git/state and renders the global status bar.
 *
 * <p>Shows: current branch, HEAD SHA, projection/index freshness,
 * and any in-progress operations.
 */
(function () {
    'use strict';

    var t = TaxonomyI18n.t;

    var POLL_INTERVAL = 10000; // 10 seconds
    var statusBar = null;
    var pollTimer = null;
    var pendingRead = null;
    var requestedBranch = null;
    var requestedContext = null;
    var pageActive = true;
    // A hard navigation can start while a periodic poll is due. Stop the timer
    // at beforeunload, ahead of pagehide, so WebKit never starts a fetch in a
    // departing document; also cancel any already owned read.
    function stopPolling() {
        pageActive = false;
        clearInterval(pollTimer);
        pollTimer = null;
        if (pendingRead) pendingRead.abort();
        pendingRead = null;
    }
    window.addEventListener('beforeunload', stopPolling);
    window.addEventListener('pagehide', stopPolling);
    window.addEventListener('pageshow', function (event) {
        if (event.persisted && statusBar) {
            pageActive = true; pollGitState(); pollTimer = setInterval(pollGitState, POLL_INTERVAL);
        }
    });

    // ── Initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        statusBar = document.getElementById('gitStatusBar');
        if (!statusBar) return;

        // Initial fetch
        pollGitState();

        // Start polling
        pollTimer = setInterval(pollGitState, POLL_INTERVAL);
    });

    document.addEventListener('taxonomy:repository-context-changed', function (event) {
        if (!pageActive || !statusBar) return;
        var context = window.TaxonomyContextBar?.getCurrentContext?.();
        if (!context) return;
        // Initial context hydration can revoke the default-branch request before
        // its body arrives. Read the selected source immediately with its own guard.
        if (event.detail?.selectionChanged || context.branch !== requestedBranch
                || (pendingRead && sourceIdentity(context) !== requestedContext)) {
            pollGitState();
        }
    });

    // ── Polling ─────────────────────────────────────────────────────

    function pollGitState() {
        if (!pageActive) return;
        if (pendingRead) pendingRead.abort();
        var controller = new AbortController();
        pendingRead = controller;
        // Use the current context's branch if available, otherwise default to 'draft'
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = (ctx && ctx.branch) ? ctx.branch : 'draft';
        var runtime = window.__TaxonomyAnalysisSessionContext?.runtime;
        var workspace = runtime?.workspaceId, analysis = runtime?.analysisGeneration;
        var contextSource = sourceIdentity(ctx);
        requestedBranch = branch;
        requestedContext = contextSource;
        function ownsSource() {
            var currentRuntime = window.__TaxonomyAnalysisSessionContext?.runtime;
            var currentContext = window.TaxonomyContextBar?.getCurrentContext?.();
            return runtime === currentRuntime && workspace === currentRuntime?.workspaceId
                && analysis === currentRuntime?.analysisGeneration
                && contextSource === sourceIdentity(currentContext);
        }

        window.TaxonomyVersioningApi.state(branch, { signal: controller.signal })
            .then(function (r) {
                if (!r.ok) throw Object.assign(new Error('HTTP ' + r.status), { status: r.status });
                return r.json();
            })
            .then(function (state) {
                if (pageActive && pendingRead === controller && !controller.signal.aborted && ownsSource()) {
                    pendingRead = null;
                    renderStatusBar(state);
                    document.dispatchEvent(new CustomEvent('taxonomy:server-contact', { detail: { available: true } }));
                }
            })
            .catch(function (err) {
                if (pageActive && pendingRead === controller && !controller.signal.aborted && ownsSource()
                        && err.name !== 'AbortError' && err.code !== 'ABORTED') {
                    // Authentication remains owned by the canonical boundary.
                    // Its current poll still reports contact failure to session startup.
                    if (err.status !== 401 && err.status !== 403) renderError(err.message);
                    document.dispatchEvent(new CustomEvent('taxonomy:server-contact', {
                        detail: { available: false, status: err.status }
                    }));
                }
            }).finally(function () {
                if (pendingRead === controller) pendingRead = null;
            });
    }

    function sourceIdentity(context) {
        return JSON.stringify([context?.contextId, context?.repositoryId, context?.workspaceId,
            context?.branch, context?.commitId]);
    }

    // ── Rendering ───────────────────────────────────────────────────

    function renderStatusBar(state) {
        if (!statusBar) return;

        var parts = [];

        // Branch + SHA
        var sha = state.headCommit ? state.headCommit.substring(0, 7) : '—';
        parts.push(
            '<span class="git-indicator">' +
            '&#128256; <span class="git-branch">' + escapeHtml(state.currentBranch || 'unknown') + '</span>' +
            ' @ <span class="git-sha">' + sha + '</span>' +
            '</span>'
        );

        parts.push('<span class="git-sep">│</span>');

        // Projection status
        var projDot = state.projectionStale ? 'stale' : 'fresh';
        parts.push(
            '<span class="git-indicator">' +
            '<span class="dot ' + projDot + '"></span> ' + t('git.projection') + ': ' + (state.projectionStale ? t('git.stale') : t('git.fresh')) +
            '</span>'
        );

        parts.push('<span class="git-sep">│</span>');

        // Index status
        var idxDot = state.indexStale ? 'stale' : 'fresh';
        parts.push(
            '<span class="git-indicator">' +
            '<span class="dot ' + idxDot + '"></span> ' + t('git.index') + ': ' + (state.indexStale ? t('git.stale') : t('git.fresh')) +
            '</span>'
        );

        // Branch count (shown as "variants")
        if (state.branches && state.branches.length > 0) {
            parts.push('<span class="git-sep">│</span>');
            parts.push('<span class="git-indicator">' + t('git.variants.count', state.branches.length) + '</span>');
        }

        // Commit count (shown as "versions")
        if (state.totalCommits > 0) {
            parts.push('<span class="git-sep">│</span>');
            parts.push('<span class="git-indicator">' + t('git.versions.count', state.totalCommits) + '</span>');
        }

        // Operation warning
        if (state.operationInProgress) {
            parts.push('<span class="git-sep">│</span>');
            parts.push('<span class="git-operation">&#128308; ' +
                t('git.operation.in.progress', escapeHtml(state.operationKind || 'operation')) + '</span>');
        }

        // Context mode indicator
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        if (ctx && ctx.mode === 'READ_ONLY') {
            parts.push('<span class="git-sep">│</span>');
            parts.push('<span class="git-indicator"><span class="badge bg-warning text-dark" style="font-size:0.7rem;">READ-ONLY</span></span>');
        }

        // Workspace sync owns this separately updated child. Keep its accepted
        // state when the Git poll replaces the rest of the status bar.
        var syncIndicator = statusBar.querySelector('#syncStatusIndicator');
        statusBar.innerHTML = parts.join(' ');
        if (syncIndicator) statusBar.appendChild(syncIndicator);
        statusBar.classList.remove('d-none');

        // Apply action guards based on the current state
        if (window.TaxonomyActionGuards) {
            window.TaxonomyActionGuards.guardAll(state);
        }
    }

    function renderError(msg) {
        if (!statusBar) return;
        statusBar.innerHTML = '<span class="git-indicator"><span class="dot error"></span> ' + t('git.status.unavailable') + '</span>';
        statusBar.classList.remove('d-none');
    }

    // ── Helpers ──────────────────────────────────────────────────────

    var escapeHtml = TaxonomyUtils.escapeHtml;

    // Expose for other modules
    window.TaxonomyGitStatus = {
        refresh: pollGitState
    };
}());
