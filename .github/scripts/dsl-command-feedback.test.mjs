import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const sourceRoot = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const transportSource = readFileSync(new URL('api/taxonomy-api-client.js', sourceRoot), 'utf8');
const editorSource = readFileSync(new URL('shared/taxonomy-dsl-editor.js', sourceRoot), 'utf8');
const i18nSource = readFileSync(new URL('taxonomy-i18n.js', sourceRoot), 'utf8');
const originalText = 'element BP {\n  taxonomy: BP;\n}\n';
const originalMessage = 'Keep the reviewed requirement changes';
const history = { commits: [{ documentId: 22 }, { documentId: 21 }] };
const escapeHtml = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;');

function response(body, status = 200, type = 'application/json') {
    return new Response(typeof body === 'string' ? body : JSON.stringify(body), {
        status, headers: { 'Content-Type': type, 'X-Request-ID': 'dsl-response' }
    });
}

function materializationMessages(locale) {
    // I18nApiController serves these message-bundle values to the browser.
    const filename = locale === 'de' ? 'messages_de.properties' : 'messages.properties';
    const lines = readFileSync(new URL('../../i18n/' + filename, sourceRoot), 'utf8').split(/\r?\n/);
    return Object.fromEntries(['dsl.materialized', 'dsl.incremental.done'].map(key => {
        const line = lines.find(value => value.startsWith(key + '='));
        assert.ok(line, `${filename} must translate ${key}`);
        return [key, line.slice(key.length + 1).replace(/\\u([\da-f]{4})/gi,
            (_, hex) => String.fromCharCode(parseInt(hex, 16)))];
    }));
}

function element(value = '') {
    const node = { value, className: '', textContent: '', innerHTML: '', children: [],
        addEventListener() {}, querySelectorAll: () => [],
        appendChild(child) { this.children.push(child); },
        insertBefore(child) { this.children.unshift(child); } };
    node.classList = {
        add(name) { if (!this.contains(name)) node.className += ' ' + name; },
        remove(name) { node.className = node.className.split(/\s+/).filter(value => value !== name).join(' '); },
        contains(name) { return node.className.split(/\s+/).includes(name); }
    };
    return node;
}

// Execute the production editor and canonical transport. Only the DOM and HTTP
// boundary are fixtures; the hook exposes existing handlers without changing them.
function harness(reply, locale) {
    const calls = [], statuses = [], events = [], operationResults = [], ready = [], timers = new Map();
    const translations = locale ? materializationMessages(locale) : null;
    let timerSequence = 0;
    const nodes = new Map(['dslEditorContainer', 'dslParseBtn', 'dslValidateBtn', 'dslFormatBtn',
        'dslCommitBtn', 'dslMaterializeBtn', 'dslMaterializeIncrBtn', 'dslLoadCurrentBtn',
        'dslBranchSelect', 'dslNewBranchBtn', 'dslMergeBtn', 'dslAuthorInput', 'dslMessageInput',
        'dslValidationOutput', 'dslHistoryBody', 'dslDiffOutput', 'dslStatusArea']
        .map(id => [id, element()]));
    nodes.get('dslBranchSelect').value = 'draft';
    nodes.get('dslMessageInput').value = originalMessage;
    const status = nodes.get('dslStatusArea');
    let statusText = '';
    Object.defineProperty(status, 'textContent', {
        get: () => statusText,
        set(value) { statusText = value; statuses.push({ text: value, className: status.className }); }
    });
    const view = { state: { doc: { length: originalText.length, toString: () => originalText } },
        dispatch() { throw new Error('A version command must not replace the editor draft'); } };
    const document = {
        documentElement: { lang: locale || 'en' },
        getElementById: id => nodes.get(id) || null,
        querySelector(selector) {
            if (selector === 'meta[name="_csrf"]') return { content: 'fixture-csrf' };
            if (selector === 'meta[name="_csrf_header"]') return { content: 'X-CSRF-TOKEN' };
            return null;
        },
        createElement: () => element(),
        addEventListener(name, callback) { if (name === 'DOMContentLoaded') ready.push(callback); },
        dispatchEvent(event) { events.push(event); }
    };
    const context = vm.createContext({
        console, document, URL, Request, Response, Headers, AbortController,
        CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
        location: new URL('https://taxonomy.example.test/taxonomy/'),
        TaxonomyI18n: { t: (key, ...args) => [key, ...args.map(String)].join(': ') },
        TaxonomyUtils: { escapeHtml }, TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
        TaxonomyOperationResult: {
            showError(...args) { operationResults.push({ kind: 'error', args }); },
            showSuccess(...args) { operationResults.push({ kind: 'success', args }); }
        },
        addEventListener() {}, prompt: () => 'review',
        setTimeout(callback, delay) { const id = ++timerSequence; timers.set(id, { callback, delay }); return id; },
        clearTimeout(id) { timers.delete(id); },
        fetch(input, init) {
            if (translations && input === '/api/i18n/' + locale) return Promise.resolve(response(translations));
            calls.push({ input, init });
            return Promise.resolve().then(() => reply(input, init, calls.length));
        }
    });
    context.window = context;
    if (locale) vm.runInContext(i18nSource, context, { filename: 'taxonomy-i18n.js' });
    vm.runInContext(transportSource, context, { filename: 'taxonomy-api-client.js' });
    const hook = 'window.__commands = { commit: commitDsl, materialize: materializeDsl, '
        + 'incremental: materializeIncremental, branch: createBranch, merge: mergeBranch, '
        + 'cherryPick: function () { return cherryPickCommit("fixture-commit"); } };\n}());';
    assert.ok(editorSource.endsWith('}());\n'), 'editor initialization hook must remain explicit');
    vm.runInContext(editorSource.replace(/\}\(\)\);\s*$/, hook), context,
        { filename: 'taxonomy-dsl-editor.js' });
    ready.forEach(callback => callback());
    // The initial cm-ready lifecycle is outside this command feedback fixture.
    context.dslCmView = view;
    return { context, calls, statuses, events, operationResults, nodes, status, view,
        async run(command) {
            if (locale) await context.TaxonomyI18n.ready();
            context.__commands[command]();
            for (let attempt = 0; attempt < 100 && !/alert-(danger|success)/.test(status.className); attempt++) {
                await new Promise(resolve => setImmediate(resolve));
            }
            assert.match(status.className, /alert-(danger|success)/, 'the command must settle visibly');
        },
        fireStatusTimers() {
            for (const [id, timer] of timers) {
                if (timer.delay === 6000) { timers.delete(id); timer.callback(); }
            }
        }
    };
}

