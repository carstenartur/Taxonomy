/** Role-aware visibility for controls whose backing APIs mutate application state. */
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

    function refresh() {
        return fetch('/api/account/me')
            .then(function (response) {
                if (!response.ok) throw new Error('HTTP ' + response.status);
                return response.json();
            })
            .then(function (data) {
                context = data;
                applyStaticSurfaces(document);
                installMutationObserver();
                installAccessibleAlertBridge();
                document.dispatchEvent(new CustomEvent('taxonomy:roles-ready', {
                    detail: context
                }));
                resolveReady(context);
                return context;
            })
            .catch(function (error) {
                console.error('[Taxonomy] Unable to load role context', error);
                applyStaticSurfaces(document);
                installMutationObserver();
                installAccessibleAlertBridge();
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
