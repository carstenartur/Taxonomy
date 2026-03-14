/**
 * Action Guards — checks repository state and disables/warns UI buttons.
 *
 * <p>Uses the already-fetched state from taxonomy-git-status.js polling
 * (via guardAll) to apply guards without extra API calls.
 *
 * <p>Buttons opt-in via data-guard attributes:
 *   data-guard="commit|merge|cherry-pick|import|export|analyze|materialize"
 *
 * <p>Guard rules:
 *   - operationInProgress blocks: commit, merge, cherry-pick, import, materialize
 *   - projectionStale warns: export, accept-hypothesis
 *   - indexStale warns: analyze
 */
window.TaxonomyActionGuards = (function () {
    'use strict';

    // Guard rule definitions: which operations are blocked / warned
    var BLOCK_ON_OPERATION = ['commit', 'merge', 'cherry-pick', 'import', 'materialize'];
    var WARN_ON_PROJECTION_STALE = ['export', 'accept-hypothesis'];
    var WARN_ON_INDEX_STALE = ['analyze'];

    // Data attribute used to store the original title before guards overwrite it
    var ORIG_TITLE_ATTR = 'data-guard-original-title';
    // Data attribute set when a guard blocks the button (so we only re-enable our own blocks)
    var GUARD_BLOCKED_ATTR = 'data-guard-blocked';

    function escapeHtml(s) {
        if (!s) return '';
        return s.replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    /**
     * Apply guard state to all buttons with [data-guard] attributes.
     *
     * @param {object} state — RepositoryState from /api/git/state
     */
    function guardAll(state) {
        if (!state) return;

        var buttons = document.querySelectorAll('[data-guard]');
        buttons.forEach(function (btn) {
            var guardType = btn.getAttribute('data-guard');
            applyGuard(btn, guardType, state);
        });
    }

    /**
     * Store the original title once so it can be restored later.
     */
    function saveOriginalTitle(btn) {
        if (!btn.hasAttribute(ORIG_TITLE_ATTR)) {
            btn.setAttribute(ORIG_TITLE_ATTR, btn.getAttribute('title') || '');
        }
    }

    /**
     * Restore the original title that was present before guards were applied.
     */
    function restoreOriginalTitle(btn) {
        if (btn.hasAttribute(ORIG_TITLE_ATTR)) {
            var original = btn.getAttribute(ORIG_TITLE_ATTR);
            if (original) {
                btn.setAttribute('title', original);
            } else {
                btn.removeAttribute('title');
            }
        }
    }

    /**
     * Apply guard logic to a single button.
     */
    function applyGuard(btn, guardType, state) {
        // Save the original title on first encounter
        saveOriginalTitle(btn);

        // Clear previous guard state
        removeWarningBadge(btn);

        // If we previously blocked this button, unblock it first
        if (btn.hasAttribute(GUARD_BLOCKED_ATTR)) {
            btn.classList.remove('guard-blocked');
            btn.disabled = false;
            btn.removeAttribute(GUARD_BLOCKED_ATTR);
        }

        // Restore original title (may be overwritten below by a guard message)
        restoreOriginalTitle(btn);

        // Check blocks: operation in progress blocks write operations
        if (state.operationInProgress && BLOCK_ON_OPERATION.indexOf(guardType) !== -1) {
            btn.classList.add('guard-blocked');
            btn.setAttribute('title',
                'Blocked: ' + escapeHtml(state.operationKind || 'operation') + ' in progress — complete it first');
            btn.disabled = true;
            btn.setAttribute(GUARD_BLOCKED_ATTR, 'true');
            return;
        }

        // Check warnings
        if (state.projectionStale && WARN_ON_PROJECTION_STALE.indexOf(guardType) !== -1) {
            addWarningBadge(btn, 'Projection stale');
            btn.setAttribute('title', 'Warning: DB projection is stale — data may not reflect latest Git changes');
        }

        if (state.indexStale && WARN_ON_INDEX_STALE.indexOf(guardType) !== -1) {
            addWarningBadge(btn, 'Index stale');
            btn.setAttribute('title', 'Warning: Search index outdated — results may be incomplete');
        }

        if (state.projectionStale && guardType === 'materialize') {
            addWarningBadge(btn, 'Re-materialization needed');
        }
    }

    /**
     * Add a yellow warning badge next to a button.
     */
    function addWarningBadge(btn, text) {
        // Check if badge already exists
        var existing = btn.parentNode.querySelector('.guard-warning-badge[data-guard-for="' + btn.id + '"]');
        if (existing) {
            existing.textContent = '\u26A0\uFE0F ' + text;
            return;
        }
        var badge = document.createElement('span');
        badge.className = 'guard-warning-badge';
        badge.setAttribute('data-guard-for', btn.id || '');
        badge.textContent = '\u26A0\uFE0F ' + text;
        btn.parentNode.insertBefore(badge, btn.nextSibling);
    }

    /**
     * Remove warning badge for a button.
     */
    function removeWarningBadge(btn) {
        var badge = btn.parentNode.querySelector('.guard-warning-badge[data-guard-for="' + btn.id + '"]');
        if (badge) {
            badge.parentNode.removeChild(badge);
        }
    }

    /**
     * Check a single operation via the /api/dsl/operation/check endpoint.
     *
     * @param {string} buttonId — DOM ID of the button
     * @param {string} operationType — e.g. "commit", "merge"
     * @param {string} [branch='draft'] — branch name
     */
    function guard(buttonId, operationType, branch) {
        branch = branch || 'draft';
        var btn = document.getElementById(buttonId);
        if (!btn) return;

        fetch('/api/dsl/operation/check?branch=' + encodeURIComponent(branch) +
            '&operationType=' + encodeURIComponent(operationType))
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (check) {
                if (!check) return;
                if (check.blocks && check.blocks.length > 0) {
                    btn.classList.add('guard-blocked');
                    btn.disabled = true;
                    btn.setAttribute('title', check.blocks.join('; '));
                } else {
                    btn.classList.remove('guard-blocked');
                    btn.disabled = false;
                    if (check.warnings && check.warnings.length > 0) {
                        addWarningBadge(btn, check.warnings[0]);
                        btn.setAttribute('title', check.warnings.join('; '));
                    }
                }
            })
            .catch(function () {
                // silently ignore
            });
    }

    /**
     * Show a merge/cherry-pick preview dialog before executing the operation.
     *
     * @param {string} fromBranch — source branch
     * @param {string} intoBranch — target branch
     * @param {function} onProceed — callback if user confirms
     */
    function showMergePreview(fromBranch, intoBranch, onProceed) {
        fetch('/api/dsl/merge/preview?from=' + encodeURIComponent(fromBranch) +
            '&into=' + encodeURIComponent(intoBranch))
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (preview) {
                if (!preview) {
                    if (confirm('Could not load merge preview. Proceed anyway?')) {
                        onProceed();
                    }
                    return;
                }
                if (preview.conflictExpected) {
                    alert('\u26A0\uFE0F Merge conflict expected!\n\n' +
                        'The merge from "' + fromBranch + '" into "' + intoBranch +
                        '" would fail due to conflicts.\nResolve conflicts first.');
                } else if (preview.fastForward) {
                    if (confirm('\u2705 Fast-forward merge possible.\n\nNo conflicts. Proceed?')) {
                        onProceed();
                    }
                } else {
                    if (confirm('Merge preview: ' + (preview.commitCount || '?') +
                        ' commit(s) to merge from "' + fromBranch + '" into "' + intoBranch +
                        '".\n\nProceed?')) {
                        onProceed();
                    }
                }
            })
            .catch(function () {
                if (confirm('Merge preview unavailable. Proceed anyway?')) {
                    onProceed();
                }
            });
    }

    /**
     * Show a cherry-pick preview dialog before executing the operation.
     *
     * @param {string} commitId — commit SHA to cherry-pick
     * @param {string} targetBranch — target branch
     * @param {function} onProceed — callback if user confirms
     */
    function showCherryPickPreview(commitId, targetBranch, onProceed) {
        fetch('/api/dsl/cherry-pick/preview?commitId=' + encodeURIComponent(commitId) +
            '&targetBranch=' + encodeURIComponent(targetBranch))
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (preview) {
                if (!preview) {
                    if (confirm('Could not load cherry-pick preview. Proceed anyway?')) {
                        onProceed();
                    }
                    return;
                }
                if (preview.conflictExpected) {
                    alert('\u26A0\uFE0F Cherry-pick would cause conflicts!\n\n' +
                        'Commit ' + commitId.substring(0, 7) + ' cannot be cleanly applied to "' +
                        targetBranch + '".');
                } else {
                    if (confirm('\u2705 Cherry-pick looks clean.\n\nApply commit ' +
                        commitId.substring(0, 7) + ' onto "' + targetBranch + '"?')) {
                        onProceed();
                    }
                }
            })
            .catch(function () {
                if (confirm('Cherry-pick preview unavailable. Proceed anyway?')) {
                    onProceed();
                }
            });
    }

    return {
        guardAll: guardAll,
        guard: guard,
        showMergePreview: showMergePreview,
        showCherryPickPreview: showCherryPickPreview
    };
}());
