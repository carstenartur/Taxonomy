/**
 * taxonomy-context-transfer.js — Selective Transfer (Copy Back)
 *
 * Provides UI for selecting individual elements and relations to
 * transfer between architecture contexts, with conflict preview
 * and resolution.
 *
 * @module TaxonomyContextTransfer
 */
window.TaxonomyContextTransfer = (function () {
    'use strict';

    var t = TaxonomyI18n.t;
    function ignoredError(error) { return error?.code === 'ABORTED' || error?.status === 401 || error?.status === 403; }

    var shownDialog = null;
    var boundDialogs = new WeakSet();

    function ownsDialog(dialog) {
        return dialog && dialog === shownDialog &&
            dialog.workspaceId === window.__TaxonomyAnalysisSessionContext?.runtime.workspaceId;
    }

    function clearDialog() {
        shownDialog = null;
        ['transferSourceCommit', 'transferTargetCommit', 'transferElementIds', 'transferRelationIds'].forEach(function (id) {
            var input = document.getElementById(id);
            if (input) input.value = '';
        });
        var results = document.getElementById('transferPreviewResults');
        if (results) results.innerHTML = '';
    }

    function dismissDialog() {
        if (!shownDialog) return;
        var modal = shownDialog.modal;
        clearDialog();
        bootstrap.Modal.getInstance(modal)?.hide();
    }

    document.addEventListener('taxonomy:repository-context-changed', function (event) {
        if (event.detail?.selectionChanged) dismissDialog();
    });
    window.addEventListener('pagehide', dismissDialog);

    /**
     * Show the transfer dialog.
     *
     * @param {string} sourceCommitId — source context commit ID
     * @param {string} targetCommitId — target context commit ID
     * @param {string} contextId — navigation selection when the dialog opened
     */
    function showDialog(sourceCommitId, targetCommitId, contextId) {
        var modal = document.getElementById('contextTransferModal');
        if (!modal) return;
        if (!boundDialogs.has(modal)) {
            boundDialogs.add(modal);
            modal.addEventListener('hide.bs.modal', clearDialog);
            modal.addEventListener('shown.bs.modal', function () {
                // Bootstrap ignores hide() during the opening transition.
                if (!ownsDialog(shownDialog)) bootstrap.Modal.getInstance(modal)?.hide();
            });
        }
        clearDialog();
        shownDialog = { modal: modal, contextId: contextId,
            workspaceId: window.__TaxonomyAnalysisSessionContext?.runtime.workspaceId };

        var srcInput = document.getElementById('transferSourceCommit');
        var tgtInput = document.getElementById('transferTargetCommit');
        if (srcInput) srcInput.value = sourceCommitId || '';
        if (tgtInput) tgtInput.value = targetCommitId || '';

        var bsModal = bootstrap.Modal.getOrCreateInstance(modal);
        bsModal.show();
    }

    /**
     * Preview the selective transfer.
     */
    function previewTransfer() {
        var dialog = shownDialog;
        if (!ownsDialog(dialog)) return;
        var selection = buildSelection();
        if (!selection) return;

        return window.TaxonomyVersioningApi.copyBackPreview(selection)
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (preview) {
                if (ownsDialog(dialog) && preview) renderPreview('transferPreviewResults', preview);
            })
            .catch(function (error) {
                if (!ownsDialog(dialog) || ignoredError(error)) return;
                var container = document.getElementById('transferPreviewResults');
                if (container) container.innerHTML = '<p class="text-danger">' + t('transfer.preview.failed') + '</p>';
            });
    }

    /**
     * Apply the selective transfer.
     */
    function applyTransfer() {
        var dialog = shownDialog;
        if (!ownsDialog(dialog)) return;
        var selection = buildSelection();
        if (!selection) return;

        if (!confirm(t('transfer.apply.confirm'))) return;

        return window.TaxonomyVersioningApi.copyBackApply(selection, dialog.contextId)
            .then(function (response) {
                if (!ownsDialog(dialog)) return;
                if (response.status === 409) {
                    alert(t('transfer.apply.stale'));
                    return;
                }
                return response.json().then(function (result) {
                    if (!ownsDialog(dialog)) return;
                    if (response.ok && result?.success) {
                        alert(t('transfer.apply.success', (result.commitId || '').substring(0, 7)));
                        var modal = bootstrap.Modal.getInstance(document.getElementById('contextTransferModal'));
                        if (modal) modal.hide();
                        if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                    } else {
                        alert(t('transfer.apply.failed', result?.message || result?.error || t('error.internal')));
                    }
                });
            })
            .catch(function (error) {
                if (ignoredError(error)) return;
                if (ownsDialog(dialog)) alert(t('transfer.apply.failed', t('error.internal')));
            });
    }

    /**
     * Render the transfer preview.
     *
     * @param {string} containerId — DOM ID
     * @param {object} preview — preview response from API
     */
    function renderPreview(containerId, preview) {
        var container = document.getElementById(containerId);
        if (!container) return;

        var html = '<div class="alert ' + (preview.hasConflicts ? 'alert-warning' : 'alert-success') + '">';
        html += t('transfer.selected', preview.selectedElements, preview.selectedRelations);
        if (preview.hasConflicts) {
            html += ' <strong>' + t('transfer.conflicts', preview.conflicts.length) + '</strong>';
        } else {
            html += ' ' + t('transfer.no.conflicts');
        }
        html += '</div>';

        if (preview.conflicts && preview.conflicts.length > 0) {
            html += '<table class="table table-sm">';
            html += '<thead><tr><th>' + t('transfer.header.id') + '</th><th>' + t('transfer.header.current') + '</th><th>' + t('transfer.header.incoming') + '</th><th>' + t('transfer.header.affected.views') + '</th></tr></thead>';
            html += '<tbody>';
            preview.conflicts.forEach(function (c) {
                html += '<tr>';
                html += '<td>' + escapeHtml(c.elementOrRelationId) + '</td>';
                html += '<td>' + escapeHtml(c.originValue) + '</td>';
                html += '<td>' + escapeHtml(c.incomingValue) + '</td>';
                html += '<td>' + (c.affectedViews ? c.affectedViews.map(escapeHtml).join(', ') : '') + '</td>';
                html += '</tr>';
            });
            html += '</tbody></table>';
        }

        container.innerHTML = html;
    }

    /**
     * Build the transfer selection from form inputs.
     *
     * @returns {object|null} TransferSelection or null if inputs are missing
     */
    function buildSelection() {
        var srcInput = document.getElementById('transferSourceCommit');
        var tgtInput = document.getElementById('transferTargetCommit');
        var elemInput = document.getElementById('transferElementIds');
        var relInput = document.getElementById('transferRelationIds');

        if (!srcInput || !tgtInput || !srcInput.value || !tgtInput.value) {
            alert(t('transfer.required.ids'));
            return null;
        }

        var elementIds = elemInput && elemInput.value
            ? elemInput.value.split(',').map(function (s) { return s.trim(); }).filter(Boolean)
            : [];
        var relationIds = relInput && relInput.value
            ? relInput.value.split(',').map(function (s) { return s.trim(); }).filter(Boolean)
            : [];

        return {
            sourceContextId: srcInput.value.trim(),
            targetContextId: tgtInput.value.trim(),
            selectedElementIds: elementIds,
            selectedRelationIds: relationIds,
            mode: 'COPY'
        };
    }

    var escapeHtml = TaxonomyUtils.escapeHtml;

    return {
        showDialog: showDialog,
        previewTransfer: previewTransfer,
        applyTransfer: applyTransfer
    };
}());
