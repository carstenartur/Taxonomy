/**
 * taxonomy-history-search.js — Versioned History Search
 *
 * Provides UI for searching architecture history with version-context
 * metadata: lineage info, recency badges, and context-open actions.
 *
 * @module TaxonomyHistorySearch
 */
window.TaxonomyHistorySearch = (function () {
    'use strict';

    /**
     * Execute a versioned search and render results into the given container.
     *
     * @param {string} query — search query
     * @param {string} containerId — DOM ID for results container
     * @param {string} [branch='draft'] — current branch for lineage detection
     */
    function search(query, containerId, branch) {
        branch = branch || 'draft';
        var url = '/api/dsl/history/search-versioned?query=' + encodeURIComponent(query)
            + '&currentBranch=' + encodeURIComponent(branch);

        fetch(url)
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (results) {
                renderResults(containerId, results, branch);
            })
            .catch(function () {
                var container = document.getElementById(containerId);
                if (container) container.innerHTML = '<p class="text-muted">Search failed.</p>';
            });
    }

    /**
     * Render versioned search results.
     *
     * @param {string} containerId — DOM ID
     * @param {Array} results — list of VersionedSearchResult
     * @param {string} currentBranch — the active branch
     */
    function renderResults(containerId, results, currentBranch) {
        var container = document.getElementById(containerId);
        if (!container) return;

        if (!results || results.length === 0) {
            container.innerHTML = '<p class="text-muted">No results found.</p>';
            return;
        }

        var html = '<div class="list-group">';
        results.forEach(function (r) {
            var commitShort = r.commitId ? r.commitId.substring(0, 7) : '???';
            var date = r.timestamp ? new Date(r.timestamp).toLocaleDateString() : '';

            html += '<div class="list-group-item">';
            html += '<div class="d-flex justify-content-between align-items-start">';
            html += '<div>';
            html += '<span class="badge bg-secondary me-1">' + escapeHtml(r.branch) + '</span>';
            html += '<code class="me-1">' + escapeHtml(commitShort) + '</code>';
            html += '<small class="text-muted">' + escapeHtml(date) + '</small>';

            // Badges
            if (r.latestOverall) {
                html += ' <span class="badge bg-success">newest</span>';
            }
            if (r.onCurrentLineage) {
                html += ' <span class="badge bg-primary">current branch</span>';
            }
            if (!r.latestOnCurrentBranch && r.onCurrentLineage) {
                html += ' <span class="badge bg-warning text-dark">older</span>';
            }

            html += '</div>';

            // Action buttons
            html += '<div class="btn-group btn-group-sm">';
            if (r.contextOpenActions && r.contextOpenActions.indexOf('OPEN_READ_ONLY') >= 0) {
                html += '<button class="btn btn-outline-secondary" onclick="TaxonomyHistorySearch.openReadOnly(\'' + escapeHtml(r.branch) + '\',\'' + escapeHtml(r.commitId) + '\')">&#128065; View</button>';
            }
            if (r.contextOpenActions && r.contextOpenActions.indexOf('SWITCH') >= 0) {
                html += '<button class="btn btn-outline-primary" onclick="TaxonomyHistorySearch.switchTo(\'' + escapeHtml(r.branch) + '\',\'' + escapeHtml(r.commitId) + '\')">Switch</button>';
            }
            if (r.contextOpenActions && r.contextOpenActions.indexOf('CREATE_VARIANT') >= 0) {
                html += '<button class="btn btn-outline-success" onclick="TaxonomyHistorySearch.createVariant(\'' + escapeHtml(r.branch) + '\')">Variant</button>';
            }
            if (r.contextOpenActions && r.contextOpenActions.indexOf('COMPARE') >= 0) {
                html += '<button class="btn btn-outline-info" onclick="TaxonomyHistorySearch.compare(\'' + escapeHtml(r.commitId) + '\')">Compare</button>';
            }
            html += '</div>';

            html += '</div>';

            // Match info
            if (r.matchedText) {
                html += '<p class="mb-0 mt-1 small text-muted">' + escapeHtml(r.matchedText) + '</p>';
            }
            if (r.matchedElementId) {
                html += '<small class="text-info">Matched: ' + escapeHtml(r.matchedElementId) + '</small>';
            }

            html += '</div>';
        });
        html += '</div>';
        container.innerHTML = html;
    }

    function openReadOnly(branch, commitId) {
        fetch('/api/context/open?branch=' + encodeURIComponent(branch)
            + '&commitId=' + encodeURIComponent(commitId)
            + '&readOnly=true', { method: 'POST' })
            .then(function () {
                if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
            });
    }

    function switchTo(branch, commitId) {
        fetch('/api/context/open?branch=' + encodeURIComponent(branch)
            + '&commitId=' + encodeURIComponent(commitId)
            + '&readOnly=false', { method: 'POST' })
            .then(function () {
                if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
            });
    }

    function createVariant(branch) {
        if (window.TaxonomyContextBar) {
            window.TaxonomyContextBar.showVariantDialog();
        }
    }

    function compare(commitId) {
        if (window.TaxonomyContextCompare) {
            window.TaxonomyContextCompare.compareWithCommit(commitId);
        }
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    return {
        search: search,
        openReadOnly: openReadOnly,
        switchTo: switchTo,
        createVariant: createVariant,
        compare: compare
    };
}());
