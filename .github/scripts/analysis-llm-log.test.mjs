import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js', import.meta.url), 'utf8');

// Minimal DOM boundary. The production monitor, lifecycle and renderer run unchanged.
class Element {
    constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this.listeners = {}; this.attributes = {}; this.className = ''; this.connected = false; this.ownText = ''; }
    set textContent(value) { this.children.forEach(child => { child.parent = null; }); this.children = []; this.ownText = String(value); }
    get textContent() { return this.ownText + this.children.map(child => child.textContent).join(''); }
    get isConnected() { return this.connected || Boolean(this.parent?.isConnected); }
    append(...children) { children.forEach(child => { child.parent = this; this.children.push(child); }); }
    replaceChildren(...children) { this.textContent = ''; this.append(...children); }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); this.parent = null; this.connected = false; }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(name, fn) { (this.listeners[name] ||= []).push(fn); }
    emit(name) { (this.listeners[name] || []).forEach(fn => fn()); }
    insertAdjacentElement(_, element) { this.parent.append(element); }
}
function descendants(element) { return element.children.flatMap(child => [child, ...descendants(child)]); }
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function fixture(detail, status = 'FAILED') {
    const root = new Element('main'); root.connected = true;
    const log = new Element('div'); log.id = 'llmCommLogContent';
    const anchor = new Element('div'); anchor.id = 'statusArea'; root.append(anchor, log);
    const timers = new Map(); let timerId = 0, detailCalls = 0;
    const runtime = { workspaceId: 'workspace', analysisGeneration: 1 };
    let snapshot = { operationId: 'run', sequence: 1, phase: 'SCORING', status: 'RUNNING',
        startedAt: 1000, serverTime: 4000, lastActivityAt: 4000, evaluatedNodes: 1,
        calls: [{ id: 1, provider: 'GEMINI', node: 'IP', status, startedAt: 1000, durationMillis: 1000 }], memory: {} };
    const document = { documentElement: { lang: 'de' }, createElement: tag => new Element(tag),
        getElementById: id => descendants(root).find(element => element.id === id) || null,
        addEventListener() {} };
    const window = { __TaxonomyAnalysisSessionContext: { runtime },
        setTimeout(fn, delay) { const id = ++timerId; timers.set(id, { fn, delay }); return id; },
        clearTimeout(id) { timers.delete(id); },
        TaxonomyAnalysisSessionApi: {
            async getRunStatus() { return { json: async () => snapshot }; },
            async getRunCallDetail() { detailCalls++; return { json: async () => typeof detail === 'function' ? await detail() : detail }; },
            async cancelRun() {}
        }
    };
    vm.runInNewContext(source, { window, document, AbortController, console });
    const monitor = window.TaxonomyAnalysisProgress.start('run');
    return { root, log, monitor, runtime,
        get detailCalls() { return detailCalls; },
        get row() { return descendants(log).find(element => element.tagName === 'DETAILS'); },
        async tick() { const pair = [...timers].sort((a, b) => a[1].delay - b[1].delay)[0]; assert.ok(pair, 'scheduled observation'); timers.delete(pair[0]); await pair[1].fn(); await flush(); },
        async open() { this.row.open = true; this.row.emit('toggle'); await flush(); },
        ready() { snapshot = { ...snapshot, sequence: 2, calls: [{ ...snapshot.calls[0], status: 'FAILED' }] }; }
    };
}
const reply = 'please provide details.\nSecond line. <script>not executable</script>';
const failure = { prompt: 'First prompt line\nSecond prompt line', response: reply,
    error: 'Expected a JSON object; inspect the LLM communication log.', truncated: false };

