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
    function domainRequest(url, init, options) {
        return window.TaxonomyApiClient.request(url, init, Object.assign({acceptedStatuses:[400,404,409,412,422]}, options));
    }

    async function coverageRequest(path, init) {
        var source = await window.AnalysisApi.captureContext();
        var workspace = source.workspaceScopeKey === '__shared__' ? '' : source.workspaceScopeKey;
        var params = new URLSearchParams({repositoryId:source.repositoryId, workspaceId:workspace,
            branch:source.branch, commitId:source.commit});
        var headers = new Headers(init?.headers);
        headers.set('X-Taxonomy-Workspace-Id', workspace);
        return domainRequest(path + '?' + params, Object.assign({}, init, {headers:headers}), {pinnedWorkspaceId:workspace, retries:0});
    }

    function readQualityDashboard() {
        return TaxonomyApiClient.getJson('/api/relations/metrics/dashboard?limit=5', {selectedRepository:true});
    }

    function readSnapshot(relationType) {
        var url = relationType
            ? '/api/relations?type=' + encodeURIComponent(relationType)
            : '/api/relations';
        return domainRequest(url, {cache:'no-store'}, {selectedRepository:true, acceptedStatuses:[404,409,412,503]});
    }

    function readProjectionReadiness(source, options) {
        return projectionRequest('/readiness', 'GET', source, {}, options);
    }

    // Keep an admitted rebuild on the repository and branch inspected even
    // when the current tab subsequently switches to a different central source.
    function rebuildProjection(status, headEtag) {
        return projectionRequest('/rebuild', 'POST', status, {'If-Match':headEtag}, {contextSensitive:false});
    }

    function projectionRequest(path, method, source, headers, options) {
        var url = '/api/architecture/relations/projection' + path;
        var workspaceId = source;
        if (source && typeof source === 'object') {
            workspaceId = source.workspaceId == null ? '' : source.workspaceId;
            url += '?' + new URLSearchParams({repositoryId:source.repositoryId, workspaceId:workspaceId, branch:source.branch});
        }
        if (workspaceId !== undefined) headers['X-Taxonomy-Workspace-Id'] = workspaceId == null ? '' : workspaceId;
        return window.TaxonomyApiClient.request(url, {method:method, headers:headers, cache:'no-store'},
            Object.assign({idempotent:method === 'GET', selectedRepository:source === undefined,
                pinnedWorkspaceId:workspaceId, acceptedStatuses:[409,412]}, options));
    }

    function upsertRelation(sourceCode, relationType, targetCode, payload, headers, source) {
        return commandRequest(sourceCode, relationType, targetCode, source, {
            method: 'PUT', headers: headers, body: JSON.stringify(payload)
        });
    }

    function deleteRelation(sourceCode, relationType, targetCode, headers, source) {
        return commandRequest(sourceCode, relationType, targetCode, source, {method: 'DELETE', headers: headers});
    }

    function commandRequest(sourceCode, relationType, targetCode, source, init) {
        var path = '/api/architecture/relations/' + encodeURIComponent(sourceCode) + '/'
            + encodeURIComponent(relationType) + '/' + encodeURIComponent(targetCode);
        if (!source) return domainRequest(path, init, {selectedRepository:true});
        var target = new URLSearchParams({repositoryId:source.repositoryId, workspaceId:source.workspaceId, branch:source.branch});
        return domainRequest(path + '?' + target, init, {pinnedWorkspaceId:source.workspaceId});
    }

    return {
        createLegacy: function (payload) { return domainRequest('/api/relations', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)}); },
        deleteLegacy: function (id) { return domainRequest('/api/relations/' + encodeURIComponent(id), {method:'DELETE'}); },
        impact: function (payload) { return domainRequest('/api/graph/impact', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)}); },
        coverageStatistics: function () { return coverageRequest('/api/coverage/statistics'); },
        nodeCoverage: function (code) { return coverageRequest('/api/coverage/node/' + encodeURIComponent(code)); },
        recordCoverage: function (payload) { return coverageRequest('/api/coverage/record', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)}); },
        readQualityDashboard: readQualityDashboard,
        readSnapshot: readSnapshot,
        readProjectionReadiness: readProjectionReadiness,
        rebuildProjection: rebuildProjection,
        upsertRelation: upsertRelation,
        deleteRelation: deleteRelation
    };
}());

