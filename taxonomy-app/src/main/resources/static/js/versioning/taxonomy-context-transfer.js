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
                if (container) container.innerHTML = '<p class="text-danger">Preview failed.</p>';
            });
    }

    /**
     * Apply the selective transfer.
     */
    function applyTransfer() {
        var selection = buildSelection();
        if (!selection) return;

        if (!confirm('Apply the selective transfer? This will create a new commit.')) return;

        fetch('/api/context/copy-back/apply', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(selection)
        })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (result) {
                if (result && result.success) {
                    alert('Transfer completed. Commit: ' + (result.commitId || '').substring(0, 7));
                    var modal = bootstrap.Modal.getInstance(document.getElementById('contextTransferModal'));
                    if (modal) modal.hide();
                    if (window.TaxonomyContextBar) window.TaxonomyContextBar.fetchAndRender('contextBar');
                } else {
                    alert('Transfer failed: ' + (result ? result.error : 'Unknown error'));
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
        html += 'Selected: ' + preview.selectedElements + ' elements, ' + preview.selectedRelations + ' relations.';
        if (preview.hasConflicts) {
            html += ' <strong>' + preview.conflicts.length + ' conflict(s) detected.</strong>';
        } else {
            html += ' No conflicts.';
        }
        html += '</div>';

        if (preview.conflicts && preview.conflicts.length > 0) {
            html += '<table class="table table-sm">';
            html += '<thead><tr><th>ID</th><th>Current</th><th>Incoming</th><th>Affected Views</th></tr></thead>';
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
            alert('Source and target commit IDs are required.');
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
