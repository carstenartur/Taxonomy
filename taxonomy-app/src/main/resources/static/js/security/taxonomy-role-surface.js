/** Role-aware and accessibility-aware surfaces for authenticated application controls. */
window.TaxonomyRoleSurface = (function () {
    'use strict';

    var context = {
        username: null,
        roles: [],
        architectureMutationAllowed: false,
        administrator: false
    };
    var resolveReady;
    var ready = new Promise(function (resolve) { resolveReady = resolve; });
    var observer;

    var architectureSelectors = [
        '#docImportUploadBtn',
        '#createRelationBtn',
        '#archiMateImportBtn',
        '#importExecuteBtn',
        '#proposeRelationsSubmit',
        '.btn-accept',
        '.btn-reject',
        '.proposal-select',
        '#proposalSelectAll',
        '#bulkProposalActions',
        '.relation-delete-btn'
    ];

    var administratorSelectors = [
        '#wsCreateBtn',
        'button[onclick*="TaxonomyWorkspaceSync.syncFromShared"]',
        'button[onclick*="TaxonomyWorkspaceSync.publish"]',
        'button[onclick*="TaxonomyWorkspaceSync.resolveDivergence"]'
    ];

    function hasAnyRole(requiredRoles) {
        var required = Array.isArray(requiredRoles)
            ? requiredRoles
            : String(requiredRoles || '').split(',');
        return required.some(function (role) {
            return context.roles.indexOf(String(role).trim()) >= 0;
        });
    }

    function canMutateArchitecture() {
        return context.architectureMutationAllowed === true;
    }

    function isAdministrator() {
        return context.administrator === true;
    }

    function setAllowed(element, allowed) {
        element.hidden = !allowed;
        element.setAttribute('aria-hidden', allowed ? 'false' : 'true');
        if ('disabled' in element) element.disabled = !allowed;
    }

    function applyElement(element) {
        var required = element.getAttribute('data-required-roles');
        if (required) setAllowed(element, hasAnyRole(required));
    }

    function applySelectorGroup(scope, selectors, allowed) {
        selectors.forEach(function (selector) {
            scope.querySelectorAll(selector).forEach(function (element) {
                setAllowed(element, allowed);
            });
        });
    }

    function ensureStableEvidenceAnchors() {
        var panel = document.getElementById('documentImportPanel');
        if (panel && !document.getElementById('docImportPanel')) {
            var wrapper = document.createElement('div');
            wrapper.id = 'docImportPanel';
            wrapper.className = 'document-import-accessibility-surface';
            panel.parentNode.insertBefore(wrapper, panel);
            wrapper.appendChild(panel);
        }
    }

    function applyStaticSurfaces(root) {
        var scope = root && root.querySelectorAll ? root : document;
        scope.querySelectorAll('[data-required-roles]').forEach(applyElement);
        applySelectorGroup(scope, architectureSelectors, canMutateArchitecture());
        applySelectorGroup(scope, administratorSelectors, isAdministrator());
    }

    function installMutationObserver() {
        if (observer || !document.body) return;
        observer = new MutationObserver(function (records) {
            records.forEach(function (record) {
                record.addedNodes.forEach(function (node) {
                    if (node.nodeType === Node.ELEMENT_NODE) {
                        applyStaticSurfaces(node);
                        if (node.matches && node.matches('[data-required-roles]')) applyElement(node);
                        architectureSelectors.forEach(function (selector) {
                            if (node.matches && node.matches(selector)) setAllowed(node, canMutateArchitecture());
                        });
                        administratorSelectors.forEach(function (selector) {
                            if (node.matches && node.matches(selector)) setAllowed(node, isAdministrator());
                        });
                    }
                });
            });
        });
        observer.observe(document.body, { childList: true, subtree: true });
    }

    function installAccessibleAlertBridge() {
        if (window.alert.__taxonomyAccessibleBridge) return;
        var originalAlert = window.alert.bind(window);
        function accessibleAlert(message) {
            if (window.TaxonomyOperationResult) {
                window.TaxonomyOperationResult.showError('Error', String(message || ''));
            } else {
                originalAlert(message);
            }
        }
        accessibleAlert.__taxonomyAccessibleBridge = true;
        accessibleAlert.__taxonomyOriginalAlert = originalAlert;
        window.alert = accessibleAlert;
    }

    function renderWorkspaceOffline(error) {
        var panel = document.getElementById('syncStatePanel');
        var title = window.TaxonomyI18n
            ? window.TaxonomyI18n.t('workspace.sync.failed')
            : 'Workspace synchronization failed';
        var detail = window.TaxonomyI18n
            ? window.TaxonomyI18n.t('analyze.connection.lost')
            : 'The remote repository cannot be reached. Check the connection and retry.';
        if (panel) {
            panel.innerHTML = '<div class="alert alert-warning py-2 mb-0" role="status" aria-live="polite">' +
                '<strong>' + TaxonomyUtils.escapeHtml(title) + '</strong><br>' +
                '<span class="small">' + TaxonomyUtils.escapeHtml(detail) + '</span></div>';
        }
        if (window.TaxonomyOperationResult) {
            window.TaxonomyOperationResult.showWarning(title, detail, error && error.message);
        }
    }

    function refreshWorkspaceConnection() {
        return window.TaxonomyApiClient.getJson('/api/workspace/sync-state')
            .catch(function (error) {
                renderWorkspaceOffline(error);
                throw error;
            });
    }

    function installWorkspaceOfflineGuard() {
        window.TaxonomySyncOfflineGuard = {
            refresh: refreshWorkspaceConnection,
            renderOffline: renderWorkspaceOffline
        };
        document.addEventListener('click', function (event) {
            var tab = event.target.closest('[data-versions-tab="sync"]');
            if (tab) {
                window.setTimeout(function () {
                    refreshWorkspaceConnection().catch(function () { /* rendered above */ });
                }, 0);
            }
        });
    }

    function refresh() {
        return window.TaxonomyApiClient.getJson('/api/account/me')
            .then(function (data) {
                context = data;
                ensureStableEvidenceAnchors();
                applyStaticSurfaces(document);
                installMutationObserver();
                installAccessibleAlertBridge();
                installWorkspaceOfflineGuard();
                document.dispatchEvent(new CustomEvent('taxonomy:roles-ready', {
                    detail: context
                }));
                resolveReady(context);
                return context;
            })
            .catch(function (error) {
                console.error('[Taxonomy] Unable to load role context', error);
                ensureStableEvidenceAnchors();
                applyStaticSurfaces(document);
                installMutationObserver();
                installAccessibleAlertBridge();
                installWorkspaceOfflineGuard();
                resolveReady(context);
                return context;
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', refresh);
    } else {
        refresh();
    }

    return {
        ready: ready,
        refresh: refresh,
        getContext: function () { return context; },
        hasAnyRole: hasAnyRole,
        canMutateArchitecture: canMutateArchitecture,
        isAdministrator: isAdministrator,
        applyStaticSurfaces: applyStaticSurfaces
    };
}());
