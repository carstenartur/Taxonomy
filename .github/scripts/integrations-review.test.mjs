import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/integrations.js', import.meta.url), 'utf8');
const apiSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/integration-api.js', import.meta.url), 'utf8');
const template = await readFile(new URL('../../taxonomy-app/src/main/resources/templates/integrations.html', import.meta.url), 'utf8');

class Element {
    constructor(tag = 'div') {
        this.tag = tag; this.children = []; this.listeners = {}; this.dataset = {};
        this.files = []; this.value = ''; this.textContent = '';
    }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this._text = ''; this.children = nodes; }
    set textContent(value) { this._text = String(value); this.children = []; }
    get textContent() { return this._text + this.children.map(node => node.textContent).join(''); }
    set value(value) { this._value = value; if (this.type === 'file' && value === '') this.files = []; }
    get value() { return this._value; }
    get options() { return this.children.filter(node => node.tag === 'option'); }
    get selectedOptions() { return this.options.filter(node => node.value === this.value); }
    setAttribute(name, value) { this[name] = value; }
    addEventListener(type, handler) { this.listeners[type] = handler; }
    focus() {}
}

async function page(profileId, { profilesMissing = false, publication = false, availabilityMissing = false, failPreview = false, predecessor = null, change = null,
    profileVersion = '1', descriptorVersion = profileVersion, capabilities = ['FILE_IMPORT'], mediaTypes = ['application/xml'], endpointCandidates = {},
    mayWrite = true, checkpoint = null, checkpointId = null, fileBases = [], fileExportModes = [], fileExport = null, operationDirection = null } = {}) {
    const elements = new Map();
    for (const match of template.matchAll(/<(\w+)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
        const element = new Element(match[1]);
        element.type = /\btype="([^"]+)"/.exec(match[2])?.[1];
        element.hidden = /\bhidden\b/.test(match[2]); element.disabled = /\bdisabled\b/.test(match[2]);
        elements.set(match[3], element);
    }
    const el = id => elements.get(id) || null;
    const authority = el('connectionAuthority');
    for (const mode of ['LINK_ONLY', 'IMPORT_COPY', 'MIRROR_READ', 'PUBLISH_TARGET', 'BIDIRECTIONAL']) {
        const option = new Element('option'); option.value = mode; authority.append(option);
    }
    authority.value = 'IMPORT_COPY';
    el('integrationFile').accept = '.reqif,.xml,.xmi';
    const operation = {
        id: 'op', status: 'PREVIEWED', direction: operationDirection || (publication ? 'PUSH' : 'INBOUND'), fileExport,
        context: { profile: profileId, profileVersion, authority: 'IMPORT_COPY' },
        document: { losses: [], metadata: {} },
        changes: [change || { id: 'change', externalId: 'RELATION:r', kind: 'ADD', conflicts: [],
            after: { kind: 'RELATION', title: 'Dependency', attributes: {}, extensions: {} } }]
    };
    const overview = { connection: { id: 'conn', connectorId: profileId, profileVersion, authority: 'IMPORT_COPY', checkpointId },
        current: { branch: 'draft', semanticRevision: 7 }, checkpoint, fileBases, fileExportModes,
        history: [{ id: 'op', status: 'PREVIEWED', createdAt: 'first' }, { id: 'other', status: 'PREVIEWED', createdAt: 'second' }], oslcCatalogPath: '/oslc' };
    const exportOperation = { ...structuredClone(operation), id: 'export-op', direction: 'OUTBOUND' };
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
            const match = path.match(/^\/conn\/operations\/(op|other|export-op)(.*)$/);
            if (match) {
                const id = match[1];
                if (!match[2]) return structuredClone(id === 'export-op' ? exportOperation : { ...operation, id });
                if (match[2] === '/publication') return structuredClone({ ...publicationOperation, operationId: id,
                    requestFingerprint: 'request-' + id, preview: { ...publicationOperation.preview, fingerprint: 'preview-' + id } });
                if (match[2] === '/endpoint-options') return { external: endpointCandidates };
                if (match[2] === '/events') return [];
            }
            throw new Error('Unexpected read: ' + path);
        },
        async write(path, request) {
            writes.push({ path, request });
            if (failPreview && path.endsWith('/publication-previews')) throw new Error('Preview transport unavailable');
            if (path.endsWith('/export-previews')) return structuredClone(exportOperation);
            if (path.endsWith('/files')) { exportOperation.status = 'COMPLETED'; exportOperation.review = request; return structuredClone(exportOperation); }
            return publicationOperation;
        },
        async upload(path, request, file) { uploads.push({ path, request, file }); return structuredClone(path.endsWith('/export-previews') ? exportOperation : operation); },
        downloadUrl: path => '/api/integrations' + path
    };
    let requestSequence = 0;
    vm.runInNewContext(source, {
        document: { body: { dataset: { mayWrite: String(mayWrite) } }, getElementById: el,
            querySelectorAll: selector => selector === '#integrationChanges select' ? descendants(el('integrationChanges')).filter(node => node.tag === 'select') : [], createElement: tag => new Element(tag) },
        window: { IntegrationApi: api, TaxonomyI18n: { ready: async () => {}, t: key => key, resolveUrl: value => value } },
        location: { href: 'https://taxonomy.test/integrations?connection=conn&operation=op', search: '?connection=conn&operation=op' },
        history: { replaceState() {} }, crypto: { randomUUID: () => 'request-' + (++requestSequence) }, URL, URLSearchParams
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(el('integrationError').hidden, true, el('integrationError').textContent);
    return { el, uploads, writes, reads, api, publicationOperation, overview, operation, exportOperation };
}

function descendants(node) { return node.children.flatMap(child => [child, ...descendants(child)]); }

const basedCapabilities = ['FILE_IMPORT', 'FILE_EXPORT', 'BASED_FILE_EXPORT_V1'];
const qeaTypes = ['application/vnd.sparx.qea', 'application/vnd.sqlite3', 'application/octet-stream'];
const storedBase = { id: 'retained-base', sha256: 'a'.repeat(64), mediaType: qeaTypes[0], bytes: 4096,
    modelIdentifier: '{MODEL-BASE}', sourceOperationId: 'prior-import' };
const frozenFileExport = { mode: 'UPDATE_COPY', identityNamespace: 'namespace-original', baseFileId: 'frozen-base',
    baseSha256: 'b'.repeat(64), baseMediaType: qeaTypes[0], baseBytes: 8192, modelIdentifier: '{MODEL-FROZEN}',
    outputFileId: null, outputSha256: null, outputBytes: null };
function basedPage(options = {}) {
    return page('external-file-profile', { capabilities: basedCapabilities, mediaTypes: qeaTypes,
        fileExportModes: ['NEW_PROJECT'], fileBases: [storedBase], ...options });
}
function binaryFile(name = 'base.qea') {
    return Object.assign(new Blob([Uint8Array.from([83, 81, 76, 105, 116, 101, 0, 255, 128, 13, 10])], { type: '' }), { name });
}
function selectExportFile(el, file = binaryFile()) {
    el('integrationExportBaseFile').files = [file];
    el('integrationExportBaseFile').listeners.change();
    return file;
}

test('binary API upload preserves every byte and sends scoped multipart without automatic retries', async () => {
    const calls = [], window = { TaxonomyApiClient: { sendFormData: (...args) => calls.push(args) } };
    vm.runInNewContext(apiSource, { window, location: { search: '?repositoryId=repo&workspaceId=ws&branch=draft' }, URLSearchParams, Blob, FormData });
    const file = binaryFile();
    const request = { operationId: 'op', mode: 'NEW_PROJECT', baseFileId: null };
    window.IntegrationApi.upload('/conn/export-previews', request, file);
    const [path, form, method, options] = calls[0];
    assert.equal(path, '/api/integrations/conn/export-previews?repositoryId=repo&workspaceId=ws&branch=draft');
    assert.deepEqual([...form.keys()], ['request', 'file']);
    assert.deepEqual(new Uint8Array(await form.get('file').arrayBuffer()), new Uint8Array(await file.arrayBuffer()));
    assert.equal(form.get('request').type, 'application/json');
    assert.deepEqual(JSON.parse(await form.get('request').text()), request);
    assert.equal(method, 'POST'); assert.equal(options.retries, 0);
});

test('retained-base API upload sends only the JSON multipart part when the optional file is absent', async () => {
    const forms = [], window = { TaxonomyApiClient: { sendFormData: (path, form) => forms.push(form) } };
    vm.runInNewContext(apiSource, { window, location: { search: '' }, URLSearchParams, Blob, FormData });
    for (const file of [undefined, null]) window.IntegrationApi.upload('/conn/export-previews', { baseFileId: storedBase.id }, file);
    for (const form of forms) {
        assert.deepEqual([...form.keys()], ['request']);
        assert.equal(JSON.parse(await form.get('request').text()).baseFileId, storedBase.id);
    }
});

test('based export uses labelled existing-page controls and defaults by connection state, independent of mode order', async () => {
    for (const fileExportModes of [['UPDATE_COPY', 'NEW_PROJECT'], ['NEW_PROJECT', 'UPDATE_COPY']]) {
        for (const checkpoint of [null, { id: 'checkpoint', externalVersion: 'external-7' }]) {
            const { el } = await basedPage({ fileExportModes, checkpoint });
            assert.equal(el('integrationFileExport').tag, 'fieldset');
            assert.equal(el('integrationFileExport').hidden, false);
            assert.equal(el('integrationExportMode').value, checkpoint ? 'UPDATE_COPY' : 'NEW_PROJECT');
            assert.equal(el('integrationExportBase').value, 'upload');
            assert.equal(el('integrationExportBaseFile').type, 'file');
            assert.ok(el('integrationExportBaseFile').accept.split(',').includes('.qea'));
            assert.equal(el('integrationExport').disabled, true, 'A basis is required before preview');
            for (const id of ['integrationExportMode', 'integrationExportBase', 'integrationExportBaseFile'])
                assert.match(template, new RegExp('<label[^>]*for="' + id + '"'));
        }
    }
    assert.equal((await basedPage({ checkpointId: 'checkpoint', fileExportModes: ['NEW_PROJECT', 'UPDATE_COPY'] })).el('integrationExportMode').value, 'UPDATE_COPY');
    assert.equal((await basedPage({ fileExportModes: ['UPDATE_COPY'] })).el('integrationExportMode').value, 'UPDATE_COPY');
});

test('based export uploads the original binary file with the exact reviewed workspace and explicit mode', async () => {
    const { el, uploads, writes, overview } = await basedPage();
    const file = selectExportFile(el);
    assert.equal(el('integrationExport').disabled, false);
    el('integrationExport').listeners.click(); await settle();
    assert.equal(writes.length, 0); assert.equal(uploads.length, 1);
    assert.equal(uploads[0].path, '/conn/export-previews'); assert.equal(uploads[0].file, file);
    assert.deepEqual(JSON.parse(JSON.stringify(uploads[0].request)), {
        operationId: 'request-1', expected: overview.current, expectedExternalVersion: null,
        mode: 'NEW_PROJECT', mediaType: qeaTypes[0], baseFileId: null
    });
});

test('retained bases use their identity and media type without attaching a previously selected file', async () => {
    const base = { ...storedBase, mediaType: 'application/vnd.sqlite3', modelIdentifier: null };
    const { el, uploads, writes } = await basedPage({ fileBases: [base], checkpoint: { externalVersion: 'external-7' }, fileExportModes: ['UPDATE_COPY'] });
    selectExportFile(el);
    el('integrationExportBase').value = base.id; el('integrationExportBase').listeners.change();
    assert.equal(el('integrationExportBaseFile').disabled, true);
    const label = el('integrationExportBase').options.find(option => option.value === base.id).textContent;
    for (const value of [base.sha256, String(base.bytes), base.sourceOperationId]) assert.ok(label.includes(value), value);
    el('integrationExport').listeners.click(); await settle();
    assert.equal(writes.length, 0); assert.equal(uploads.length, 1);
    assert.equal(uploads[0].file == null, true);
    assert.equal(uploads[0].request.baseFileId, base.id);
    assert.equal(uploads[0].request.mediaType, base.mediaType);
    assert.equal(uploads[0].request.expectedExternalVersion, 'external-7');
    assert.equal(uploads[0].request.mode, 'UPDATE_COPY');
});

test('legacy file exporters keep the existing JSON request and hide the based export controls', async () => {
    const { el, writes, uploads, overview } = await page('archimate-3.2', { capabilities: ['FILE_EXPORT'] });
    assert.equal(el('integrationFileExport').hidden, true); assert.equal(el('integrationExport').disabled, false);
    el('integrationExport').listeners.click(); await settle();
    assert.equal(uploads.length, 0); assert.equal(writes[0].path, '/conn/export-previews');
    assert.deepEqual(JSON.parse(JSON.stringify(writes[0].request)), { operationId: 'request-1', expected: overview.current, expectedExternalVersion: null });
});

test('based export handlers cannot bypass permissions, profile availability, allowed modes or required bases', async () => {
    for (const options of [
        { mayWrite: false }, { profilesMissing: true }, { descriptorVersion: 'unavailable' },
        { capabilities: ['FILE_IMPORT', 'BASED_FILE_EXPORT_V1'] }, { fileExportModes: [] }, { fileExportModes: ['FUTURE_MODE'] }, {}
    ]) {
        const { el, writes, uploads } = await basedPage(options);
        if (Object.keys(options).length) selectExportFile(el);
        assert.equal(el('integrationExport').disabled, true, JSON.stringify(options));
        el('integrationExport').listeners.click(); await settle();
        assert.equal(writes.length + uploads.length, 0, JSON.stringify(options));
    }
});

test('an unknown retained base or disallowed mode cannot start a file export', async () => {
    for (const field of ['integrationExportMode', 'integrationExportBase']) {
        const { el, uploads, writes } = await basedPage(); selectExportFile(el);
        el(field).value = 'unavailable'; el(field).listeners.change();
        assert.equal(el('integrationExport').disabled, true);
        el('integrationExport').listeners.click(); await settle();
        assert.equal(uploads.length + writes.length, 0);
    }
});

test('a failed preview retries the same operation, frozen workspace and binary file', async () => {
    const { el, api, overview } = await basedPage(); const file = selectExportFile(el); const attempts = [];
    api.upload = async (path, request, file) => { attempts.push({ path, request, file }); throw new Error('Response lost'); };
    el('integrationExport').listeners.click(); await settle();
    overview.current.semanticRevision = 8;
    el('integrationExport').listeners.click(); await settle();
    assert.equal(attempts.length, 2); assert.equal(attempts[0].request, attempts[1].request);
    assert.equal(attempts[0].request.operationId, attempts[1].request.operationId);
    assert.equal(attempts[1].request.expected.semanticRevision, 7);
    assert.equal(attempts[1].file, file);
});

test('refreshing an unchanged legacy exporter preserves its failed request for retry', async () => {
    const { el, api } = await page('archimate-3.2', { capabilities: ['FILE_EXPORT'] }); const attempts = [];
    api.write = async (path, request) => { attempts.push(request); throw new Error('Response lost'); };
    el('integrationExport').listeners.click(); await settle();
    el('integrationRefresh').listeners.click(); await settle();
    el('integrationExport').listeners.click(); await settle();
    assert.equal(attempts.length, 2); assert.equal(attempts[1], attempts[0]);
});

test('creating a new connection clears the previous export basis and pending request', async () => {
    const { el, api, overview } = await basedPage({ fileExport: frozenFileExport, operationDirection: 'OUTBOUND' });
    selectExportFile(el); const attempts = [], read = api.read;
    api.upload = async (path, request, file) => { attempts.push({ path, request, file }); throw new Error('Response lost'); };
    el('integrationExport').listeners.click(); await settle();
    api.write = async () => ({ id: 'next-conn' });
    api.read = path => path === '' ? Promise.resolve([{ id: 'next-conn', displayName: 'New connection', connectorId: 'external-file-profile' }])
        : path === '/next-conn' ? Promise.resolve({ ...overview, connection: { ...overview.connection, id: 'next-conn' }, history: [], fileBases: [] }) : read(path);
    el('connectionProfile').value = 'external-file-profile@1';
    el('integrationCreate').listeners.submit({ preventDefault() {} }); await settle();
    assert.equal(el('integrationConnection').value, 'next-conn');
    assert.equal(el('integrationExportBaseFile').files.length, 0);
    assert.equal(el('integrationExport').disabled, true);
    assert.equal(el('integrationFileExportSummary').hidden, true);
    selectExportFile(el); el('integrationExport').listeners.click(); await settle();
    assert.equal(attempts.length, 2); assert.equal(attempts[1].path, '/next-conn/export-previews');
    assert.notEqual(attempts[1].request.operationId, attempts[0].request.operationId);
});

for (const field of ['mode', 'base', 'file', 'connection']) test('changing the export ' + field + ' invalidates a failed pending preview', async () => {
    const { el, api } = await basedPage({ fileExportModes: ['NEW_PROJECT', 'UPDATE_COPY'] });
    selectExportFile(el); const attempts = [];
    api.upload = async (path, request, file) => { attempts.push({ request, file }); throw new Error('Response lost'); };
    el('integrationExport').listeners.click(); await settle();
    if (field === 'mode') { el('integrationExportMode').value = 'UPDATE_COPY'; el('integrationExportMode').listeners.change(); }
    if (field === 'base') { el('integrationExportBase').value = storedBase.id; el('integrationExportBase').listeners.change(); }
    if (field === 'file') selectExportFile(el, binaryFile('replacement.qea'));
    if (field === 'connection') {
        el('integrationConnection').value = ''; el('integrationConnection').listeners.change(); await settle();
        el('integrationConnection').value = 'conn'; el('integrationConnection').listeners.change(); await settle(); selectExportFile(el);
    }
    el('integrationExport').listeners.click(); await settle();
    assert.equal(attempts.length, 2); assert.notEqual(attempts[0].request.operationId, attempts[1].request.operationId);
    if (field === 'mode') assert.equal(attempts[1].request.mode, 'UPDATE_COPY');
    if (field === 'base') { assert.equal(attempts[1].request.baseFileId, storedBase.id); assert.equal(attempts[1].file == null, true); }
    if (field === 'file') assert.notEqual(attempts[0].file, attempts[1].file);
});

test('an in-flight export disables inputs and ignores both duplicate clicks and an obsolete response', async () => {
    const { el, api, exportOperation } = await basedPage(); selectExportFile(el); let complete, attempts = 0;
    api.upload = () => { attempts++; return new Promise(resolve => { complete = resolve; }); };
    el('integrationExport').listeners.click(); await settle();
    for (const id of ['integrationExport', 'integrationExportMode', 'integrationExportBase', 'integrationExportBaseFile']) assert.equal(el(id).disabled, true, id);
    el('integrationExport').listeners.click(); assert.equal(attempts, 1);
    selectExportFile(el, binaryFile('newer.qea'));
    complete(exportOperation); await settle();
    assert.ok(!el('integrationOperation').textContent.includes('export-op'));
    assert.equal(el('integrationExport').disabled, false);
});

test('connection reset removes file selections and frozen provenance and does not accept a late export response', async () => {
    const { el, api, exportOperation } = await basedPage({ fileExport: frozenFileExport, operationDirection: 'OUTBOUND' });
    selectExportFile(el); let complete;
    api.upload = () => new Promise(resolve => { complete = resolve; });
    el('integrationExport').listeners.click(); await settle();
    el('integrationConnection').value = ''; el('integrationConnection').listeners.change();
    complete(exportOperation); await settle();
    assert.equal(el('integrationExportBaseFile').files.length, 0);
    assert.equal(el('integrationExportMode').value, ''); assert.equal(el('integrationExportBase').value, '');
    assert.equal(el('integrationFileExport').hidden, true);
    assert.equal(el('integrationFileExportSummary').hidden, true); assert.equal(el('integrationFileExportSummary').textContent, '');
    assert.equal(el('integrationOperation').textContent, ''); assert.equal(el('integrationExport').disabled, true);
});

test('review provenance is frozen by the operation and survives changes to the next export selection', async () => {
    const { el, overview } = await basedPage({ fileExport: frozenFileExport, operationDirection: 'OUTBOUND' });
    const summary = el('integrationFileExportSummary');
    assert.equal(summary.hidden, false);
    for (const value of [frozenFileExport.baseFileId, frozenFileExport.baseSha256, frozenFileExport.baseMediaType,
        String(frozenFileExport.baseBytes), frozenFileExport.modelIdentifier, frozenFileExport.identityNamespace, 'integration.exportMode.UPDATE_COPY'])
        assert.ok(summary.textContent.includes(value), value);
    assert.ok(!summary.textContent.includes(storedBase.sha256));
    const frozen = summary.textContent;
    overview.fileBases[0] = { ...storedBase, sha256: 'c'.repeat(64) };
    selectExportFile(el); el('integrationExportBase').value = storedBase.id; el('integrationExportBase').listeners.change();
    assert.equal(summary.textContent, frozen);
});

test('completed based exports retain the existing review, file download and output provenance routes', async () => {
    const fileExport = { ...frozenFileExport, outputFileId: 'output-id', outputSha256: 'd'.repeat(64), outputBytes: 12288 };
    const { el, writes, exportOperation } = await basedPage({ fileExport, operationDirection: 'OUTBOUND' });
    selectExportFile(el); el('integrationExport').listeners.click(); await settle();
    el('integrationAccept').listeners.click(); el('integrationRationale').value = 'Reviewed file copy';
    el('integrationApply').listeners.click(); await settle();
    assert.equal(writes[0].path, '/conn/files'); assert.equal(writes[0].request.operationId, 'export-op');
    assert.equal(exportOperation.status, 'COMPLETED');
    assert.equal(el('integrationDownload').hidden, false);
    assert.equal(el('integrationDownload').href, '/api/integrations/conn/operations/export-op/file');
    for (const value of ['output-id', fileExport.outputSha256, '12288']) assert.ok(el('integrationFileExportSummary').textContent.includes(value), value);
});

test('a refreshed missing retained base cannot silently reuse an old hidden upload', async () => {
    const { el, overview } = await basedPage(); selectExportFile(el);
    el('integrationExportBase').value = storedBase.id; el('integrationExportBase').listeners.change();
    overview.fileBases = [];
    el('integrationRefresh').listeners.click(); await settle();
    assert.equal(el('integrationExportBase').value, 'upload');
    assert.equal(el('integrationExportBaseFile').files.length, 0);
    assert.equal(el('integrationExport').disabled, true);
});

test('operations without based-file provenance clear the previous file summary', async () => {
    for (const publication of [false, true]) {
        const { el, api, operation } = await basedPage({ fileExport: frozenFileExport, operationDirection: 'OUTBOUND' });
        const read = api.read;
        api.read = path => path === '/conn/operations/other' ? Promise.resolve({ ...operation, id: 'other', direction: publication ? 'PUSH' : 'INBOUND', fileExport: null }) : read(path);
        el('integrationHistory').children[1].children[0].listeners.click(); await settle();
        assert.equal(el('integrationFileExportSummary').hidden, true);
        assert.equal(el('integrationFileExportSummary').textContent, '');
    }
});

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
