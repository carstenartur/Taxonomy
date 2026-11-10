/**
 * Raw-response API boundary for workspace provisioning and branch projection.
 *
 * The canonical named client supplies reverse-proxy base-path, CSRF,
 * authentication, source routing and guarded response-body handling while
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
        return window.TaxonomyApiClient.request(url, options || {}, {
            retries: 0, acceptedStatuses: [400, 404, 409, 412, 422, 423]
        })
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
