import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js', import.meta.url), 'utf8');
const clientSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/api/taxonomy-api-client.js', import.meta.url), 'utf8');
const apiSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/api/analysis-session-api.js', import.meta.url), 'utf8');
const routingSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-api-routing.js', import.meta.url), 'utf8');
const id = 'cb2a3d71-e849-4a50-9855-1f9cb8f81402';
function fixture(fetcher) {
    const scope = { workspaceId: 'workspace-a', generation: 1, invalidating: false };
    const timers = new Map(), calls = [], snapshots = [], unavailable = [];
    let serial = 0;
    const authFailures = [];
    const schedule = (fn, delay) => { const key = ++serial; timers.set(key, { fn, delay }); return key; };
    const unschedule = key => timers.delete(key);
    const document = {
        addEventListener() {}, getElementById() { return null; },
        querySelector(selector) {
            return selector === 'meta[name="_csrf"]' ? { content: 'test-token' }
                : selector === 'meta[name="_csrf_header"]' ? { content: 'X-CSRF-TOKEN' } : null;
        },
        dispatchEvent(event) { authFailures.push(event.detail); }
    };
    const window = {
        location: { href: 'https://taxonomy.example/', origin: 'https://taxonomy.example' },
        TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
        __TaxonomyAnalysisSessionContext: { runtime: scope },
        fetch: async (url, options) => {
            calls.push({ url, options: { ...options, headers: Object.fromEntries(options.headers) } });
            return fetcher(url, options);
        }
    };
    const sandbox = vm.createContext({ window, document, AbortController, console,
        URL, Request, Headers, Response, CustomEvent, setTimeout: schedule, clearTimeout: unschedule });
    vm.runInContext(clientSource, sandbox);
    vm.runInContext(apiSource, sandbox);
    vm.runInContext(routingSource, sandbox);
    vm.runInContext(source, sandbox);
    const monitor = window.TaxonomyAnalysisProgress.createMonitor({
        id, context: () => ({ ...scope }), api: window.TaxonomyAnalysisSessionApi,
        setTimeout: schedule, clearTimeout: unschedule,
        onSnapshot: (value, changed) => snapshots.push({ value, changed }),
        onUnavailable: message => unavailable.push(message)
    });
    async function step(delay) {
        const entry = [...timers].find(([, item]) => item.delay === delay);
        assert.ok(entry, `Expected timer ${delay}; got ${[...timers.values()].map(v => v.delay)}`);
        timers.delete(entry[0]);
        await entry[1].fn();
        await new Promise(resolve => setImmediate(resolve));
    }
    return { scope, timers, calls, snapshots, unavailable, authFailures, api: window.TaxonomyAnalysisSessionApi, monitor, step };
}
function response(data, status = 200) {
    return new Response(status === 202 ? null : JSON.stringify(data), { status });
}
function snapshot(sequence = 1, status = 'RUNNING') {
    return { operationId: id, sequence, status, phase: 'LLM_REQUEST', calls: [{ id: 1, status: 'STARTED' }],
        rawScores: { CP: 80 }, memory: { percent: 81, warning: true } };
}

test('observes a started call and partial scores before completion; never starts work', async () => {
    const f = fixture(async () => response(snapshot()));
    await f.step(0);
    assert.equal(f.snapshots[0].value.calls[0].status, 'STARTED');
    assert.equal(f.snapshots[0].value.rawScores.CP, 80);
    assert.equal(f.snapshots[0].value.memory.warning, true);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].options.method, 'GET');
    assert.equal(f.calls[0].options.headers['x-taxonomy-workspace-id'], 'workspace-a');
    assert.match(f.calls[0].url, /workspaceId=workspace-a/);
    f.monitor.stop();
    assert.equal(f.timers.size, 0);
});

test('unchanged business sequence still updates heartbeat and heap data', async () => {
    let count = 0;
    const f = fixture(async () => response({ ...snapshot(), serverTime: ++count, memory: { percent: count } }));
    await f.step(0); await f.step(1000);
    assert.deepEqual(f.snapshots.map(s => s.changed), [true, false]);
    assert.equal(f.snapshots[1].value.serverTime, 2);
    assert.equal(f.snapshots[1].value.memory.percent, 2);
    f.monitor.stop();
});

test('rejects another operation and out-of-order responses', async () => {
    const values = [snapshot(3), snapshot(2), { ...snapshot(4), operationId: 'another-run' }];
    const f = fixture(async () => response(values.shift()));
    await f.step(0); await f.step(1000); await f.step(1000);
    assert.equal(f.snapshots.length, 1);
    assert.equal(f.snapshots[0].value.sequence, 3);
    f.monitor.stop();
});

