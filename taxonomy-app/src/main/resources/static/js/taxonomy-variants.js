/**
 * taxonomy-variants.js — Variants Dashboard
 *
 * Provides a card-based UI panel listing all architecture variants (Git branches)
 * with actions to switch, compare, merge, and delete.
 * Uses i18n-aware labels and card layout.
 *
 * @module TaxonomyVariants
 */
window.TaxonomyVariants = (function () {
    'use strict';

    var t = TaxonomyI18n.t;

    var containerId = 'variantsBrowser';

    /**
     * Load and render the variants browser.
     */
    function refresh() {
        var container = document.getElementById(containerId);
        if (!container) return;

        container.innerHTML = '<div class="text-muted small">' + escapeHtml(t('variants.loading')) + '</div>';

        fetch('/api/git/state?branch=draft')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (state) {
                if (!state || !state.branches) {
                    container.innerHTML = '<div class="text-muted small">' + escapeHtml(t('variants.unavailable')) + '</div>';
                    return;
                }
                renderVariants(container, state);
            })
            .catch(function (err) {
                container.innerHTML = '<div class="text-danger small">' + escapeHtml(t('variants.load_error')) + escapeHtml(err.message) + '</div>';
            });
    }

    /**
     * Render the variant list as cards.
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
            container.innerHTML = '<div class="variant-empty-state">'
                + '<span class="empty-icon">' + t('variants.empty.icon') + '</span>'
                + '<p class="fw-semibold">' + escapeHtml(t('variants.empty.title')) + '</p>'
                + '<p class="small text-muted mb-3">' + escapeHtml(t('variants.empty.description')) + '</p>'
                + '<button class="btn btn-success btn-sm" onclick="TaxonomyContextBar.showVariantDialog()">' + escapeHtml(t('variants.empty.create')) + '</button>'
                + '</div>';
            return;
        }

        var html = '<div class="variant-list">';
        branches.forEach(function (branch) {
            var isActive = (branch === activeBranch);
            var isDraft = (branch === 'draft');

            html += '<div class="variant-card' + (isActive ? ' variant-active' : '') + '">';

            // Header: name + badges
            html += '<div class="variant-header">';
            html += '<div class="variant-name">';
            html += '\uD83C\uDF3F ' + escapeHtml(TaxonomyI18n.formatBranch(branch));
            if (isActive) {
                html += ' <span class="badge bg-primary ms-1" style="font-size:0.7rem;">' + escapeHtml(t('variants.badge.active')) + '</span>';
            }
            if (isDraft) {
                html += ' <span class="badge bg-secondary ms-1" style="font-size:0.7rem;">' + escapeHtml(t('variants.badge.main')) + '</span>';
            }
            html += '</div>';
            html += '</div>'; // variant-header

            // Meta info
            html += '<div class="variant-meta">';
            if (isDraft) {
                html += escapeHtml(t('variants.meta.main'));
            } else {
                html += escapeHtml(t('variants.meta.variant'));
            }
            html += '</div>';

            // Actions
            html += '<div class="variant-actions">';
            if (!isActive) {
                html += '<button class="btn btn-outline-primary btn-sm" '
                    + 'onclick="TaxonomyVariants.switchTo(\'' + escapeAttr(branch) + '\')" title="' + escapeHtml(t('variants.btn.open.title')) + '">'
                    + escapeHtml(t('variants.btn.open')) + '</button>';
            }
            html += '<button class="btn btn-outline-info btn-sm" '
                + 'onclick="TaxonomyVariants.compareTo(\'' + escapeAttr(branch) + '\')" title="' + escapeHtml(t('variants.btn.compare.title')) + '">'
                + escapeHtml(t('variants.btn.compare')) + '</button>';
            if (!isActive && !isDraft) {
                html += '<button class="btn btn-outline-warning btn-sm" '
                    + 'onclick="TaxonomyVariants.mergeFrom(\'' + escapeAttr(branch) + '\')" title="' + escapeHtml(t('variants.btn.integrate.title')) + '">'
                    + escapeHtml(t('variants.btn.integrate')) + '</button>';
                html += '<button class="btn btn-outline-danger btn-sm" '
                    + 'onclick="TaxonomyVariants.deleteBranch(\'' + escapeAttr(branch) + '\')" title="' + escapeHtml(t('variants.btn.delete.title')) + '">'
                    + escapeHtml(t('variants.btn.delete')) + '</button>';
            }
            html += '</div>'; // variant-actions

            html += '</div>'; // variant-card
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
                if (window.TaxonomyContextCompare) {
                    window.TaxonomyContextCompare.showDialog(ctx);
                }
            });
    }

    /**
     * Merge a variant into the current branch with preview.
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
            // AP 5: Use modal confirmation instead of plain confirm()
            showMergeConfirmation(fromBranch, intoBranch);
        }
    }

    /**
     * Show a modal merge confirmation with preview (AP 5).
     */
    function showMergeConfirmation(fromBranch, intoBranch) {
        var fromDisplay = TaxonomyI18n.formatBranch(fromBranch);
        var intoDisplay = TaxonomyI18n.formatBranch(intoBranch);

        // Try to load a diff preview first
        fetch('/api/context/compare?leftBranch=' + encodeURIComponent(intoBranch)
            + '&rightBranch=' + encodeURIComponent(fromBranch))
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (comparison) {
                var previewHtml = '';
                if (comparison && comparison.summary) {
                    var s = comparison.summary;
                    var total = (s.elementsAdded || 0) + (s.elementsRemoved || 0) + (s.elementsChanged || 0)
                        + (s.relationsAdded || 0) + (s.relationsRemoved || 0) + (s.relationsChanged || 0);
                    previewHtml = '<div class="restore-preview">'
                        + t('variants.merge.preview', total)
                        + '<div class="small text-muted mt-1">'
                        + t('variants.merge.preview_detail', (s.elementsAdded || 0), (s.elementsRemoved || 0), (s.elementsChanged || 0))
                        + '</div></div>';
                }

                var bodyHtml = '<p>' + t('variants.merge.confirm', escapeHtml(fromDisplay), escapeHtml(intoDisplay)) + '</p>'
                    + previewHtml;

                showConfirmModal(t('variants.merge.title'), bodyHtml, t('variants.merge.btn'), 'btn-warning', function () {
                    executeMerge(fromBranch, intoBranch);
                });
            })
            .catch(function () {
                if (confirm(t('variants.merge.confirm', fromDisplay, intoDisplay).replace(/<[^>]*>/g, ''))) {
                    executeMerge(fromBranch, intoBranch);
                }
            });
    }

    /**
     * Execute a merge operation.
     *
     * @param {string} fromBranch — source branch
     * @param {string} intoBranch — target branch
     */
    function executeMerge(fromBranch, intoBranch) {
        var fromDisplay = TaxonomyI18n.formatBranch(fromBranch);
        var intoDisplay = TaxonomyI18n.formatBranch(intoBranch);

        fetch('/api/dsl/merge?from=' + encodeURIComponent(fromBranch)
            + '&into=' + encodeURIComponent(intoBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('variants.merge.error'), result.error);
                    } else {
                        alert(t('variants.merge.error') + ': ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('variants.merge.success.title'),
                            t('variants.merge.success.msg', fromDisplay, intoDisplay));
                    }
                    if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('variants.merge.error'), err.message);
                } else {
                    alert(t('variants.merge.error') + ': ' + err.message);
                }
            });
    }

    /**
     * Delete a variant (branch).
     *
     * @param {string} branch — branch name to delete
     */
    function deleteBranch(branch) {
        var display = TaxonomyI18n.formatBranch(branch);
        showConfirmModal(
            t('variants.delete.title'),
            '<p>' + t('variants.delete.confirm', escapeHtml(display)) + '</p>'
            + '<p class="text-danger small">' + escapeHtml(t('variants.delete.warning')) + '</p>',
            t('variants.delete.btn'),
            'btn-danger',
            function () {
                executeDelete(branch);
            }
        );
    }

    function executeDelete(branch) {
        fetch('/api/dsl/branch?name=' + encodeURIComponent(branch), { method: 'DELETE' })
            .then(function (r) {
                if (!r.ok && r.status === 404) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('variants.delete.error'), t('variants.delete.not_found', branch));
                    }
                    return null;
                }
                return r.json();
            })
            .then(function (result) {
                if (!result) return;
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('variants.delete.error'), result.error);
                    } else {
                        alert(t('variants.delete.error') + ': ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('variants.delete.success.title'),
                            t('variants.delete.success.msg', branch));
                    }
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('variants.delete.error'), err.message);
                } else {
                    alert(t('variants.delete.error') + ': ' + err.message);
                }
            });
    }

    /**
     * Show a modal confirmation dialog (used instead of confirm()).
     */
    function showConfirmModal(title, bodyHtml, confirmLabel, confirmBtnClass, onConfirm) {
        var existing = document.getElementById('variantConfirmModal');
        if (existing) existing.remove();

        var modalHtml =
            '<div class="modal fade" id="variantConfirmModal" tabindex="-1">' +
            '<div class="modal-dialog">' +
            '<div class="modal-content">' +
            '<div class="modal-header">' +
            '<h5 class="modal-title">' + escapeHtml(title) + '</h5>' +
            '<button type="button" class="btn-close" data-bs-dismiss="modal"></button>' +
            '</div>' +
            '<div class="modal-body">' + bodyHtml + '</div>' +
            '<div class="modal-footer">' +
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">' + escapeHtml(t('common.cancel')) + '</button>' +
            '<button type="button" class="btn ' + confirmBtnClass + ' btn-sm" id="variantConfirmBtn">' + escapeHtml(confirmLabel) + '</button>' +
            '</div></div></div></div>';

        document.body.insertAdjacentHTML('beforeend', modalHtml);
        var modalEl = document.getElementById('variantConfirmModal');
        var modal = new bootstrap.Modal(modalEl);

        document.getElementById('variantConfirmBtn').addEventListener('click', function () {
            modal.hide();
            if (onConfirm) onConfirm();
        });

        modal.show();
        modalEl.addEventListener('hidden.bs.modal', function () { modalEl.remove(); });
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
