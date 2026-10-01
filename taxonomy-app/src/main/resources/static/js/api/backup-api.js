window.BackupApi = (function () {
    'use strict';
    var base = '/api/backups/jobs';
    function job(id) { return base + '/' + encodeURIComponent(id); }
    return {
        list: function () { return window.TaxonomyApiClient.getJson(base); },
        status: function (id) { return window.TaxonomyApiClient.getJson(job(id)); },
        submit: function (request) { return window.TaxonomyApiClient.sendJson(base, request, 'POST', { retries: 0 }); },
        cancel: function (id) { return window.TaxonomyApiClient.sendJson(job(id) + '/cancel', {}, 'POST', { retries: 0 }); },
        downloadUrl: function (id) { return window.TaxonomyI18n.resolveUrl(job(id) + '/download'); }
    };
}());
