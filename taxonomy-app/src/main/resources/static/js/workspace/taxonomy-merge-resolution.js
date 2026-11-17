/**
 * taxonomy-merge-resolution.js — Merge & Cherry-Pick Conflict Resolution
 *
 * Provides a side-by-side conflict resolution UI that loads conflict details
 * from the API and lets the user manually resolve conflicts.
 *
 * @module TaxonomyMergeResolution
 */
window.TaxonomyMergeResolution = (function () {
    'use strict';
    var t = TaxonomyI18n.t;
    function ignoredError(error) { return error?.code === 'ABORTED' || error?.status === 401 || error?.status === 403; }


    var currentConflict = null; // Stores current conflict context

    function dismissConflict() {
        if (!currentConflict) return;
        currentConflict = null;
        hideModal('mergeConflictModal');
        populateConflictModal({});
    }

    document.addEventListener('taxonomy:repository-context-changed', function (event) {
        if (event.detail?.selectionChanged) dismissConflict();
    });
    document.addEventListener('taxonomy-api-auth-failure', dismissConflict);
    window.addEventListener('pagehide', dismissConflict);

    // ── Initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        // "Use Ours" button
        var useOursBtn = document.getElementById('conflictUseOursBtn');
        if (useOursBtn) {
            useOursBtn.addEventListener('click', function () {
                var textarea = document.getElementById('conflictResolvedContent');
                var oursContent = document.getElementById('conflictOursContent');
                if (textarea && oursContent) {
                    textarea.value = oursContent.textContent;
                }
            });
        }

        // "Use Theirs" button
        var useTheirsBtn = document.getElementById('conflictUseTheirsBtn');
        if (useTheirsBtn) {
            useTheirsBtn.addEventListener('click', function () {
                var textarea = document.getElementById('conflictResolvedContent');
                var theirsContent = document.getElementById('conflictTheirsContent');
                if (textarea && theirsContent) {
                    textarea.value = theirsContent.textContent;
                }
            });
        }

        // "Resolve & Commit" button
        var resolveBtn = document.getElementById('conflictResolveBtn');
        if (resolveBtn) {
            resolveBtn.addEventListener('click', submitResolution);
        }

        // Diverged strategy buttons
        document.querySelectorAll('[data-diverged-strategy]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var strategy = btn.getAttribute('data-diverged-strategy');
                resolveDiverged(strategy);
            });
        });
    });

    // ── Merge Conflict ──────────────────────────────────────────────

    /**
     * Show the merge conflict resolution modal.
     *
     * @param {string} fromBranch — source branch
     * @param {string} intoBranch — target branch
     */
    function showMergeConflict(fromBranch, intoBranch) {
        currentConflict = { type: 'merge', fromBranch: fromBranch, intoBranch: intoBranch,
            current: window.TaxonomyApiClient.captureContext() };
        var conflict = currentConflict;

        window.TaxonomyVersioningApi.mergeConflicts(fromBranch, intoBranch)
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (conflict !== currentConflict || !conflict.current()) return;
                if (data.conflict === false) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showWarning(t('merge.no.conflict.title'),
                            t('merge.no.conflict.merge', fromBranch, intoBranch));
                    }
                    return;
                }
                if (!data.targetCommit) return;
                conflict.targetCommit = data.targetCommit;
                populateConflictModal(data);
                // Ensure title is set for merge context
                var titleEl = document.getElementById('mergeConflictModalLabel');
                if (titleEl) titleEl.textContent = t('merge.conflict.title');
                showModal('mergeConflictModal');
            })
            .catch(function (err) {
                if (ignoredError(err) || conflict !== currentConflict || !conflict.current()) return;
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('merge.error.title'), t('merge.error.load', err.message));
                }
            });
    }

    /**
     * Show the cherry-pick conflict resolution modal.
     *
     * @param {string} commitId — commit SHA to cherry-pick
     * @param {string} targetBranch — target branch
     */
    function showCherryPickConflict(commitId, targetBranch) {
        currentConflict = { type: 'cherry-pick', commitId: commitId, targetBranch: targetBranch,
            current: window.TaxonomyApiClient.captureContext() };
        var conflict = currentConflict;

        window.TaxonomyVersioningApi.cherryPickConflicts(commitId, targetBranch)
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (conflict !== currentConflict || !conflict.current()) return;
                if (data.conflict === false) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showWarning(t('merge.no.conflict.title'),
                            t('merge.no.conflict.cherrypick', targetBranch));
                    }
                    return;
                }
                if (!data.targetCommit) return;
                conflict.targetCommit = data.targetCommit;
                populateConflictModal(data);
                // Update modal title for cherry-pick context
                var titleEl = document.getElementById('mergeConflictModalLabel');
                if (titleEl) titleEl.textContent = t('merge.cherrypick.conflict.title');
                showModal('mergeConflictModal');
            })
            .catch(function (err) {
                if (ignoredError(err) || conflict !== currentConflict || !conflict.current()) return;
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('merge.error.title'), t('merge.error.load', err.message));
                }
            });
    }

    function populateConflictModal(data) {
        var oursLabel = document.getElementById('conflictOursLabel');
        var theirsLabel = document.getElementById('conflictTheirsLabel');
        var oursContent = document.getElementById('conflictOursContent');
        var theirsContent = document.getElementById('conflictTheirsContent');
        var resolved = document.getElementById('conflictResolvedContent');

        // textContent already escapes HTML, so use raw labels (no escapeHtml)
        var target = data.oursLabel || 'target';
        if (data.targetCommit) target += ' @ ' + data.targetCommit.substring(0, 12);
        if (oursLabel) oursLabel.textContent = t('merge.ours.label', target);
        var basePanel = document.getElementById('conflictBasePanel');
        var base = document.getElementById('conflictBaseContent');
        if (basePanel && base) {
            basePanel.hidden = data.baseContent == null;
            basePanel.open = false;
            base.textContent = data.baseContent || '';
        }
        if (theirsLabel) theirsLabel.textContent = t('merge.theirs.label', data.theirsLabel || 'source');
        if (oursContent) oursContent.textContent = data.oursContent || '';
        if (theirsContent) theirsContent.textContent = data.theirsContent || '';
        if (resolved) resolved.value = data.oursContent || '';
    }

    function submitResolution() {
        if (!currentConflict) return;

        if (!currentConflict.current()) {
            dismissConflict();
            return;
        }
        var resolved = document.getElementById('conflictResolvedContent');
        if (!resolved || !resolved.value.trim()) {
            if (window.TaxonomyOperationResult) {
                window.TaxonomyOperationResult.showWarning(t('merge.empty.content'), t('merge.empty.warning'));
            }
            return;
        }

        var conflict = currentConflict;
        if (!conflict.targetCommit) return;
        var operation = conflict.type === 'merge'
            ? window.TaxonomyVersioningApi.resolveMerge(conflict.fromBranch, conflict.intoBranch, conflict.targetCommit, resolved.value)
            : window.TaxonomyVersioningApi.resolveCherryPick(conflict.commitId, conflict.targetBranch, conflict.targetCommit, resolved.value);
        operation
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (conflict !== currentConflict || !conflict.current()) return;
            if (data.error) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('merge.resolution.failed'), data.error);
                }
            } else {
                hideModal('mergeConflictModal');
                if (window.TaxonomyOperationResult) {
                    var label = currentConflict.type === 'merge' ? 'Merge' : 'Cherry-Pick';
                    window.TaxonomyOperationResult.showSuccess(t('merge.resolved.title', label),
                        t('merge.resolved.detail', (data.commitId || '').substring(0, 7)));
                }
                // Refresh UI
                if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                if (window.TaxonomyVariants) window.TaxonomyVariants.refresh();
                currentConflict = null;
            }
        })
        .catch(function (err) {
            if (ignoredError(err) || conflict !== currentConflict || !conflict.current()) return;
            if (window.TaxonomyOperationResult) {
                window.TaxonomyOperationResult.showError(t('merge.error.title'), t('merge.resolution.error', err.message));
            }
        });
    }

    // ── Diverged Resolution ─────────────────────────────────────────

    function resolveDiverged(strategy) {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = (ctx && ctx.branch) ? ctx.branch : 'draft';

        window.TaxonomyVersioningApi.resolveDiverged(strategy, branch)
            .then(function (r) { return r.json(); })
            .then(function (data) {
                hideModal('syncDivergedModal');
                if (data.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('merge.resolution.failed'), data.message || data.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('merge.diverged.resolved'), data.message || 'Success');
                    }
                    if (window.TaxonomyWorkspaceSync) window.TaxonomyWorkspaceSync.refresh();
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                }
            })
            .catch(function (err) {
                if (ignoredError(err)) return;
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('merge.error.title'), t('merge.resolution.error', err.message));
                }
            });
    }

    // ── Helpers ──────────────────────────────────────────────────────

    function showModal(id) {
        var el = document.getElementById(id);
        if (el && typeof bootstrap !== 'undefined') {
            var modal = new bootstrap.Modal(el);
            modal.show();
        }
    }

    function hideModal(id) {
        var el = document.getElementById(id);
        if (el && typeof bootstrap !== 'undefined') {
            var modal = bootstrap.Modal.getInstance(el);
            if (modal) modal.hide();
        }
    }

    var escapeHtml = TaxonomyUtils.escapeHtml;

    return {
        showMergeConflict: showMergeConflict,
        showCherryPickConflict: showCherryPickConflict,
        resolveDiverged: resolveDiverged
    };
}());
