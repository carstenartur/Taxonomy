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
