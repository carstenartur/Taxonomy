/* document-templates-api.js – HTTP boundary for the standalone template workspace. */
window.TaxonomyDocumentTemplatesApi = (function (global) {
    'use strict';

    function requestJson(url, init) {
        return global.TaxonomyApiClient.requestJson(url, Object.assign({cache: 'no-store'}, init));
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
