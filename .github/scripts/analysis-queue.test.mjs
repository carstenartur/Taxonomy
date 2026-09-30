import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js', import.meta.url), 'utf8');
const id = 'cb2a3d71-e849-4a50-9855-1f9cb8f81402';
function fixture({ cancel, snapshots = [], view = false } = {}) {
    const timers = new Map(), writes = [], observed = [], elements = new Map();
    const scope = { workspaceId: 'workspace-a', generation: 1, analysisGeneration: 1, invalidating: false };
    let serial = 0, reads = 0;
    const schedule = (fn, delay) => { const key = ++serial; timers.set(key, { fn, delay }); return key; };
    const unschedule = key => timers.delete(key);
    class Element {
        constructor(tag) { this.tag = tag; this.children = []; this.value = ''; this.isConnected = true; }
        set id(value) { this.identifier = value; elements.set(value, this); }
        get id() { return this.identifier; }
        set textContent(value) { this.value = String(value); this.children = []; }
        get textContent() { return this.value + this.children.map(child => child.textContent).join(' '); }
        append(...children) { this.children.push(...children); }
        setAttribute() {}
        addEventListener() {}
        insertAdjacentElement() {}
        replaceChildren(...children) { this.children = children; this.value = ''; }
        remove() { this.isConnected = false; elements.delete(this.id); }
    }
    const document = { documentElement: { lang: 'de' }, createElement: tag => new Element(tag),
        addEventListener() {}, dispatchEvent() {}, getElementById: key => elements.get(key) || null };
    const api = {
        async getRunStatus(operation, options) {
            assert.equal(operation, id);
            assert.equal(options.workspaceId, 'workspace-a');
            const value = snapshots[Math.min(reads++, snapshots.length - 1)] || snapshot();
            return { status: 200, json: async () => value };
        },
        async cancelRun(operation, options) {
            writes.push({ operation, workspaceId: options.workspaceId });
            if (cancel) await cancel(writes.length);
        }
    };
    const window = { setTimeout: schedule, clearTimeout: unschedule, TaxonomyAnalysisSessionApi: api,
        __TaxonomyAnalysisSessionContext: { runtime: scope } };
    vm.runInNewContext(source, { window, document, AbortController, console,
        CustomEvent: class { constructor(name, options) { this.detail = options.detail; } } });
    const monitor = view ? window.TaxonomyAnalysisProgress.start(id, value => observed.push(value))
        : window.TaxonomyAnalysisProgress.createMonitor({ id, context: () => ({ ...scope }), api,
            setTimeout: schedule, clearTimeout: unschedule, onSnapshot: value => observed.push(value), onUnavailable() {} });
    async function step(delay) {
        const entry = [...timers].find(([, item]) => item.delay === delay);
        assert.ok(entry, `Missing timer ${delay}`);
        timers.delete(entry[0]);
        await entry[1].fn();
    }
    return { monitor, scope, writes, observed, elements, step };
}
function snapshot(status = 'QUEUED', sequence = 1) {
    return { operationId: id, sequence, status, phase: status === 'QUEUED' ? 'QUEUED' : 'LLM_REQUEST',
        calls: [], rawScores: {}, evaluatedNodes: 0, memory: {}, startedAt: 1000, lastActivityAt: 1000,
        serverTime: 32000, elapsedMillis: 31000, queueWaitMillis: 30000, executionMillis: 1000 };
}

test('an early rejected cancellation is retried once when its queued registration appears', async () => {
    const f = fixture({ cancel: async count => { if (count === 1) throw Object.assign(new Error('not registered'), { status: 404 }); } });
    await f.monitor.cancel();
    await f.step(0);
    assert.equal(f.writes.length, 2);
    await f.step(1000);
    assert.equal(f.writes.length, 2);
    f.monitor.stop();
});

test('workspace departure cancels a queued operation only in its original scope', async () => {
    const f = fixture();
    f.scope.workspaceId = 'workspace-b';
    f.monitor.cancelAndStop();
    await f.step(0);
    assert.deepEqual(f.writes, [{ operation: id, workspaceId: 'workspace-a' }]);
});

test('an ambiguous cancellation response is never retried by queued cleanup', async () => {
    const f = fixture({ cancel: async () => { throw new Error('response lost'); } });
    await f.monitor.cancel();
    f.monitor.cancelAndStop();
    await f.step(0);
    assert.equal(f.writes.length, 1);
});

test('queued, running and terminal observation never creates new work', async () => {
    const f = fixture({ snapshots: [snapshot(), snapshot('RUNNING', 2), snapshot('COMPLETED', 3)] });
    await f.step(0); await f.step(1000); await f.step(1000);
    assert.deepEqual(f.observed.map(value => value.status), ['QUEUED', 'RUNNING', 'COMPLETED']);
    assert.equal(f.writes.length, 0);
});

test('queued view explains waiting and keeps cancellation enabled without a fictional position', async () => {
    const f = fixture({ view: true });
    await f.step(0);
    const panel = f.elements.get('analysisLiveProgress');
    assert.match(panel.textContent, /Wartet auf einen freien Analyseplatz/);
    assert.match(panel.textContent, /Noch keine LLM-Anfrage/);
    assert.doesNotMatch(panel.textContent, /Position|Platz [0-9]/);
    const cancel = panel.children.find(child => child.tag === 'button');
    assert.equal(cancel.disabled, false);
    f.monitor.stop();
});

test('view separates thirty seconds queue time from one second execution time', async () => {
    const f = fixture({ view: true, snapshots: [snapshot('RUNNING')] });
    await f.step(0);
    assert.match(f.elements.get('analysisQueueWait')?.textContent || '', /30 s/);
    assert.match(f.elements.get('analysisElapsed').textContent, /0 min 01 s/);
    f.monitor.stop();
});
