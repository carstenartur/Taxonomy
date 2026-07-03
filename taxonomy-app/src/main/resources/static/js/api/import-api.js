/* import-api.js – Framework import API module for the Taxonomy UI.
 *
 * Wraps all /api/import/* endpoints.  Feature modules should call these
 * named functions instead of constructing fetch('/api/import/…') directly.
 *
 * Depends on: TaxonomyApiClient (api/taxonomy-api-client.js)
 */
window.ImportApi = (function () {
    'use strict';

    var client = window.TaxonomyApiClient;

    /**
     * Load the list of available import profiles.
     * GET /api/import/profiles
     * @returns {Promise<Array>}
     */
    function loadProfiles() {
        return client.getJson('/api/import/profiles');
    }

    /**
     * Preview (dry-run) an import for the given profile.
     * POST /api/import/preview/{profileId}
     * @param {string}   profileId
     * @param {FormData} formData  – must contain the file under the key "file"
     * @returns {Promise<any>}
     */
    function preview(profileId, formData) {
        return client.sendFormData(
            '/api/import/preview/' + encodeURIComponent(profileId),
            formData
        );
    }

    /**
     * Execute a full import for the given profile on the specified branch.
     * POST /api/import/{profileId}?branch={branch}
     * @param {string}   profileId
     * @param {string}   branch
     * @param {FormData} formData  – must contain the file under the key "file"
     * @returns {Promise<any>}
     */
    function execute(profileId, branch, formData) {
        return client.sendFormData(
            '/api/import/' + encodeURIComponent(profileId) +
                '?branch=' + encodeURIComponent(branch),
            formData
        );
    }

    /**
     * Import an ArchiMate file.
     * POST /api/import/archimate
     * @param {FormData} formData  – must contain the file under the key "file"
     * @returns {Promise<any>}
     */
    function importArchiMate(formData) {
        return client.sendFormData('/api/import/archimate', formData);
    }

    return {
        loadProfiles:    loadProfiles,
        preview:         preview,
        execute:         execute,
        importArchiMate: importArchiMate
    };
})();
