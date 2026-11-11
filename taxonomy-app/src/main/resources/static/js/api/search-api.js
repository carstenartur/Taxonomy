/* Search reads share authenticated cancellation and exact workspace routing. */
window.TaxonomySearchApi = (function () {
    'use strict';
    var client = window.TaxonomyApiClient;
    return {
        status: function () { return client.request('/api/embedding/status', {}, {contextSensitive:false, workspaceScoped:false}); },
        search: function (query, mode, limit, options) {
            var path = ['semantic', 'hybrid', 'graph'].includes(mode) ? '/' + mode : '';
            return client.request('/api/search' + path + '?q=' + encodeURIComponent(query) + '&maxResults=' + encodeURIComponent(limit), {}, options);
        },
        similar: function (code, topK, options) {
            return client.request('/api/search/similar/' + encodeURIComponent(code) + '?topK=' + encodeURIComponent(topK), {}, options);
        }
    };
}());
