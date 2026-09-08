window.IntegrationApi = (function () {
    'use strict';
    function scope() {
        var current = new URLSearchParams(location.search), query = new URLSearchParams();
        ['repositoryId', 'workspaceId', 'branch'].forEach(function (key) { if (current.has(key)) query.set(key, current.get(key)); });
        return query.toString();
    }
    function url(path) { return '/api/integrations' + path + '?' + scope(); }
    return {
        read: function (path) { return window.TaxonomyApiClient.getJson(url(path)); },
        write: function (path, body) { return window.TaxonomyApiClient.request(url(path), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, { retries: 0 }).then(function (r) { return r.json(); }); },
        upload: function (path, request, file) {
            var form = new FormData(); form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' })); form.append('file', file);
            return window.TaxonomyApiClient.request(url(path), { method: 'POST', body: form }, { retries: 0 }).then(function (r) { return r.json(); });
        },
        downloadUrl: function (path) { return window.TaxonomyI18n.resolveUrl(url(path)); }
    };
}());
