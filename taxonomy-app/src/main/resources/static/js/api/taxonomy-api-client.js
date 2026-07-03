/* taxonomy-api-client.js – Base HTTP client for the Taxonomy UI.
 *
 * Provides named helpers for JSON GET/POST/PUT/DELETE and FormData uploads.
 * All helpers read CSRF metadata from <meta name="_csrf"> and
 * <meta name="_csrf_header"> when present and inject the header automatically.
 *
 * Load this script BEFORE all api/*.js feature modules and before any
 * feature module that uses TaxonomyApiClient.
 *
 * Convention: new UI code must call named functions from an api/*.js module
 * instead of constructing fetch('/api/…') calls directly.  See
 * docs/dev/03-ui-task-map.md for details.
 */
window.TaxonomyApiClient = (function () {
    'use strict';

    // ── CSRF ──────────────────────────────────────────────────────────────────

    function csrfHeaders() {
        var token  = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !token.content) return {};
        var h = {};
        h[(header && header.content) || 'X-CSRF-TOKEN'] = token.content;
        return h;
    }

    // ── Response helpers ──────────────────────────────────────────────────────

    function checkStatus(res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res;
    }

    function parseJson(res) {
        return res.json().catch(function () {
            throw new Error('Invalid JSON response from server');
        });
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    /**
     * HTTP GET – returns a Promise resolving to the parsed JSON body.
     * @param {string} url
     * @returns {Promise<any>}
     */
    function getJson(url) {
        return fetch(url)
            .then(checkStatus)
            .then(parseJson);
    }

    /**
     * HTTP POST/PUT/DELETE with a JSON body – returns a Promise resolving to
     * the parsed JSON body.
     * @param {string} url
     * @param {any}    body   – will be serialised with JSON.stringify
     * @param {string} [method='POST']
     * @returns {Promise<any>}
     */
    function sendJson(url, body, method) {
        return fetch(url, {
            method: method || 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, csrfHeaders()),
            body: JSON.stringify(body)
        })
        .then(checkStatus)
        .then(parseJson);
    }

    /**
     * HTTP POST with a FormData body – returns a Promise resolving to the
     * parsed JSON body.  Injects the CSRF header when present.
     * @param {string}   url
     * @param {FormData} formData
     * @param {string}   [method='POST']
     * @returns {Promise<any>}
     */
    function sendFormData(url, formData, method) {
        return fetch(url, {
            method: method || 'POST',
            headers: csrfHeaders(),
            body: formData
        })
        .then(checkStatus)
        .then(parseJson);
    }

    /**
     * HTTP DELETE – returns a Promise resolving to the parsed JSON body.
     * @param {string} url
     * @returns {Promise<any>}
     */
    function deleteJson(url) {
        return fetch(url, {
            method: 'DELETE',
            headers: csrfHeaders()
        })
        .then(checkStatus)
        .then(parseJson);
    }

    return {
        getJson:      getJson,
        sendJson:     sendJson,
        sendFormData: sendFormData,
        deleteJson:   deleteJson
    };
})();
