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

async function page(profileId, { profilesMissing = false, publication = false, availabilityMissing = false, failPreview = false, predecessor = null } = {}) {
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
    const overview = { connection: { connectorId: profileId, authority: 'IMPORT_COPY' }, current: {}, history: [{ id: 'op', status: 'PREVIEWED', createdAt: 'first' }, { id: 'other', status: 'PREVIEWED', createdAt: 'second' }], oslcCatalogPath: '/oslc' };
    const uploads = [], writes = [];
    const publicationOperation = { operationId: 'op', predecessorOperationId: predecessor, mode: 'PUSH', phase: 'PREVIEWED', status: 'PREVIEWED', allowedActions: ['REVIEW', 'PUBLISH', 'CANCEL'], scope: { rootResource: 'urn:model', selectorFingerprint: 'all' }, expectedExternalRevision: 'scope-1', items: [], acknowledgedCount: 0, unknownCount: 0, remainingCount: 0, preview: { fingerprint: 'frozen', changes: [{ id: 'change', externalId: 'RELATION:r', local: operation.changes[0].after, remote: null, merged: operation.changes[0].after, localFields: ['title'], remoteFields: [], conflicts: [], dependencies: [] }], losses: [] } };
    overview.publicationAvailability = { available: publication, modes: publication ? ['PUSH', 'SYNCHRONIZE'] : [], reasonCode: publication ? null : 'PUBLICATION_GUARANTEES_UNVERIFIED' };
    if (availabilityMissing) delete overview.publicationAvailability;
    const api = {
        async read(path) {
            if (path === '/profiles') return profilesMissing ? [] : [{ id: profileId, title: profileId,
                capabilities: ['FILE_IMPORT'], mediaTypes: ['application/xml'] }];
            if (path === '') return [{ id: 'conn', displayName: 'Test', connectorId: profileId }];
            if (path === '/conn' || path.startsWith('/conn?')) return overview;
            const match = path.match(/^\/conn\/operations\/(op|other)(.*)$/);
            if (match) {
                const id = match[1];
                if (!match[2]) return structuredClone({ ...operation, id });
                if (match[2] === '/publication') return structuredClone({ ...publicationOperation, operationId: id,
                    requestFingerprint: 'request-' + id, preview: { ...publicationOperation.preview, fingerprint: 'preview-' + id } });
                if (match[2] === '/endpoint-options') return { external: {} };
                if (match[2] === '/events') return [];
            }
            throw new Error('Unexpected read: ' + path);
        },
        async write(path, request) { writes.push({ path, request }); if (failPreview && path.endsWith("/publication-previews")) throw new Error("Preview transport unavailable"); return publicationOperation; },
        async upload(path, request) { uploads.push({ path, request }); return operation; }
    };
    vm.runInNewContext(source, {
        document: { body: { dataset: { mayWrite: 'true' } }, getElementById: el,
            querySelectorAll: selector => selector === '#integrationChanges select' ? descendants(el('integrationChanges')).filter(node => node.tag === 'select') : [], createElement: tag => new Element(tag) },
        window: { IntegrationApi: api, TaxonomyI18n: { ready: async () => {}, t: key => key, resolveUrl: value => value } },
        location: { href: 'https://taxonomy.test/integrations?connection=conn&operation=op', search: '?connection=conn&operation=op' },
        history: { replaceState() {} }, crypto: { randomUUID: () => 'request' }, URL, URLSearchParams
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(el('integrationError').hidden, true, el('integrationError').textContent);
    return { el, uploads, writes, api, publicationOperation };
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


test('missing availability never displays verified publication', async () => {
    const { el } = await page('taxonomy-publication-contract-v1', { availabilityMissing: true });
    assert.equal(el('integrationPush').disabled, true);
    assert.equal(el('integrationPublicationReason').textContent, 'integration.reason.PUBLICATION_GUARANTEES_UNVERIFIED');
});

test('a different preview direction cannot reuse the failed request mode', async () => {
    const { el, writes } = await page('taxonomy-publication-contract-v1', { publication: true, failPreview: true });
    el('integrationPush').listeners.click(); await new Promise(resolve => setImmediate(resolve));
    el('integrationSynchronize').listeners.click(); await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(writes.map(value => value.request.mode), ['PUSH', 'SYNCHRONIZE']);
});


test('reconciliation reload links to the durable predecessor', async () => {
    const { el } = await page('taxonomy-publication-contract-v1', { publication: true, predecessor: 'prior-operation' });
    assert.equal(el('integrationPredecessor').hidden, false);
    assert.ok(el('integrationPredecessor').textContent.includes('prior-operation'));
    assert.equal(new URL(el('integrationPredecessor').href).searchParams.get('operation'), 'prior-operation');
});

test('changing connection clears publication and predecessor evidence', async () => {
    const { el } = await page('taxonomy-publication-contract-v1', { publication: true, predecessor: 'prior-operation' });
    el('integrationConnection').value = ''; el('integrationConnection').listeners.change();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(el('integrationPublicationOutcomes').hidden, true);
    assert.equal(el('integrationPredecessor').hidden, true);
});

const settle = () => new Promise(resolve => setImmediate(resolve));
function choosePublication(el, choice = 'KEEP_LOCAL', rationale = 'Original rationale') {
    const select = descendants(el('integrationChanges')).find(node => node.tag === 'select' && node.options.some(option => option.value === 'KEEP_LOCAL'));
    select.value = choice; select.listeners.change();
    el('integrationRationale').value = rationale;
    el('integrationRationale').listeners.input?.();
}
async function rejectedPublication() {
    const state = await page('taxonomy-publication-contract-v1', { publication: true });
    state.api.write = async (path, request) => { state.writes.push({ path, request }); throw new Error('Review rejected before acceptance'); };
    choosePublication(state.el, 'MERGE');
    state.el('integrationApply').listeners.click(); await settle();
    return state;
}

test('confirmed unaccepted publication submits visible bulk correction, not the rejected review', async () => {
    const { el, writes, publicationOperation } = await rejectedPublication();
    el('integrationReject').listeners.click();
    el('integrationApply').listeners.click(); await settle();
    assert.equal(writes[1].request.resolutions.change, 'SKIP');
    // The other bulk action must also compose the actual visible decision.
    publicationOperation.preview.changes[0].remote = publicationOperation.preview.changes[0].local;
    el('integrationRefresh').listeners.click(); await settle();
    el('integrationAccept').listeners.click();
    el('integrationRationale').value = 'Independent merge'; el('integrationRationale').listeners.input?.();
    el('integrationApply').listeners.click(); await settle();
    assert.equal(writes[2].request.resolutions.change, 'MERGE');
});

test('confirmed unaccepted publication submits corrected rationale', async () => {
    const { el, writes } = await rejectedPublication();
    el('integrationRationale').value = 'Corrected explicit rationale'; el('integrationRationale').listeners.input?.();
    el('integrationApply').listeners.click(); await settle();
    assert.equal(writes[1].request.review.rationale, 'Corrected explicit rationale');
});

test('a different displayed preview never submits the previous operation review', async () => {
    const { el, writes } = await rejectedPublication();
    el('integrationHistory').children[1].children[0].listeners.click(); await settle();
    el('integrationReject').listeners.click();
    el('integrationRationale').value = 'Other operation rationale'; el('integrationRationale').listeners.input?.();
    el('integrationApply').listeners.click(); await settle();
    assert.equal(writes[1].request.review.operationId, 'other');
    assert.equal(writes[1].request.review.previewFingerprint, 'preview-other');
    assert.equal(writes[1].request.resolutions.change, 'SKIP');
});

test('lost POST response and failed status read freeze visible review until authoritative acceptance, then retry by ID', async () => {
    const { el, writes, api, publicationOperation } = await page('taxonomy-publication-contract-v1', { publication: true });
    const read = api.read; let unavailable = false;
    api.read = async path => { if (unavailable && path.endsWith('/publication')) throw new Error('Status unavailable'); return read(path); };
    api.write = async (path, request) => { writes.push({ path, request }); unavailable = true; throw new Error('Response lost; acceptance unknown'); };
    choosePublication(el); el('integrationApply').listeners.click(); await settle();
    const frozen = JSON.stringify(writes[0].request);
    for (const id of ['integrationApply', 'integrationAccept', 'integrationReject', 'integrationRationale']) assert.equal(el(id).disabled, true, id);
    assert.ok(descendants(el('integrationChanges')).filter(node => node.tag === 'select').every(node => node.disabled));
    assert.equal(el('integrationApply').textContent, 'integration.publicationAwaitingStatus');
    // Even a directly dispatched stale handler cannot publish a changed review.
    el('integrationReject').listeners.click(); el('integrationApply').listeners.click(); await settle();
    assert.equal(writes.length, 1); assert.equal(JSON.stringify(writes[0].request), frozen);
    unavailable = false;
    Object.assign(publicationOperation, { phase: 'PARTIAL', status: 'PARTIAL', allowedActions: ['RETRY'], review: writes[0].request });
    el('integrationRefresh').listeners.click(); await settle();
    assert.equal(el('integrationRationale').value, 'Original rationale');
    assert.equal(el('integrationApply').disabled, true); assert.equal(el('integrationRetry').disabled, false);
    api.write = async (path, request) => { writes.push({ path, request }); return publicationOperation; };
    el('integrationRetry').listeners.click(); await settle();
    assert.equal(writes[1].path, '/conn/operations/op/retry'); assert.deepEqual(Object.keys(writes[1].request), []);
});

test('an ambiguous operation remains bound while visiting another preview and unlocks only on successful own status read', async () => {
    const { el, writes, api } = await page('taxonomy-publication-contract-v1', { publication: true });
    const read = api.read; let unavailable = false;
    api.read = async path => { if (unavailable && path === '/conn/operations/op/publication') throw new Error('Status unavailable'); return read(path); };
    api.write = async (path, request) => { writes.push({ path, request }); unavailable = true; throw new Error('Response lost'); };
    choosePublication(el); el('integrationApply').listeners.click(); await settle();
    el('integrationHistory').children[1].children[0].listeners.click(); await settle();
    el('integrationReject').listeners.click(); el('integrationRationale').value = 'Other visible rationale'; el('integrationRationale').listeners.input?.();
    el('integrationApply').listeners.click(); await settle();
    assert.equal(writes[1].request.review.operationId, 'other');
    assert.equal(writes[1].request.resolutions.change, 'SKIP');
    unavailable = false;
    el('integrationHistory').children[0].children[0].listeners.click(); await settle();
    assert.equal(el('integrationApply').disabled, false);
    el('integrationReject').listeners.click(); el('integrationRationale').value = 'Now confirmed editable'; el('integrationRationale').listeners.input?.();
    el('integrationApply').listeners.click(); await settle();
    assert.equal(writes[2].request.review.operationId, 'op');
    assert.equal(writes[2].request.review.rationale, 'Now confirmed editable');
});

test('late publication response cannot replace a changed connection selection', async () => {
    const { el, api, publicationOperation } = await page('taxonomy-publication-contract-v1', { publication: true });
    let complete;
    api.write = () => new Promise(resolve => { complete = resolve; });
    choosePublication(el); el('integrationApply').listeners.click(); await settle();
    el('integrationConnection').value = ''; el('integrationConnection').listeners.change();
    complete(publicationOperation); await settle();
    assert.equal(el('integrationPublicationOutcomes').hidden, true);
    assert.equal(el('integrationConnection').value, '');
});

test('late endpoint options cannot replace the options of another displayed operation', async () => {
    const { el, api, publicationOperation } = await page('taxonomy-publication-contract-v1', { publication: true });
    publicationOperation.preview.changes[0].local.extensions = { source: 'source', target: 'target' };
    const read = api.read; let complete;
    api.read = async path => {
        if (path === '/conn/operations/op/endpoint-options') return new Promise(resolve => { complete = resolve; });
        if (path === '/conn/operations/other/endpoint-options') return { external: { source: { businessIdentity: 'current-source' }, target: { businessIdentity: 'current-target' } } };
        return read(path);
    };
    el('integrationRefresh').listeners.click(); await settle();
    el('integrationHistory').children[1].children[0].listeners.click(); await settle();
    complete({ external: { source: { businessIdentity: 'obsolete-source' }, target: { businessIdentity: 'obsolete-target' } } }); await settle();
    const values = descendants(el('integrationChanges')).filter(node => node.tag === 'option').map(node => node.value);
    assert.ok(values.includes('current-source')); assert.ok(values.includes('current-target'));
    assert.ok(!values.includes('obsolete-source')); assert.ok(!values.includes('obsolete-target'));
});

test('authoritative cancellation resolves a lost-response review without enabling edits or another publish', async () => {
    const { el, api, publicationOperation } = await page('taxonomy-publication-contract-v1', { publication: true });
    api.write = async () => { Object.assign(publicationOperation, { phase: 'CANCELLED', status: 'CANCELLED', allowedActions: [] }); throw new Error('Concurrent cancellation; response lost'); };
    choosePublication(el); el('integrationApply').listeners.click(); await settle();
    assert.equal(el('integrationApply').textContent, 'integration.publishReviewed');
    assert.equal(el('integrationApply').disabled, true); assert.equal(el('integrationRationale').disabled, true);
    assert.ok(el('integrationOperation').textContent.includes('integration.phase.CANCELLED'));
});
