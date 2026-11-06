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

async function page(profileId, { profilesMissing = false, publication = false, availabilityMissing = false, failPreview = false, predecessor = null, change = null,
    profileVersion = '1', descriptorVersion = profileVersion, capabilities = ['FILE_IMPORT'], mediaTypes = ['application/xml'], endpointCandidates = {} } = {}) {
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
    el('integrationFile').accept = '.reqif,.xml,.xmi';
    const operation = {
        id: 'op', status: 'PREVIEWED', direction: publication ? 'PUSH' : 'INBOUND', context: { profile: profileId, profileVersion, authority: 'IMPORT_COPY' },
        document: { losses: [], metadata: {} },
        changes: [change || { id: 'change', externalId: 'RELATION:r', kind: 'ADD', conflicts: [],
            after: { kind: 'RELATION', title: 'Dependency', attributes: {}, extensions: {} } }]
    };
    const overview = { connection: { connectorId: profileId, profileVersion, authority: 'IMPORT_COPY' }, current: {}, history: [{ id: 'op', status: 'PREVIEWED', createdAt: 'first' }, { id: 'other', status: 'PREVIEWED', createdAt: 'second' }], oslcCatalogPath: '/oslc' };
    const uploads = [], writes = [], reads = [];
    const publicationOperation = { operationId: 'op', predecessorOperationId: predecessor, mode: 'PUSH', phase: 'PREVIEWED', status: 'PREVIEWED', allowedActions: ['REVIEW', 'PUBLISH', 'CANCEL'], scope: { rootResource: 'urn:model', selectorFingerprint: 'all' }, expectedExternalRevision: 'scope-1', items: [], acknowledgedCount: 0, unknownCount: 0, remainingCount: 0, preview: { fingerprint: 'frozen', changes: [{ id: 'change', externalId: 'RELATION:r', local: operation.changes[0].after, remote: null, merged: operation.changes[0].after, localFields: ['title'], remoteFields: [], conflicts: [], dependencies: [] }], losses: [] } };
    overview.publicationAvailability = { available: publication, modes: publication ? ['PUSH', 'SYNCHRONIZE'] : [], reasonCode: publication ? null : 'PUBLICATION_GUARANTEES_UNVERIFIED' };
    if (availabilityMissing) delete overview.publicationAvailability;
    const api = {
        async read(path) {
            reads.push(path);
            if (path === '/profiles') return profilesMissing ? [] : [{ id: profileId, version: descriptorVersion, title: profileId,
                capabilities, mediaTypes }];
            if (path === '') return [{ id: 'conn', displayName: 'Test', connectorId: profileId }];
            if (path === '/conn' || path.startsWith('/conn?')) return overview;
            const match = path.match(/^\/conn\/operations\/(op|other)(.*)$/);
            if (match) {
                const id = match[1];
                if (!match[2]) return structuredClone({ ...operation, id });
                if (match[2] === '/publication') return structuredClone({ ...publicationOperation, operationId: id,
                    requestFingerprint: 'request-' + id, preview: { ...publicationOperation.preview, fingerprint: 'preview-' + id } });
                if (match[2] === '/endpoint-options') return { external: endpointCandidates };
                if (match[2] === '/events') return [];
            }
            throw new Error('Unexpected read: ' + path);
        },
        async write(path, request) { writes.push({ path, request }); if (failPreview && path.endsWith("/publication-previews")) throw new Error("Preview transport unavailable"); return publicationOperation; },
        async upload(path, request, file) { uploads.push({ path, request, file }); return operation; }
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
    return { el, uploads, writes, reads, api, publicationOperation };
}

function descendants(node) { return node.children.flatMap(child => [child, ...descendants(child)]); }

test('review rows lead with the object name and retain its exact external identity', async () => {
    for (const publication of [false, true]) {
        const { el } = await page('archimate-3.2', { publication });
        const cell = el('integrationChanges').children[0].children[0];
        assert.equal(cell.children[0]?.textContent, 'Dependency');
        assert.equal(cell.children[1].tag, 'details');
        assert.equal(cell.children[1].children[1].textContent, 'RELATION:r');
        const decision = descendants(el('integrationChanges').children[0]).find(node => node.tag === 'select');
        assert.ok(decision['aria-label'].includes('Dependency'));
        assert.ok(decision['aria-label'].includes('RELATION:r'));
    }
});

test('an unnamed object keeps its external identity visible', async () => {
    const { el } = await page('archimate-3.2', { change: {
        id: 'unnamed', externalId: 'ELEMENT:123', kind: 'ADD', conflicts: [],
        after: { kind: 'ELEMENT', title: '', attributes: {}, extensions: {} }
    } });
    const cell = el('integrationChanges').children[0].children[0];
    assert.equal(cell.children[0].textContent, 'ELEMENT:123');
    assert.equal(cell.children.length, 1);
});

test('review search finds the previous name of a deletion candidate', async () => {
    const { el } = await page('archimate-3.2', { change: {
        id: 'delete', externalId: 'ELEMENT:123', kind: 'REMOVE_CANDIDATE', conflicts: [],
        before: { kind: 'ELEMENT', title: 'Previous capability', attributes: {}, extensions: {} }, after: null
    } });
    el('integrationFilter').value = 'previous capability';
    el('integrationFilter').listeners.input();
    assert.equal(el('integrationChanges').children.length, 1);
    assert.equal(el('integrationChanges').children[0].children[0].children[0].textContent, 'Previous capability');
});

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

test('a neutral canonical profile offers typed endpoint review and canonical relation choices', async () => {
    const { el, reads } = await page('external-model', {
        capabilities: ['FILE_IMPORT', 'ARCHITECTURE_MODEL', 'CANONICAL_PROJECTION_V1'],
        endpointCandidates: { source: { kind: 'ARCHITECTURE_ELEMENT', businessIdentity: 'arch-source' },
            target: { kind: 'ARCHITECTURE_ELEMENT', businessIdentity: 'arch-target' } },
        change: { id: 'change', externalId: 'RELATION:r', kind: 'ADD', conflicts: [],
            after: { kind: 'RELATION', title: 'Connection', attributes: {}, extensions: { source: 'source', target: 'target' } } }
    });
    assert.ok(reads.includes('/conn/operations/op/endpoint-options'));
    const options = descendants(el('integrationChanges')).filter(node => node.tag === 'option').map(node => node.value);
    assert.ok(options.includes('PRESERVE_ONLY'));
    assert.ok(options.includes('REQUIREMENT_MAPPING'));
    assert.ok(options.includes('USES'));
    assert.ok(options.includes('PRODUCES'));
    assert.ok(options.includes('arch-source'));
    assert.ok(options.includes('arch-target'));
});

test('a different profile version cannot grant typed endpoint controls', async () => {
    const { el, reads } = await page('external-model', {
        profileVersion: '1', descriptorVersion: '2',
        capabilities: ['FILE_IMPORT', 'ARCHITECTURE_MODEL', 'CANONICAL_PROJECTION_V1']
    });
    assert.ok(!reads.includes('/conn/operations/op/endpoint-options'));
    assert.ok(!descendants(el('integrationChanges')).some(node => node.tag === 'option' && node.value === 'PRESERVE_ONLY'));
});

test('a read-only file source cannot be configured as a publication target', async () => {
    const { el } = await page('external-model', { capabilities: ['FILE_IMPORT', 'ARCHITECTURE_MODEL', 'CANONICAL_PROJECTION_V1'] });
    el('connectionProfile').value = 'external-model@1';
    el('connectionAuthority').value = 'PUBLISH_TARGET';
    el('connectionProfile').listeners.change();
    assert.equal(el('connectionAuthority').value, 'IMPORT_COPY');
    for (const option of el('connectionAuthority').options) {
        assert.equal(option.disabled, ['PUBLISH_TARGET', 'BIDIRECTIONAL'].includes(option.value));
    }
});

test('a QEA profile accepts its file extension and uploads the unchanged binary file with an advertised media type', async () => {
    const mediaTypes = ['application/vnd.sparx.qea', 'application/vnd.sqlite3', 'application/octet-stream'];
    const { el, uploads } = await page('external-model', {
        capabilities: ['FILE_IMPORT', 'ARCHITECTURE_MODEL', 'CANONICAL_PROJECTION_V1'], mediaTypes
    });
    assert.ok(el('integrationFile').accept.split(',').includes('.qea'));
    assert.ok(el('integrationFile').accept.split(',').includes('.qeax'));
    assert.ok(!el('integrationFile').accept.split(',').includes('.xml'));
    const file = { name: 'model.qea', type: '', bytes: Uint8Array.from([83, 81, 76, 105, 116, 101, 32, 102, 111, 114, 109, 97, 116, 32, 51, 0, 255]) };
    el('integrationFile').files = [file];
    el('integrationImport').listeners.submit({ preventDefault() {} });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(uploads.length, 1);
    assert.equal(uploads[0].file, file);
    assert.equal(uploads[0].request.mediaType, 'application/vnd.sparx.qea');
    assert.equal(el('integrationExport').disabled, true);
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

// Read the durable operation, not Playwright's lossy DevTools response cache.
async function acceptanceHarness(overrides = {}) {
    const module = await import('./integration-acceptance.mjs');
    const url = 'https://taxonomy.test/integrations?repositoryId=repo&workspaceId=ws&branch=draft&connection=conn&operation=next';
    let reads = 0;
    const operation = { id: 'next', connectionId: 'conn', direction: 'OUTBOUND', status: 'PREVIEWED',
        context: { connectionId: 'conn', authority: 'BIDIRECTIONAL', profile: 'archimate-3.1', profileVersion: '1',
            internalState: { repositoryId: 'repo', workspaceScopeKey: 'ws', branch: 'draft' } },
        document: { losses: [] }, changes: [] };
    const page = { url: () => url, waitForFunction: async (fn, arg) => {
        const document = { getElementById: id => id === 'integrationOperation' ? { textContent: 'next · Previewed' } : { disabled: false } };
        assert.equal(vm.runInNewContext('(' + fn.toString() + ')(arg)', { document, location: { href: overrides.stale ? url.replace('operation=next', 'operation=old') : url }, URL, arg }), true);
    }, evaluate: async (fn, arg) => vm.runInNewContext('(' + fn.toString() + ')(arg)', {
        location: { href: url }, URL, window: { IntegrationApi: { downloadUrl: path => '/api/integrations' + path + '?repositoryId=repo&workspaceId=ws&branch=draft' } }
    , arg }), request: { get: async endpoint => {
        reads++;
        assert.equal(endpoint, 'https://taxonomy.test/api/integrations/conn/operations/next?repositoryId=repo&workspaceId=ws&branch=draft');
        return { status: () => overrides.httpStatus || 200, text: async () => 'Operation unavailable',
            json: async () => { overrides.corrupt?.(operation); return operation; } };
    } } };
    const response = { status: () => overrides.postStatus || 200,
        text: async () => { throw new Error('evicted from inspector cache'); },
        json: async () => { throw new Error('evicted from inspector cache'); } };
    return { module, page, response, reads: () => reads };
}
test('large export acceptance follows the rendered identity and reads the persisted scoped operation', async () => {
    const h = await acceptanceHarness();
    const result = await h.module.readRenderedExportPreview(h.page, h.response, 'conn', 'old', { id: 'archimate-3.1', version: '1' });
    assert.equal(result.id, 'next'); assert.equal(h.reads(), 1);
});
for (const [name, overrides] of Object.entries({
    'failed preview POST': { postStatus: 500 }, 'failed operation GET': { httpStatus: 403 }, 'stale UI operation': { stale: true },
    'wrong operation': { corrupt: op => { op.id = 'foreign'; } },
    'wrong connection': { corrupt: op => { op.connectionId = 'foreign'; } },
    'wrong direction': { corrupt: op => { op.direction = 'INBOUND'; } },
    'wrong status': { corrupt: op => { op.status = 'COMPLETED'; } },
    'wrong profile': { corrupt: op => { op.context.profileVersion = '2'; } },
    'wrong workspace': { corrupt: op => { op.context.internalState.workspaceScopeKey = 'foreign'; } }
})) test('persisted export acceptance rejects ' + name, async () => {
    const h = await acceptanceHarness(overrides);
    assert.equal(typeof h.module.readRenderedExportPreview, 'function');
    await assert.rejects(() => h.module.readRenderedExportPreview(h.page, h.response, 'conn', 'old', { id: 'archimate-3.1', version: '1' }));
});
