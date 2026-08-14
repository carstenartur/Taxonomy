/**
 * Raw-response API boundary for Git-authoritative proposal review commands.
 *
 * Callers need the strong branch ETag and must distinguish projected success,
 * accepted-with-pending-recovery, stale preconditions and ordinary review
 * rejection. The canonical fetch wrapper still supplies base-path and CSRF
 * handling; this boundary intentionally returns the unconsumed Response.
 */
window.TaxonomyProposalsApi = (function () {
    'use strict';

    function readHead() {
        return fetch('/api/proposals/head', {
            method: 'GET',
            cache: 'no-store'
        });
    }

    function review(proposalId, action, headers) {
        return fetch('/api/proposals/'
                + encodeURIComponent(proposalId) + '/'
                + encodeURIComponent(action.toLowerCase()), {
            method: 'POST',
            headers: headers
        });
    }

    return {
        readHead: readHead,
        review: review
    };
}());
