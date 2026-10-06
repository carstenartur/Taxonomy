import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const resources = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const source = await readFile(new URL('static/js/integrations.js', resources), 'utf8');
const template = await readFile(new URL('templates/integrations.html', resources), 'utf8');
const main = template.match(/<main\b[^>]*>([\s\S]*?)<\/main>/)[1];
const flush = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
class Element {
    constructor(tag = 'div', attributes = '') {
        this.tag = tag; this.children = []; this.listeners = {}; this.dataset = {};
        this.value = ''; this.textContent = ''; this.files = [];
        this.disabled = /\sdisabled(?:\s|=|\/?>|$)/.test(attributes);
        this.hidden = /\shidden(?:\s|=|\/?>|$)/.test(attributes);
    }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; this.textContent = ''; }
    get options() { return this.children.filter(node => node.tag === 'option'); }
    get selectedOptions() { return this.options.filter(node => node.value === this.value); }
    setAttribute(name, value) { this[name] = value; }
    addEventListener(type, listener) { this.listeners[type] = listener; }
    focus() {}
}

function harness({ mayWrite = true, failProfiles = false, empty = false } = {}) {
    const elements = new Map();
    for (const match of main.matchAll(/<([a-z][a-z0-9]*)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
        elements.set(match[3], new Element(match[1], match[2]));
    }
    const el = id => {
        assert.ok(elements.has(id), 'production template contains ' + id);
        return elements.get(id);
    };
    const authority = el('connectionAuthority');
    for (const value of ['IMPORT_COPY', 'MIRROR_READ', 'BIDIRECTIONAL', 'PUBLISH_TARGET', 'LINK_ONLY']) {
        const option = new Element('option'); option.value = value; authority.append(option);
    }
    authority.value = 'IMPORT_COPY';
    const translations = deferred(), details = deferred(), reads = [], writes = [];
    const summary = {
        operationId: 'op', mode: 'PUSH', status: 'RECOVERY_REQUIRED', phase: 'RECOVERY_REQUIRED',
        allowedActions: ['RETRY'], items: [{ resourceId: 'item-1', mutation: 'CREATE', state: 'ACKNOWLEDGED' }],
        acknowledgedCount: 1, unknownCount: 1, remainingCount: 2,
        preview: { changes: [], losses: [] }, scope: { rootResource: 'model', selectorFingerprint: 'all' }
    };
    const api = {
        async read(path) {
            reads.push(path);
            if (path === '/profiles') {
                if (failProfiles) throw new Error('Profiles unavailable');
                return [{ id: 'test', version: '1', capabilities: [], title: 'TEST ONLY' }];
            }
            if (path === '') return empty ? [] : [{ id: 'conn', connectorId: 'test', profileVersion: '1', displayName: 'TEST ONLY' }];
            if (path === '/conn') return {
                connection: { connectorId: 'test', profileVersion: '1', displayName: 'TEST ONLY' },
                history: [], current: {}, oslcCatalogPath: '/oslc', publicationAvailability: { available: false, modes: [] }
            };
            if (path === '/conn/operations/op') return { id: 'op', direction: 'PUSH', status: 'RECOVERY_REQUIRED' };
            if (path === '/conn/operations/op/events') return [];
            if (path === '/conn/operations/op/publication') return details.promise;
            throw new Error('Unexpected read ' + path);
        },
        async write(path, body) { writes.push({ path, body }); return summary; }
    };
    const document = {
        body: { dataset: { mayWrite: String(mayWrite) } }, getElementById: el,
        createElement: tag => new Element(tag),
        querySelectorAll(selector) {
            if (selector === 'button, #integrationConnection') {
                return [...elements.values()].filter(node => node.tag === 'button').concat(el('integrationConnection'));
            }
            if (selector.startsWith('#integrationCreate input')) {
                return ['connectionName', 'connectionProfile', 'connectionAuthority', 'connectionProject',
                    'externalSystem', 'externalRepository', 'externalConfiguration', 'connectionRemote',
                    'integrationFile', 'integrationComplete', 'integrationRemoteUri', 'integrationRemoteVersion'].map(el);
            }
            return [];
        }
    };
    vm.runInNewContext(source, {
        document, window: { IntegrationApi: api, TaxonomyI18n: {
            ready: () => translations.promise, t: key => key, resolveUrl: value => value
        } }, URL, URLSearchParams, location: {
            href: 'https://example.test/integrations?connection=conn&operation=op',
            search: empty ? '' : '?connection=conn&operation=op'
        }, history: { replaceState() {} }, crypto: { randomUUID: () => 'request' }
    }, { filename: 'integrations.js' });
    return { el, translations, details, reads, writes, summary };
}

const guarded = ['integrationApply', 'integrationCancel', 'integrationRetry', 'integrationReconcile',
    'integrationAccept', 'integrationReject', 'integrationPush', 'integrationSynchronize', 'integrationExport'];
function locked(ui) {
    for (const id of guarded) assert.equal(ui.el(id).disabled, true, id + ' must not advertise an action without loaded context');
}

test('server HTML keeps all action buttons disabled before any JavaScript is downloaded', () => {
    const buttons = [...main.matchAll(/<button\b([^>]*)>/g)];
    assert.ok(buttons.length >= guarded.length);
    for (const [, attributes] of buttons) assert.match(attributes, /\sdisabled(?:\s|=|$)/, attributes);
    assert.match(main, /<select\b[^>]*id="integrationConnection"[^>]*\sdisabled(?:\s|>|=)/);
});

test('delayed translations and premature events cannot unlock controls or bypass startup', async () => {
    const ui = harness();
    locked(ui);
    ui.el('integrationPublicationRevision').listeners.input();
    ui.el('connectionProfile').listeners.change();
    locked(ui);
    ui.el('integrationRetry').listeners.click();
    ui.el('integrationCreate').listeners.submit({ preventDefault() {} });
    ui.el('integrationRefresh').listeners.click();
    await flush();
    assert.deepEqual(ui.reads, []);
    assert.deepEqual(ui.writes, []);
    assert.equal(ui.el('integrationError').hidden, true);
    ui.translations.resolve(); await flush();
    assert.ok(ui.reads.includes('/conn/operations/op/publication'));
    locked(ui);
    assert.equal(ui.el('integrationPublicationOutcomes').hidden, true);
    assert.equal(ui.el('integrationPublicationCounts').textContent, '');
    ui.details.resolve(ui.summary); await flush();
    assert.equal(ui.el('integrationRetry').disabled, false);
    assert.equal(ui.el('integrationPublicationOutcomes').hidden, false);
    assert.match(ui.el('integrationPublicationCounts').textContent, /integration\.acknowledged: 1/);
    assert.match(ui.el('integrationPublicationCounts').textContent, /integration\.unknown: 1/);
    assert.match(ui.el('integrationOperation').textContent, /^op ·/);
    assert.equal(ui.el('integrationApply').disabled, true);
    assert.equal(ui.el('integrationReconcile').disabled, true);
    ui.el('integrationRetry').listeners.click(); await flush();
    assert.equal(ui.writes.length, 1);
    assert.equal(ui.writes[0].path, '/conn/operations/op/retry');
});

test('a translation failure leaves startup actions locked and reports the real failure', async () => {
    const ui = harness();
    ui.translations.reject(new Error('Translations unavailable')); await flush();
    locked(ui);
    ui.el('integrationPublicationRevision').listeners.input();
    locked(ui);
    ui.el('integrationCreate').listeners.submit({ preventDefault() {} });
    await flush();
    assert.deepEqual(ui.reads, []); assert.deepEqual(ui.writes, []);
    assert.equal(ui.el('integrationError').hidden, false);
    assert.equal(ui.el('integrationError').textContent, 'Translations unavailable');
});

test('failed initial profiles do not expose recovery actions for an absent operation', async () => {
    const ui = harness({ failProfiles: true });
    ui.translations.resolve(); await flush();
    locked(ui);
    assert.deepEqual(ui.reads, ['/profiles']);
    assert.equal(ui.el('integrationError').textContent, 'Profiles unavailable');
});

test('read-only observer sees loaded publication counts but cannot resume or publish', async () => {
    const ui = harness({ mayWrite: false });
    ui.translations.resolve(); ui.details.resolve(ui.summary); await flush();
    locked(ui);
    assert.equal(ui.el('integrationPublicationOutcomes').hidden, false);
    assert.match(ui.el('integrationPublicationCounts').textContent, /integration\.unknown: 1/);
    assert.deepEqual(ui.writes, []);
});

test('successful empty connection inventory enables connection creation without inventing an operation', async () => {
    const ui = harness({ empty: true });
    ui.translations.resolve(); await flush();
    locked(ui);
    assert.equal(ui.el('connectionName').disabled, false);
    assert.equal(ui.el('integrationConnection').disabled, false);
    assert.equal(ui.el('integrationRefresh').disabled, false);
    assert.equal(ui.el('integrationError').hidden, true);
});
