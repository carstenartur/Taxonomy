/* analysis-session-api.js – HTTP boundary for resumable ad-hoc analysis state */
(function () {
    'use strict';

    function request(url, options) {
        var client = window.TaxonomyApiClient;
        if (!client || typeof client.request !== 'function') {
            return Promise.reject(new Error('Taxonomy API client is not available'));
        }
        var method = String((options && options.method) || 'GET').toUpperCase();
        return client.request(url, options, {
            idempotent: method === 'GET' || method === 'HEAD'
        });
    }

    window.TaxonomyAnalysisSessionApi = Object.freeze({
        request: request
    });
}());
