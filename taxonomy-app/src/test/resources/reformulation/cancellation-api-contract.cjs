'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const requests = [];
const document = {
    querySelector(selector) {
        if (selector === 'meta[name="_csrf"]') return { content: 'browser-token' };
        if (selector === 'meta[name="_csrf_header"]') return { content: 'X-CSRF-TOKEN' };
        return null;
    }
};
const context = vm.createContext({
    window: { location: { pathname: '/' } }, document, URLSearchParams,
    fetch: async (url, init) => {
        requests.push({ url, init });
        return { ok: true, status: 200, json: async () => ({ status: 'CANCELLED' }) };
    }
});
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), context);
(async () => {
    const api = context.window.TaxonomyPortfolioApi;
    const runId = '0195139a-706d-4a11-882a-0a881036f352';
    const result = await api.updateReformulation(7, 11, 'proposal-id',
        'synthesis-runs/' + runId + '/cancel', 2, {});
    assert.equal(result.status, 'CANCELLED');
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url,
        '/api/projects/7/requirements/11/reformulations/proposal-id/synthesis-runs/' + runId + '/cancel');
    assert.equal(requests[0].init.method, 'POST');
    assert.equal(requests[0].init.credentials, 'same-origin');
    assert.equal(requests[0].init.headers['If-Match'], '"2"');
    assert.equal(requests[0].init.headers['X-CSRF-TOKEN'], 'browser-token');
    for (const operation of ['synthesis-runs//cancel', 'synthesis-runs/../cancel',
        'synthesis-runs/a%2Fb/cancel', 'synthesis-runs/a/cancel/extra',
        'synthesis-runs/a/cancel?adopt=true', 'adoptions']) {
        await assert.rejects(api.updateReformulation(7, 11, 'proposal-id', operation, 2, {}),
            /Unknown reformulation operation/);
    }
    assert.equal(requests.length, 1, 'Rejected operations must not issue requests');
    for (const operation of ['answers', 'revisions', 'variants', 'synthesis-runs', 'statements/safe-id']) {
        await api.updateReformulation(7, 11, 'proposal-id', operation, 2, {});
    }
    assert.equal(requests.length, 6);
    console.log('REFORMULATION_CANCEL_API_ADAPTER_OK');
})().catch(error => { console.error(error); process.exitCode = 1; });
