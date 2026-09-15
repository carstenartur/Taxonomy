import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync('taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js', 'utf8');
const id = 'cb2a3d71-e849-4a50-9855-1f9cb8f81402';
function fixture(fetcher) {
    const scope = { workspaceId: 'workspace-a', generation: 1, invalidating: false };
    const timers = new Map(), calls = [], snapshots = [], unavailable = [];
    let serial = 0;
    const window = {};
    vm.runInNewContext(source, { window, AbortController, console, document: { addEventListener() {} } });
    const monitor = window.TaxonomyAnalysisProgress.createMonitor({
        id, context: () => ({ ...scope }),
        fetch: async (url, options) => { calls.push({ url, options }); return fetcher(url, options); },
        headers: () => ({ 'X-CSRF-TOKEN': 'test-token' }),
        setTimeout: (fn, delay) => { const key = ++serial; timers.set(key, { fn, delay }); return key; },
        clearTimeout: key => timers.delete(key),
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
    return { scope, timers, calls, snapshots, unavailable, monitor, step };
}
function response(data, status = 200) {
    return { ok: status >= 200 && status < 300, status, json: async () => data };
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
    assert.equal(f.calls[0].options.method, undefined);
    assert.equal(f.calls[0].options.headers['X-Taxonomy-Workspace-Id'], 'workspace-a');
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
    assert.equal(f.calls[0].options.headers['X-CSRF-TOKEN'], 'test-token');
    assert.equal(f.calls[0].options.headers['X-Taxonomy-Workspace-Id'], 'workspace-a');
    assert.match(f.calls[0].url, /\/cancel\?workspaceId=workspace-a$/);
    assert.equal(f.snapshots.length, 0);
    f.monitor.stop();
});

test('temporary connection failure retries observation without repeating analysis', async () => {
    let fail = true;
    const f = fixture(async () => { if (fail) { fail = false; throw new Error('offline'); } return response(snapshot()); });
    await f.step(0); await f.step(1000);
    assert.deepEqual(f.unavailable, ['offline']);
    assert.equal(f.snapshots.length, 1);
    assert.equal(f.calls.length, 2);
    assert.ok(f.calls.every(call => !call.options.method));
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
    assert.ok(f.calls.every(call => !call.options.method));
    assert.equal(f.timers.size, 0);
});

test('explicitly stopped monitoring cannot cancel a superseded run', async () => {
    const f = fixture(async () => response(snapshot()));
    f.monitor.stop();
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 0);
    assert.equal(f.timers.size, 0);
});
