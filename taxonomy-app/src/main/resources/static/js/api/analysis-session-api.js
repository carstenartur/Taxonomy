/* analysis-session-api.js – HTTP boundary for resumable ad-hoc analysis state */
(function () {
    'use strict';

    function request(url, options) {
        return window.fetch(url, options);
    }

    window.TaxonomyAnalysisSessionApi = Object.freeze({
        request: request
    });
}());
