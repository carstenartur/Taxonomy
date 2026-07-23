/** Role-aware visibility for controls whose backing APIs mutate architecture state. */
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

    function applyElement(element) {
        var required = element.getAttribute('data-required-roles');
        if (!required) return;
        var allowed = hasAnyRole(required);
        element.hidden = !allowed;
        element.setAttribute('aria-hidden', allowed ? 'false' : 'true');
        if ('disabled' in element) element.disabled = !allowed;
    }

    function applyStaticSurfaces(root) {
        var scope = root || document;
        scope.querySelectorAll('[data-required-roles]').forEach(applyElement);
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
                document.dispatchEvent(new CustomEvent('taxonomy:roles-ready', {
                    detail: context
                }));
                resolveReady(context);
                return context;
            })
            .catch(function (error) {
                console.error('[Taxonomy] Unable to load role context', error);
                applyStaticSurfaces(document);
                resolveReady(context);
                return context;
            });
    }

    document.addEventListener('DOMContentLoaded', refresh);

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
