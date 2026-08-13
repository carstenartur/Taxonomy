/**
 * Raw-response API boundary for workspace provisioning and branch projection.
 *
 * The canonical fetch wrapper installed by taxonomy-api-client.js supplies
 * reverse-proxy base-path and CSRF handling. This client owns transport while
 * the workspace UI owns only modal and lifecycle presentation.
 */
window.TaxonomyWorkspaceProvisioningApi = (function () {
    'use strict';

    function provisioningStatus() {
        return requestJson('/api/workspace/provisioning-status', {
            cache: 'no-store'
        });
    }

    function provisionWorkspace() {
        return requestJson('/api/workspace/provision', {
            method: 'POST'
        });
    }

    function projectionReadiness() {
        return requestJson('/api/architecture/relations/projection/readiness', {
            cache: 'no-store'
        });
    }

    function rebuildProjection(expectedHead) {
        return requestJson('/api/architecture/relations/projection/rebuild', {
            method: 'POST',
            headers: {
                'If-Match': '"' + expectedHead + '"'
            }
        });
    }

    function requestJson(url, options) {
        return fetch(url, options || {})
            .then(function (response) {
                return response.text().then(function (text) {
                    var body = {};
                    if (text) {
                        try {
                            body = JSON.parse(text);
                        } catch (error) {
                            body = { message: text };
                        }
                    }
                    return {
                        ok: response.ok,
                        status: response.status,
                        body: body
                    };
                });
            });
    }

    return {
        provisioningStatus: provisioningStatus,
        provisionWorkspace: provisionWorkspace,
        projectionReadiness: projectionReadiness,
        rebuildProjection: rebuildProjection
    };
}());
