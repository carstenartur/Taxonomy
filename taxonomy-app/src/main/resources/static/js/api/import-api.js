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

    // The explicit page selection must survive shared active-tab routing. Use the
    // existing canonical client's request policy, including an explicit empty central pin.
    function returnUpload(path, data) {
        var selection = new URL(window.location.href).searchParams;
        var query = new URLSearchParams();
        ['repositoryId', 'workspaceId', 'branch'].forEach(function (key) {
            if (selection.has(key)) query.set(key, selection.get(key));
        });
        var workspace = selection.has('workspaceId') ? selection.get('workspaceId') : undefined;
        var headers = {};
        if (workspace !== undefined) headers['X-Taxonomy-Workspace-Id'] = workspace;
        return client.request(path + (query.size ? '?' + query.toString() : ''),
            {method: 'POST', body: data, headers: headers}, {pinnedWorkspaceId: workspace})
            .then(function (response) { return response.json(); });
    }

    return {
        loadProfiles:    loadProfiles,
        preview:         preview,
        execute:         execute,
        importArchiMate: importArchiMate,
        previewArchiReturn: function (data) { return returnUpload('/api/import/preview/archimate-return', data); },
        applyArchiReturn: function (data) { return returnUpload('/api/import/archimate-return', data); }
    };
})();
