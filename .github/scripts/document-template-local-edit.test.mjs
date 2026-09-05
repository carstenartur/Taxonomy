import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import vm from 'node:vm';
import test from 'node:test';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const resource = path => readFileSync(resolve(root, 'taxonomy-app/src/main/resources', path), 'utf8');
const source = resource('static/js/document-templates/document-template-local-edit.js');
const apiSource = resource('static/js/api/document-templates-api.js');
const revision = 'a'.repeat(40);
const next = 'b'.repeat(40);
const id = 'decision-rationale-report';
const labels = Object.fromEntries(['failed', 'invalid', 'tooLarge', 'busy', 'saved',
    'conflict', 'authentication', 'uncertain'].map(key => [key, key]));

function boot(fetch, overrides = {}) {
    const nodes = new Map();
    function node(id) {
        if (!nodes.has(id)) nodes.set(id, {
            dataset: {}, listeners: {}, hidden: true, disabled: true, textContent: '',
            classList: { toggle() {} }, attributes: {}, files: [],
            addEventListener(name, callback) { this.listeners[name] = callback; },
            setAttribute(name, value) { this.attributes[name] = value; },
            setCustomValidity(value) { this.validationMessage = value; },
            focus() { this.focused = true; }
        });
        return nodes.get(id);
    }
    const workspace = node('documentTemplateLocalEdit');
    Object.assign(workspace.dataset, { revision, templateId: id, maxBytes: '1000',
        uploadUrl: '/taxonomy/api/admin/document-templates/' + id + '?displayName=Report' }, overrides);
    node('localTemplateLabels').dataset = labels;
    const file = node('localTemplateFile');
    file.files = [{ name: id + '.dotx', size: 200 }];
    const form = node('localTemplateForm');
    form.reportValidity = () => !file.validationMessage;
    node('localTemplateCompare').href = 'https://example.test/taxonomy/admin/document-templates/' + id;
    const context = { URL, Headers, console,
        document: { getElementById: node, querySelector: selector => ({ content:
            selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'browser-csrf' }) },
        window: { fetch, location: { origin: 'https://example.test',
            href: 'https://example.test/taxonomy/admin/document-templates/' + id + '/local-edit?revision=' + revision } }
    };
    vm.createContext(context);
    vm.runInContext(apiSource, context);
    vm.runInContext(source, context);
    return { node, file, workspace,
        submit: () => form.listeners.submit?.({ preventDefault() {} }) };
}
const response = (status = 201, payload = { templateId: id, headCommit: next }) => ({
    ok: status >= 200 && status < 300, status,
    headers: new Headers({ 'Content-Type': 'application/json' }),
    json: async () => payload
});

test('upload uses browser credentials, CSRF and exact original revision behind a context path', async () => {
    let request;
    const app = boot(async (url, init) => { request = { url, init }; return response(); });
    await app.submit();
    assert.equal(new URL(request.url).pathname, '/taxonomy/api/admin/document-templates/' + id);
    assert.equal(request.init.method, 'PUT');
    assert.equal(request.init.credentials, 'same-origin');
    assert.equal(request.init.headers.get('If-Match'), '"' + revision + '"');
    assert.equal(request.init.headers.get('X-CSRF-TOKEN'), 'browser-csrf');
    assert.equal(request.init.body, app.file.files[0]);
    assert.equal(app.node('localTemplateResult').hidden, false);
    assert.equal(app.node('localTemplateSavedRevision').textContent, next);
    assert.equal(new URL(app.node('localTemplateCompare').href).searchParams.get('from'), revision);
    assert.equal(new URL(app.node('localTemplateCompare').href).searchParams.get('to'), next);
    assert.equal(app.node('localTemplateFields').disabled, true);
});

test('double submit while busy and after success never sends another upload', async () => {
    let finish;
    let calls = 0;
    const app = boot(() => { calls++; return new Promise(resolve => { finish = resolve; }); });
    const pending = app.submit();
    assert.equal(app.node('localTemplateForm').attributes['aria-busy'], 'true');
    assert.equal(app.node('localTemplateFields').disabled, true);
    await app.submit();
    finish(response());
    await pending;
    await app.submit();
    assert.equal(calls, 1);
    assert.equal(app.node('localTemplateForm').attributes['aria-busy'], 'false');
});

