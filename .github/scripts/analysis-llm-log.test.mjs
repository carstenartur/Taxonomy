import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js', import.meta.url), 'utf8');
const css = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/css/taxonomy.css', import.meta.url), 'utf8');
const template = readFileSync(new URL('../../taxonomy-app/src/main/resources/templates/index.html', import.meta.url), 'utf8');
const utilsSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-utils.js', import.meta.url), 'utf8');

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
function fixture(detail, status = 'FAILED', options = {}) {
    const root = new Element('main'); root.connected = true;
    const log = new Element('div'); log.id = 'llmCommLogContent';
    const anchor = new Element('div'); anchor.id = 'statusArea'; root.append(anchor, log);
    const timers = new Map(); let timerId = 0, detailCalls = 0;
    const clipboardWrites = [];
    const runtime = { workspaceId: 'workspace', analysisGeneration: 1 };
    let snapshot = { operationId: 'run', sequence: 1, phase: 'SCORING', status: 'RUNNING',
        startedAt: 1000, serverTime: 4000, lastActivityAt: 4000, evaluatedNodes: 1,
        calls: [{ id: 1, provider: 'GEMINI', node: 'IP', status, startedAt: 1000, durationMillis: 1000 }], memory: {} };
    snapshot.calls[0].startedAt = Object.hasOwn(options, 'startedAt') ? options.startedAt : 1000;
    const document = { documentElement: { lang: options.locale || 'de' }, createElement: tag => new Element(tag),
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
    const navigator = { clipboard: { async writeText(value) { clipboardWrites.push(value); } } };
    vm.runInNewContext(source, { window, document, navigator, AbortController, console });
    const monitor = window.TaxonomyAnalysisProgress.start('run');
    return { root, log, monitor, runtime, clipboardWrites,
        get detailCalls() { return detailCalls; },
        get button() { return descendants(root).find(element => element.tagName === 'BUTTON'); },
        update(values) { snapshot = { ...snapshot, ...values, sequence: snapshot.sequence + 1 }; },
        get row() { return descendants(log).find(element => element.tagName === 'DETAILS'); },
        async tick() { const pair = [...timers].sort((a, b) => a[1].delay - b[1].delay)[0]; assert.ok(pair, 'scheduled observation'); timers.delete(pair[0]); await pair[1].fn(); await flush(); },
        async open() { this.row.open = true; this.row.emit('toggle'); await flush(); },
        ready() { snapshot = { ...snapshot, sequence: 2, calls: [{ ...snapshot.calls[0], status: 'FAILED' }] }; }
    };
}
const reply = 'please provide details.\nSecond line. <script>not executable</script>';
const failure = { prompt: 'First prompt line\nSecond prompt line', response: reply,
    error: 'Expected a JSON object; inspect the LLM communication log.', truncated: false };

test('shared clipboard helper falls back to a temporary textarea outside secure clipboard contexts', async () => {
    const appended = [];
    let selected = false, removed = false, copied = false;
    const body = {
        appendChild(element) { appended.push(element); element.parent = this; }
    };
    const document = {
        readyState: 'loading',
        documentElement: { lang: 'de' },
        body,
        addEventListener() {},
        createElement(tag) {
            assert.equal(tag, 'textarea');
            return {
                value: '', readOnly: false, className: '',
                select() { selected = true; },
                remove() { removed = true; }
            };
        },
        execCommand(command) { assert.equal(command, 'copy'); copied = true; return true; }
    };
    const window = { isSecureContext: false };
    const navigator = {};
    vm.runInNewContext(utilsSource, { window, document, navigator, console, DOMParser: class {} });
    assert.equal(typeof window.TaxonomyUtils.copyText, 'function', 'shared copy helper is exported');
    await window.TaxonomyUtils.copyText('exact diagnostic text');
    assert.equal(appended.length, 1);
    assert.equal(appended[0].value, 'exact diagnostic text');
    assert.equal(appended[0].readOnly, true);
    assert.equal(selected, true);
    assert.equal(copied, true);
    assert.equal(removed, true);
});

test('expanded LLM diagnostics use the page flow instead of nested vertical scrollports', () => {
    const marker = 'id="llmCommLogContent"';
    const idIndex = template.indexOf(marker);
    assert.notEqual(idIndex, -1, 'LLM communication log container exists');
    const tagStart = template.lastIndexOf('<div', idIndex);
    const tagEnd = template.indexOf('>', idIndex);
    const logTag = template.slice(tagStart, tagEnd + 1);
    assert.doesNotMatch(logTag, /max-height\s*:/i);
    assert.doesNotMatch(logTag, /overflow-y\s*:\s*auto/i);

    const diagnosticRule = css.match(/#llmCommLogContent \.llm-log-prompt,\s*#llmCommLogContent \.llm-log-response\s*\{[^}]*\}/);
    assert.ok(diagnosticRule, 'prompt/response diagnostic style exists');
    assert.doesNotMatch(diagnosticRule[0], /max-height\s*:/i);
    assert.doesNotMatch(diagnosticRule[0], /overflow-y\s*:\s*auto/i);
});

test('expanded response and prompt each provide a direct copy action', async () => {
    const view = fixture(failure); await view.tick(); await view.open();
    const copyButtons = descendants(view.log).filter(element =>
        element.tagName === 'BUTTON' && element.className.split(/\s+/).includes('llm-log-copy'));
    assert.equal(copyButtons.length, 2, 'response and prompt both expose copy controls');
    copyButtons[0].emit('click'); copyButtons[1].emit('click'); await flush();
    assert.deepEqual(view.clipboardWrites, [reply, failure.prompt]);
    view.monitor.stop();
});

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


test('collapsed calls expose stable absolute server start time and call/run identities', async () => {
    const timestamp = Date.parse('2026-09-23T08:41:03.123Z');
    const view = fixture(failure, 'FAILED', { startedAt: timestamp });
    await view.tick();
    const time = descendants(view.row).find(element => element.tagName === 'TIME');
    assert.ok(time, 'absolute start belongs to collapsed summary');
    assert.equal(time.attributes.datetime, '2026-09-23T08:41:03.123Z');
    assert.match(time.textContent, /2026-09-23 08:41:03[.]123 UTC/);
    assert.match(view.row.children[0].textContent, /Aufruf #1/);
    assert.match(view.log.textContent, /Vorgang: run/);
    view.update({ serverTime: timestamp + 120000 }); await view.tick();
    const updated = descendants(view.row).find(element => element.tagName === 'TIME');
    assert.equal(updated.textContent, time.textContent, 'polling must not manufacture a new start');
    view.monitor.stop();
});

for (const timestamp of [undefined, null, 'invalid', Number.NaN, 9e20]) {
    test('missing or invalid server timestamp is explicit: ' + String(timestamp), async () => {
        const view = fixture(failure, 'FAILED', { startedAt: timestamp }); await view.tick();
        assert.match(view.row.children[0].textContent, /Startzeit unbekannt/);
        assert.ok(!descendants(view.row).some(element => element.tagName === 'TIME'));
        view.monitor.stop();
    });
}

test('English log uses the same timestamp contract and translated labels', async () => {
    const view = fixture(failure, 'FAILED', { locale: 'en' }); await view.tick();
    assert.match(view.row.children[0].textContent, /Call #1/);
    assert.match(view.row.children[0].textContent, /Started:/);
    assert.match(view.log.textContent, /Operation: run/); view.monitor.stop();
});

test('finished analysis keeps a genuinely disabled but clearly non-actionable button', async () => {
    const view = fixture(failure); await view.tick();
    assert.equal(view.button.disabled, false); assert.match(view.button.className, /btn-danger/);
    view.monitor.finish('PARTIAL', false);
    assert.equal(view.button.disabled, true);
    assert.doesNotMatch(view.button.className, /btn-danger/);
    assert.match(view.button.textContent, /Analyse beendet/);
    assert.doesNotMatch(view.button.textContent, /abbrechen/);
});

test('terminal snapshots and transport loss do not leave an apparent cancel action', async () => {
    for (const status of ['PARTIAL', 'COMPLETED', 'ERROR', 'CANCELLED']) {
        const view = fixture(failure); view.update({ status }); await view.tick();
        assert.equal(view.button.disabled, true);
        assert.doesNotMatch(view.button.className, /btn-danger/);
        assert.match(view.button.textContent, /Analyse beendet/);
    }
    const view = fixture(failure); await view.tick(); view.monitor.transportFailed();
    assert.equal(view.button.disabled, true); assert.doesNotMatch(view.button.className, /btn-danger/);
    assert.match(view.button.textContent, /Nicht verfügbar/);
});

test('cancelling has its own disabled non-action label', async () => {
    const view = fixture(failure); await view.tick(); await view.monitor.cancel();
    assert.equal(view.button.disabled, true); assert.doesNotMatch(view.button.className, /btn-danger/);
    assert.match(view.button.textContent, /Abbruch angefordert/);
    view.update({ status: 'CANCELLING' }); await view.tick();
    assert.match(view.button.textContent, /Abbruch angefordert/); view.monitor.stop();
});

test('a truncated diagnostic preview is not independently classified as invalid JSON', async () => {
    const view = fixture({ prompt: '', response: '{"BR":', responseLength: 20000, truncated: true, error: '' }, 'COMPLETED');
    await view.tick(); await view.open();
    assert.match(view.log.textContent, /20000/);
    assert.ok(!descendants(view.log).some(element => element.className.includes('llm-log-error-detail')));
    view.monitor.stop();
});

test('terminal duration is kept visibly after the final result, not replaced by its receipt text', async () => {
    const view = fixture(failure);
    view.update({ status: 'COMPLETED', elapsedMillis: 123456, finishedAt: 124456, serverTime: 900000 });
    await view.tick(); view.monitor.finish('SUCCESS', false);
    const duration = descendants(view.root).find(element => element.id === 'analysisElapsed');
    assert.ok(duration, 'dedicated readable duration');
    assert.match(duration.textContent, /2 min 03/);
    assert.doesNotMatch(duration.textContent, /899/);
});

test('legacy terminal snapshots do not manufacture an analysis duration from later server time', async () => {
    const view = fixture(failure);
    view.update({ status: 'COMPLETED', serverTime: 900000 });
    await view.tick(); view.monitor.finish('SUCCESS', false);
    const duration = descendants(view.root).find(element => element.id === 'analysisElapsed');
    assert.ok(duration); assert.match(duration.textContent, /nicht aufgezeichnet|not recorded/i);
});


test('authoritative result duration survives missing final diagnostics', async () => {
    const view = fixture(failure); await view.tick();
    view.monitor.finish('SUCCESS', false, 123456);
    assert.match(descendants(view.root).find(element => element.id === 'analysisElapsed').textContent, /2 min 03 s/);
});
test('authoritative result duration is not replaced by a differently scoped diagnostic duration', async () => {
    const view = fixture(failure); await view.tick();
    view.update({ status: 'COMPLETED', elapsedMillis: 130000 });
    view.monitor.finish('SUCCESS', true, 123456); await flush();
    assert.match(descendants(view.root).find(element => element.id === 'analysisElapsed').textContent, /2 min 03 s/);
});

for (const value of [undefined, null, -1, Number.NaN]) {
    test('completion without valid measured timing clears the live estimate: ' + String(value), async () => {
        const view = fixture(failure); await view.tick();
        const elapsed = descendants(view.root).find(element => element.id === 'analysisElapsed');
        assert.match(elapsed.textContent, /0 min 03 s/);
        view.monitor.finish('ERROR', false, value);
        assert.match(elapsed.textContent, /Nicht aufgezeichnet/);
    });
}

test('transport loss without terminal evidence clears live duration, not the diagnostic log', async () => {
    const view = fixture(failure); await view.tick(); await view.open();
    view.monitor.transportFailed();
    assert.match(descendants(view.root).find(element => element.id === 'analysisElapsed').textContent, /Nicht aufgezeichnet/);
    assert.ok(view.log.textContent.includes(reply));
});

test('terminal zero remains measured rather than unknown after completion', async () => {
    const view = fixture(failure); await view.tick(); view.monitor.finish('SUCCESS', false, 0);
    assert.match(descendants(view.root).find(element => element.id === 'analysisElapsed').textContent, /0 min 00 s/);
});

test('English unmeasured completion is explicit', async () => {
    const view = fixture(failure, 'FAILED', { locale: 'en' }); await view.tick();
    view.monitor.finish('ERROR', false);
    assert.match(descendants(view.root).find(element => element.id === 'analysisElapsed').textContent, /Analysis duration: Not recorded/);
});
