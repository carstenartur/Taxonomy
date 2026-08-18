/* copilot-api.js – persistent requirement Copilot/Autopilot API boundary. */
window.TaxonomyCopilotApi = (function () {
    'use strict';

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        if (!token) return {};
        const headerName = document.querySelector('meta[name="_csrf_header"]')?.content
            || 'X-CSRF-TOKEN';
        return { [headerName]: token };
    }

    async function payload(response) {
        if (response.status === 204) return null;
        return response.json().catch(function () { return null; });
    }

    async function requireOk(response) {
        if (response.ok) return response;
        const body = await payload(response);
        const error = new Error(body?.detail || body?.message || body?.error
            || ('HTTP ' + response.status));
        error.status = response.status;
        error.responseBody = body;
        throw error;
    }

    async function getJson(url) {
        return payload(await requireOk(await fetch(url, {
            headers: { Accept: 'application/json' },
            credentials: 'same-origin',
            cache: 'no-store'
        })));
    }

    async function postJson(url, body) {
        return payload(await requireOk(await fetch(url, {
            method: 'POST',
            headers: Object.assign({
                Accept: 'application/json',
                'Content-Type': 'application/json'
            }, csrfHeaders()),
            credentials: 'same-origin',
            body: JSON.stringify(body === undefined ? {} : body)
        })));
    }

    function positiveInteger(value, name) {
        const parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed < 1) {
            throw new TypeError(name + ' must be a positive integer');
        }
        return parsed;
    }

    function projectPath(projectId) {
        return '/api/projects/' + positiveInteger(projectId, 'projectId');
    }

    return {
        status: function () {
            return getJson('/api/ai-automation');
        },
        start: function (projectId, requirementId, request) {
            return postJson(projectPath(projectId) + '/requirements/'
                + positiveInteger(requirementId, 'requirementId') + '/copilot', request);
        },
        latest: function (projectId, requirementId) {
            return getJson(projectPath(projectId) + '/requirements/'
                + positiveInteger(requirementId, 'requirementId') + '/copilot/latest');
        },
        get: function (projectId, operationId) {
            return getJson(projectPath(projectId) + '/copilot-operations/'
                + encodeURIComponent(String(operationId)));
        },
        cancel: function (projectId, operationId) {
            return postJson(projectPath(projectId) + '/copilot-operations/'
                + encodeURIComponent(String(operationId)) + '/cancel', {});
        }
    };
}());
