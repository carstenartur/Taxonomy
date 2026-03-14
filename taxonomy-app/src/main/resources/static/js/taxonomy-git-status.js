/**
 * Git Status Bar — polls /api/git/state and renders the global status bar.
 *
 * <p>Shows: current branch, HEAD SHA, projection/index freshness,
 * and any in-progress operations.
 */
(function () {
    'use strict';

    var POLL_INTERVAL = 10000; // 10 seconds
    var statusBar = null;
    var pollTimer = null;

    // ── Initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        statusBar = document.getElementById('gitStatusBar');
        if (!statusBar) return;

        // Initial fetch
        pollGitState();

        // Start polling
        pollTimer = setInterval(pollGitState, POLL_INTERVAL);
    });

    // ── Polling ─────────────────────────────────────────────────────

    function pollGitState() {
        fetch('/api/git/state?branch=draft')
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (state) {
                renderStatusBar(state);
            })
            .catch(function (err) {
                renderError(err.message);
            });
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
        var projLabel = state.projectionStale ? 'STALE' : 'fresh';
        parts.push(
            '<span class="git-indicator">' +
            '<span class="dot ' + projDot + '"></span> Projection: ' + projLabel +
            '</span>'
        );

        parts.push('<span class="git-sep">│</span>');

        // Index status
        var idxDot = state.indexStale ? 'stale' : 'fresh';
        var idxLabel = state.indexStale ? 'STALE' : 'fresh';
        parts.push(
            '<span class="git-indicator">' +
            '<span class="dot ' + idxDot + '"></span> Index: ' + idxLabel +
            '</span>'
        );

        // Branch count
        if (state.branches && state.branches.length > 0) {
            parts.push('<span class="git-sep">│</span>');
            parts.push('<span class="git-indicator">' + state.branches.length + ' branch' +
                (state.branches.length !== 1 ? 'es' : '') + '</span>');
        }

        // Commit count
        if (state.totalCommits > 0) {
            parts.push('<span class="git-sep">│</span>');
            parts.push('<span class="git-indicator">' + state.totalCommits + ' commit' +
                (state.totalCommits !== 1 ? 's' : '') + '</span>');
        }

        // Operation warning
        if (state.operationInProgress) {
            parts.push('<span class="git-sep">│</span>');
            parts.push('<span class="git-operation">&#128308; ' +
                escapeHtml(state.operationKind || 'operation') + ' in progress</span>');
        }

        statusBar.innerHTML = parts.join(' ');
        statusBar.classList.remove('d-none');

        // Apply action guards based on the current state
        if (window.TaxonomyActionGuards) {
            window.TaxonomyActionGuards.guardAll(state);
        }
    }

    function renderError(msg) {
        if (!statusBar) return;
        statusBar.innerHTML = '<span class="git-indicator"><span class="dot error"></span> Git status unavailable</span>';
        statusBar.classList.remove('d-none');
    }

    // ── Helpers ──────────────────────────────────────────────────────

    function escapeHtml(s) {
        if (!s) return '';
        return s.replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    // Expose for other modules
    window.TaxonomyGitStatus = {
        refresh: pollGitState
    };
}());
