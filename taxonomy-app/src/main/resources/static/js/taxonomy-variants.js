/**
 * taxonomy-variants.js — Variant (Branch) Browser
 *
 * Provides a UI panel listing all architecture variants (Git branches)
 * with actions to switch, compare, merge, and delete.
 *
 * @module TaxonomyVariants
 */
window.TaxonomyVariants = (function () {
    'use strict';

    var containerId = 'variantsBrowser';

    /**
     * Load and render the variants browser.
     */
    function refresh() {
        var container = document.getElementById(containerId);
        if (!container) return;

        container.innerHTML = '<div class="text-muted small">Loading variants…</div>';

        fetch('/api/git/state?branch=draft')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (state) {
                if (!state || !state.branches) {
                    container.innerHTML = '<div class="text-muted small">No variant information available.</div>';
                    return;
                }
                renderVariants(container, state);
            })
            .catch(function (err) {
                container.innerHTML = '<div class="text-danger small">Failed to load variants: ' + escapeHtml(err.message) + '</div>';
            });
    }

    /**
     * Render the variant list.
     *
     * @param {HTMLElement} container — target container element
     * @param {object} state — RepositoryState from API
     */
    function renderVariants(container, state) {
        var branches = state.branches || [];
        var currentBranch = state.currentBranch || 'draft';
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var activeBranch = ctx ? ctx.branch : currentBranch;

        if (branches.length === 0) {
            container.innerHTML = '<div class="text-muted small p-2">No variants found.</div>';
            return;
        }

        var html = '<div class="list-group">';
        branches.forEach(function (branch) {
            var isActive = (branch === activeBranch);
            var isDraft = (branch === 'draft');

            html += '<div class="list-group-item' + (isActive ? ' list-group-item-primary' : '') + '">';
            html += '<div class="d-flex justify-content-between align-items-center">';

            // Branch info
            html += '<div>';
            html += '<strong>' + escapeHtml(branch) + '</strong>';
            if (isActive) {
                html += ' <span class="badge bg-primary ms-1">active</span>';
            }
            if (isDraft) {
                html += ' <span class="badge bg-secondary ms-1">main</span>';
            }
            html += '</div>';

            // Action buttons
            html += '<div class="d-flex gap-1">';
            if (!isActive) {
                html += '<button class="btn btn-outline-primary btn-sm py-0 px-1" style="font-size:0.7rem;" '
                    + 'onclick="TaxonomyVariants.switchTo(\'' + escapeAttr(branch) + '\')" title="Switch to this variant">'
                    + '&#8594; Switch</button>';
            }
            html += '<button class="btn btn-outline-info btn-sm py-0 px-1" style="font-size:0.7rem;" '
                + 'onclick="TaxonomyVariants.compareTo(\'' + escapeAttr(branch) + '\')" title="Compare with current">'
                + '&#8596; Compare</button>';
            if (!isActive && !isDraft) {
                html += '<button class="btn btn-outline-warning btn-sm py-0 px-1" style="font-size:0.7rem;" '
                    + 'onclick="TaxonomyVariants.mergeFrom(\'' + escapeAttr(branch) + '\')" title="Merge this variant into current">'
                    + '&#128256; Merge</button>';
                html += '<button class="btn btn-outline-danger btn-sm py-0 px-1" style="font-size:0.7rem;" '
                    + 'onclick="TaxonomyVariants.deleteBranch(\'' + escapeAttr(branch) + '\')" title="Delete this variant">'
                    + '&#128465; Delete</button>';
            }
            html += '</div>';

            html += '</div>';
            html += '</div>';
        });
        html += '</div>';

        container.innerHTML = html;
    }

    /**
     * Switch to a different variant.
     *
     * @param {string} branch — branch name to switch to
     */
    function switchTo(branch) {
        fetch('/api/context/open?branch=' + encodeURIComponent(branch) + '&readOnly=false', { method: 'POST' })
            .then(function () {
                if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                refresh();
            });
    }

    /**
     * Compare a branch with the current context.
     *
     * @param {string} branch — branch to compare
     */
    function compareTo(branch) {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var currentBranch = ctx ? ctx.branch : 'draft';

        var leftSel = document.getElementById('compareLeftBranch');
        var rightSel = document.getElementById('compareRightBranch');

        // Populate branch selectors, then set values and show modal
        fetch('/api/git/branches')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var branches = data.branches || data || [];
                [leftSel, rightSel].forEach(function (sel) {
                    if (!sel) return;
                    sel.innerHTML = '';
                    branches.forEach(function (b) {
                        var opt = document.createElement('option');
                        opt.value = b;
                        opt.textContent = b;
                        sel.appendChild(opt);
                    });
                });
                if (leftSel) leftSel.value = currentBranch;
                if (rightSel) rightSel.value = branch;

                var results = document.getElementById('contextCompareResults');
                if (results) results.innerHTML = '';

                var modal = document.getElementById('contextCompareModal');
                if (modal && typeof bootstrap !== 'undefined') {
                    var bsModal = new bootstrap.Modal(modal);
                    bsModal.show();
                }
            })
            .catch(function () {
                // Fallback: just open the dialog
                if (window.TaxonomyContextCompare) {
                    window.TaxonomyContextCompare.showDialog(ctx);
                }
            });
    }

    /**
     * Merge a variant into the current branch.
     *
     * @param {string} fromBranch — source branch to merge from
     */
    function mergeFrom(fromBranch) {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var intoBranch = ctx ? ctx.branch : 'draft';

        if (window.TaxonomyActionGuards) {
            window.TaxonomyActionGuards.showMergePreview(fromBranch, intoBranch, function () {
                executeMerge(fromBranch, intoBranch);
            });
        } else {
            if (confirm('Merge "' + fromBranch + '" into "' + intoBranch + '"?')) {
                executeMerge(fromBranch, intoBranch);
            }
        }
    }

    /**
     * Execute a merge operation.
     *
     * @param {string} fromBranch — source branch
     * @param {string} intoBranch — target branch
     */
    function executeMerge(fromBranch, intoBranch) {
        fetch('/api/dsl/merge?from=' + encodeURIComponent(fromBranch)
            + '&into=' + encodeURIComponent(intoBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError('Merge Failed', result.error);
                    } else {
                        alert('Merge failed: ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Merge Successful',
                            'Merged "' + fromBranch + '" into "' + intoBranch + '" → ' +
                            (result.commitId || '').substring(0, 7));
                    }
                    if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('Merge Failed', err.message);
                } else {
                    alert('Merge failed: ' + err.message);
                }
            });
    }

    /**
     * Delete a variant (branch).
     *
     * @param {string} branch — branch name to delete
     */
    function deleteBranch(branch) {
        if (!confirm('Delete variant "' + branch + '"?\n\nThis cannot be undone.')) {
            return;
        }

        fetch('/api/dsl/branch?name=' + encodeURIComponent(branch), { method: 'DELETE' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError('Delete Failed', result.error);
                    } else {
                        alert('Delete failed: ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Variant Deleted',
                            'Branch "' + branch + '" has been deleted.');
                    }
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('Delete Failed', err.message);
                } else {
                    alert('Delete failed: ' + err.message);
                }
            });
    }

    function escapeHtml(s) {
        if (!s) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function escapeAttr(s) {
        return escapeHtml(s);
    }

    return {
        refresh: refresh,
        switchTo: switchTo,
        compareTo: compareTo,
        mergeFrom: mergeFrom,
        deleteBranch: deleteBranch
    };
}());
