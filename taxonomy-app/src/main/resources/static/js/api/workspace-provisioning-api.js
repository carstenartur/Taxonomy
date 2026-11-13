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

    function rebuildProjection(expectedHead, source) {
        var url = '/api/architecture/relations/projection/rebuild';
        var policy = {};
        if (source) {
            var workspaceId = source.workspaceId == null ? '' : source.workspaceId;
            url += '?' + new URLSearchParams({repositoryId:source.repositoryId, workspaceId:workspaceId, branch:source.branch});
            policy.pinnedWorkspaceId = workspaceId;
        }
        return requestJson(url, {method:'POST', headers:{'If-Match':'"' + expectedHead + '"'}}, policy);
    }

    function requestJson(url, options, policy) {
        return window.TaxonomyApiClient.request(url, options || {}, Object.assign({
            retries: 0, acceptedStatuses: [400, 404, 409, 412, 422, 423]
        }, policy))
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
