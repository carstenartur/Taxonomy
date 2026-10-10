/* HTTP boundary for host-owned, data-only plugin capabilities. */
window.TaxonomyCapabilitiesApi = (function (global) {
    'use strict';

    async function load() {
        const response = await global.TaxonomyApiClient.request('/api/capabilities', {
            method: 'GET',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { Accept: 'application/json' }
        }, { idempotent: true });
        if (response.redirected) {
            throw new Error('Capabilities unavailable (HTTP ' + response.status + ')');
        }
        return response.json();
    }

    return Object.freeze({ load });
}(window));
