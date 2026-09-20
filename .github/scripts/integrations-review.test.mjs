import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/integrations.js', import.meta.url), 'utf8');

class Element {
    constructor(tag = 'div') {
        this.tag = tag; this.children = []; this.listeners = {}; this.dataset = {};
        this.value = ''; this.textContent = ''; this.files = [];
    }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    get options() { return this.children.filter(node => node.tag === 'option'); }
    get selectedOptions() { return this.options.filter(node => node.value === this.value); }
    setAttribute(name, value) { this[name] = value; }
    addEventListener(type, handler) { this.listeners[type] = handler; }
    focus() {}
}

async function page(profileId, { profilesMissing = false, publication = false } = {}) {
    const elements = new Map();
    const el = id => {
        if (!elements.has(id)) elements.set(id, new Element());
        return elements.get(id);
    };
    const authority = el('connectionAuthority');
    for (const mode of ['LINK_ONLY', 'IMPORT_COPY', 'MIRROR_READ', 'PUBLISH_TARGET', 'BIDIRECTIONAL']) {
        const option = new Element('option'); option.value = mode; authority.append(option);
    }
    authority.value = 'IMPORT_COPY';
    const operation = {
        id: 'op', status: 'PREVIEWED', direction: publication ? 'PUSH' : 'INBOUND', context: { profile: profileId, authority: 'IMPORT_COPY' },
        document: { losses: [], metadata: {} },
        changes: [{ id: 'change', externalId: 'RELATION:r', kind: 'ADD', conflicts: [],
            after: { kind: 'RELATION', title: 'Dependency', attributes: {}, extensions: {} } }]
    };
    const overview = { connection: { connectorId: profileId, authority: 'IMPORT_COPY' }, current: {}, history: [], oslcCatalogPath: '/oslc' };
    const uploads = [], writes = [];
    const publicationOperation = { operationId: 'op', mode: 'PUSH', phase: 'PREVIEWED', status: 'PREVIEWED', allowedActions: ['REVIEW', 'PUBLISH', 'CANCEL'], scope: { rootResource: 'urn:model', selectorFingerprint: 'all' }, expectedExternalRevision: 'scope-1', items: [], acknowledgedCount: 0, unknownCount: 0, remainingCount: 0, preview: { fingerprint: 'frozen', changes: [{ id: 'change', externalId: 'RELATION:r', local: operation.changes[0].after, remote: null, merged: operation.changes[0].after, localFields: ['title'], remoteFields: [], conflicts: [], dependencies: [] }], losses: [] } };
    overview.publicationAvailability = { available: publication, modes: publication ? ['PUSH', 'SYNCHRONIZE'] : [], reasonCode: publication ? null : 'PUBLICATION_GUARANTEES_UNVERIFIED' };
    const api = {
        async read(path) {
            if (path === '/profiles') return profilesMissing ? [] : [{ id: profileId, title: profileId,
                capabilities: ['FILE_IMPORT'], mediaTypes: ['application/xml'] }];
            if (path === '') return [{ id: 'conn', displayName: 'Test', connectorId: profileId }];
            if (path === '/conn' || path.startsWith('/conn?')) return overview;
            if (path === '/conn/operations/op') return operation;
            if (path === '/conn/operations/op/publication') return publicationOperation;
            if (path === '/conn/operations/op/endpoint-options') return { external: {} };
            if (path === '/conn/operations/op/events') return [];
            throw new Error('Unexpected read: ' + path);
        },
        async write(path, request) { writes.push({ path, request }); return publicationOperation; },
        async upload(path, request) { uploads.push({ path, request }); return operation; }
    };
    vm.runInNewContext(source, {
        document: { body: { dataset: { mayWrite: 'true' } }, getElementById: el,
            querySelectorAll: () => [], createElement: tag => new Element(tag) },
        window: { IntegrationApi: api, TaxonomyI18n: { ready: async () => {}, t: key => key, resolveUrl: value => value } },
        location: { href: 'https://taxonomy.test/integrations?connection=conn&operation=op', search: '?connection=conn&operation=op' },
        history: { replaceState() {} }, crypto: { randomUUID: () => 'request' }, URL, URLSearchParams
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(el('integrationError').hidden, true, el('integrationError').textContent);
    return { el, uploads, writes };
}

function descendants(node) { return node.children.flatMap(child => [child, ...descendants(child)]); }

test('Sparx reviewers can select only serializable relation mappings', async () => {
    for (const profile of ['sparx-xmi-2.1', 'sparx-oslc-am-2.0']) {
        const { el } = await page(profile);
        const remap = descendants(el('integrationChanges')).find(node => node.tag === 'select'
            && node.children.some(option => option.value === 'REALIZES'));
        assert.deepEqual(remap.children.map(option => option.value),
            ['', 'REALIZES', 'COMMUNICATES_WITH', 'CONTAINS', 'RELATED_TO', 'CONSUMES', 'DEPENDS_ON']);
    }
    const { el } = await page('archimate-3.2');
    const choices = descendants(el('integrationChanges')).filter(node => node.tag === 'option').map(node => node.value);
    assert.ok(choices.includes('SUPPORTS'));
    assert.ok(choices.includes('ASSIGNED_TO'));
});

test('a missing connection profile produces an actionable import error', async () => {
    const { el, uploads } = await page('unavailable-profile', { profilesMissing: true });
    el('integrationFile').files = [{ name: 'model.xml' }];
    el('integrationImport').listeners.submit({ preventDefault() {} });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(el('integrationError').textContent, 'integration.profileUnavailable');
    assert.equal(el('integrationError').hidden, false);
    assert.equal(uploads.length, 0);
});

 test('publication reload renders directed choices and publishes through its own route', async () => {
    const { el, writes } = await page('taxonomy-publication-contract-v1', { publication: true });
    const select = descendants(el('integrationChanges')).find(node => node.tag === 'select' && node.children.some(option => option.value === 'KEEP_LOCAL'));
    assert.ok(select, 'Directed publication decisions must be restored after reload');
    select.value = 'KEEP_LOCAL'; select.listeners.change();
    el('integrationRationale').value = 'Reviewed outbound addition';
    el('integrationApply').listeners.click();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(writes[0].path, '/conn/publish');
    assert.equal(writes[0].request.resolutions.change, 'KEEP_LOCAL');
    assert.deepEqual(Object.keys(writes[0].request.review.decisions), []);
});
