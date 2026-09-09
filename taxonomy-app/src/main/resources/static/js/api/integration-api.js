window.IntegrationApi = (function () {
    'use strict';
    function scope() {
        var current = new URLSearchParams(location.search), query = new URLSearchParams();
        ['repositoryId', 'workspaceId', 'branch'].forEach(function (key) { if (current.has(key)) query.set(key, current.get(key)); });
        return query.toString();
    }
    function url(path) { var query = scope(); return '/api/integrations' + path + (query ? '?' + query : ''); }
    return {
        read: function (path) { return window.TaxonomyApiClient.getJson(url(path)); },
        write: function (path, body) { return window.TaxonomyApiClient.sendJson(url(path), body, 'POST', { retries: 0 }); },
        upload: function (path, request, file) {
            var form = new FormData(); form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' })); form.append('file', file);
            return window.TaxonomyApiClient.sendFormData(url(path), form, 'POST', { retries: 0 });
        },
        downloadUrl: function (path) { return window.TaxonomyI18n.resolveUrl(url(path)); }
    };
}());
