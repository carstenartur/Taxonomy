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

    /**
     * Show the transfer dialog.
     *
     * @param {string} sourceCommitId — source context commit ID
     * @param {string} targetCommitId — target context commit ID
     */
    function showDialog(sourceCommitId, targetCommitId) {
        var modal = document.getElementById('contextTransferModal');
        if (!modal) return;

        var srcInput = document.getElementById('transferSourceCommit');
        var tgtInput = document.getElementById('transferTargetCommit');
        if (srcInput) srcInput.value = sourceCommitId || '';
        if (tgtInput) tgtInput.value = targetCommitId || '';

        var bsModal = new bootstrap.Modal(modal);
        bsModal.show();
    }

    /**
     * Preview the selective transfer.
     */
    function previewTransfer() {
        var selection = buildSelection();
        if (!selection) return;

        fetch('/api/context/copy-back/preview', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(selection)
        })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (preview) {
                if (preview) renderPreview('transferPreviewResults', preview);
            })
            .catch(function () {
                var container = document.getElementById('transferPreviewResults');
                if (container) container.innerHTML = '<p class="text-danger">' + t('transfer.preview.failed') + '</p>';
            });
    }

    /**
     * Apply the selective transfer.
     */
    function applyTransfer() {
        var selection = buildSelection();
        if (!selection) return;

        if (!confirm(t('transfer.apply.confirm'))) return;

        fetch('/api/context/copy-back/apply', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(selection)
        })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (result) {
                if (result && result.success) {
                    alert(t('transfer.apply.success', (result.commitId || '').substring(0, 7)));
                    var modal = bootstrap.Modal.getInstance(document.getElementById('contextTransferModal'));
                    if (modal) modal.hide();
                    if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                } else {
                    alert(t('transfer.apply.failed', result ? result.error : 'Unknown error'));
                }
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
