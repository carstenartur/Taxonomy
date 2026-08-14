/**
 * Raw-response API boundary for direct Git-authoritative hypothesis review.
 *
 * Callers must inspect status and ETag because 202 means Git authority exists
 * with pending recovery and 412 means the branch moved concurrently.
 */
window.TaxonomyHypothesesApi = (function () {
    'use strict';

    function readHead() {
        return fetch('/api/dsl/hypotheses/head', {
            method: 'GET',
            cache: 'no-store'
        });
    }

    function review(hypothesisId, action, headers) {
        return fetch('/api/dsl/hypotheses/'
                + encodeURIComponent(hypothesisId) + '/'
                + encodeURIComponent(action.toLowerCase()), {
            method: 'POST',
            headers: headers
        });
    }

    function applyForSession(hypothesisId) {
        return fetch('/api/dsl/hypotheses/'
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
