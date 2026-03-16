/**
 * taxonomy-context-compare.js — Context Compare View
 *
 * Provides a three-level comparison between two architecture contexts:
 * 1. Summary — counts of added/changed/removed elements and relations
 * 2. Semantic Changes — individual change descriptions
 * 3. Raw DSL Diff — textual diff for expert users
 *
 * @module TaxonomyContextCompare
 */
window.TaxonomyContextCompare = (function () {
    'use strict';

    /**
     * Show the compare dialog, pre-filled with the current context.
     * Loads available branches into dropdown selectors.
     *
     * @param {object} currentContext — the current ContextRef
     */
    function showDialog(currentContext) {
        var modal = document.getElementById('contextCompareModal');
        if (!modal) return;

        var leftBranch = document.getElementById('compareLeftBranch');
        var rightBranch = document.getElementById('compareRightBranch');

        // Populate branch selectors
        populateBranchSelectors(function () {
            if (leftBranch && currentContext) {
                leftBranch.value = currentContext.branch || 'draft';
            }
            if (rightBranch) {
                rightBranch.value = 'draft';
            }
        });

        // Clear previous results
        var results = document.getElementById('contextCompareResults');
        if (results) results.innerHTML = '';

        var bsModal = new bootstrap.Modal(modal);
        bsModal.show();
    }

    /**
     * Populate branch selector dropdowns from API.
     *
     * @param {function} callback — called after branches are loaded
     */
    function populateBranchSelectors(callback) {
        fetch('/api/git/branches')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var branches = data.branches || data || [];
                ['compareLeftBranch', 'compareRightBranch'].forEach(function (id) {
                    var sel = document.getElementById(id);
                    if (!sel) return;
                    var current = sel.value || 'draft';
                    sel.innerHTML = '';
                    branches.forEach(function (b) {
                        var opt = document.createElement('option');
                        opt.value = b;
                        opt.textContent = b;
                        sel.appendChild(opt);
                    });
                    sel.value = current;
                });
                if (callback) callback();
            })
            .catch(function () {
                if (callback) callback();
            });
    }

    /**
     * Compare with a specific commit (from search results).
     *
     * @param {string} commitId — the commit to compare against
     */
    function compareWithCommit(commitId) {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var leftBranch = ctx ? ctx.branch : 'draft';
        var leftCommit = ctx ? ctx.commitId : null;

        var url = '/api/context/compare?leftBranch=' + encodeURIComponent(leftBranch)
            + '&rightBranch=' + encodeURIComponent(leftBranch);
        if (leftCommit) url += '&leftCommit=' + encodeURIComponent(leftCommit);
        url += '&rightCommit=' + encodeURIComponent(commitId);

        executeCompare(url);
    }

    /**
     * Execute a compare request and render results.
     *
     * @param {string} url — the compare API URL
     */
    function executeCompare(url) {
        fetch(url)
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (comparison) {
                if (comparison) {
                    renderComparison('contextCompareResults', comparison);
                }
            })
            .catch(function () {
                var container = document.getElementById('contextCompareResults');
                if (container) container.innerHTML = '<p class="text-danger">Compare failed.</p>';
            });
    }

    /**
     * Trigger compare from the modal form.
     */
    function doCompare() {
        var leftBranch = document.getElementById('compareLeftBranch');
        var rightBranch = document.getElementById('compareRightBranch');
        var leftCommit = document.getElementById('compareLeftCommit');
        var rightCommit = document.getElementById('compareRightCommit');

        var url = '/api/context/compare?leftBranch=' + encodeURIComponent(leftBranch ? leftBranch.value : 'draft')
            + '&rightBranch=' + encodeURIComponent(rightBranch ? rightBranch.value : 'draft');
        if (leftCommit && leftCommit.value) url += '&leftCommit=' + encodeURIComponent(leftCommit.value);
        if (rightCommit && rightCommit.value) url += '&rightCommit=' + encodeURIComponent(rightCommit.value);

        executeCompare(url);
    }

    /**
     * Render the comparison results.
     *
     * @param {string} containerId — DOM ID for results
     * @param {object} comparison — ContextComparison from API
     */
    function renderComparison(containerId, comparison) {
        var container = document.getElementById(containerId);
        if (!container) return;

        var html = '';

        // Level 1: Summary
        var s = comparison.summary;
        if (s) {
            html += '<div class="card mb-3">';
            html += '<div class="card-header"><strong>Summary</strong> — ' + s.elementsAdded + ' added, '
                + s.elementsChanged + ' changed, ' + s.elementsRemoved + ' removed elements; '
                + s.relationsAdded + ' added, ' + s.relationsChanged + ' changed, '
                + s.relationsRemoved + ' removed relations</div>';
            html += '</div>';
        }

        // Level 2: Semantic Changes
        if (comparison.changes && comparison.changes.length > 0) {
            html += '<div class="card mb-3"><div class="card-header"><strong>Semantic Changes</strong></div>';
            html += '<ul class="list-group list-group-flush">';
            comparison.changes.forEach(function (c) {
                var icon = c.changeType === 'ADD' ? '&#10133;' : c.changeType === 'REMOVE' ? '&#10134;' : '&#9998;';
                var badgeClass = c.changeType === 'ADD' ? 'bg-success' : c.changeType === 'REMOVE' ? 'bg-danger' : 'bg-warning text-dark';
                html += '<li class="list-group-item">';
                html += '<span class="badge ' + badgeClass + ' me-1">' + icon + ' ' + escapeHtml(c.changeType) + '</span>';
                html += '<span class="badge bg-secondary me-1">' + escapeHtml(c.category) + '</span>';
                html += escapeHtml(c.description);
                if (c.beforeValue && c.afterValue) {
                    html += '<br><small class="text-muted">' + escapeHtml(c.beforeValue)
                        + ' → ' + escapeHtml(c.afterValue) + '</small>';
                }
                html += '</li>';
            });
            html += '</ul></div>';
        }

        // Level 3: Raw DSL Diff (collapsible)
        if (comparison.rawDslDiff) {
            html += '<div class="card mb-3">';
            html += '<div class="card-header">';
            html += '<a data-bs-toggle="collapse" href="#rawDiffCollapse" class="text-decoration-none">';
            html += '<strong>Raw DSL Diff</strong> (click to expand)</a></div>';
            html += '<div id="rawDiffCollapse" class="collapse">';
            html += '<div class="card-body"><pre class="mb-0 small">' + escapeHtml(comparison.rawDslDiff) + '</pre></div>';
            html += '</div></div>';
        }

        if (!html) {
            html = '<p class="text-muted">No differences found.</p>';
        }

        container.innerHTML = html;
    }

    var escapeHtml = TaxonomyUtils.escapeHtml;

    return {
        showDialog: showDialog,
        compareWithCommit: compareWithCommit,
        doCompare: doCompare,
        renderComparison: renderComparison
    };
}());
