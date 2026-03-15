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

    var currentConflict = null; // Stores current conflict context

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
        currentConflict = { type: 'merge', fromBranch: fromBranch, intoBranch: intoBranch };

        fetch('/api/dsl/merge/conflicts?from=' + encodeURIComponent(fromBranch) +
            '&into=' + encodeURIComponent(intoBranch))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.conflict === false) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showWarning('No Conflict',
                            'No conflict detected between "' + fromBranch + '" and "' + intoBranch + '".');
                    }
                    return;
                }
                populateConflictModal(data);
                // Ensure title is set for merge context
                var titleEl = document.getElementById('mergeConflictModalLabel');
                if (titleEl) titleEl.textContent = '\u26A0\uFE0F Merge Conflict \u2014 Manual Resolution Required';
                showModal('mergeConflictModal');
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('Error', 'Could not load conflict details: ' + err.message);
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
        currentConflict = { type: 'cherry-pick', commitId: commitId, targetBranch: targetBranch };

        fetch('/api/dsl/cherry-pick/conflicts?commitId=' + encodeURIComponent(commitId) +
            '&targetBranch=' + encodeURIComponent(targetBranch))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.conflict === false) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showWarning('No Conflict',
                            'No conflict detected for cherry-pick onto "' + targetBranch + '".');
                    }
                    return;
                }
                populateConflictModal(data);
                // Update modal title for cherry-pick context
                var titleEl = document.getElementById('mergeConflictModalLabel');
                if (titleEl) titleEl.textContent = '\u26A0\uFE0F Cherry-Pick Conflict \u2014 Manual Resolution Required';
                showModal('mergeConflictModal');
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('Error', 'Could not load conflict details: ' + err.message);
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
        if (oursLabel) oursLabel.textContent = 'Ours (' + (data.oursLabel || 'target') + ')';
        if (theirsLabel) theirsLabel.textContent = 'Theirs (' + (data.theirsLabel || 'source') + ')';
        if (oursContent) oursContent.textContent = data.oursContent || '';
        if (theirsContent) theirsContent.textContent = data.theirsContent || '';
        if (resolved) resolved.value = data.oursContent || '';
    }

    function submitResolution() {
        if (!currentConflict) return;

        var resolved = document.getElementById('conflictResolvedContent');
        if (!resolved || !resolved.value.trim()) {
            if (window.TaxonomyOperationResult) {
                window.TaxonomyOperationResult.showWarning('Empty Content', 'Resolved content cannot be empty.');
            }
            return;
        }

        var url, method = 'POST';
        if (currentConflict.type === 'merge') {
            url = '/api/dsl/merge/resolve?fromBranch=' + encodeURIComponent(currentConflict.fromBranch) +
                '&intoBranch=' + encodeURIComponent(currentConflict.intoBranch);
        } else {
            url = '/api/dsl/cherry-pick/resolve?commitId=' + encodeURIComponent(currentConflict.commitId) +
                '&targetBranch=' + encodeURIComponent(currentConflict.targetBranch);
        }

        fetch(url, {
            method: method,
            headers: { 'Content-Type': 'text/plain' },
            body: resolved.value
        })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.error) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('Resolution Failed', data.error);
                }
            } else {
                hideModal('mergeConflictModal');
                if (window.TaxonomyOperationResult) {
                    var label = currentConflict.type === 'merge' ? 'Merge' : 'Cherry-Pick';
                    window.TaxonomyOperationResult.showSuccess(label + ' Conflict Resolved',
                        'Content committed successfully: ' + (data.commitId || '').substring(0, 7));
                }
                // Refresh UI
                if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                if (window.TaxonomyVariants) window.TaxonomyVariants.refresh();
                currentConflict = null;
            }
        })
        .catch(function (err) {
            if (window.TaxonomyOperationResult) {
                window.TaxonomyOperationResult.showError('Error', 'Resolution failed: ' + err.message);
            }
        });
    }

    // ── Diverged Resolution ─────────────────────────────────────────

    function resolveDiverged(strategy) {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var branch = (ctx && ctx.branch) ? ctx.branch : 'draft';

        fetch('/api/workspace/resolve-diverged?strategy=' + encodeURIComponent(strategy) +
            '&userBranch=' + encodeURIComponent(branch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                hideModal('syncDivergedModal');
                if (data.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError('Resolution Failed', data.message || data.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Diverged State Resolved', data.message || 'Success');
                    }
                    if (window.TaxonomyWorkspaceSync) window.TaxonomyWorkspaceSync.refresh();
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('Error', 'Resolution failed: ' + err.message);
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

    function escapeHtml(s) {
        if (!s) return '';
        return s.replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    return {
        showMergeConflict: showMergeConflict,
        showCherryPickConflict: showCherryPickConflict,
        resolveDiverged: resolveDiverged
    };
}());
