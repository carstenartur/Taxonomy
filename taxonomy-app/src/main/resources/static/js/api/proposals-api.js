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
        return window.TaxonomyApiClient.request(url, init, Object.assign({selectedRepository:true,acceptedStatuses:[400,404,409,412,422]}, options));
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

    function adoptionRequest(id, target, init, options) {
        const query = new URLSearchParams({repositoryId: target.repositoryId,
            workspaceId: target.workspaceId || '', branch: target.branch});
        return domainRequest('/api/proposals/' + encodeURIComponent(id) + '/adoption?' + query, init,
            Object.assign({}, options, {selectedRepository: false, pinnedWorkspaceId: target.workspaceId || ''}));
    }

    function previewAdoption(id, target, options) {
        return adoptionRequest(id, target, {method: 'GET', cache: 'no-store'}, options);
    }

    function adopt(preview, key, options) {
        const {sourceBranch, sourceCommit, targetRepositoryId, targetWorkspaceId, targetBranch} = preview;
        return adoptionRequest(preview.proposalId,
            {repositoryId: targetRepositoryId, workspaceId: targetWorkspaceId, branch: targetBranch}, {
                method: 'POST',
                headers: {'Content-Type': 'application/json', 'If-Match': '"' + preview.targetCommit + '"',
                    'Idempotency-Key': key},
                body: JSON.stringify({sourceBranch, sourceCommit, targetRepositoryId, targetWorkspaceId, targetBranch})
            }, options);
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
        previewAdoption: previewAdoption,
        adopt: adopt,
        readHead: readHead,
        review: review
    };
}());
