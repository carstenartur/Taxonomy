/**
 * taxonomy-context-compare.js — Visual comparison
 *
 * Provides a three-level comparison between two architecture contexts:
 * 1. Summary card — counts with visual indicators
 * 2. Three-column grid — Added / Changed / Removed
 * 3. Raw DSL Diff — collapsible expert mode with colored diff
 *
 * @module TaxonomyContextCompare
 */
window.TaxonomyContextCompare = (function () {
    'use strict';

    var t = TaxonomyI18n.t;
    var escapeHtml = TaxonomyUtils.escapeHtml;

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

        populateBranchSelectors(function () {
            if (leftBranch && currentContext) {
                leftBranch.value = currentContext.branch || 'draft';
            }
            if (rightBranch) {
                rightBranch.value = 'draft';
            }
        });

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
        var container = document.getElementById('contextCompareResults');
        if (container) container.innerHTML = '<div class="text-muted small">' + escapeHtml(t('compare.loading')) + '</div>';

        fetch(url)
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (comparison) {
                if (comparison) {
                    renderComparison('contextCompareResults', comparison);
                }
            })
            .catch(function () {
                if (container) container.innerHTML = '<p class="text-danger">' + escapeHtml(t('compare.failed')) + '</p>';
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
     * Render the comparison results with visual cards and columns.
     *
     * @param {string} containerId — DOM ID for results
     * @param {object} comparison — ContextComparison from API
     */
    function renderComparison(containerId, comparison) {
        var container = document.getElementById(containerId);
        if (!container) return;

        var html = '';

        // Level 1: Summary Card
        var s = comparison.summary;
        if (s) {
            var leftName = TaxonomyI18n.formatBranch(comparison.leftBranch || '');
            var rightName = TaxonomyI18n.formatBranch(comparison.rightBranch || '');

            html += '<div class="compare-summary-card">';
            html += '<div class="compare-title">' + escapeHtml(leftName) + ' \u2194 ' + escapeHtml(rightName) + '</div>';
            html += '<div class="mb-2 small text-muted">' + escapeHtml(t('compare.summary')) + '</div>';
            html += '<div class="compare-stats">';

            if (s.elementsAdded > 0) {
                html += '<span class="compare-stat text-success">\uD83D\uDFE2 ' + escapeHtml(t('compare.elements.added', s.elementsAdded)) + '</span>';
            }
            if (s.elementsRemoved > 0) {
                html += '<span class="compare-stat text-danger">\uD83D\uDD34 ' + escapeHtml(t('compare.elements.removed', s.elementsRemoved)) + '</span>';
            }
            if (s.elementsChanged > 0) {
                html += '<span class="compare-stat text-warning">\uD83D\uDFE1 ' + escapeHtml(t('compare.elements.changed', s.elementsChanged)) + '</span>';
            }
            if (s.relationsAdded > 0) {
                html += '<span class="compare-stat text-success">' + escapeHtml(t('compare.relations.added', s.relationsAdded)) + '</span>';
            }
            if (s.relationsRemoved > 0) {
                html += '<span class="compare-stat text-danger">' + escapeHtml(t('compare.relations.removed', s.relationsRemoved)) + '</span>';
            }
            if (s.relationsChanged > 0) {
                html += '<span class="compare-stat text-warning">' + escapeHtml(t('compare.relations.changed', s.relationsChanged)) + '</span>';
            }

            var total = (s.elementsAdded || 0) + (s.elementsRemoved || 0) + (s.elementsChanged || 0)
                + (s.relationsAdded || 0) + (s.relationsRemoved || 0) + (s.relationsChanged || 0);
            if (total === 0) {
                html += '<span class="compare-stat text-muted">' + escapeHtml(t('compare.no.differences')) + '</span>';
            }

            html += '</div>'; // compare-stats
            html += '</div>'; // compare-summary-card
        }

        // Level 2: Three-column grid for changes
        if (comparison.changes && comparison.changes.length > 0) {
            var added = [];
            var changed = [];
            var removed = [];
            var requirements = [];

            comparison.changes.forEach(function (c) {
                if (c.category === 'REQUIREMENT' || c.category === 'requirement') {
                    requirements.push(c);
                } else if (c.changeType === 'ADD') added.push(c);
                else if (c.changeType === 'REMOVE') removed.push(c);
                else changed.push(c);
            });

            html += '<div data-compare-section="elements">';
            html += '<div class="compare-changes-grid">';

            // Added column
            html += '<div class="compare-column">';
            html += '<div class="col-header col-added">\uD83D\uDFE2 ' + escapeHtml(t('compare.column.added', added.length)) + '</div>';
            html += '<div class="col-items">';
            if (added.length === 0) {
                html += '<div class="col-empty">' + escapeHtml(t('compare.column.empty.added')) + '</div>';
            } else {
                added.forEach(function (c) {
                    html += '<div class="col-item item-added">';
                    html += '<span class="badge bg-secondary me-1" style="font-size:0.65rem;">' + escapeHtml(c.category) + '</span>';
                    html += '<span>' + escapeHtml(c.description) + '</span>';
                    html += '</div>';
                });
            }
            html += '</div></div>';

            // Changed column
            html += '<div class="compare-column">';
            html += '<div class="col-header col-changed">\uD83D\uDFE1 ' + escapeHtml(t('compare.column.changed', changed.length)) + '</div>';
            html += '<div class="col-items">';
            if (changed.length === 0) {
                html += '<div class="col-empty">' + escapeHtml(t('compare.column.empty.changed')) + '</div>';
            } else {
                changed.forEach(function (c) {
                    html += '<div class="col-item item-changed">';
                    html += '<span class="badge bg-secondary me-1" style="font-size:0.65rem;">' + escapeHtml(c.category) + '</span>';
                    html += '<span>' + escapeHtml(c.description) + '</span>';
                    if (c.beforeValue && c.afterValue) {
                        html += '<div class="small text-muted mt-1">' + escapeHtml(c.beforeValue) + ' \u2192 ' + escapeHtml(c.afterValue) + '</div>';
                    }
                    html += '</div>';
                });
            }
            html += '</div></div>';

            // Removed column
            html += '<div class="compare-column">';
            html += '<div class="col-header col-removed">\uD83D\uDD34 ' + escapeHtml(t('compare.column.removed', removed.length)) + '</div>';
            html += '<div class="col-items">';
            if (removed.length === 0) {
                html += '<div class="col-empty">' + escapeHtml(t('compare.column.empty.removed')) + '</div>';
            } else {
                removed.forEach(function (c) {
                    html += '<div class="col-item item-removed">';
                    html += '<span class="badge bg-secondary me-1" style="font-size:0.65rem;">' + escapeHtml(c.category) + '</span>';
                    html += '<span>' + escapeHtml(c.description) + '</span>';
                    html += '</div>';
                });
            }
            html += '</div></div>';

            html += '</div>'; // compare-changes-grid
            html += '</div>'; // data-compare-section="elements"

            // Relations section
            if (comparison.relationChanges && comparison.relationChanges.length > 0) {
                html += '<div data-compare-section="relations">';
                html += '<div class="compare-changes-grid">';
                html += '<div class="compare-column">';
                html += '<div class="col-header col-added">\uD83D\uDFE2 Relations Added</div>';
                html += '<div class="col-items">';
                var relAdded = comparison.relationChanges.filter(function(c) { return c.changeType === 'ADD'; });
                if (relAdded.length === 0) {
                    html += '<div class="col-empty">No added relations</div>';
                } else {
                    relAdded.forEach(function(c) {
                        html += '<div class="col-item item-added"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '<div class="compare-column">';
                html += '<div class="col-header col-changed">\uD83D\uDFE1 Relations Changed</div>';
                html += '<div class="col-items">';
                var relChanged = comparison.relationChanges.filter(function(c) { return c.changeType !== 'ADD' && c.changeType !== 'REMOVE'; });
                if (relChanged.length === 0) {
                    html += '<div class="col-empty">No changed relations</div>';
                } else {
                    relChanged.forEach(function(c) {
                        html += '<div class="col-item item-changed"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '<div class="compare-column">';
                html += '<div class="col-header col-removed">\uD83D\uDD34 Relations Removed</div>';
                html += '<div class="col-items">';
                var relRemoved = comparison.relationChanges.filter(function(c) { return c.changeType === 'REMOVE'; });
                if (relRemoved.length === 0) {
                    html += '<div class="col-empty">No removed relations</div>';
                } else {
                    relRemoved.forEach(function(c) {
                        html += '<div class="col-item item-removed"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '</div>'; // compare-changes-grid
                html += '</div>'; // data-compare-section="relations"
            }

            // Requirements section
            if (requirements.length > 0) {
                html += '<div data-compare-section="requirements">';
                html += '<div class="compare-changes-grid">';
                html += '<div class="compare-column">';
                html += '<div class="col-header col-added">\uD83D\uDFE2 Requirements Added</div>';
                html += '<div class="col-items">';
                var reqAdded = requirements.filter(function(c) { return c.changeType === 'ADD'; });
                if (reqAdded.length === 0) {
                    html += '<div class="col-empty">No added requirements</div>';
                } else {
                    reqAdded.forEach(function(c) {
                        html += '<div class="col-item item-added"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '<div class="compare-column">';
                html += '<div class="col-header col-changed">\uD83D\uDFE1 Requirements Changed</div>';
                html += '<div class="col-items">';
                var reqChanged = requirements.filter(function(c) { return c.changeType !== 'ADD' && c.changeType !== 'REMOVE'; });
                if (reqChanged.length === 0) {
                    html += '<div class="col-empty">No changed requirements</div>';
                } else {
                    reqChanged.forEach(function(c) {
                        html += '<div class="col-item item-changed"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '<div class="compare-column">';
                html += '<div class="col-header col-removed">\uD83D\uDD34 Requirements Removed</div>';
                html += '<div class="col-items">';
                var reqRemoved = requirements.filter(function(c) { return c.changeType === 'REMOVE'; });
                if (reqRemoved.length === 0) {
                    html += '<div class="col-empty">No removed requirements</div>';
                } else {
                    reqRemoved.forEach(function(c) {
                        html += '<div class="col-item item-removed"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '</div>'; // compare-changes-grid
                html += '</div>'; // data-compare-section="requirements"
            }
        }

        // Level 3: Raw DSL Diff (collapsible expert mode)
        if (comparison.rawDslDiff) {
            html += '<div class="card mb-3">';
            html += '<div class="card-header">';
            html += '<a data-bs-toggle="collapse" href="#rawDiffCollapse" class="text-decoration-none">';
            html += '\u25B8 <strong>' + escapeHtml(t('compare.dsl.diff')) + '</strong> ' + escapeHtml(t('compare.dsl.diff.expert')) + '</a></div>';
            html += '<div id="rawDiffCollapse" class="collapse">';
            html += '<div class="card-body"><pre class="mb-0 small" style="white-space:pre-wrap;">' + renderColoredDiff(comparison.rawDslDiff) + '</pre></div>';
            html += '</div></div>';
        }

        if (!html) {
            html = '<p class="text-muted">' + escapeHtml(t('compare.no.diff')) + '</p>';
        }

        container.innerHTML = html;
    }

    /**
     * Render a colored unified diff.
     */
    function renderColoredDiff(diffText) {
        if (!diffText) return '';
        return diffText.split('\n').map(function (line) {
            var escaped = escapeHtml(line);
            if (line.startsWith('+')) {
                return '<span style="color:#198754;background:rgba(25,135,84,0.08);">' + escaped + '</span>';
            }
            if (line.startsWith('-')) {
                return '<span style="color:#dc3545;background:rgba(220,53,69,0.08);">' + escaped + '</span>';
            }
            if (line.startsWith('@')) {
                return '<span style="color:#0d6efd;">' + escaped + '</span>';
            }
            return escaped;
        }).join('\n');
    }

    return {
        showDialog: showDialog,
        compareWithCommit: compareWithCommit,
        doCompare: doCompare,
        renderComparison: renderComparison
    };
}());
