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

    // Explicit contract with taxonomy-dsl SemanticChangeType. Unknown kinds are
    // errors, not silently relabelled as a supported modification.
    var semanticTypes = new Set([
        'ELEMENT_ADDED', 'ELEMENT_REMOVED', 'ELEMENT_TITLE_CHANGED',
        'ELEMENT_DESCRIPTION_CHANGED', 'ELEMENT_TYPE_CHANGED',
        'ELEMENT_TAXONOMY_CHANGED', 'ELEMENT_EXTENSIONS_CHANGED',
        'RELATION_ADDED', 'RELATION_REMOVED', 'RELATION_STATUS_CHANGED',
        'RELATION_CONFIDENCE_CHANGED', 'RELATION_PROVENANCE_CHANGED',
        'RELATION_EXTENSIONS_CHANGED'
    ]);

    function fromDocumentDiff(diff, left, right) {
        if (!diff || !diff.details || !Array.isArray(diff.semanticChanges)) {
            throw new Error('Invalid document comparison response');
        }
        var summary = {}, total = 0;
        ['Elements', 'Relations'].forEach(function (kind) {
            ['added', 'removed', 'changed'].forEach(function (change) {
                var key = change + kind;
                var count = diff[key];
                if (!Number.isSafeInteger(count) || count < 0 || !Array.isArray(diff.details[key])
                        || diff.details[key].length !== count) {
                    throw new Error('Incomplete document comparison response');
                }
                summary[kind.toLowerCase() + change[0].toUpperCase() + change.slice(1)] = count;
                total += count;
            });
        });
        if (diff.totalChanges !== total || diff.isEmpty !== (total === 0)
                || ((total === 0) !== (diff.semanticChanges.length === 0))) {
            throw new Error('Inconsistent document comparison response');
        }
        var changes = diff.semanticChanges.map(function (change) {
            if (!change || !['element', 'relation'].includes(change.entityKind)
                    || !semanticTypes.has(change.changeType)
                    || !change.changeType.startsWith(change.entityKind.toUpperCase() + '_')
                    || typeof change.entityId !== 'string'
                    || typeof change.description !== 'string') {
                throw new Error('Invalid semantic comparison change');
            }
            return { category: change.entityKind.toUpperCase(), id: change.entityId,
                changeType: change.changeType.endsWith('_ADDED') ? 'ADD'
                    : change.changeType.endsWith('_REMOVED') ? 'REMOVE' : 'MODIFY',
                description: change.description, beforeValue: change.beforeValue, afterValue: change.afterValue };
        });
        return { left: left, right: right, summary: summary, changes: changes, rawDslDiff: null };
    }

    var currentRawDslDiff = null;
    var compareRequest = 0;
    var branchesReady = false;
    var boundDialogs = new WeakSet();

    function selectionHint() {
        return t('compare.left.branch') + ' → ' + t('compare.right.branch') + ' — ' + t('compare.btn');
    }

    function setSidesDisabled(disabled) {
        ['compareLeftBranch', 'compareRightBranch', 'compareLeftCommit', 'compareRightCommit'].forEach(function (id) {
            var control = document.getElementById(id);
            if (control) control.disabled = disabled;
        });
    }

    function bindDialog(modal) {
        if (boundDialogs.has(modal)) return;
        boundDialogs.add(modal);
        modal.addEventListener('hide.bs.modal', function () {
            compareRequest++;
            branchesReady = false;
            currentRawDslDiff = null;
        });
        ['compareLeftBranch', 'compareRightBranch', 'compareLeftCommit', 'compareRightCommit'].forEach(function (id) {
            var control = document.getElementById(id);
            if (!control) return;
            control.addEventListener(control.tagName === 'SELECT' ? 'change' : 'input', function () {
                compareRequest++;
                currentRawDslDiff = null;
                var results = document.getElementById('contextCompareResults');
                if (results) results.textContent = selectionHint();
            });
        });
    }

    var t = TaxonomyI18n.t;
    var escapeHtml = TaxonomyUtils.escapeHtml;

    /**
     * Show the compare dialog, pre-filled with the current context.
     * Loads available branches into dropdown selectors.
     *
     * @param {object} currentContext — the current ContextRef
     */
    async function showDialog(currentContext, otherBranch, autoCompare, otherCommit) {
        var modal = document.getElementById('contextCompareModal');
        if (!modal) return;
        bindDialog(modal);
        setSidesDisabled(true);
        branchesReady = false;
        var request = ++compareRequest;
        currentRawDslDiff = null;
        var results = document.getElementById('contextCompareResults');
        var button = modal.querySelector('[onclick="TaxonomyContextCompare.doCompare()"]');
        if (button) button.disabled = true;
        if (results) results.textContent = t('compare.loading');
        bootstrap.Modal.getOrCreateInstance(modal).show();
        try {
            var data = await window.TaxonomyApiClient.getJson('/api/git/branches');
            var branches = data.branches || data;
            if (!Array.isArray(branches) || !branches.length || branches.some(function (branch) { return typeof branch !== 'string'; })) {
                throw new Error('No branches available');
            }
            if (request !== compareRequest) return;
            var left = (currentContext && currentContext.branch) || (branches.includes('draft') ? 'draft' : branches[0]);
            var right = otherBranch || (branches.includes('draft') ? 'draft' : branches[0]);
            if (!branches.includes(left) || !branches.includes(right)) throw new Error('Selected branch is unavailable');
            ['compareLeftBranch', 'compareRightBranch'].forEach(function (id, index) {
                var select = document.getElementById(id);
                if (!select) return;
                select.replaceChildren();
                branches.forEach(function (branch) {
                    var option = document.createElement('option'); option.value = branch; option.textContent = TaxonomyI18n.formatBranch(branch);
                    select.appendChild(option);
                });
                select.value = index === 0 ? left : right;
            });
            document.getElementById('compareLeftCommit').value = (currentContext && currentContext.commitId) || '';
            document.getElementById('compareRightCommit').value = otherCommit || '';
            branchesReady = true;
            setSidesDisabled(false);
            if (button) button.disabled = false;
            if (results) results.textContent = selectionHint();
            if (autoCompare) doCompare();
        } catch (error) {
            if (request === compareRequest && results) results.innerHTML = '<p class="text-danger">' + escapeHtml(t('compare.failed')) + '</p>';
        }
    }

    function compareWithCommit(commitId) {
        var context = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        showDialog(context, context ? context.branch : null, true, commitId);
    }

    /**
     * Execute a compare request and render results.
     *
     * @param {string} url — the compare API URL
     */
    async function executeCompare(url) {
        var request = ++compareRequest;
        var container = document.getElementById('contextCompareResults');
        if (container) container.innerHTML = '<div class="text-muted small">' + escapeHtml(t('compare.loading')) + '</div>';
        try {
            var comparison = await window.TaxonomyApiClient.getJson(url);
            if (!comparison || !comparison.summary || !Array.isArray(comparison.changes)) throw new Error('Invalid comparison');
            if (request === compareRequest) renderComparison('contextCompareResults', comparison);
        } catch (error) {
            if (request === compareRequest && container) container.innerHTML = '<p class="text-danger">' + escapeHtml(t('compare.failed')) + '</p>';
        }
    }

    /**
     * Trigger compare from the modal form.
     */
    function doCompare() {
        if (!branchesReady) return;
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

        currentRawDslDiff = comparison.rawDslDiff || null;

        var html = '';

        // Level 1: Summary Card
        var s = comparison.summary;
        if (s) {
            function contextLabel(ref, legacyBranch) {
                return TaxonomyI18n.formatBranch((ref && ref.branch) || legacyBranch || '')
                    + (ref && ref.commitId ? ' · ' + ref.commitId.substring(0, 7) : '');
            }
            var leftName = contextLabel(comparison.left, comparison.leftBranch);
            var rightName = contextLabel(comparison.right, comparison.rightBranch);

            html += '<div class="compare-summary-card">';
            html += '<div class="compare-title">' + escapeHtml(leftName) + ' \u2192 ' + escapeHtml(rightName) + '</div>';
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
            if (total === 0 && (!comparison.changes || comparison.changes.length === 0)) {
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
            var relationChanges = [];

            comparison.changes.forEach(function (c) {
                if (c.category === 'REQUIREMENT' || c.category === 'requirement') {
                    requirements.push(c);
                } else if (c.category === 'RELATION' || c.category === 'relation') {
                    relationChanges.push(c);
                } else if (c.changeType === 'ADD') added.push(c);
                else if (c.changeType === 'REMOVE') removed.push(c);
                else changed.push(c);
            });

            if (added.length || changed.length || removed.length) {
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
                        if (c.beforeValue != null && c.afterValue != null) {
                            html += '<div class="small text-muted mt-1">' + escapeHtml(String(c.beforeValue)) + ' \u2192 ' + escapeHtml(String(c.afterValue)) + '</div>';
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

            } else {
                html += '<p class="small text-muted" data-compare-section="elements">'
                    + escapeHtml(t('compare.filter.elements')) + ': '
                    + escapeHtml(t('compare.no.differences')) + '</p>';
            }

            // Relations section
            if (relationChanges.length > 0) {
                html += '<div data-compare-section="relations">';
                html += '<div class="compare-changes-grid">';
                html += '<div class="compare-column">';
                var relAdded = relationChanges.filter(function(c) { return c.changeType === 'ADD'; });
                html += '<div class="col-header col-added">\uD83D\uDFE2 ' + escapeHtml(t('compare.relations.column.added', relAdded.length)) + '</div>';
                html += '<div class="col-items">';
                if (relAdded.length === 0) {
                    html += '<div class="col-empty">' + escapeHtml(t('compare.relations.column.empty.added')) + '</div>';
                } else {
                    relAdded.forEach(function(c) {
                        html += '<div class="col-item item-added"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '<div class="compare-column">';
                var relChanged = relationChanges.filter(function(c) { return c.changeType !== 'ADD' && c.changeType !== 'REMOVE'; });
                html += '<div class="col-header col-changed">\uD83D\uDFE1 ' + escapeHtml(t('compare.relations.column.changed', relChanged.length)) + '</div>';
                html += '<div class="col-items">';
                if (relChanged.length === 0) {
                    html += '<div class="col-empty">' + escapeHtml(t('compare.relations.column.empty.changed')) + '</div>';
                } else {
                    relChanged.forEach(function(c) {
                        html += '<div class="col-item item-changed"><span>' + escapeHtml(c.description) + '</span>';
                        if (c.beforeValue != null && c.afterValue != null) {
                            html += '<div class="small text-muted">' + escapeHtml(String(c.beforeValue)) + ' → ' + escapeHtml(String(c.afterValue)) + '</div>';
                        }
                        html += '</div>';
                    });
                }
                html += '</div></div>';
                html += '<div class="compare-column">';
                var relRemoved = relationChanges.filter(function(c) { return c.changeType === 'REMOVE'; });
                html += '<div class="col-header col-removed">\uD83D\uDD34 ' + escapeHtml(t('compare.relations.column.removed', relRemoved.length)) + '</div>';
                html += '<div class="col-items">';
                if (relRemoved.length === 0) {
                    html += '<div class="col-empty">' + escapeHtml(t('compare.relations.column.empty.removed')) + '</div>';
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
                var reqAdded = requirements.filter(function(c) { return c.changeType === 'ADD'; });
                html += '<div class="col-header col-added">\uD83D\uDFE2 ' + escapeHtml(t('compare.requirements.column.added', reqAdded.length)) + '</div>';
                html += '<div class="col-items">';
                if (reqAdded.length === 0) {
                    html += '<div class="col-empty">' + escapeHtml(t('compare.requirements.column.empty.added')) + '</div>';
                } else {
                    reqAdded.forEach(function(c) {
                        html += '<div class="col-item item-added"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '<div class="compare-column">';
                var reqChanged = requirements.filter(function(c) { return c.changeType !== 'ADD' && c.changeType !== 'REMOVE'; });
                html += '<div class="col-header col-changed">\uD83D\uDFE1 ' + escapeHtml(t('compare.requirements.column.changed', reqChanged.length)) + '</div>';
                html += '<div class="col-items">';
                if (reqChanged.length === 0) {
                    html += '<div class="col-empty">' + escapeHtml(t('compare.requirements.column.empty.changed')) + '</div>';
                } else {
                    reqChanged.forEach(function(c) {
                        html += '<div class="col-item item-changed"><span>' + escapeHtml(c.description) + '</span></div>';
                    });
                }
                html += '</div></div>';
                html += '<div class="compare-column">';
                var reqRemoved = requirements.filter(function(c) { return c.changeType === 'REMOVE'; });
                html += '<div class="col-header col-removed">\uD83D\uDD34 ' + escapeHtml(t('compare.requirements.column.removed', reqRemoved.length)) + '</div>';
                html += '<div class="col-items">';
                if (reqRemoved.length === 0) {
                    html += '<div class="col-empty">' + escapeHtml(t('compare.requirements.column.empty.removed')) + '</div>';
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
            html += '<div class="card-header d-flex align-items-center">';
            html += '<a data-bs-toggle="collapse" href="#rawDiffCollapse" class="text-decoration-none flex-grow-1">';
            html += '\u25B8 <strong>' + escapeHtml(t('compare.dsl.diff')) + '</strong> ' + escapeHtml(t('compare.dsl.diff.expert')) + '</a>';
            html += '<button class="btn btn-sm btn-outline-info ms-2" onclick="TaxonomyContextCompare.toggleSideBySide()">\u2194 Side-by-Side</button>';
            html += '</div>';
            html += '<div id="rawDiffCollapse" class="collapse">';
            html += '<div class="card-body">';
            html += '<div id="dslTextDiffContainer"><pre class="mb-0 small" style="white-space:pre-wrap;">' + renderColoredDiff(comparison.rawDslDiff) + '</pre></div>';
            html += '<div id="dslMergeViewContainer" style="display:none;"></div>';
            html += '</div>';
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

    /**
     * Reconstruct left and right document texts from a unified diff.
     *
     * @param {string} diffText — unified diff output
     * @returns {{ left: string, right: string }}
     */
    function parseDiffSides(diffText) {
        if (!diffText) return { left: '', right: '' };
        var leftLines = [];
        var rightLines = [];
        diffText.split('\n').forEach(function (line) {
            if (line.startsWith('@@') || line.startsWith('---') || line.startsWith('+++')) {
                return; // skip diff headers
            }
            if (line.startsWith('-')) {
                leftLines.push(line.substring(1));
            } else if (line.startsWith('+')) {
                rightLines.push(line.substring(1));
            } else if (line.startsWith(' ')) {
                // Context line with standard space prefix
                leftLines.push(line.substring(1));
                rightLines.push(line.substring(1));
            } else {
                // Unprefixed line (e.g. empty line in diff output)
                leftLines.push(line);
                rightLines.push(line);
            }
        });
        return { left: leftLines.join('\n'), right: rightLines.join('\n') };
    }

    /**
     * Toggle the side-by-side CodeMirror merge view for a raw DSL diff.
     *
     * @param {string} diffText — the raw unified diff
     */
    function toggleSideBySide(diffText) {
        var rawDiff = diffText || currentRawDslDiff;
        var mergeContainer = document.getElementById('dslMergeViewContainer');
        var textDiff = document.getElementById('dslTextDiffContainer');
        if (!mergeContainer || !textDiff || !rawDiff) return;

        var isActive = mergeContainer.style.display !== 'none';
        if (isActive) {
            mergeContainer.style.display = 'none';
            mergeContainer.innerHTML = '';
            textDiff.style.display = '';
            return;
        }

        var sides = parseDiffSides(rawDiff);
        textDiff.style.display = 'none';
        mergeContainer.style.display = '';

        import('/js/shared/taxonomy-dsl-codemirror.mjs').then(function (mod) {
            mod.createMergeView(mergeContainer, sides.left, sides.right);
        });
    }

    return {
        fromDocumentDiff: fromDocumentDiff,
        showDialog: showDialog,
        compareWithCommit: compareWithCommit,
        doCompare: doCompare,
        renderComparison: renderComparison,
        toggleSideBySide: toggleSideBySide
    };
}());
