/**
 * Shared ViewContext renderer — displays Git commit provenance and
 * projection/index freshness inside individual UI views.
 *
 * <p>Re-used by analyze, graph, DSL, and export views.
 * CSS classes reuse the git-status.css theme variables.
 */
window.TaxonomyViewContext = (function () {
    'use strict';

    function escapeHtml(s) {
        if (!s) return '';
        return s.replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    /**
     * Render a ViewContext badge into a DOM container.
     *
     * @param {string} containerId — DOM ID of the target element
     * @param {object} viewContext — ViewContext object from API response
     */
    function render(containerId, viewContext) {
        var container = document.getElementById(containerId);
        if (!container || !viewContext) return;

        var parts = [];

        // Commit info
        var sha = viewContext.basedOnCommit
            ? viewContext.basedOnCommit.substring(0, 7)
            : '—';
        var branch = escapeHtml(viewContext.basedOnBranch || 'unknown');
        var ts = viewContext.commitTimestamp
            ? new Date(viewContext.commitTimestamp).toLocaleString()
            : '';

        parts.push(
            '<span class="vc-commit">&#128204; Based on: <strong>' + branch +
            '</strong> @ <code>' + sha + '</code>' +
            (ts ? ' <small class="text-muted">(' + ts + ')</small>' : '') +
            '</span>'
        );

        // Provisional relations warning
        if (viewContext.includesProvisionalRelations) {
            parts.push(
                '<span class="vc-warning">&#9888;&#65039; Includes provisional relations</span>'
            );
        }

        // Projection staleness
        if (viewContext.projectionStale) {
            parts.push(
                '<span class="vc-stale"><span class="dot stale"></span> Projection: STALE</span>'
            );
        } else {
            parts.push(
                '<span class="vc-fresh"><span class="dot fresh"></span> Projection: fresh</span>'
            );
        }

        // Index staleness
        if (viewContext.indexStale) {
            parts.push(
                '<span class="vc-stale"><span class="dot stale"></span> Index: STALE</span>'
            );
        } else {
            parts.push(
                '<span class="vc-fresh"><span class="dot fresh"></span> Index: fresh</span>'
            );
        }

        container.innerHTML = parts.join(' ');
        container.classList.remove('d-none');
    }

    /**
     * Extract viewContext from an API response and render it.
     *
     * @param {string} containerId — DOM ID
     * @param {object} apiResponse — full API response that may contain viewContext
     */
    function renderFromResponse(containerId, apiResponse) {
        if (apiResponse && apiResponse.viewContext) {
            render(containerId, apiResponse.viewContext);
        }
    }

    /**
     * Fetch the current ViewContext from the Git state API and render it.
     *
     * @param {string} containerId — DOM ID
     * @param {string} [branch='draft'] — branch name
     */
    function fetchAndRender(containerId, branch) {
        branch = branch || 'draft';
        fetch('/api/git/state?branch=' + encodeURIComponent(branch))
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (state) {
                if (!state) return;
                // Build a ViewContext-like object from RepositoryState
                render(containerId, {
                    basedOnCommit: state.headCommit,
                    basedOnBranch: state.currentBranch,
                    commitTimestamp: state.headTimestamp,
                    includesProvisionalRelations: true,
                    projectionStale: state.projectionStale,
                    indexStale: state.indexStale
                });
            })
            .catch(function () {
                // silently ignore — the global git-status bar already shows errors
            });
    }

    /**
     * Hide a ViewContext container.
     *
     * @param {string} containerId — DOM ID
     */
    function hide(containerId) {
        var container = document.getElementById(containerId);
        if (container) {
            container.classList.add('d-none');
            container.innerHTML = '';
        }
    }

    return {
        render: render,
        renderFromResponse: renderFromResponse,
        fetchAndRender: fetchAndRender,
        hide: hide
    };
}());
