/* Portfolio API boundary for guided project decisions. */
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

    async function expectJson(response) {
        const payload = await responsePayload(response);
        if (response.ok) return payload;
        const message = payload?.detail || payload?.message || payload?.error
            || ('HTTP ' + response.status);
        const error = new Error(message);
        error.status = response.status;
        error.responseBody = payload;
        throw error;
    }

    async function getJson(url) {
        return expectJson(await fetch(url, {
            headers: { Accept: 'application/json' },
            credentials: 'same-origin',
            cache: 'no-store'
        }));
    }

    async function sendJson(url, method, body) {
        return expectJson(await fetch(url, {
            method: method,
            headers: Object.assign({
                Accept: 'application/json',
                'Content-Type': 'application/json'
            }, csrfHeaders()),
            credentials: 'same-origin',
            body: JSON.stringify(body)
        }));
    }

    function positiveInteger(value, fieldName) {
        const parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed <= 0) {
            throw new TypeError(fieldName + ' must be a positive integer');
        }
        return parsed;
    }

    return {
        searchTaxonomy: function (query) {
            return getJson('/api/search?q=' + encodeURIComponent(String(query || '').trim())
                + '&maxResults=30');
        },
        getProjectPortfolio: function (projectId) {
            return getJson('/api/projects/' + positiveInteger(projectId, 'projectId') + '/portfolio');
        },
        updateConflict: function (projectId, conflictId, decision) {
            return sendJson('/api/projects/' + positiveInteger(projectId, 'projectId')
                + '/conflicts/' + positiveInteger(conflictId, 'conflictId'), 'PATCH', decision);
        }
    };
}());
