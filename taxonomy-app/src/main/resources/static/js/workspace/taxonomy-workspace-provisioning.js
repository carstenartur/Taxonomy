/**
 * taxonomy-workspace-provisioning.js — Workspace Provisioning UI
 *
 * Checks the provisioning status of the user's workspace on page load.
 * If the workspace is not yet provisioned, a modal dialog guides the user
 * through the creation of their personal working copy.
 *
 * Uses i18n keys for all user-visible labels.
 *
 * @module TaxonomyWorkspaceProvisioning
 */
window.TaxonomyWorkspaceProvisioning = (function () {
    'use strict';

    var t = TaxonomyI18n.t;
    var POLL_INTERVAL = 2000; // 2 seconds
    var pollTimer = null;

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Check workspace provisioning status and show dialog if needed.
     * Called on page load after i18n strings are available.
     */
    function check() {
        fetch('/api/workspace/provisioning-status')
            .then(function (r) { return r.json(); })
            .then(function (status) {
                switch (status.status) {
                    case 'NOT_PROVISIONED':
                        showProvisioningDialog(status);
                        break;
                    case 'PROVISIONING':
                        showSpinner();
                        pollUntilReady();
                        break;
                    case 'FAILED':
                        showRetryDialog(status.error);
                        break;
                    case 'READY':
                        // Workspace is ready, nothing to do
                        break;
                }
            })
            .catch(function (err) {
                console.warn('Could not check provisioning status:', err);
            });
    }

    // ── Provisioning Dialog ─────────────────────────────────────────

    function showProvisioningDialog(status) {
        removeExistingModal();
        var modal = document.createElement('div');
        modal.id = 'workspaceProvisioningModal';
        modal.className = 'modal fade';
        modal.setAttribute('data-bs-backdrop', 'static');
        modal.setAttribute('data-bs-keyboard', 'false');
        modal.setAttribute('tabindex', '-1');
        modal.innerHTML =
            '<div class="modal-dialog modal-dialog-centered">' +
            '  <div class="modal-content">' +
            '    <div class="modal-header">' +
            '      <h5 class="modal-title">' +
            '        <i class="bi bi-folder-plus me-2"></i>' +
                     t('workspace.provisioning.title') +
            '      </h5>' +
            '    </div>' +
            '    <div class="modal-body">' +
            '      <p>' + t('workspace.provisioning.message') + '</p>' +
            '      <div class="text-center mt-3">' +
            '        <button class="btn btn-primary" id="provisionBtn">' +
            '          <i class="bi bi-play-fill me-1"></i>' +
                       t('workspace.provisioning.button') +
            '        </button>' +
            '      </div>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(modal);
        var bsModal = new bootstrap.Modal(modal);
        bsModal.show();
        document.getElementById('provisionBtn').addEventListener('click', function () {
            doProvision(bsModal);
        });
    }

    function showSpinner() {
        removeExistingModal();
        var modal = document.createElement('div');
        modal.id = 'workspaceProvisioningModal';
        modal.className = 'modal fade';
        modal.setAttribute('data-bs-backdrop', 'static');
        modal.setAttribute('data-bs-keyboard', 'false');
        modal.setAttribute('tabindex', '-1');
        modal.innerHTML =
            '<div class="modal-dialog modal-dialog-centered">' +
            '  <div class="modal-content">' +
            '    <div class="modal-header">' +
            '      <h5 class="modal-title">' +
            '        <i class="bi bi-folder-plus me-2"></i>' +
                     t('workspace.provisioning.title') +
            '      </h5>' +
            '    </div>' +
            '    <div class="modal-body text-center">' +
            '      <div class="spinner-border text-primary mb-3" role="status">' +
            '        <span class="visually-hidden">Loading...</span>' +
            '      </div>' +
            '      <p>' + t('workspace.provisioning.preparing') + '</p>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(modal);
        var bsModal = new bootstrap.Modal(modal);
        bsModal.show();
    }

    function showRetryDialog(error) {
        removeExistingModal();
        var modal = document.createElement('div');
        modal.id = 'workspaceProvisioningModal';
        modal.className = 'modal fade';
        modal.setAttribute('data-bs-backdrop', 'static');
        modal.setAttribute('data-bs-keyboard', 'false');
        modal.setAttribute('tabindex', '-1');
        var safeError = error ? TaxonomyUtils.escapeHtml(error) : '';
        modal.innerHTML =
            '<div class="modal-dialog modal-dialog-centered">' +
            '  <div class="modal-content">' +
            '    <div class="modal-header">' +
            '      <h5 class="modal-title text-danger">' +
            '        <i class="bi bi-exclamation-triangle me-2"></i>' +
                     t('workspace.provisioning.failed') +
            '      </h5>' +
            '    </div>' +
            '    <div class="modal-body">' +
            '      <div class="alert alert-danger">' + safeError + '</div>' +
            '      <div class="text-center">' +
            '        <button class="btn btn-warning" id="retryProvisionBtn">' +
            '          <i class="bi bi-arrow-clockwise me-1"></i>' +
                       t('workspace.provisioning.retry') +
            '        </button>' +
            '      </div>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(modal);
        var bsModal = new bootstrap.Modal(modal);
        bsModal.show();
        document.getElementById('retryProvisionBtn').addEventListener('click', function () {
            doProvision(bsModal);
        });
    }

    function showReadyDialog() {
        removeExistingModal();
        var modal = document.createElement('div');
        modal.id = 'workspaceProvisioningModal';
        modal.className = 'modal fade';
        modal.setAttribute('tabindex', '-1');
        modal.innerHTML =
            '<div class="modal-dialog modal-dialog-centered">' +
            '  <div class="modal-content">' +
            '    <div class="modal-header">' +
            '      <h5 class="modal-title text-success">' +
            '        <i class="bi bi-check-circle me-2"></i>' +
                     t('workspace.provisioning.ready') +
            '      </h5>' +
            '    </div>' +
            '    <div class="modal-body text-center">' +
            '      <p>' + t('workspace.provisioning.ready') + '</p>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(modal);
        var bsModal = new bootstrap.Modal(modal);
        bsModal.show();
        setTimeout(function () {
            bsModal.hide();
            location.reload();
        }, 1500);
    }

    // ── Provisioning action ─────────────────────────────────────────

    function doProvision(existingModal) {
        if (existingModal) {
            existingModal.hide();
        }
        showSpinner();
        fetch('/api/workspace/provision', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.status === 'READY') {
                    showReadyDialog();
                } else if (result.error) {
                    showRetryDialog(result.error || result.message);
                } else {
                    pollUntilReady();
                }
            })
            .catch(function (err) {
                showRetryDialog(err.message || String(err));
            });
    }

    function pollUntilReady() {
        if (pollTimer) clearInterval(pollTimer);
        pollTimer = setInterval(function () {
            fetch('/api/workspace/provisioning-status')
                .then(function (r) { return r.json(); })
                .then(function (status) {
                    if (status.status === 'READY') {
                        clearInterval(pollTimer);
                        pollTimer = null;
                        showReadyDialog();
                    } else if (status.status === 'FAILED') {
                        clearInterval(pollTimer);
                        pollTimer = null;
                        showRetryDialog(status.error);
                    }
                    // PROVISIONING: keep polling
                })
                .catch(function () {
                    // Network error, keep polling
                });
        }, POLL_INTERVAL);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    function removeExistingModal() {
        var existing = document.getElementById('workspaceProvisioningModal');
        if (existing) {
            var bsModal = bootstrap.Modal.getInstance(existing);
            if (bsModal) bsModal.hide();
            existing.remove();
        }
    }

    return {
        check: check
    };
})();
