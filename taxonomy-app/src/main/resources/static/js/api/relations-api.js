/**
 * Raw-response API boundary for Git-authoritative relation commands.
 *
 * Relation mutations need access to HTTP status codes and ETag/projection
 * headers, including expected 202, 404, 409 and 412 responses. The canonical
 * fetch wrapper installed by taxonomy-api-client.js still supplies base-path
 * and CSRF handling; this feature client keeps transport out of UI modules.
 */
window.TaxonomyRelationsApi = (function () {
    'use strict';

    function readSnapshot(relationType) {
        var url = relationType
            ? '/api/relations?type=' + encodeURIComponent(relationType)
            : '/api/relations';
        return fetch(url, { cache: 'no-store' });
    }

    function upsertRelation(sourceCode, relationType, targetCode, payload, headers) {
        return fetch(commandUrl(sourceCode, relationType, targetCode), {
            method: 'PUT',
            headers: headers,
            body: JSON.stringify(payload)
        });
    }

    function deleteRelation(sourceCode, relationType, targetCode, headers) {
        return fetch(commandUrl(sourceCode, relationType, targetCode), {
            method: 'DELETE',
            headers: headers
        });
    }

    function commandUrl(sourceCode, relationType, targetCode) {
        return '/api/architecture/relations/'
            + encodeURIComponent(sourceCode) + '/'
            + encodeURIComponent(relationType) + '/'
            + encodeURIComponent(targetCode);
    }

    return {
        readSnapshot: readSnapshot,
        upsertRelation: upsertRelation,
        deleteRelation: deleteRelation
    };
}());
