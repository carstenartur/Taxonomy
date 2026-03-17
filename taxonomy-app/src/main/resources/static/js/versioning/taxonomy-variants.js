/**
 * taxonomy-variants.js — Variants Dashboard
 *
 * Provides a card-based UI panel listing all architecture variants (Git branches)
 * with actions to switch, compare, merge, and delete.
 * Uses i18n keys for all user-visible labels.
 *
 * @module TaxonomyVariants
 */
window.TaxonomyVariants = (function () {
    'use strict';

    var t = TaxonomyI18n.t;
    var escapeHtml = TaxonomyUtils.escapeHtml;
    var containerId = 'variantsBrowser';

    function escapeAttr(s) {
        return escapeHtml(s);
    }

    function refresh() {
        var container = document.getElementById(containerId);
        if (!container) return;

        container.innerHTML = '<div class="text-muted small">' + escapeHtml(t('variants.loading')) + '</div>';

        fetch('/api/git/state?branch=draft')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (state) {
                if (!state || !state.branches) {
                    container.innerHTML = '<div class="text-muted small">' + escapeHtml(t('variants.none.available')) + '</div>';
                    return;
                }
                renderVariants(container, state);
            })
            .catch(function (err) {
                container.innerHTML = '<div class="text-danger small">' + escapeHtml(t('variants.load.failed', err.message)) + '</div>';
            });
    }

    function renderVariants(container, state) {
        var branches = state.branches || [];
        var currentBranch = state.currentBranch || 'draft';
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var activeBranch = ctx ? ctx.branch : currentBranch;

        if (branches.length === 0) {
            container.innerHTML = '<div class="variant-empty-state">'
                + '<span class="empty-icon">\uD83C\uDF3F</span>'
                + '<p class="fw-semibold">' + escapeHtml(t('variants.empty.title')) + '</p>'
                + '<p class="small text-muted mb-3">' + escapeHtml(t('variants.empty.description')) + '</p>'
                + '<button class="btn btn-success btn-sm" onclick="TaxonomyContextBar.showVariantDialog()">\uD83C\uDF3F ' + escapeHtml(t('variants.empty.create')) + '</button>'
                + '</div>';
            return;
        }

        var html = '<div class="variant-list">';
        branches.forEach(function (branch) {
            var isActive = (branch === activeBranch);
            var isDraft = (branch === 'draft');
            var branchDisplay = TaxonomyI18n.formatBranch(branch);

            html += '<div class="variant-card' + (isActive ? ' variant-active' : '') + '">';
            html += '<div class="variant-header">';
            html += '<div class="variant-name">';
            html += '\uD83C\uDF3F ' + escapeHtml(branchDisplay);
            if (isActive) {
                html += ' <span class="badge bg-primary ms-1" style="font-size:0.7rem;">\u2713 ' + escapeHtml(t('variants.active')) + '</span>';
            }
            if (isDraft) {
                html += ' <span class="badge bg-secondary ms-1" style="font-size:0.7rem;">\uD83C\uDFE0 ' + escapeHtml(t('variants.main')) + '</span>';
            }
            html += '</div></div>';

            html += '<div class="variant-meta">';
            html += isDraft ? escapeHtml(t('variants.main.description')) : escapeHtml(t('variants.derived.description'));
            html += '</div>';

            html += '<div class="variant-actions">';
            if (!isActive) {
                html += '<button class="btn btn-outline-primary btn-sm" '
                    + 'onclick="TaxonomyVariants.switchTo(\'' + escapeAttr(branch) + '\')" title="' + escapeAttr(t('variants.open.title')) + '">'
                    + '\u27A1 ' + escapeHtml(t('variants.open')) + '</button>';
            }
            html += '<button class="btn btn-outline-info btn-sm" '
                + 'onclick="TaxonomyVariants.compareTo(\'' + escapeAttr(branch) + '\')" title="' + escapeAttr(t('variants.compare.title')) + '">'
                + '\uD83D\uDD0D ' + escapeHtml(t('versions.entry.compare')) + '</button>';
            if (!isActive && !isDraft) {
                html += '<button class="btn btn-outline-warning btn-sm" '
                    + 'onclick="TaxonomyVariants.mergeFrom(\'' + escapeAttr(branch) + '\')" title="' + escapeAttr(t('variants.integrate.title')) + '">'
                    + '\uD83D\uDD00 ' + escapeHtml(t('variants.integrate')) + '</button>';
                html += '<button class="btn btn-outline-danger btn-sm" '
                    + 'onclick="TaxonomyVariants.deleteBranch(\'' + escapeAttr(branch) + '\')" title="' + escapeAttr(t('variants.delete.title')) + '">'
                    + '\uD83D\uDDD1 ' + escapeHtml(t('variants.delete.btn')) + '</button>';
            }
            html += '</div>';
            html += '</div>';
        });
        html += '</div>';
        container.innerHTML = html;
    }

    function switchTo(branch) {
        fetch('/api/context/open?branch=' + encodeURIComponent(branch) + '&readOnly=false', { method: 'POST' })
            .then(function () {
                if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                refresh();
            });
    }

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

    function mergeFrom(fromBranch) {
        var ctx = window.TaxonomyContextBar ? window.TaxonomyContextBar.getCurrentContext() : null;
        var intoBranch = ctx ? ctx.branch : 'draft';

        if (window.TaxonomyActionGuards) {
            window.TaxonomyActionGuards.showMergePreview(fromBranch, intoBranch, function () {
                executeMerge(fromBranch, intoBranch);
            });
        } else {
            showMergeConfirmation(fromBranch, intoBranch);
        }
    }

    function showMergeConfirmation(fromBranch, intoBranch) {
        var fromDisplay = TaxonomyI18n.formatBranch(fromBranch);
        var intoDisplay = TaxonomyI18n.formatBranch(intoBranch);

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
                        + '<strong>' + escapeHtml(t('variants.integrate.preview', total)) + '</strong>'
                        + '<div class="small text-muted mt-1">'
                        + escapeHtml(t('variants.integrate.preview.detail', s.elementsAdded || 0, s.elementsRemoved || 0, s.elementsChanged || 0))
                        + '</div></div>';
                }

                var bodyHtml = t('variants.integrate.confirm.body', escapeHtml(fromDisplay), escapeHtml(intoDisplay))
                    + previewHtml;

                showConfirmModal(t('variants.integrate.confirm.title'), bodyHtml, t('variants.integrate.confirm.btn'), 'btn-warning', function () {
                    executeMerge(fromBranch, intoBranch);
                });
            })
            .catch(function () {
                if (confirm(t('variants.integrate.confirm.body', fromDisplay, intoDisplay).replace(/<[^>]*>/g, ''))) {
                    executeMerge(fromBranch, intoBranch);
                }
            });
    }

    function executeMerge(fromBranch, intoBranch) {
        var fromDisplay = TaxonomyI18n.formatBranch(fromBranch);
        var intoDisplay = TaxonomyI18n.formatBranch(intoBranch);

        fetch('/api/dsl/merge?from=' + encodeURIComponent(fromBranch)
            + '&into=' + encodeURIComponent(intoBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('variants.integrate.failed'), result.error);
                    } else {
                        alert(t('variants.integrate.failed') + ': ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('variants.integrate.success'),
                            t('variants.integrate.success.detail', fromDisplay, intoDisplay));
                    }
                    if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('variants.integrate.failed'), err.message);
                } else {
                    alert(t('variants.integrate.failed') + ': ' + err.message);
                }
            });
    }

    function deleteBranch(branch) {
        var display = TaxonomyI18n.formatBranch(branch);
        showConfirmModal(
            t('variants.delete.confirm.title'),
            t('variants.delete.confirm.body', escapeHtml(display))
            + '<p class="text-danger small">\u26A0 ' + escapeHtml(t('variants.delete.confirm.warning')) + '</p>',
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
                        window.TaxonomyOperationResult.showError(t('variants.delete.failed'), t('variants.delete.notfound', branch));
                    }
                    return null;
                }
                return r.json();
            })
            .then(function (result) {
                if (!result) return;
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError(t('variants.delete.failed'), result.error);
                    } else {
                        alert(t('variants.delete.failed') + ': ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess(t('variants.delete.success'),
                            t('variants.delete.success.detail', branch));
                    }
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError(t('variants.delete.failed'), err.message);
                } else {
                    alert(t('variants.delete.failed') + ': ' + err.message);
                }
            });
    }

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
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">' + escapeHtml(t('dialog.cancel')) + '</button>' +
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

    return {
        refresh: refresh,
        switchTo: switchTo,
        compareTo: compareTo,
        mergeFrom: mergeFrom,
        deleteBranch: deleteBranch
    };
}());