function commandReply(command, result) {
    return (input, init = {}) => {
        if (input.startsWith('/api/dsl/history')) return response(history);
        if (input === '/api/dsl/branches' && (init.method || 'GET') === 'GET') {
            return response([{ name: 'draft' }, { name: 'review' }]);
        }
        return typeof result === 'function' ? result(input, init) : result;
    };
}

function assertFailure(h, status) {
    assert.equal(h.nodes.get('dslMessageInput').value, originalMessage, 'a failed command must retain the commit message');
    assert.equal(h.view.state.doc.toString(), originalText, 'a failed command must retain the DSL draft');
    assert.equal(h.nodes.get('dslBranchSelect').value, 'draft', 'failure must not switch the selected branch');
    assert.match(h.status.className, /alert-danger/);
    assert.match(h.status.textContent, new RegExp('HTTP ' + status));
    assert.equal(h.statuses.some(item => item.className.includes('alert-success')), false,
        'a failed HTTP response must never announce success');
    assert.equal(h.operationResults.some(item => item.kind === 'success'), false);
}

for (const command of ['commit', 'materialize', 'incremental', 'branch', 'merge', 'cherryPick']) {
    for (const status of [403, 409, 500]) {
        test(`${command}: HTTP ${status} problem details are errors and retain the draft`, async () => {
            const h = harness(commandReply(command, response({ title: 'Command rejected',
                detail: 'The requested change could not be applied.' }, status, 'application/problem+json')));
            await h.run(command);
            assertFailure(h, status);
            assert.match(h.status.textContent, /The requested change could not be applied/);
            assert.equal(h.calls.length, command === 'incremental' ? 2 : 1,
                'failed commands are not retried and do not refresh successful-operation state');
        });
    }
}

for (const command of ['commit', 'materialize', 'incremental']) {
    test(`${command}: HTTP validation failures keep detailed diagnostics and warnings`, async () => {
        const h = harness(commandReply(command, response({ valid: false,
            errors: ['line 2: missing <relationship>'], warnings: ['Review the source requirement.'] }, 400)));
        await h.run(command);
        assert.equal(h.nodes.get('dslMessageInput').value, originalMessage);
        assert.match(h.status.className, /alert-danger/);
        assert.match(h.nodes.get('dslValidationOutput').innerHTML, /line 2: missing &lt;relationship&gt;/);
        assert.match(h.nodes.get('dslValidationOutput').innerHTML, /Review the source requirement/);
        assert.equal(h.statuses.some(item => item.className.includes('alert-success')), false);
    });
}

test('incremental materialization rejects valid:false even when returned with HTTP 200', async () => {
    const h = harness(commandReply('incremental', response({ valid: false, relationsCreated: 0,
        warnings: ['The previous materialization could not be compared.'] })));
    await h.run('incremental');
    assert.match(h.status.className, /alert-danger/);
    assert.match(h.nodes.get('dslValidationOutput').innerHTML, /previous materialization/);
    assert.equal(h.statuses.some(item => item.className.includes('alert-success')), false);
});

