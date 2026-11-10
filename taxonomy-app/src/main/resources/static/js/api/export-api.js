/* Export boundary: binary bodies retain cancellation and immutable request context. */
window.ExportApi = (function () {
    'use strict';
    var client = window.TaxonomyApiClient;
    return {
        report: function (format, payload, options) {
            if (!['markdown', 'html', 'docx'].includes(format)) throw new Error('Unknown report format');
            return client.request('/api/report/' + format, {method: 'POST',
                headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)}, options);
        },
        scoreExport: function (body) { return client.sendJson('/api/scores/export', body); },
        diagramResponse: function (url, serializedArchitecture) {
            return client.request(url.indexOf('/api/diagram/current/') === 0 ? url
                : url.replace('/api/diagram/', '/api/diagram/current/'), {
                method: 'POST', headers: {'Content-Type': 'application/json'}, body: serializedArchitecture
            });
        }
    };
}());
