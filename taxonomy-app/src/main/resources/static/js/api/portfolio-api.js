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

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        if (!token) return {};
        const headerName = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
        return { [headerName]: token };
    }

    async function responsePayload(response) {
        if (response.status === 204) return null;
        return response.json().catch(function () { return null; });
    }

    function problemMessage(payload, response) {
        return payload?.detail || payload?.message || payload?.error
            || ('HTTP ' + response.status);
    }

    async function requireOk(response) {
        if (response.ok) return response;
        const payload = await responsePayload(response);
        const error = new Error(problemMessage(payload, response));
        error.status = response.status;
        error.responseBody = payload;
        throw error;
    }

    async function getResponse(url, accept) {
        return requireOk(await fetch(url, {
            headers: { Accept: accept || 'application/json' },
            credentials: 'same-origin',
            cache: 'no-store'
        }));
    }

    async function getJson(url) {
        return responsePayload(await getResponse(url, 'application/json'));
    }

    async function sendJsonResponse(url, method, body) {
        return requireOk(await fetch(url, {
            method: method,
            headers: Object.assign({
                Accept: 'application/json',
                'Content-Type': 'application/json'
            }, csrfHeaders()),
            credentials: 'same-origin',
            body: JSON.stringify(body === undefined ? {} : body)
        }));
    }

    async function sendJson(url, method, body) {
        return responsePayload(await sendJsonResponse(url, method, body));
    }

    async function sendFormData(url, formData) {
        return responsePayload(await requireOk(await fetch(url, {
            method: 'POST',
            headers: csrfHeaders(),
            credentials: 'same-origin',
            body: formData
        })));
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

    function reportUrl(projectId, format, parameters) {
        const query = new URLSearchParams(parameters || {}).toString();
        return projectPath(projectId) + '/reports/' + encodeURIComponent(String(format))
            + (query ? '?' + query : '');
    }

    return {
        // ── Taxonomy lookup ───────────────────────────────────────────────────
        searchTaxonomy: function (query) {
            return getJson('/api/search?q=' + encodeURIComponent(String(query || '').trim())
                + '&maxResults=30');
        },

        // ── Account ───────────────────────────────────────────────────────────
        getAccount: function () {
            return getJson('/api/account/me');
        },

        // ── Projects, requirements and portfolio views ────────────────────────
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
        updateConflict: function (projectId, conflictId, decision) {
            return sendJson(projectPath(projectId) + '/conflicts/'
                + positiveInteger(conflictId, 'conflictId'), 'PATCH', decision);
        },

        // ── Analysis jobs ─────────────────────────────────────────────────────
        listAnalysisJobs: function (projectId) {
            return getJson(projectPath(projectId) + '/analysis-jobs');
        },
        getAnalysisJob: function (projectId, jobId) {
            return getJson(projectPath(projectId) + '/analysis-jobs/'
                + encodeURIComponent(String(jobId)));
        },
        analyzeRequirement: function (projectId, requirementId, body) {
            return sendJsonResponse(
                requirementPath(projectId, requirementId) + '/analyses', 'POST', body);
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
        fetchReport: function (projectId, format, parameters, accept) {
            return getResponse(reportUrl(projectId, format, parameters), accept);
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
