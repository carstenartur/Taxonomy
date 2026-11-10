/* Global reference/admin reads are independent of initial workspace hydration.
 * Authentication, caller signals, deadlines and page lifecycle still apply. */
window.TaxonomyReferenceApi = (function () {
    'use strict';
    var client = window.TaxonomyApiClient;
    function read(path, headers, options) {
        return client.request(path, {headers:headers}, Object.assign({contextSensitive:false, workspaceScoped:false}, options));
    }
    function prompt(code) { return '/api/prompts/' + encodeURIComponent(code); }
    return {
        about: function () { return read('/api/about'); },
        aiStatus: function (provider) { return read('/api/ai-status' + (provider === 'CUSTOM_OPENAI' ? '?provider=CUSTOM_OPENAI' : '')); },
        diagnostics: function (headers) { return read('/api/diagnostics', headers); },
        taxonomy: function (signal) { return read('/api/taxonomy', null, {signal:signal, acceptedStatuses:[503]}); },
        startup: function (signal) { return read('/api/status/startup', null, {signal:signal}); },
        health: function (headers) { return read('/api/admin/health-summary', headers); },
        logs: function (level, component, headers) {
            var query = new URLSearchParams({limit:'200'});
            if (level) query.set('level', level);
            if (component) query.set('component', component);
            return read('/api/admin/logs?' + query, headers);
        },
        promptCategories: function (headers) { return read('/api/prompts/categories', headers, {acceptedStatuses:[404]}); },
        prompts: function (headers) { return read('/api/prompts', headers); },
        prompt: function (code, headers) { return read(prompt(code), headers); },
        savePrompt: function (code, template, headers) {
            return client.request(prompt(code), {method:'PUT',
                headers:Object.assign({'Content-Type':'application/json'}, headers), body:JSON.stringify({template:template})}, {workspaceScoped:false});
        },
        deletePrompt: function (code, headers) { return client.request(prompt(code), {method:'DELETE', headers:headers}, {workspaceScoped:false}); }
    };
}());
