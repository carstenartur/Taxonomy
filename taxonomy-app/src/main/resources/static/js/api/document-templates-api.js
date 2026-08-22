/* document-templates-api.js – HTTP boundary for the standalone template workspace. */
window.TaxonomyDocumentTemplatesApi = (function (global) {
    'use strict';

    function csrfMetadata() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        return token ? { name: header || 'X-CSRF-TOKEN', value: token } : null;
    }

    function responseMessage(payload, fallback) {
        if (payload && typeof payload === 'object') {
            return payload.detail || payload.message || payload.error || fallback;
        }
        return typeof payload === 'string' && payload.trim() ? payload.trim() : fallback;
    }

    async function responsePayload(response) {
        const type = response.headers.get('content-type') || '';
        if (type.includes('application/json')) {
            try {
                return await response.json();
            } catch (ignored) {
                return null;
            }
        }
        try {
            return await response.text();
        } catch (ignored) {
            return null;
        }
    }

    async function requestJson(url, init, fallback) {
        const request = Object.assign({
            credentials: 'same-origin',
            cache: 'no-store'
        }, init || {});
        const method = String(request.method || 'GET').toUpperCase();
        const headers = new Headers(request.headers || {});
        if (!headers.has('Accept')) {
            headers.set('Accept', 'application/json');
        }
        const csrf = csrfMetadata();
        if (csrf && method !== 'GET' && method !== 'HEAD' && !headers.has(csrf.name)) {
            headers.set(csrf.name, csrf.value);
        }
        request.method = method;
        request.headers = headers;

        const response = await global.fetch(url, request);
        if (!response.ok) {
            throw new Error(responseMessage(await responsePayload(response), fallback));
        }
        return response.status === 204 ? null : response.json();
    }

    function list(url, fallback) {
        return requestJson(url, { method: 'GET' }, fallback);
    }

    function history(url, fallback) {
        return requestJson(url, { method: 'GET' }, fallback);
    }

    function upload(url, file, headers, fallback) {
        return requestJson(url, {
            method: 'PUT',
            headers: headers,
            body: file
        }, fallback);
    }

    return Object.freeze({
        list,
        history,
        upload
    });
}(window));
