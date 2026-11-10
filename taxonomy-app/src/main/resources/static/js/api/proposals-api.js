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
    function domainRequest(url, init, options) {
        return window.TaxonomyApiClient.request(url, init, Object.assign({acceptedStatuses:[400,404,409,412,422]}, options));
    }

    function readHead() {
        return domainRequest('/api/proposals/head', {
            method: 'GET',
            cache: 'no-store'
        });
    }

    function review(proposalId, action, headers) {
        return domainRequest('/api/proposals/'
                + encodeURIComponent(proposalId) + '/'
                + encodeURIComponent(action.toLowerCase()), {
            method: 'POST',
            headers: headers
        });
    }

    function post(path, payload, options) { return domainRequest('/api/proposals' + path, {
        method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)
    }, options); }
    return {
        fromHypothesis: function (payload, options) { return post('/from-hypothesis', payload, options); },
        bulk: function (payload) { return post('/bulk', payload); },
        propose: function (payload) { return post('/propose', payload); },
        list: function (status, options) { return domainRequest('/api/proposals' + (status === 'PENDING' ? '/pending' : ''), undefined, options); },
        pending: function (options) { return domainRequest('/api/proposals/pending', undefined, options); },
        readHead: readHead,
        review: review
    };
}());