test('late response after a generation change never reaches the browser', async () => {
    let resolve;
    const f = fixture(() => new Promise(r => { resolve = r; }));
    const pending = f.step(0);
    f.scope.generation++;
    resolve(response(snapshot()));
    await pending;
    assert.equal(f.snapshots.length, 0);
    assert.equal(f.timers.size, 0);
    f.monitor.stop();
});

test('cancellation is explicit, deduplicated, CSRF-protected and pinned to original workspace', async () => {
    const f = fixture(async () => response(snapshot(2, 'CANCELLING')));
    f.scope.workspaceId = 'workspace-b';
    await Promise.all([f.monitor.cancel(), f.monitor.cancel()]);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].options.method, 'POST');
    assert.equal(f.calls[0].options.headers['x-csrf-token'], 'test-token');
    assert.equal(f.calls[0].options.headers['x-taxonomy-workspace-id'], 'workspace-a');
    assert.match(f.calls[0].url, /\/cancel\?workspaceId=workspace-a$/);
    assert.equal(f.snapshots.length, 0);
    f.monitor.stop();
});

test('temporary connection failure retries observation without repeating analysis', async () => {
    let fail = true;
    const f = fixture(async () => { if (fail) { fail = false; throw new Error('offline'); } return response(snapshot()); });
    await f.step(0); await f.step(1000);
    assert.deepEqual(f.unavailable, ['Network request failed']);
    assert.equal(f.snapshots.length, 1);
    assert.equal(f.calls.length, 2);
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    f.monitor.stop();
});

test('an unregistered run reports waiting, not fake progress', async () => {
    const f = fixture(async () => response({}, 404));
    await f.step(0);
    assert.deepEqual(f.unavailable, ['WAITING_FOR_RUN']);
    assert.equal(f.snapshots.length, 0);
    f.monitor.stop();
});

test('terminal status releases timers and allows lazy details only in the original scope', async () => {
    const f = fixture(async url => response(url.includes('/calls/')
        ? { prompt: 'bounded prompt', response: 'bounded response', truncated: true }
        : snapshot(2, 'COMPLETED')));
    await f.step(0);
    assert.equal(f.timers.size, 0);
    assert.equal((await f.monitor.detail(1)).truncated, true);
    f.scope.workspaceId = 'workspace-b';
    await assert.rejects(f.monitor.detail(1), /STALE_ANALYSIS/);
    assert.equal(f.calls.length, 2);
});


test('terminal observation never sends a later cancellation write', async () => {
    const f = fixture(async () => response(snapshot(2, 'COMPLETED')));
    await f.step(0);
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 1);
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    assert.equal(f.timers.size, 0);
});

test('explicitly stopped monitoring cannot cancel a superseded run', async () => {
    const f = fixture(async () => response(snapshot()));
    f.monitor.stop();
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 0);
    assert.equal(f.timers.size, 0);
});

test('early admission polls accept an empty pending response without starting a run', async () => {
    let count = 0;
    const f = fixture(async () => ++count === 1
        ? { ok: true, status: 202, json: async () => { throw new Error('pending response has no JSON body'); } }
        : response(snapshot(count)));
    await f.step(0);
    assert.deepEqual(f.unavailable, ['WAITING_FOR_RUN']);
    assert.equal(f.snapshots.length, 0);
    await f.step(1000); await f.step(1000);
    assert.equal(f.snapshots.length, 2);
    assert.ok(f.calls.slice(0, 2).every(call => call.url.endsWith('&waitForRegistration=true')));
    assert.ok(!f.calls[2].url.includes('waitForRegistration'));
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    f.monitor.stop();
});

test('a known run disappearing is not reported as pending admission', async () => {
    let count = 0;
    const f = fixture(async () => response(++count === 1 ? snapshot() : {}, count === 1 ? 200 : 404));
    await f.step(0); await f.step(1000);
    assert.deepEqual(f.unavailable, ['HTTP 404']);
    assert.equal(f.snapshots.length, 1);
    assert.ok(!f.calls[1].url.includes('waitForRegistration'));
    f.monitor.stop();
});