/*
 * Proposal review is part of the same Git-authoritative relation surface. Load
 * its raw-response API first and then the capture-phase browser adapter. The
 * readiness promise prevents duplicate scripts and makes delayed loading safe.
 */
(function () {
    'use strict';
    var loader = document.currentScript;
    if (!loader || !loader.src) return;

    function loadProposalAdapter() {
        if (document.querySelector(
            'script[data-taxonomy-proposal-command-adapter]')) return;
        var script = document.createElement('script');
        script.src = new URL(
            '../relations/taxonomy-proposals-git-commands.js',
            loader.src).href;
        script.async = false;
        script.setAttribute(
            'data-taxonomy-proposal-command-adapter', 'true');
        document.head.appendChild(script);
    }

    function createProposalApiPromise() {
        if (window.TaxonomyProposalsApi) {
            return Promise.resolve(window.TaxonomyProposalsApi);
        }
        return new Promise(function (resolve, reject) {
            var script = document.querySelector(
                'script[data-taxonomy-proposals-api]');
            var created = false;
            if (!script) {
                script = document.createElement('script');
                script.src = new URL('proposals-api.js', loader.src).href;
                script.async = false;
                script.setAttribute(
                    'data-taxonomy-proposals-api', 'true');
                created = true;
            }
            script.addEventListener('load', function () {
                if (window.TaxonomyProposalsApi) {
                    resolve(window.TaxonomyProposalsApi);
                } else {
                    reject(new Error(
                        'Proposal review API client did not initialise.'));
                }
            }, { once: true });
            script.addEventListener('error', function () {
                reject(new Error(
                    'Failed to load proposal review API client.'));
            }, { once: true });
            if (created) {
                document.head.appendChild(script);
            } else if (window.TaxonomyProposalsApi) {
                resolve(window.TaxonomyProposalsApi);
            }
        });
    }

    if (!window.TaxonomyProposalsApiReady) {
        window.TaxonomyProposalsApiReady = createProposalApiPromise();
    }
    window.TaxonomyProposalsApiReady
        .then(loadProposalAdapter)
        .catch(function (error) {
            console.error('[Taxonomy] ' + error.message);
        });
}());

/* Load the direct hypothesis-review transport and compatibility adapter. */
(function () {
    'use strict';
    var loader = document.currentScript;
    if (!loader || !loader.src) return;

    function loadAdapter() {
        if (document.querySelector(
            'script[data-taxonomy-hypothesis-command-adapter]')) return;
        var script = document.createElement('script');
        script.src = new URL(
            '../relations/taxonomy-hypotheses-git-commands.js',
            loader.src).href;
        script.async = false;
        script.setAttribute(
            'data-taxonomy-hypothesis-command-adapter', 'true');
        document.head.appendChild(script);
    }

    function createApiPromise() {
        if (window.TaxonomyHypothesesApi) {
            return Promise.resolve(window.TaxonomyHypothesesApi);
        }
        return new Promise(function (resolve, reject) {
            var script = document.querySelector(
                'script[data-taxonomy-hypotheses-api]');
            var created = false;
            if (!script) {
                script = document.createElement('script');
                script.src = new URL('hypotheses-api.js', loader.src).href;
                script.async = false;
                script.setAttribute(
                    'data-taxonomy-hypotheses-api', 'true');
                created = true;
            }
            script.addEventListener('load', function () {
                if (window.TaxonomyHypothesesApi) {
                    resolve(window.TaxonomyHypothesesApi);
                } else {
                    reject(new Error(
                        'Hypothesis review API client did not initialise.'));
                }
            }, { once: true });
            script.addEventListener('error', function () {
                reject(new Error(
                    'Failed to load hypothesis review API client.'));
            }, { once: true });
            if (created) {
                document.head.appendChild(script);
            } else if (window.TaxonomyHypothesesApi) {
                resolve(window.TaxonomyHypothesesApi);
            }
        });
    }

    if (!window.TaxonomyHypothesesApiReady) {
        window.TaxonomyHypothesesApiReady = createApiPromise();
    }
    window.TaxonomyHypothesesApiReady
        .then(loadAdapter)
        .catch(function (error) {
            console.error('[Taxonomy] ' + error.message);
        });
}());
