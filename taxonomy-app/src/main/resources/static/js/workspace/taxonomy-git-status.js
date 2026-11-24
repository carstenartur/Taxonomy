/**
 * Git Status Bar — polls /api/git/state and renders the global status bar.
 *
 * <p>Shows: current branch, HEAD SHA, projection/index freshness,
 * and any in-progress operations.
 */
(function () {
    'use strict';

    var t = TaxonomyI18n.t;

    var POLL_INTERVAL = 10000;
    var statusBar = null;
    var pollTimer = window.TaxonomyApiClient.createPollTimer(POLL_INTERVAL, pollGitState);
    var pendingRead = null;
    var ownsPolledSource = null;
    var requestedBranch = null;
    var requestedContext = null;
    var lastReadFailed = false;
    var pageActive = true;
    var resumeTimer = null;
    // A hard navigation can start while a periodic poll is due. Stop the timer
    // at beforeunload, ahead of pagehide, so WebKit never starts a fetch in a
    // departing document; also cancel any already owned read.
    function stopPolling() {
        pageActive = false;
        pollTimer.cancel();
        if (resumeTimer !== null) clearTimeout(resumeTimer);
        resumeTimer = null;
        if (pendingRead) pendingRead.abort();
        pendingRead = null;
    }
    window.addEventListener('beforeunload', function () {
        stopPolling();
        resumeTimer = setTimeout(function () {
            resumeTimer = null;
            pageActive = true;
            if (statusBar) pollGitState();
        }, POLL_INTERVAL);
    });
    window.addEventListener('pagehide', stopPolling);
    window.addEventListener('pageshow', function (event) {
        if (!event.persisted || pageActive || !statusBar) return;
        pageActive = true;
        pollGitState();
    });

    // ── Initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        statusBar = document.getElementById('gitStatusBar');
        if (statusBar) pollGitState();
    });

    document.addEventListener('taxonomy:repository-context-changed', function (event) {
        if (!pageActive || !statusBar) return;
        if (ownsPolledSource && !ownsPolledSource()) return pollGitState();
        var context = selectedContext();
        if (!context) return;
        // Initial context hydration can revoke the default-branch request before
        // its body arrives. Read the selected source immediately with its own guard.
        if (event.detail?.selectionChanged || context.branch !== requestedBranch
                || ((pendingRead || lastReadFailed) && sourceIdentity(context) !== requestedContext)) {
            if (event.detail?.selectionChanged) paintStatus('');
            pollTimer.reset();
            pollGitState();
        }
    });
    document.addEventListener('taxonomy:architecture-source-changed', function () {
        if (ownsPolledSource && !ownsPolledSource()) pollGitState();
    });

    function selectedContext() {
        var bar = window.TaxonomyContextBar;
        return bar?.getContextForWorkspaceSource ? bar.getContextForWorkspaceSource() : bar?.getCurrentContext?.();
    }

    // ── Polling ─────────────────────────────────────────────────────

    function pollGitState() {
        if (!pageActive) return;
        if (ownsPolledSource && !ownsPolledSource()) {
            if (statusBar) paintStatus('');
            pollTimer.reset();
        }
        if (pollTimer.isStopped()) return;
        pollTimer.cancel();
        if (pendingRead) pendingRead.abort();
        var controller = new AbortController();
        pendingRead = controller;
        var ctx = selectedContext();
        var branch = ctx?.branch || window.TaxonomyArchitectureWorkflow?.getScope?.()?.branch || 'draft';
        var runtime = window.__TaxonomyAnalysisSessionContext?.runtime;
        var analysis = runtime?.analysisGeneration;
        var ownsWorkspace = window.TaxonomyApiClient.captureWorkspaceSource();
        var ownsAuthority = window.TaxonomyApiClient.captureContext({contextSensitive: false});
        ownsPolledSource = ownsWorkspace;
        var contextSource = sourceIdentity(ctx);
        requestedBranch = branch;
        requestedContext = contextSource;
        var error;
        function ownsSource() {
            return ownsWorkspace() && analysis === runtime?.analysisGeneration
                && contextSource === sourceIdentity(selectedContext());
        }

        function current() {
            return pageActive && pendingRead === controller && !controller.signal.aborted && ownsSource();
        }

        return window.TaxonomyVersioningApi.state(branch, { signal: controller.signal, acceptedStatuses: [] })
            .then(function (r) { return r.json(); })
            .then(async function (state) {
                // Startup may receive Git state before the locale catalogue finishes loading.
                if (TaxonomyI18n.ready) await TaxonomyI18n.ready();
                if (current() && ownsAuthority()) {
                    renderStatusBar(state);
                    document.dispatchEvent(new CustomEvent('taxonomy:server-contact', { detail: { available: true } }));
                }
            })
            .catch(async function (err) {
                error = err;
                if (!current() || err.name === 'AbortError' || err.code === 'ABORTED') return;
                // The canonical boundary reports authentication failures.
                if (err.status !== 401 && err.status !== 403) {
                    if (TaxonomyI18n.ready) await TaxonomyI18n.ready();
                    if (!current() || !ownsAuthority()) return;
                    renderError(err);
                }
                if (current()) {
                    var feedback = {available: err.status === 409, status: err.status};
                    if (err.status === 409) {
                        feedback.detail = err.detail || err.title;
                        feedback.code = err.responseBody?.code;
                        feedback.workspaceStatus = err.responseBody?.workspaceStatus;
                    }
                    document.dispatchEvent(new CustomEvent('taxonomy:server-contact', {
                        detail: feedback
                    }));
                }
            }).finally(function () {
                if (pendingRead !== controller) return;
                pendingRead = null;
                lastReadFailed = Boolean(error);
                if (pageActive) pollTimer.schedule({error: ownsSource() ? error : null});
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

        parts.push(readinessIndicator('projection', state.projectionReadiness, state.projectionStale));
        parts.push('<span class="git-sep">│</span>');
        parts.push(readinessIndicator('index', state.indexReadiness, state.indexStale));

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
        var ctx = selectedContext();
        if (ctx && ctx.mode === 'READ_ONLY') {
            parts.push('<span class="git-sep">│</span>');
            parts.push('<span class="git-indicator"><span class="badge bg-warning text-dark" style="font-size:0.7rem;">READ-ONLY</span></span>');
        }

        paintStatus(parts.join(' '));

        // Apply action guards based on the current state
        if (window.TaxonomyActionGuards) {
            window.TaxonomyActionGuards.guardAll(state);
        }
    }

    function readinessIndicator(kind, evidence, stale) {
        var status = evidence ? evidence.toLowerCase() : stale ? 'stale' : 'unverified';
        var dot = status === 'fresh' ? 'fresh' : 'stale';
        return '<span class="git-indicator"><span class="dot ' + dot + '"></span> '
            + escapeHtml(t('git.' + kind)) + ': ' + escapeHtml(t('git.' + status)) + '</span>';
    }

    function paintStatus(html) {
        // Workspace sync owns this independently updated child.
        var syncIndicator = statusBar.querySelector('#syncStatusIndicator');
        statusBar.innerHTML = html;
        if (syncIndicator) statusBar.appendChild(syncIndicator);
        statusBar.classList.remove('d-none');
    }

    function renderError(error) {
        if (!statusBar) return;
        var label = t('git.status.unavailable');
        var explanation = '';
        var dot = 'error';
        if (error.status === 409) {
            var body = error.responseBody;
            explanation = error.detail || error.title || '';
            if (body?.code === 'WORKSPACE_NOT_READY'
                    && ['NOT_PROVISIONED', 'PROVISIONING', 'FAILED'].includes(body.workspaceStatus)) {
                label = t('workspace.sync.lifecycle.' + body.workspaceStatus.toLowerCase().replace('_', '.'));
                if (body.workspaceStatus !== 'FAILED') dot = 'stale';
            } else if (body?.code === 'INITIAL_SOURCE_PENDING') {
                label = t('startup.ui.sourcePending');
                dot = 'stale';
            } else if (explanation) {
                label = explanation;
                explanation = '';
            }
        }
        var title = explanation ? ' title="' + escapeHtml(explanation) + '"' : '';
        paintStatus('<span class="git-indicator"' + title + '><span class="dot ' + dot + '"></span> '
            + escapeHtml(label) + '</span>');
    }

    // ── Helpers ──────────────────────────────────────────────────────

    var escapeHtml = TaxonomyUtils.escapeHtml;

    // Expose for other modules
    window.TaxonomyGitStatus = {
        refresh: function () {
            if (!pageActive) return;
            if (pollTimer.isStopped()) pollTimer.reset();
            pollGitState();
        }
    };
}());