test('all three telemetry operations use canonical CSRF, request IDs and no-store transport', async () => {
    const f = fixture(async url => response(url.includes('/calls/') ? { prompt: 'bounded' } : snapshot()));
    await f.step(0);
    await f.monitor.detail('1/a?b');
    await f.monitor.cancel();
    assert.equal(f.calls.length, 3);
    assert.match(f.calls[1].url, /calls\/1%2Fa%3Fb\?workspaceId=workspace-a$/);
    for (const { options } of f.calls) {
        assert.equal(options.headers['x-taxonomy-workspace-id'], 'workspace-a');
        assert.ok(options.headers['x-request-id']);
        assert.equal(options.cache, 'no-store');
        assert.equal(options.credentials, 'same-origin');
    }
    assert.equal(f.calls[2].options.headers['x-csrf-token'], 'test-token');
    assert.equal(f.calls[2].options.keepalive, true);
    f.monitor.stop();
    assert.equal(f.timers.size, 0);
});

test('the polling deadline aborts the canonical request and reports a timeout', async () => {
    const f = fixture((url, options) => new Promise((resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const pending = f.step(0);
    await f.step(5000);
    await pending;
    assert.equal(f.calls[0].options.signal.aborted, true);
    assert.deepEqual(f.unavailable, ['CONNECTION_TIMEOUT']);
    f.monitor.stop();
    assert.equal(f.timers.size, 0);
});

test('stopping the monitor aborts the actual transport without a late UI error', async () => {
    const f = fixture((url, options) => new Promise((resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const pending = f.step(0);
    f.monitor.stop();
    await pending;
    assert.equal(f.calls[0].options.signal.aborted, true);
    assert.equal(f.snapshots.length, 0);
    assert.equal(f.unavailable.length, 0);
    assert.equal(f.timers.size, 0);
});

test('authorization failure is reported centrally and never retried as a cancellation write', async () => {
    const f = fixture(async () => response({ detail: 'Denied' }, 403));
    await f.monitor.cancel();
    assert.equal(f.calls.length, 1);
    assert.equal(f.authFailures.length, 1);
    assert.equal(f.authFailures[0].status, 403);
    assert.deepEqual(f.unavailable, ['HTTP 403: Denied']);
    f.monitor.stop();
});


test('unscoped session requests still follow the active tab while operation cancellation stays pinned', async () => {
    const f = fixture(async () => response(snapshot()));
    f.scope.workspaceId = 'workspace-b';
    await f.api.request('/api/analysis-drafts/current', {});
    await f.monitor.cancel();
    assert.equal(f.calls.length, 2);
    assert.match(f.calls[0].url, /analysis-drafts\/current\?workspaceId=workspace-b$/);
    assert.match(f.calls[1].url, /cancel\?workspaceId=workspace-a$/);
    assert.equal(f.calls[1].options.headers['x-taxonomy-workspace-id'], 'workspace-a');
    f.monitor.stop();
});

test('cancellation before registration is retained and delivered once admission is observed', async () => {
    let admitted = false;
    const f = fixture(async (url, options) => response(
        options.method === 'POST' ? snapshot(2, 'CANCELLING') : snapshot(),
        !admitted ? options.method === 'POST' ? 404 : 202 : 200));
    await f.monitor.cancel();
    await f.monitor.cancel();
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
    await f.step(0);
    assert.equal(f.snapshots.length, 0);
    admitted = true;
    await f.step(1000);
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 2);
    await f.step(1000);
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 2);
    assert.ok(!f.unavailable.some(message => message.includes('404')));
    f.monitor.stop();
});

test('a deferred cancellation does not write to an already completed run', async () => {
    const f = fixture(async (url, options) => options.method === 'POST'
        ? response({}, 404) : response(snapshot(2, 'COMPLETED')));
    await f.monitor.cancel();
    await f.step(0);
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
    assert.equal(f.timers.size, 0);
});


test('a known run disappearing terminates polling and cancellation without discarding the last snapshot', async () => {
    let count = 0;
    const f = fixture(async () => ++count === 1 ? response(snapshot()) : response({}, 404));
    await f.step(0); await f.step(1000);
    assert.equal(f.snapshots.length, 1);
    assert.equal(f.timers.size, 0, 'expired runs must not be polled forever');
    assert.equal(f.monitor.isCurrent(), false);
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 2);
});

for (const status of [400, 401, 403]) {
    test(`permanent observation rejection ${status} releases the polling timer`, async () => {
        const f = fixture(async () => response({}, status));
        await f.step(0);
        assert.equal(f.unavailable.length, 1);
        assert.equal(f.timers.size, 0);
        assert.equal(f.monitor.isCurrent(), false);
    });
}
