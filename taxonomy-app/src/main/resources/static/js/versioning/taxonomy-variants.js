/**
 * taxonomy-variants.js — Varianten-Dashboard
 *
 * Provides a card-based UI panel listing all architecture variants (Git branches)
 * with actions to switch, compare, merge, and delete.
 * Uses user-friendly German labels and card layout.
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

        container.innerHTML = '<div class="text-muted small">Varianten werden geladen\u2026</div>';

        fetch('/api/git/state?branch=draft')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (state) {
                if (!state || !state.branches) {
                    container.innerHTML = '<div class="text-muted small">Keine Varianten-Informationen verf\u00FCgbar.</div>';
                    return;
                }
                renderVariants(container, state);
            })
            .catch(function (err) {
                container.innerHTML = '<div class="text-danger small">Varianten konnten nicht geladen werden: ' + escapeHtml(err.message) + '</div>';
            });
    }

    /**
     * Format branch name for display.
     */
    function formatBranchName(branch) {
        if (!branch || branch === 'draft') return 'Hauptversion';
        return branch;
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
                + '<span class="empty-icon">\uD83C\uDF3F</span>'
                + '<p class="fw-semibold">Keine Varianten vorhanden</p>'
                + '<p class="small text-muted mb-3">Varianten erm\u00F6glichen paralleles Arbeiten an verschiedenen Architektur-Entw\u00FCrfen, ohne die Hauptversion zu beeinflussen.</p>'
                + '<button class="btn btn-success btn-sm" onclick="TaxonomyContextBar.showVariantDialog()">\uD83C\uDF3F Erste Variante erstellen</button>'
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
            html += '\uD83C\uDF3F ' + escapeHtml(formatBranchName(branch));
            if (isActive) {
                html += ' <span class="badge bg-primary ms-1" style="font-size:0.7rem;">\u2713 Aktiv</span>';
            }
            if (isDraft) {
                html += ' <span class="badge bg-secondary ms-1" style="font-size:0.7rem;">\uD83C\uDFE0 Haupt</span>';
            }
            html += '</div>';
            html += '</div>'; // variant-header

            // Meta info
            html += '<div class="variant-meta">';
            if (isDraft) {
                html += 'Hauptversion \u2014 Referenz f\u00FCr alle Varianten';
            } else {
                html += 'Variante \u2014 abgeleitet von Hauptversion';
            }
            html += '</div>';

            // Actions
            html += '<div class="variant-actions">';
            if (!isActive) {
                html += '<button class="btn btn-outline-primary btn-sm" '
                    + 'onclick="TaxonomyVariants.switchTo(\'' + escapeAttr(branch) + '\')" title="Zu dieser Variante wechseln">'
                    + '\u27A1 \u00D6ffnen</button>';
            }
            html += '<button class="btn btn-outline-info btn-sm" '
                + 'onclick="TaxonomyVariants.compareTo(\'' + escapeAttr(branch) + '\')" title="Mit aktueller Version vergleichen">'
                + '\uD83D\uDD0D Vergleichen</button>';
            if (!isActive && !isDraft) {
                html += '<button class="btn btn-outline-warning btn-sm" '
                    + 'onclick="TaxonomyVariants.mergeFrom(\'' + escapeAttr(branch) + '\')" title="\u00C4nderungen dieser Variante in die aktuelle Version \u00FCbernehmen">'
                    + '\uD83D\uDD00 Integrieren</button>';
                html += '<button class="btn btn-outline-danger btn-sm" '
                    + 'onclick="TaxonomyVariants.deleteBranch(\'' + escapeAttr(branch) + '\')" title="Diese Variante l\u00F6schen">'
                    + '\uD83D\uDDD1 L\u00F6schen</button>';
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
        var fromDisplay = fromBranch === 'draft' ? 'Hauptversion' : fromBranch;
        var intoDisplay = intoBranch === 'draft' ? 'Hauptversion' : intoBranch;

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
                        + '<strong>Vorschau:</strong> ' + total + ' \u00C4nderung(en) werden \u00FCbernommen'
                        + '<div class="small text-muted mt-1">'
                        + '+' + (s.elementsAdded || 0) + ' Elemente, '
                        + '\u2212' + (s.elementsRemoved || 0) + ' entfernt, '
                        + '~' + (s.elementsChanged || 0) + ' ge\u00E4ndert'
                        + '</div></div>';
                }

                var bodyHtml = '<p>\u00C4nderungen aus <strong>' + escapeHtml(fromDisplay) + '</strong> '
                    + 'in <strong>' + escapeHtml(intoDisplay) + '</strong> \u00FCbernehmen?</p>'
                    + previewHtml;

                showConfirmModal('Integrieren', bodyHtml, 'Integrieren', 'btn-warning', function () {
                    executeMerge(fromBranch, intoBranch);
                });
            })
            .catch(function () {
                if (confirm('\u00C4nderungen aus "' + fromDisplay + '" in "' + intoDisplay + '" \u00FCbernehmen?')) {
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
        var fromDisplay = fromBranch === 'draft' ? 'Hauptversion' : fromBranch;
        var intoDisplay = intoBranch === 'draft' ? 'Hauptversion' : intoBranch;

        fetch('/api/dsl/merge?from=' + encodeURIComponent(fromBranch)
            + '&into=' + encodeURIComponent(intoBranch), { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError('Integration fehlgeschlagen', result.error);
                    } else {
                        alert('Integration fehlgeschlagen: ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Erfolgreich integriert',
                            '\u00C4nderungen aus \u201E' + fromDisplay + '\u201C in \u201E' + intoDisplay + '\u201C \u00FCbernommen.');
                    }
                    if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('Integration fehlgeschlagen', err.message);
                } else {
                    alert('Integration fehlgeschlagen: ' + err.message);
                }
            });
    }

    /**
     * Delete a variant (branch).
     *
     * @param {string} branch — branch name to delete
     */
    function deleteBranch(branch) {
        var display = branch === 'draft' ? 'Hauptversion' : branch;
        showConfirmModal(
            'Variante l\u00F6schen',
            '<p>Variante <strong>' + escapeHtml(display) + '</strong> wirklich l\u00F6schen?</p>'
            + '<p class="text-danger small">\u26A0 Dies kann nicht r\u00FCckg\u00E4ngig gemacht werden.</p>',
            'L\u00F6schen',
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
                        window.TaxonomyOperationResult.showError('L\u00F6schen fehlgeschlagen', 'Variante \u201E' + branch + '\u201C nicht gefunden.');
                    }
                    return null;
                }
                return r.json();
            })
            .then(function (result) {
                if (!result) return;
                if (result.error) {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showError('L\u00F6schen fehlgeschlagen', result.error);
                    } else {
                        alert('L\u00F6schen fehlgeschlagen: ' + result.error);
                    }
                } else {
                    if (window.TaxonomyOperationResult) {
                        window.TaxonomyOperationResult.showSuccess('Variante gel\u00F6scht',
                            'Variante \u201E' + branch + '\u201C wurde gel\u00F6scht.');
                    }
                    if (window.TaxonomyGitStatus) window.TaxonomyGitStatus.refresh();
                    refresh();
                }
            })
            .catch(function (err) {
                if (window.TaxonomyOperationResult) {
                    window.TaxonomyOperationResult.showError('L\u00F6schen fehlgeschlagen', err.message);
                } else {
                    alert('L\u00F6schen fehlgeschlagen: ' + err.message);
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
            '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">Abbrechen</button>' +
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

    var escapeHtml = TaxonomyUtils.escapeHtml;

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