test('a reload and repeated conflict preserve the original precondition and local file', async () => {
    const heads = [];
    for (let reload = 0; reload < 2; reload++) {
        const app = boot(async (_url, request) => {
            heads.push(request.headers.get('If-Match'));
            return response(412, { error: 'Server head is newer' });
        });
        const original = app.file.files[0];
        await app.submit();
        await app.submit();
        assert.equal(app.file.files[0], original);
        assert.equal(app.node('localTemplateMessage').textContent, 'conflict');
        assert.equal(app.node('localTemplateResult').hidden, true);
        assert.equal(app.node('localTemplateFields').disabled, false);
        assert.equal(app.node('localTemplateMessage').focused, true);
    }
    assert.deepEqual(heads, Array(4).fill('"' + revision + '"'));
});

for (const status of [401, 403, 409, 412, 400, 413, 422, 500]) {
    test(`HTTP ${status} retains the file and does not retry`, async () => {
        let calls = 0;
        const app = boot(async () => { calls++; return response(status, { error: 'Invalid placeholder' }); });
        const original = app.file.files[0];
        await app.submit();
        assert.equal(calls, 1);
        assert.equal(app.file.files[0], original);
        const expected = [401, 403].includes(status) ? 'authentication'
            : [409, 412].includes(status) ? 'conflict'
            : [400, 413, 422].includes(status) ? 'invalid Invalid placeholder' : 'uncertain';
        assert.equal(app.node('localTemplateMessage').textContent, expected);
        assert.equal(app.node('localTemplateResult').hidden, true);
    });
}

test('network loss after a possible commit is uncertain, not a failed-save claim', async () => {
    let calls = 0;
    const app = boot(async () => { calls++; throw new Error('connection lost'); });
    await app.submit();
    assert.equal(calls, 1);
    assert.equal(app.node('localTemplateMessage').textContent, 'uncertain');
    assert.equal(app.file.files.length, 1);
});

test('malformed or wrong-template success is not presented as a confirmed save', async () => {
    for (const payload of [null, {}, { templateId: 'other', headCommit: next },
        { templateId: id, headCommit: 'main' }]) {
        const app = boot(async () => response(201, payload));
        await app.submit();
        assert.equal(app.node('localTemplateMessage').textContent, 'uncertain');
        assert.equal(app.node('localTemplateResult').hidden, true);
    }
});

test('empty, non-DOTX and oversized files are rejected before upload', async () => {
    for (const file of [undefined, { name: 'report.docx', size: 100 },
        { name: 'report.dotx', size: 0 }, { name: 'report.dotx', size: 1001 }]) {
        let calls = 0;
        const app = boot(async () => { calls++; return response(); });
        app.file.files = file ? [file] : [];
        await app.submit();
        assert.equal(calls, 0);
        assert.ok(app.file.validationMessage);
        app.file.listeners.change();
        assert.equal(app.file.validationMessage, '');
    }
});

test('missing revision, mutable refs and cross-origin upload targets fail closed', () => {
    for (const config of [{ revision: '' }, { revision: 'main' }, { revision: '*' },
        { maxBytes: 'NaN' }, { uploadUrl: 'https://other.test/upload' }]) {
        const app = boot(() => { throw new Error('must not upload'); }, config);
        assert.equal(app.node('localTemplateFields').disabled, true);
        assert.equal(app.node('localTemplateMessage').textContent, 'failed');
    }
});

test('download and upload UI share the original server-bound revision and localized labels', () => {
    const html = resource('templates/document-template-local-edit.html');
    assert.match(html, /revision=\$\{template.headCommit\}/);
    assert.match(html, /data-revision=\$\{template.headCommit\}/);
    assert.doesNotMatch(html, /ms-word:|\/dav\//);
    const en = resource('i18n/messages_document_templates.properties');
    const de = resource('i18n/messages_document_templates_de.properties');
    for (const [, key] of html.matchAll(/#\{([^}]+)\}/g)) {
        assert.ok(en.includes(key + '='), 'English label: ' + key);
        assert.ok(de.includes(key + '='), 'German label: ' + key);
    }
    const list = resource('static/js/document-templates/document-templates.js');
    assert.match(list, /localUrl.searchParams.set\('revision', template.headCommit\)/);
    assert.doesNotMatch(list, /headers\['If-Match'\]/);
    assert.match(list, /templatesById.has\(templateId\)/);
});