test('failed incremental history is reported as an HTTP error without issuing a materialization', async () => {
    const h = harness(() => response({ errorCode: 'HISTORY_LOAD_FAILED', commits: [] }, 503));
    await h.run('incremental');
    assertFailure(h, 503);
    assert.equal(h.calls.length, 1);
});

test('an HTML server error retains HTTP status and never consumes the commit message', async () => {
    const h = harness(commandReply('commit', response('<html>Service unavailable</html>', 500, 'text/html')));
    await h.run('commit');
    assertFailure(h, 500);
});

test('the earlier progress timer cannot hide the resulting error', async () => {
    const h = harness(commandReply('commit', response({ valid: false, errors: ['Review line 2.'] }, 400)));
    await h.run('commit');
    h.fireStatusTimers();
    assert.match(h.status.className, /alert-danger/);
    assert.equal(h.status.classList.contains('d-none'), false, 'the error stays available until replaced by another status');
});

const successes = {
    commit: { valid: true, commitId: 'saved-commit' },
    materialize: { valid: true, relationsCreated: 2, hypothesesCreated: 1, documentId: 22 },
    incremental: { valid: true, relationsCreated: 1, hypothesesCreated: 0, documentId: 22 },
    branch: { branch: 'review', forkedFrom: 'draft', commitId: 'saved-commit' },
    merge: { fromBranch: 'draft', intoBranch: 'review', commitId: 'saved-commit' },
    cherryPick: { targetBranch: 'review', commitId: 'saved-commit' }
};
for (const [command, body] of Object.entries(successes)) {
    test(`${command}: acknowledged success uses canonical request policy without changing the draft`, async () => {
        const h = harness(commandReply(command, response(body)));
        await h.run(command);
        assert.match(h.status.className, /alert-success/);
        assert.equal(h.view.state.doc.toString(), originalText);
        assert.equal(h.nodes.get('dslMessageInput').value, command === 'commit' ? '' : originalMessage);
        const writes = h.calls.filter(call => call.init?.method === 'POST');
        assert.equal(writes.length, 1);
        assert.equal(writes[0].init.credentials, 'same-origin');
        assert.equal(writes[0].init.headers.get('X-CSRF-TOKEN'), 'fixture-csrf');
        assert.ok(writes[0].init.headers.get('X-Request-ID'));
    });
}

const materializationCopy = {
    en: {
        materialize: (relations, proposals) => `\u2705 Materialized: ${relations} relations, ${proposals} relation proposals`,
        incremental: (relations, proposals) => `\u2705 Incrementally materialized: ${relations} relations, ${proposals} relation proposals`
    },
    de: {
        materialize: (relations, proposals) => `\u2705 Materialisiert: ${relations} Beziehungen, ${proposals} Beziehungsvorschläge`,
        incremental: (relations, proposals) => `\u2705 Inkrementell materialisiert: ${relations} Beziehungen, ${proposals} Beziehungsvorschläge`
    }
};
for (const locale of ['en', 'de']) {
    for (const command of ['materialize', 'incremental']) {
        for (const [relationsCreated, hypothesesCreated] of [[3, 2], [0, 0], [0, 2], [3, 0]]) {
            test(`${command}: ${locale} reports backend counts ${relationsCreated}/${hypothesesCreated} in the correct categories`, async () => {
                // DslDocumentApiController returns hypothesesCreated, never elementsCreated.
                // The full endpoint also returns errors; incremental returns warnings only.
                const body = { valid: true, warnings: [], relationsCreated, hypothesesCreated, documentId: 22 };
                if (command === 'materialize') body.errors = [];
                const h = harness(commandReply(command, response(body)), locale);
                await h.run(command);
                assert.match(h.status.className, /alert-success/);
                assert.equal(h.status.textContent,
                    materializationCopy[locale][command](relationsCreated, hypothesesCreated));
                assert.doesNotMatch(h.status.textContent, /undefined|NaN|elements|Elemente|\{\d+\}/);
                assert.equal(h.view.state.doc.toString(), originalText);
                assert.equal(h.nodes.get('dslMessageInput').value, originalMessage);
            });
        }
    }
}

test('a successful older commit does not erase a message edited while it was pending', async () => {
    let finish;
    const pending = new Promise(resolve => { finish = resolve; });
    const h = harness(commandReply('commit', () => pending));
    const completed = h.run('commit');
    h.nodes.get('dslMessageInput').value = 'Message for the next edit';
    finish(response(successes.commit));
    await completed;
    assert.match(h.status.className, /alert-success/);
    assert.equal(h.nodes.get('dslMessageInput').value, 'Message for the next edit');
});
