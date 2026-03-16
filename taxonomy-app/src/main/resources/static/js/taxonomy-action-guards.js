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
    var BLOCK_ON_READ_ONLY = ['commit', 'merge', 'cherry-pick', 'import', 'materialize'];
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

        // Check blocks: read-only context blocks write operations
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        if (ctx && ctx.mode === 'READ_ONLY' && BLOCK_ON_READ_ONLY.indexOf(guardType) !== -1) {
            btn.classList.add('guard-blocked');
            btn.setAttribute('title', 'Blocked: Context is read-only — return to origin or switch context first');
            btn.disabled = true;
            btn.setAttribute(GUARD_BLOCKED_ATTR, 'true');
            return;
        }

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
                    showMergePreviewModal('Could not load merge preview.', 'warning', fromBranch, intoBranch, onProceed);
                    return;
                }
                if (!preview.canMerge) {
                    // Distinguish real conflicts from non-conflict failures
                    // (e.g. branch not found, errors) by checking for null commits
                    var hasWarnings = preview.warnings && preview.warnings.length > 0;
                    var isBranchError = !preview.fromCommit || !preview.intoCommit;

                    if (isBranchError) {
                        // Non-conflict failure — show warning, not conflict modal
                        var warnMsg = hasWarnings ? preview.warnings.join('; ') :
                            'Merge from "' + fromBranch + '" into "' + intoBranch + '" cannot proceed.';
                        showMergePreviewModal(warnMsg, 'danger', fromBranch, intoBranch, null);
                    } else if (window.TaxonomyMergeResolution) {
                        window.TaxonomyMergeResolution.showMergeConflict(fromBranch, intoBranch);
                    } else {
                        showMergePreviewModal(
                            '\u26A0\uFE0F Merge conflict expected! The merge from "' + fromBranch +
                            '" into "' + intoBranch + '" would fail due to conflicts.',
                            'danger', fromBranch, intoBranch, null);
                    }
                } else if (preview.alreadyMerged) {
                    showMergePreviewModal(
                        'Already merged: "' + fromBranch + '" is already an ancestor of "' + intoBranch + '".',
                        'info', fromBranch, intoBranch, null);
                } else if (preview.fastForwardable) {
                    showMergePreviewModal(
                        '\u2705 Fast-forward merge possible. No conflicts detected.',
                        'success', fromBranch, intoBranch, onProceed);
                } else {
                    showMergePreviewModal(
                        'Merge preview: commits to merge from "' + fromBranch + '" into "' + intoBranch + '". No conflicts detected.',
                        'success', fromBranch, intoBranch, onProceed);
                }
            })
            .catch(function () {
                showMergePreviewModal('Merge preview unavailable.', 'warning', fromBranch, intoBranch, onProceed);
            });
    }

    /**
     * Display the merge preview in a Bootstrap modal.
     */
    function showMergePreviewModal(message, type, fromBranch, intoBranch, onProceed) {
        var modalEl = document.getElementById('mergePreviewModal');
        var contentEl = document.getElementById('mergePreviewContent');
        var proceedBtn = document.getElementById('mergePreviewProceedBtn');

        if (!modalEl || !contentEl) {
            // Fallback to confirm if modal not available
            if (onProceed && confirm(message + '\n\nProceed?')) {
                onProceed();
            }
            return;
        }

        var alertClass = type === 'danger' ? 'alert-danger' :
                         type === 'warning' ? 'alert-warning' :
                         type === 'info' ? 'alert-info' : 'alert-success';

        contentEl.innerHTML = '<div class="alert ' + alertClass + ' mb-0">' +
            '<strong>From:</strong> ' + escapeHtml(fromBranch) +
            ' <strong>→ Into:</strong> ' + escapeHtml(intoBranch) +
            '<hr class="my-2">' + escapeHtml(message) + '</div>';

        if (proceedBtn) {
            if (onProceed) {
                proceedBtn.classList.remove('d-none');
                proceedBtn.onclick = function () {
                    var modal = bootstrap.Modal.getInstance(modalEl);
                    if (modal) modal.hide();
                    onProceed();
                };
            } else {
                proceedBtn.classList.add('d-none');
                proceedBtn.onclick = null;
            }
        }

        if (typeof bootstrap !== 'undefined') {
            var modal = new bootstrap.Modal(modalEl);
            modal.show();
        }
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
                    showCherryPickPreviewModal('Could not load cherry-pick preview.',
                        'warning', commitId, targetBranch, onProceed);
                    return;
                }
                if (!preview.canCherryPick) {
                    // Distinguish real conflicts from non-conflict failures
                    var hasWarnings = preview.warnings && preview.warnings.length > 0;
                    var isInputError = !preview.targetCommit;

                    if (isInputError) {
                        // Non-conflict failure — show warning, not conflict modal
                        var warnMsg = hasWarnings ? preview.warnings.join('; ') :
                            'Cherry-pick of ' + commitId.substring(0, 7) + ' cannot proceed.';
                        showCherryPickPreviewModal(warnMsg, 'danger', commitId, targetBranch, null);
                    } else if (window.TaxonomyMergeResolution) {
                        window.TaxonomyMergeResolution.showCherryPickConflict(commitId, targetBranch);
                    } else {
                        showCherryPickPreviewModal(
                            '\u26A0\uFE0F Cherry-pick would cause conflicts! Commit ' +
                            commitId.substring(0, 7) + ' cannot be cleanly applied to "' + targetBranch + '".',
                            'danger', commitId, targetBranch, null);
                    }
                } else {
                    showCherryPickPreviewModal(
                        '\u2705 Cherry-pick looks clean. Apply commit ' +
                        commitId.substring(0, 7) + ' onto "' + targetBranch + '"?',
                        'success', commitId, targetBranch, onProceed);
                }
            })
            .catch(function () {
                showCherryPickPreviewModal('Cherry-pick preview unavailable.',
                    'warning', commitId, targetBranch, onProceed);
            });
    }

    /**
     * Display the cherry-pick preview in a Bootstrap modal.
     */
    function showCherryPickPreviewModal(message, type, commitId, targetBranch, onProceed) {
        var modalEl = document.getElementById('cherryPickPreviewModal');
        var contentEl = document.getElementById('cherryPickPreviewContent');
        var proceedBtn = document.getElementById('cherryPickPreviewProceedBtn');

        if (!modalEl || !contentEl) {
            if (onProceed && confirm(message + '\n\nProceed?')) {
                onProceed();
            }
            return;
        }

        var alertClass = type === 'danger' ? 'alert-danger' :
                         type === 'warning' ? 'alert-warning' : 'alert-success';

        contentEl.innerHTML = '<div class="alert ' + alertClass + ' mb-0">' +
            '<strong>Commit:</strong> ' + escapeHtml(commitId.substring(0, 7)) +
            ' <strong>→ Branch:</strong> ' + escapeHtml(targetBranch) +
            '<hr class="my-2">' + escapeHtml(message) + '</div>';

        if (proceedBtn) {
            if (onProceed) {
                proceedBtn.classList.remove('d-none');
                proceedBtn.onclick = function () {
                    var modal = bootstrap.Modal.getInstance(modalEl);
                    if (modal) modal.hide();
                    onProceed();
                };
            } else {
                proceedBtn.classList.add('d-none');
                proceedBtn.onclick = null;
            }
        }

        if (typeof bootstrap !== 'undefined') {
            var modal = new bootstrap.Modal(modalEl);
            modal.show();
        }
    }

    return {
        guardAll: guardAll,
        guard: guard,
        showMergePreview: showMergePreview,
        showCherryPickPreview: showCherryPickPreview
    };
}());
