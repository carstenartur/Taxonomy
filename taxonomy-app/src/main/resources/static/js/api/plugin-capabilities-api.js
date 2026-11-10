/* HTTP boundary for host-owned, data-only plugin capabilities. */
window.TaxonomyCapabilitiesApi = (function (global) {
    'use strict';

    async function load(options) {
        const response = await global.TaxonomyApiClient.request('/api/capabilities', {
            method: 'GET',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { Accept: 'application/json' }
        }, Object.assign({}, options || {}, {
            // The host catalog and installed features are global startup data.
            // Keep authentication, caller/page cancellation and body deadlines.
            idempotent: true, contextSensitive: false, workspaceScoped: false
        }));
        if (response.redirected) {
            throw new Error('Capabilities unavailable (HTTP ' + response.status + ')');
        }
        return response.json();
    }

    return Object.freeze({ load });
}(window));
