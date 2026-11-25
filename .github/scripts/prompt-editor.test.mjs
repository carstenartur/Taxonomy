import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const path = '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js';
const source = await readFile(new URL(path, import.meta.url), 'utf8');
const start = source.indexOf('// ── Prompt template editor');
const end = source.indexOf('// ── Manual Scoring Mode', start);
assert.ok(start >= 0 && end > start, 'The production editor section must exist');
const drain = () => new Promise(resolve => setImmediate(resolve));
const response = (body, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => body});
const record = (code, template, overridden = false) =>
    ({code, name: code, template, defaultTemplate: code + ' default', overridden, editable: true});

async function editor() {
    const ids = new Map();
    function element() {
        const classes = new Set();
        return {value: '', innerHTML: '', textContent: '', disabled: false, hidden: false, children: [], events: new Map(),
            classList: {toggle(name, on) { on ? classes.add(name) : classes.delete(name); },
                contains: name => classes.has(name), add: name => classes.add(name), remove: name => classes.delete(name)},
            addEventListener(type, fn) { this.events.set(type, fn); },
            appendChild(child) { this.children.push(child); },
            replaceChildren(...children) { this.children = children; },
            setAttribute(name, value) { this[name] = value; }};
    }
    function get(id) {
        if (!ids.has(id)) ids.set(id, element());
        return ids.get(id);
    }
    get('promptSelect').value = 'A';
    const requests = [];
    function request(kind, code, value) {
        return new Promise(resolve => requests.push({kind, code, value, resolve}));
    }
    const api = {
        promptCategories: async () => response({SCORING: [record('A', 'A default'), record('B', 'B default')]}),
        prompt: code => request('get', code),
        savePrompt: (code, value) => request('save', code, value),
        deletePrompt: code => request('reset', code)
    };
    const document = {getElementById: get, createElement: element};
    vm.runInNewContext(source.slice(start, end) + '\ninitPromptEditor();', {
        document, window: {TaxonomyReferenceApi: api}, isAdminMode: () => true,
        t: (key, ...args) => [key, ...args].join(' '), escapeHtml: s => s
    });
    await drain();
    const text = get('promptTextarea'), save = get('promptSaveBtn'), reset = get('promptResetBtn');
    const fire = (id, type) => get(id).events.get(type)?.({target: get(id)});
    return {get, text, save, reset, requests, fire,
        select(code) { get('promptSelect').value = code; fire('promptSelect', 'change'); },
        edit(value) { text.value = value; fire('promptTextarea', 'input'); },
        async answer(kind, code, body, status = 200) {
            const match = requests.find(r => !r.answered && r.kind === kind && r.code === code);
            assert.ok(match, 'Expected pending ' + kind + ' for ' + code);
            match.answered = true;
            match.resolve(response(body, status));
            await drain();
        },
        snapshot() {
            return {text: text.value, status: get('promptStatusMsg').innerHTML + get('promptStatusMsg').textContent,
                override: get('promptOverrideBadge').classList.contains('d-none'),
                defaultBadge: get('promptDefaultBadge').classList.contains('d-none'), save: save.disabled, reset: reset.disabled};
        }};
}

for (const lateStatus of [200, 503]) {
    test('late template response (' + lateStatus + ') preserves the newer selection and its draft', async () => {
        const f = await editor();
        f.select('B');
        await f.answer('get', 'B', record('B', 'B current'));
        f.edit('B edits');
        const expected = f.snapshot();
        await f.answer('get', 'A', record('A', 'A late', true), lateStatus);
        assert.deepEqual(f.snapshot(), expected);
    });
}

test('failed and pending reads prevent edits and writes, and retry restores the selected template', async () => {
    const f = await editor();
    function assertGuarded() {
        assert.equal(f.text.disabled || f.text.readOnly, true);
        assert.equal(f.save.disabled, true);
        assert.equal(f.reset.disabled, true);
        f.fire('promptSaveBtn', 'click');
        f.fire('promptResetBtn', 'click');
        assert.ok(f.requests.every(r => r.kind === 'get'));
    }
    assertGuarded();
    await f.answer('get', 'A', null, 503);
    assertGuarded();
    assert.match(f.snapshot().status, /unavailable/);
    f.fire('promptRetryBtn', 'click');
    assertGuarded();
    await f.answer('get', 'A', record('A', 'A default'));
    assert.equal(f.text.value, 'A default');
    assert.equal(f.text.disabled || Boolean(f.text.readOnly), false);
    assert.equal(f.reset.disabled, true);
    assert.equal(f.save.disabled, true);
    f.edit('   ');
    assert.equal(f.save.disabled, true);
    f.edit('changed');
    assert.equal(f.save.disabled, false);
    f.fire('promptSaveBtn', 'click');
    assert.equal(f.requests.find(r => r.kind === 'save').value, 'changed');
});

for (const operation of ['save', 'reset']) {
    test('pending ' + operation + ' for A preserves the unsaved draft for B', async () => {
        const f = await editor();
        await f.answer('get', 'A', record('A', 'A current', true));
        if (operation === 'save') f.edit('A edits');
        f.fire(operation === 'save' ? 'promptSaveBtn' : 'promptResetBtn', 'click');
        f.select('B');
        await f.answer('get', 'B', record('B', 'B current', true));
        f.edit('B edits');
        const expected = f.snapshot();
        await f.answer(operation, 'A', record('A', operation === 'save' ? 'A edits' : 'A default'));
        if (f.requests.some(r => !r.answered && r.kind === 'get' && r.code === 'A')) {
            await f.answer('get', 'A', record('A', 'A default'));
        }
        assert.deepEqual(f.snapshot(), expected);
    });
}

test('returning to a template during its save reads persisted content after the write', async () => {
    const f = await editor();
    await f.answer('get', 'A', record('A', 'A current', true));
    f.edit('A after save');
    f.fire('promptSaveBtn', 'click');
    f.select('B');
    f.select('A');
    assert.ok(f.requests.every(r => r.code !== 'A' || r.kind !== 'get' || r.answered),
        'Revisiting must wait for the in-flight write before reading A');
    await f.answer('save', 'A', record('A', 'A after save', true));
    await f.answer('get', 'A', record('A', 'A after save', true));
    assert.equal(f.text.value, 'A after save');
    f.edit('new unsaved draft');
    const expected = f.snapshot();
    await f.answer('get', 'B', record('B', 'B stale'));
    assert.deepEqual(f.snapshot(), expected);
});

test('the first read of A cannot overwrite a later draft of A after navigating via B', async () => {
    const f = await editor();
    f.select('B');
    f.select('A');
    const oldRead = f.requests.find(r => r.code === 'A');
    const newRead = f.requests.findLast(r => r.code === 'A');
    assert.notEqual(oldRead, newRead);
    newRead.resolve(response(record('A', 'A latest', true)));
    await drain();
    assert.equal(f.text.value, 'A latest');
    f.edit('A unsaved');
    const expected = f.snapshot();
    oldRead.resolve(response(record('A', 'A obsolete')));
    await drain();
    assert.deepEqual(f.snapshot(), expected);
});

test('a read after resetting A cannot overwrite edits made after switching to B', async () => {
    const f = await editor();
    await f.answer('get', 'A', record('A', 'A custom', true));
    f.fire('promptResetBtn', 'click');
    await f.answer('reset', 'A', record('A', 'A default'));
    f.select('B');
    await f.answer('get', 'B', record('B', 'B current', true));
    f.edit('B unsaved');
    const expected = f.snapshot();
    await f.answer('get', 'A', record('A', 'A default'));
    assert.deepEqual(f.snapshot(), expected);
});
