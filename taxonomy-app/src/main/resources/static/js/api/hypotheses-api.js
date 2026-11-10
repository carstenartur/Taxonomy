/**
 * Raw-response API boundary for direct Git-authoritative hypothesis review.
 *
 * Callers must inspect status and ETag because 202 means Git authority exists
 * with pending recovery and 412 means the branch moved concurrently.
 */
window.TaxonomyHypothesesApi = (function () {
    'use strict';
    function domainRequest(url, init, options) {
        return window.TaxonomyApiClient.request(url, init, Object.assign({acceptedStatuses:[400,404,409,412,422]}, options));
    }

    function readHead() {
        return domainRequest('/api/dsl/hypotheses/head', {
            method: 'GET',
            cache: 'no-store'
        });
    }

    function review(hypothesisId, action, headers) {
        return domainRequest('/api/dsl/hypotheses/'
                + encodeURIComponent(hypothesisId) + '/'
                + encodeURIComponent(action.toLowerCase()), {
            method: 'POST',
            headers: headers
        });
    }

    function applyForSession(hypothesisId) {
        return domainRequest('/api/dsl/hypotheses/'
                + encodeURIComponent(hypothesisId)
                + '/apply-session', {
            method: 'POST'
        });
    }

    return {
        readHead: readHead,
        review: review,
        applyForSession: applyForSession
    };
}());
