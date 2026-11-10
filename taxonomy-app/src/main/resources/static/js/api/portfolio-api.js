/* portfolio-api.js – Project portfolio API module for the Taxonomy UI.
 *
 * Wraps the project, requirement, solution, product, report, analysis-job and
 * portfolio Git endpoints. The guided decision layer and the dedicated
 * portfolio pages must call these named functions instead of constructing
 * fetch('/api/projects/…') calls directly.
 *
 * See docs/dev/03-ui-task-map.md for the UI API-client convention.
 */
window.TaxonomyPortfolioApi = (function () {
    'use strict';

    function sourceRequest(url, options) {
        const scope = options?.scope || (options?.pinnedWorkspaceId === undefined
            && /^\/api\/projects(?:\/|$)/.test(url) ? window.TaxonomyArchitectureWorkflow?.getScope?.() : null);
        if (!scope) return {url:url,headers:{},policy:options};
        const target = new URL(applicationUrl(url), window.location.href);
        ['repositoryId','workspaceScopeKey','workspaceId','branch'].forEach(function(key) {
            if (scope[key] !== undefined && scope[key] !== null) target.searchParams.set(key,String(scope[key]));
        });
        const headers = {};
        const policy = Object.assign({}, options);
        if (scope.workspaceScopeKey !== undefined || scope.workspaceId !== undefined) {
            const workspace = scope.workspaceScopeKey || scope.workspaceId || '__shared__';
            const workspaceId = workspace === '__shared__' ? '' : String(workspace);
            target.searchParams.set('workspaceScopeKey',workspace === '__shared__' ? '__shared__' : String(workspace));
            target.searchParams.set('workspaceId',workspaceId);
            headers['X-Taxonomy-Workspace-Id'] = workspaceId;
            policy.pinnedWorkspaceId = workspaceId;
        }
        return {url:target.pathname + target.search,headers:headers,policy:policy};
    }

    async function getResponse(url, accept, options) {
        const request = sourceRequest(url, options);
        return window.TaxonomyApiClient.request(request.url, {
            headers: Object.assign({ Accept: accept || 'application/json' }, request.headers),
            credentials: 'same-origin',
            cache: 'no-store',
        }, request.policy);
    }

    async function getJson(url, options) {
        const request = sourceRequest(url, options);
        return window.TaxonomyApiClient.requestJson(request.url, {
            method: 'GET', headers: Object.assign({Accept: 'application/json'}, request.headers), cache: 'no-store'
        }, request.policy);
    }

    function jsonRequest(url, method, body, options, headers) {
        const request = sourceRequest(url, options);
        return {url:request.url,policy:request.policy,init:{
            method: method,
            headers: Object.assign({
                Accept: 'application/json',
                'Content-Type': 'application/json'
            }, request.headers, headers || {}),
            body: JSON.stringify(body === undefined ? {} : body)
        }};
    }

    function sendJsonResponse(url, method, body, options, headers) {
        const request = jsonRequest(url, method, body, options, headers);
        return window.TaxonomyApiClient.request(request.url, request.init, request.policy);
    }

    function sendJson(url, method, body, options, headers) {
        const request = jsonRequest(url, method, body, options, headers);
        return window.TaxonomyApiClient.requestJson(request.url, request.init, request.policy);
    }

    function sendFormData(url, formData, options) {
        return window.TaxonomyApiClient.requestJson(url, {method: 'POST', body: formData}, options);
    }

    async function adoptionCommand(projectId, requirementId, proposalId, revision, suffix, body) {
        positiveInteger(revision, 'revision');
        return sendJson(requirementPath(projectId, requirementId)
            + '/reformulations/' + encodeURIComponent(proposalId) + '/' + suffix,
            'POST', body || {}, undefined, {'If-Match': '"' + revision + '"'});
    }

    function positiveInteger(value, fieldName) {
        const parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed <= 0) {
            throw new TypeError(fieldName + ' must be a positive integer');
        }
        return parsed;
    }

    function projectPath(projectId) {
        return '/api/projects/' + positiveInteger(projectId, 'projectId');
    }

    function requirementPath(projectId, requirementId) {
        return projectPath(projectId) + '/requirements/'
            + positiveInteger(requirementId, 'requirementId');
    }

    function reformulationReportPath(projectId, requirementId, proposalId, kind, identity, format) {
        const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
        if (typeof proposalId !== 'string' || !uuid.test(proposalId)) throw new TypeError('Invalid proposal ID');
        if (!['json', 'md', 'html', 'docx'].includes(format)) throw new TypeError('Unsupported report format');
        if (kind === 'revisions') identity = positiveInteger(identity, 'revision');
        else if (typeof identity !== 'string' || !uuid.test(identity)) throw new TypeError('Invalid adoption command ID');
        return requirementPath(projectId, requirementId) + '/reformulations/' + proposalId
            + '/' + kind + '/' + identity + '/export?format=' + format;
    }

    function reportUrl(projectId, format, parameters) {
        const query = new URLSearchParams(parameters || {}).toString();
        return applicationUrl(projectPath(projectId) + '/reports/' + encodeURIComponent(String(format))
            + (query ? '?' + query : ''));
    }

    function applicationUrl(path) {
        return window.TaxonomyI18n?.resolveUrl?.(path) || path;
    }

    /** Resolve server Location headers and reject resources outside this application. */
    function analysisJobUrl(location, expectedProjectId) {
        if (typeof location !== 'string' || !location) return null;
        try {
            const resolved = new URL(applicationUrl(location), window.location.href);
            if (resolved.origin !== window.location.origin || resolved.username || resolved.password
                    || resolved.search || resolved.hash) return null;
            const basePath = window.TaxonomyI18n?.getBasePath?.() || '';
            if (basePath && !resolved.pathname.startsWith(basePath + '/')) return null;
            const match = resolved.pathname.slice(basePath.length)
                .match(/^\/api\/projects\/([1-9]\d*)\/analysis-jobs\/([^/]+)$/);
            if (!match || !Number.isSafeInteger(Number(match[1]))
                    || (expectedProjectId != null && Number(match[1]) !== Number(expectedProjectId))) return null;
            const jobId = decodeURIComponent(match[2]);
            if (/[\\/]/.test(jobId) || jobId === '.' || jobId === '..') return null;
            return resolved.href;
        } catch (error) {
            return null;
        }
    }

    return {
        // ── Taxonomy lookup ───────────────────────────────────────────────────
        searchTaxonomy: function (query) {
            return getJson('/api/search?q=' + encodeURIComponent(String(query || '').trim())
                + '&maxResults=30');
        },

        // Retained inventory evidence shares the canonical cancellation and error contract.
        listInventoryComparisons: function (projectId, snapshotId) {
            return getJson(projectPath(projectId) + '/snapshots/' + encodeURIComponent(snapshotId) + '/inventory-comparisons');
        },
        createInventoryComparison: function (projectId, snapshotId, body) {
            return sendJson(projectPath(projectId) + '/snapshots/' + encodeURIComponent(snapshotId)
                + '/inventory-comparisons', 'POST', body, {timeoutMillis: 125000});
        },
        getInventoryComparison: function (id) { return getJson('/api/inventory-comparisons/' + encodeURIComponent(id)); },
        reviewInventoryComparison: function (id, body) {
            return sendJson('/api/inventory-comparisons/' + encodeURIComponent(id) + '/reviews', 'POST', body);
        },
        listInventoryReviews: function (id) { return getJson('/api/inventory-comparisons/' + encodeURIComponent(id) + '/reviews'); },
        mapInventoryCandidate: function (id, candidateId, body) {
            return sendJson('/api/inventory-comparisons/' + encodeURIComponent(id) + '/candidates/'
                + encodeURIComponent(candidateId) + '/mappings', 'POST', body);
        },
        addInventoryAssertion: function (productId, body) {
            return sendJson('/api/products/' + positiveInteger(productId, 'productId') + '/inventory-assertions', 'POST', body);
        },

        // ── Account ───────────────────────────────────────────────────────────
        getAccount: function () {
            return getJson('/api/account/me', {contextSensitive: false, workspaceScoped: false});
        },

        // ── Projects, requirements and portfolio views ────────────────────────
        listProjects: function () { return getJson('/api/projects'); },
        listProducts: function () { return getJson('/api/products'); },
        createProject: function (body) { return sendJson('/api/projects', 'POST', body); },
        createSolution: function (body) { return sendJson('/api/solutions', 'POST', body); },
        createProduct: function (body) { return sendJson('/api/products', 'POST', body); },
        addProjectSolution: function (projectId, body) {
            return sendJson(projectPath(projectId) + '/solutions', 'POST', body);
        },
        updateProjectSolution: function (projectId, id, body) {
            return sendJson(projectPath(projectId) + '/solutions/' + positiveInteger(id, 'solutionId'), 'PATCH', body);
        },
        addSolutionCoverage: function (projectId, id, body) {
            return sendJson(projectPath(projectId) + '/solutions/' + positiveInteger(id, 'solutionId') + '/taxonomy-coverage', 'POST', body);
        },
        addProductCandidate: function (projectId, solutionId, body) {
            return sendJson(projectPath(projectId) + '/solutions/' + positiveInteger(solutionId, 'solutionId') + '/products', 'POST', body);
        },
        updateProductCandidate: function (projectId, id, body) {
            return sendJson(projectPath(projectId) + '/product-candidates/' + positiveInteger(id, 'candidateId'), 'PATCH', body);
        },
        addProductCoverage: function (productId, body) {
            return sendJson('/api/products/' + positiveInteger(productId, 'productId') + '/taxonomy-coverage', 'POST', body);
        },
        updateRequirement: function (projectId, requirementId, body) {
            return sendJson(requirementPath(projectId, requirementId), 'PATCH', body);
        },
        proposeSolutions: function (projectId, body) { return sendJson(projectPath(projectId) + '/solutions/propose', 'POST', body); },
        detectConflicts: function (projectId, body) { return sendJson(projectPath(projectId) + '/conflicts/detect', 'POST', body); },
        analyzeProject: function (projectId, body) { return sendJson(projectPath(projectId) + '/analyses', 'POST', body); },
        analyzeRequirementJob: function (projectId, requirementId, body) {
            return sendJson(requirementPath(projectId, requirementId) + '/analyses', 'POST', body);
        },
        listRequirementAnalyses: function (projectId, requirementId) {
            return getJson(requirementPath(projectId, requirementId) + '/analyses');
        },
        getRequirementAnalysis: function (projectId, requirementId, snapshotId) {
            return getJson(requirementPath(projectId, requirementId) + '/analyses/' + encodeURIComponent(snapshotId));
        },
        updateSnapshotMapping: function (projectId, requirementId, snapshotId, mappingId, body) {
            return sendJson(requirementPath(projectId, requirementId) + '/analyses/' + encodeURIComponent(snapshotId)
                + '/elements/' + positiveInteger(mappingId, 'mappingId'), 'PATCH', body);
        },
        compareRequirementAnalyses: function (projectId, requirementId, newer, older) {
            return getJson(requirementPath(projectId, requirementId) + '/analyses/' + encodeURIComponent(newer)
                + '/diff/' + encodeURIComponent(older));
        },
        updateElementMapping: function (projectId, mappingId, body) {
            return sendJson(projectPath(projectId) + '/analysis-mappings/elements/' + positiveInteger(mappingId, 'mappingId'), 'PATCH', body);
        },
        linkSolutionRequirement: function (projectId, solutionId, body) {
            return sendJson(projectPath(projectId) + '/solutions/' + positiveInteger(solutionId, 'solutionId')
                + '/requirements', 'POST', body);
        },
        addCatalogSolutionCoverage: function (solutionId, body) {
            return sendJson('/api/solutions/' + positiveInteger(solutionId, 'solutionId') + '/taxonomy-coverage', 'POST', body);
        },
        getProject: function (projectId) {
            return getJson(projectPath(projectId));
        },
        getProjectPortfolio: function (projectId) {
            return getJson(projectPath(projectId) + '/portfolio');
        },
        listRequirements: function (projectId) {
            return getJson(projectPath(projectId) + '/requirements');
        },
        getRequirement: function (projectId, requirementId) {
            return getJson(requirementPath(projectId, requirementId));
        },
        downloadReformulationReport: function (projectId, requirementId, proposalId, revision, format) {
            return getResponse(reformulationReportPath(projectId, requirementId, proposalId, 'revisions', revision, format), '*/*');
        },
        downloadReformulationAdoptionReport: function (projectId, requirementId, proposalId, commandId, format) {
            return getResponse(reformulationReportPath(projectId, requirementId, proposalId, 'adoptions', commandId, format), '*/*');
        },
        listReformulations: function (projectId, requirementId) {
            return getJson(requirementPath(projectId, requirementId) + '/reformulations');
        },
        getReformulation: function (projectId, requirementId, id) {
            return getJson(requirementPath(projectId, requirementId) + '/reformulations/' + encodeURIComponent(id));
        },
        getReformulationRevision: function (projectId, requirementId, id, revision) {
            return getJson(requirementPath(projectId, requirementId) + '/reformulations/' + encodeURIComponent(id) + '/revisions/' + revision);
        },
        listReformulationRuns: function (projectId, requirementId, id) {
            return getJson(requirementPath(projectId, requirementId) + '/reformulations/' + encodeURIComponent(id) + '/synthesis-runs');
        },
        getReformulationUsage: function (projectId, requirementId, id, runId) {
            return getJson(requirementPath(projectId, requirementId) + '/reformulations/' + encodeURIComponent(id)
                + '/synthesis-runs/' + encodeURIComponent(runId) + '/usage');
        },
        getReformulationProgress: function (projectId, requirementId, id, runId, after) {
            const query = new URLSearchParams({limit: '20'});
            if (after) query.set('after', after);
            return getJson(requirementPath(projectId, requirementId) + '/reformulations/' + encodeURIComponent(id)
                + '/synthesis-runs/' + encodeURIComponent(runId) + '/progress?' + query);
        },
        getReformulationPartial: function (projectId, requirementId, id, runId, checkpointId) {
            return getJson(requirementPath(projectId, requirementId) + '/reformulations/' + encodeURIComponent(id)
                + '/synthesis-runs/' + encodeURIComponent(runId) + '/progress/checkpoints/' + encodeURIComponent(checkpointId));
        },
        createReformulation: function (projectId, requirementId, body) {
            return sendJson(requirementPath(projectId, requirementId) + '/reformulations', 'POST', body);
        },
        updateReformulation: async function (projectId, requirementId, id, operation, revision, body) {
            const allowed = /^(answers|revisions|variants|synthesis-runs(?:\/[A-Za-z0-9_-]+\/cancel)?|statements\/[A-Za-z0-9_-]+)$/;
            if (!allowed.test(operation)) throw new Error('Unknown reformulation operation');
            return sendJson(requirementPath(projectId, requirementId)
                + '/reformulations/' + encodeURIComponent(id) + '/' + operation,
                'POST', body || {}, undefined, {'If-Match': '"' + revision + '"'});
        },
        createReformulationAdoptionPreview: function (projectId, requirementId, id, revision) {
            return adoptionCommand(projectId, requirementId, id, revision, 'adoption-previews', {});
        },
        confirmReformulationAdoption: function (projectId, requirementId, id, revision, command) {
            return adoptionCommand(projectId, requirementId, id, revision, 'adoptions', command);
        },
        listReformulationAdoptions: function (projectId, requirementId, id) {
            return getJson(requirementPath(projectId, requirementId) + '/reformulations/' + encodeURIComponent(id) + '/adoptions');
        },
        listRequirementVersions: function (projectId, requirementId) {
            return getJson(requirementPath(projectId, requirementId) + '/versions');
        },
        createRequirementVersion: function (projectId, requirementId, body) {
            return sendJson(requirementPath(projectId, requirementId) + '/versions', 'POST', body);
        },
        listRequirementSnapshots: function (projectId, requirementId) {
            return getJson(requirementPath(projectId, requirementId) + '/snapshots');
        },
        getSnapshot: function (projectId, snapshotId) {
            return getJson(projectPath(projectId) + '/snapshots/'
                + encodeURIComponent(String(snapshotId)));
        },
        decisionReportOptions: function (projectId, snapshotId, language, requestOptions) {
            return getJson(projectPath(projectId) + '/snapshots/' + encodeURIComponent(String(snapshotId))
                + '/decision-report/options' + (language ? '?language=' + encodeURIComponent(language) : ''), requestOptions);
        },
        downloadDecisionReport: function (projectId, snapshotId, format, language, options, requestOptions) {
            const query = new URLSearchParams(window.TaxonomyDecisionExport?.query(options) || '');
            if (language) query.set('language', String(language));
            const suffix = query.toString();
            return getResponse(projectPath(projectId) + '/snapshots/'
                + encodeURIComponent(String(snapshotId)) + '/decision-report/'
                + encodeURIComponent(String(format)) + (suffix ? '?' + suffix : ''), '*/*', requestOptions);
        },
        updateConflict: function (projectId, conflictId, decision) {
            return sendJson(projectPath(projectId) + '/conflicts/'
                + positiveInteger(conflictId, 'conflictId'), 'PATCH', decision);
        },

        // ── Analysis jobs ─────────────────────────────────────────────────────
        analysisJobUrl: analysisJobUrl,
        listAnalysisJobs: function (projectId, options) {
            return getJson(projectPath(projectId) + '/analysis-jobs', options);
        },
        getAnalysisJob: function (projectId, jobId, options) {
            return getJson(projectPath(projectId) + '/analysis-jobs/'
                + encodeURIComponent(String(jobId)), options);
        },
        retryAnalysisJob: function (projectId, jobId, options) {
            return sendJsonResponse(projectPath(projectId) + '/analysis-jobs/' + encodeURIComponent(String(jobId))
                + '/retry-failed', 'POST', {}, options);
        },
        getAnalysisContinuation: function (projectId, jobId, itemId, options) {
            return getJson(projectPath(projectId) + '/analysis-jobs/'
                + encodeURIComponent(String(jobId)) + '/items/'
                + positiveInteger(itemId, 'itemId') + '/continuation', options);
        },
        continueAnalysis: function (projectId, jobId, itemId, decision, options) {
            return sendJson(projectPath(projectId) + '/analysis-jobs/'
                + encodeURIComponent(String(jobId)) + '/items/'
                + positiveInteger(itemId, 'itemId') + '/continue', 'POST', {
                    snapshotId: decision.snapshotId,
                    continuationVersion: decision.continuationVersion
                }, options);
        },
        analyzeRequirement: function (projectId, requirementId, body) {
            return sendJsonResponse(
                requirementPath(projectId, requirementId) + '/analyses', 'POST', Object.assign({analysisLanguage: window.TaxonomyContentLanguage?.analysis()}, body));
        },

        // ── Guided document import ────────────────────────────────────────────
        uploadDocument: function (formData) {
            return sendFormData('/api/documents/upload', formData);
        },
        extractDocumentWithAi: function (formData) {
            return sendFormData('/api/documents/extract-ai', formData);
        },
        importReviewedRequirements: function (projectId, body) {
            return sendJsonResponse(projectPath(projectId) + '/requirements/import-review',
                'POST', body);
        },

        // ── Reports ───────────────────────────────────────────────────────────
        reportUrl: reportUrl,
        fetchReport: function (projectId, format, parameters, accept, requestOptions) {
            return getResponse(reportUrl(projectId, format, parameters), accept, requestOptions);
        },

        // ── Portfolio Git and repository state ────────────────────────────────
        getGitState: function () {
            return getJson('/api/git/state');
        },
        exportPortfolio: function () {
            return getJson('/api/projects/git/export');
        },
        previewMaterialization: function (branch) {
            return getJson('/api/projects/git/materialize-preview?branch='
                + encodeURIComponent(String(branch || '')));
        },
        commitPortfolio: function (body) {
            return sendJson('/api/projects/git/commit', 'POST', body);
        },
        materializePortfolio: function (body) {
            return sendJson('/api/projects/git/materialize', 'POST', body);
        },
        mergePortfolio: function (body) {
            return sendJson('/api/projects/git/merge', 'POST', body);
        }
    };
}());

/* Load the dedicated Copilot API and UI only on a saved requirement page. */
(function loadRequirementCopilot() {
    if (!/(?:^|\/)projects\/\d+\/requirements\/\d+$/.test(window.location.pathname)) return;
    const loaderSource = document.currentScript?.src || window.location.href;
    const apiScript = document.createElement('script');
    apiScript.src = new URL('copilot-api.js', loaderSource).href;
    apiScript.onload = function () {
        const uiScript = document.createElement('script');
        uiScript.src = new URL('../portfolio/requirement-copilot.js', loaderSource).href;
        document.head.appendChild(uiScript);
    };
    document.head.appendChild(apiScript);
}());