test('failed call shows separate, whitespace-preserving response and prompt with its error', async () => {
    const view = fixture(failure); await view.tick(); await view.open();
    const blocks = descendants(view.log).filter(element => element.tagName === 'PRE');
    assert.ok(blocks.some(element => element.textContent === reply), 'response is independently visible, not appended after a long prompt');
    assert.ok(blocks.some(element => element.textContent === failure.prompt));
    assert.ok(blocks.every(element => !element.className.split(/\s+/).includes('text-wrap')), 'Bootstrap must not collapse diagnostic newlines');
    assert.ok(view.log.textContent.includes(failure.error));
    assert.ok(!descendants(view.log).some(element => element.tagName === 'SCRIPT'));
    view.monitor.stop();
});

test('valid JSON is indented without losing the exact raw response', async () => {
    const raw = '{"IP":{"score":80,"reason":"Quoted {braces}"}}';
    const view = fixture({ prompt: 'Prompt', response: raw, error: '', truncated: false }, 'COMPLETED');
    await view.tick(); await view.open();
    const blocks = descendants(view.log).filter(element => element.tagName === 'PRE').map(element => element.textContent);
    assert.ok(blocks.includes(JSON.stringify(JSON.parse(raw), null, 2)), 'indented JSON view');
    assert.ok(blocks.includes(raw), 'unchanged raw view'); view.monitor.stop();
});

test('each truncated field identifies its original size and does not claim complete evidence', async () => {
    const view = fixture({ prompt: 'prompt\n[truncated]', response: 'reply\n[truncated]', error: 'failed',
        truncated: true, promptLength: 20000, responseLength: 18000 });
    await view.tick(); await view.open();
    assert.match(view.log.textContent, /20000/); assert.match(view.log.textContent, /18000/);
    assert.doesNotMatch(view.log.textContent, /Bewertungen bleiben vollständig/); view.monitor.stop();
});

test('absent response is explicit rather than an empty apparent answer', async () => {
    const view = fixture({ prompt: '', response: '', error: 'IllegalStateException', truncated: false });
    await view.tick(); await view.open();
    assert.match(view.log.textContent, /Keine Antwort/); assert.match(view.log.textContent, /IllegalStateException/); view.monitor.stop();
});

test('an already open pending call loads the final response once without another toggle', async () => {
    const view = fixture(failure, 'STARTED'); await view.tick(); await view.open();
    assert.equal(view.detailCalls, 0); view.ready(); await view.tick();
    assert.equal(view.detailCalls, 1); assert.ok(view.log.textContent.includes(reply));
    view.row.emit('toggle'); await flush(); assert.equal(view.detailCalls, 1); view.monitor.stop();
});

test('terminal monitoring still allows on-demand inspection', async () => {
    const view = fixture(failure); await view.tick(); view.monitor.finish('PARTIAL', false); await view.open();
    assert.ok(view.log.textContent.includes(reply)); assert.equal(view.detailCalls, 1);
});

test('late diagnostic replies cannot publish across analysis generations', async () => {
    let resolve; const pending = new Promise(done => { resolve = done; });
    const view = fixture(() => pending); await view.tick(); await view.open();
    view.runtime.analysisGeneration++; resolve(failure); await flush();
    assert.ok(!view.log.textContent.includes(reply)); view.monitor.stop();
});

for (const value of [{ IP: { score: 80, reason: 'Already formatted' } }, []]) {
    test('preformatted JSON keeps an explicit expandable raw response: ' + JSON.stringify(value), async () => {
        const raw = JSON.stringify(value, null, 2);
        const view = fixture({ prompt: 'Prompt', response: raw, error: '', truncated: false }, 'COMPLETED');
        await view.tick(); await view.open();
        const rawView = descendants(view.log).find(element => element.tagName === 'DETAILS'
            && element.children.some(child => child.tagName === 'SUMMARY' && child.textContent === 'Unveränderte Rohantwort'));
        assert.ok(rawView, 'Valid JSON always has a separately identifiable raw evidence view');
        assert.ok(rawView.children.some(child => child.tagName === 'PRE' && child.textContent === raw));
        view.monitor.stop();
    });
}
