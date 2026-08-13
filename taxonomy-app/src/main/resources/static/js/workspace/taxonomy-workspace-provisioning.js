/**
 * taxonomy-workspace-provisioning.js — Workspace Provisioning UI
 *
 * Checks the provisioning status of the user's workspace on page load.
 * If the workspace is not yet provisioned, a modal dialog guides the user
 * through creation of the personal working copy and its branch projection.
 *
 * Uses i18n keys for all user-visible labels.
 *
 * @module TaxonomyWorkspaceProvisioning
 */
window.TaxonomyWorkspaceProvisioning = (function () {
    'use strict';

    var t = TaxonomyI18n.t;
    var POLL_INTERVAL = 2000;
    var pollTimer = null;
    var loaderScript = document.currentScript;
    var apiReady = loadApiClient(loaderScript);

    window.TaxonomyWorkspaceProvisioningApiReady = apiReady;

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Check workspace provisioning status and show dialog if needed.
     * Called on page load after i18n strings are available.
     */
    function check() {
        apiReady
            .then(function () {
                return Api().provisioningStatus();
            })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error(responseError(
                        'Could not check provisioning status', response));
                }
                var status = response.body;
                switch (status.status) {
                    case 'NOT_PROVISIONED':
                        showProvisioningDialog();
                        break;
                    case 'PROVISIONING':
                        showSpinner();
                        pollUntilReady();
                        break;
                    case 'FAILED':
                        showRetryDialog(status.error);
                        break;
                    case 'READY':
                        ensureRelationProjection().catch(function (error) {
                            showRetryDialog(error.message || String(error));
                        });
                        break;
                }
            })
            .catch(function (error) {
                console.warn('Could not check provisioning status:', error);
            });
    }

    // ── Provisioning Dialog ─────────────────────────────────────────

    function showProvisioningDialog() {
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
                     t('workspace.provisioning.ready.title') +
            '      </h5>' +
            '    </div>' +
            '    <div class="modal-body text-center">' +
            '      <p>' + t('workspace.provisioning.ready.message') + '</p>' +
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
        apiReady
            .then(function () {
                return Api().provisionWorkspace();
            })
            .then(function (response) {
                var result = response.body;
                if (!response.ok) {
                    throw new Error(result.message || result.error ||
                        'Workspace provisioning failed');
                }
                if (result.status === 'READY') {
                    return ensureRelationProjection().then(showReadyDialog);
                }
                if (result.error) {
                    showRetryDialog(result.error || result.message);
                    return null;
                }
                pollUntilReady();
                return null;
            })
            .catch(function (error) {
                showRetryDialog(error.message || String(error));
            });
    }

    function pollUntilReady() {
        if (pollTimer) clearInterval(pollTimer);
        pollTimer = setInterval(function () {
            apiReady
                .then(function () {
                    return Api().provisioningStatus();
                })
                .then(function (response) {
                    var status = response.body;
                    if (status.status === 'READY') {
                        clearInterval(pollTimer);
                        pollTimer = null;
                        ensureRelationProjection()
                            .then(showReadyDialog)
                            .catch(function (error) {
                                showRetryDialog(error.message || String(error));
                            });
                    } else if (status.status === 'FAILED') {
                        clearInterval(pollTimer);
                        pollTimer = null;
                        showRetryDialog(status.error);
                    }
                })
                .catch(function () {
                    // Network error, keep polling.
                });
        }, POLL_INTERVAL);
    }

    /**
     * Materialize the exact workspace branch before the UI reports the workspace
     * as usable. This keeps product reads and proposal duplicate validation on
     * the same fail-closed, Git-authoritative projection.
     */
    function ensureRelationProjection() {
        return apiReady
            .then(function () {
                return Api().projectionReadiness();
            })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error(responseError(
                        'Could not inspect relation projection', response));
                }
                var readiness = response.body;
                if (readiness.readinessState === 'READY') {
                    return readiness;
                }
                if (!readiness.currentHeadCommit) {
                    throw new Error(responseError(
                        'Workspace has no authoritative branch head', response));
                }
                return Api().rebuildProjection(readiness.currentHeadCommit)
                    .then(function (rebuildResponse) {
                        if (!rebuildResponse.ok ||
                                rebuildResponse.body.readinessState !== 'READY') {
                            throw new Error(responseError(
                                'Could not rebuild relation projection',
                                rebuildResponse));
                        }
                        return rebuildResponse.body;
                    });
            });
    }

    function Api() {
        if (!window.TaxonomyWorkspaceProvisioningApi) {
            throw new Error('Workspace provisioning API client is unavailable.');
        }
        return window.TaxonomyWorkspaceProvisioningApi;
    }

    function loadApiClient(currentScript) {
        if (window.TaxonomyWorkspaceProvisioningApi) {
            return Promise.resolve(window.TaxonomyWorkspaceProvisioningApi);
        }
        if (!currentScript || !currentScript.src) {
            return Promise.reject(new Error(
                'Cannot resolve workspace provisioning API client URL.'));
        }

        return new Promise(function (resolve, reject) {
            var script = document.createElement('script');
            script.src = new URL(
                '../api/workspace-provisioning-api.js',
                currentScript.src).href;
            script.async = false;
            script.setAttribute('data-taxonomy-workspace-provisioning-api', 'true');
            script.onload = function () {
                if (window.TaxonomyWorkspaceProvisioningApi) {
                    resolve(window.TaxonomyWorkspaceProvisioningApi);
                } else {
                    reject(new Error(
                        'Workspace provisioning API client did not initialize.'));
                }
            };
            script.onerror = function () {
                reject(new Error('Workspace provisioning API client failed to load.'));
            };
            document.head.appendChild(script);
        });
    }

    function responseError(prefix, response) {
        var body = response.body || {};
        return prefix + ' (' + response.status + '): ' +
            (body.message || body.operationStatus || body.readinessState || 'unknown error');
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
