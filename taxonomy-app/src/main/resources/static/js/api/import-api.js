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
        // Registered formats are application capabilities, independent of the
        // selected repository. Keep authentication and page-lifetime guards.
        return client.getJson('/api/import/profiles', {contextSensitive: false, workspaceScoped: false});
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
        return client.requestJson(path + (query.size ? '?' + query.toString() : ''),
            {method: 'POST', body: data, headers: headers}, {pinnedWorkspaceId: workspace});
    }

    function recoveryError(error) {
        var code = error?.responseBody?.code;
        var key = {
            ANALYSIS_EXPIRED: 'docimport.operation.expired', CANCELLED: 'docimport.operation.cancelled',
            STALE_SOURCE: 'docimport.operation.stale', COMMAND_CONFLICT: 'docimport.operation.stale',
            OUTCOME_UNKNOWN: 'docimport.operation.unknown'
        }[code];
        if (key && window.TaxonomyI18n?.t) error.message = window.TaxonomyI18n.t(key);
        return error;
    }

    async function analyzeDocument(path, data, owner, pending) {
        var context = client.captureContext();
        var current = function () { return context() && (!owner || owner()); };
        function guard() {
            if (!current()) throw Object.assign(new Error('Request context was superseded'), {code: 'ABORTED'});
        }
        var cleanup;
        try {
            var result = await client.sendFormData(path, data);
            if (result?.state !== 'RUNNING') return result;
            var id = result.operationId;
            if (!/^[a-f0-9-]{36}$/.test(id || '')) throw new Error('Invalid analysis operation');
            var statusPath = '/api/analysis-adapters/' + encodeURIComponent(id);
            guard();
            if (pending) cleanup = pending(async function () {
                guard();
                try { await client.sendJson(statusPath + '/cancel', {}, 'POST'); }
                catch (error) { if (error?.responseBody?.code !== 'CANCELLED') throw recoveryError(error); }
            });
            while (result?.state === 'RUNNING') {
                await new Promise(function (resolve) { window.setTimeout(resolve, 2000); });
                guard();
                result = await client.getJson(statusPath);
                if (result?.state === 'RUNNING' && result.operationId !== id)
                    throw new Error('Analysis operation changed');
            }
            guard();
            return result;
        } catch (error) { throw recoveryError(error); }
        finally { if (cleanup) cleanup(); }
    }

    return {
        uploadDocument: function (data) { return client.sendFormData('/api/documents/upload', data); },
        extractDocumentWithAi: function (data, owner, pending) { return analyzeDocument('/api/documents/extract-ai', data, owner, pending); },
        mapRegulation: function (data, owner, pending) { return analyzeDocument('/api/documents/map-regulation', data, owner, pending); },
        loadProfiles:    loadProfiles,
        preview:         preview,
        execute:         execute,
        importArchiMate: importArchiMate,
        previewArchiReturn: function (data) { return returnUpload('/api/import/preview/archimate-return', data); },
        applyArchiReturn: function (data) { return returnUpload('/api/import/archimate-return', data); }
    };
})();
