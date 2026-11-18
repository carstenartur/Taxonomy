import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import vm from 'node:vm';

const sourceRoot = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const scripts = await Promise.all([
    'architecture-organization-directory.js', 'api/architecture-editor-api.js', 'catalogue-responsibility-editor.js'
].map(async file => [file, await readFile(new URL(file, sourceRoot), 'utf8')]));
const drain = () => new Promise(resolve => setImmediate(resolve));
const copy = value => JSON.parse(JSON.stringify(value));
const scope = {repositoryId:'catalogue-project', workspaceScopeKey:'private-copy', branch:'draft',
    commit:'a'.repeat(40), revision:3, actor:'architect', writeMode:'PRIVATE_WORKSPACE'};
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;

async function fixture() {
    const nodes = new Map(), writes = [];
    let selectBranch, failNextExecution = false;
    const document = {documentElement:{lang:'en'}, getElementById:element, addEventListener() {},
        createElement:() => makeElement()};
    function makeElement() {
        return {ownerDocument:document, children:[], events:new Map(), value:'', hidden:false, disabled:false,
            addEventListener(type, handler) { this.events.set(type, handler); },
            querySelector(selector) { return element(selector === 'button' ? 'assignmentSubmit' : selector.slice(1)); },
            replaceChildren(...children) { this.children = children; },
            append(child) { this.children.push(child); },
            add(child) { this.children.push(child); },
            get selectedOptions() { return this.children.filter(child => child.value === this.value); },
            reportValidity:() => true, focus() {}};
    }
    function element(id) {
        if (!nodes.has(id)) nodes.set(id, makeElement());
        return nodes.get(id);
    }
    async function fire(id, type) {
        await element(id).events.get(type)({target:element(id), preventDefault() {}});
        await drain();
    }
    const directory = {context:copy(scope), editable:true,
        organizations:[{id:'operations', title:'Operations', parentId:null}], assignments:[],
        catalogue:[{code:'BP-1000', nameEn:'Business Processes', nameDe:null, parentCode:'BP', sortOrder:20}]};
    const context = vm.createContext({document, URLSearchParams, crypto:{randomUUID},
        Option:class {constructor(textContent, value) {this.textContent = textContent; this.value = value;}},
        location:new URL('https://example.test/'), addEventListener() {}, confirm:() => true,
        TaxonomyI18n:{t:key => key},
        TaxonomyArchitectureWorkspace:{analysisScope:async () => copy(scope)},
        // Tree drawing is unrelated to the command contract; selection still enters the real controller.
        TaxonomyResponsibilityTree:{create({onSelect}) {selectBranch = onSelect; return {render() {}, reset() {}};}},
        TaxonomyResponsibilityDisplay:{unit:value => value, invalidate() {}},
        TaxonomyApiClient:{
            getJson:async () => copy(directory),
            request:async (url, init) => {
                const path = new URL(url, 'https://example.test').pathname;
                const command = JSON.parse(init.body);
                writes.push({path, method:init.method, headers:copy(init.headers), command});
                const change = {changes:[{id:command.id, kind:command.kind}]};
                if (path.endsWith('/preview')) return {json:async () => ({context:copy(scope), change,
                    kind:command.kind, targetOperationId:null})};
                assert.equal(path, '/api/architecture/editor/commands');
                if (failNextExecution) {
                    failNextExecution = false;
                    throw Object.assign(new Error('The command response was lost'), {status:503});
                }
                return {json:async () => ({context:{...scope, revision:4}, commandId:command.metadata.commandId,
                    operationId:'00000000-0000-4000-8000-000000000001', replayed:false, projectionState:'READY', change})};
            }
        }
    });
    context.window = context;
    for (const [filename, source] of scripts) vm.runInContext(source, context, {filename});
    element('catalogueResponsibilityEditor').open = true;
    await fire('catalogueResponsibilityEditor', 'toggle');
    return {
        writes,
        failNextExecution() { failNextExecution = true; },
        accept:() => fire('catalogueResponsibilityAccept', 'click'),
        async stage(kind, reason = 'Assign the process owner') {
            if (kind === 'UPSERT_ORGANIZATION') {
                await fire('catalogueDirectoryNew', 'click');
                element('catalogueDirectoryName').value = 'Delivery';
                element('catalogueDirectoryReason').value = reason;
                await fire('catalogueDirectoryForm', 'submit');
            } else {
                selectBranch('BP-1000');
                element('catalogueResponsibilityUnit').value = 'operations';
                element('catalogueResponsibilityRationale').value = reason;
                await fire('catalogueResponsibilityForm', 'submit');
            }
        }
    };
}

function assertMetadata(command) {
    const fields = ['commandId', 'correlationId', 'causationId'];
    assert.deepEqual(fields.filter(field => !uuid.test(command.metadata[field] || '')), [],
        'Every required command metadata identifier must be a valid UUID');
    assert.equal(command.metadata.correlationId, command.metadata.commandId);
    assert.equal(command.metadata.causationId, command.metadata.commandId);
    assert.equal(command.metadata.rationale, 'Assign the process owner');
}

for (const kind of ['UPSERT_ORGANIZATION', 'SET_CATALOGUE_RESPONSIBILITY']) {
    test(`${kind} sends complete command metadata unchanged from preview to acceptance`, async () => {
        const f = await fixture();
        await f.stage(kind);
        assert.equal(f.writes.length, 1);
        const preview = f.writes[0];
        assert.equal(preview.path, '/api/architecture/editor/preview');
        assert.equal(preview.method, 'POST');
        assert.equal(preview.headers['If-Match'], '"workspace-revision-3"');
        assert.equal(preview.command.kind, kind);
        assert.deepEqual(preview.command.context, scope);
        assertMetadata(preview.command);
        if (kind === 'UPSERT_ORGANIZATION') {
            assert.match(preview.command.id, uuid);
            assert.deepEqual(preview.command.properties, {title:'Delivery', parentId:''});
        } else {
            assert.equal(preview.command.id, 'BP-1000');
            assert.deepEqual(preview.command.properties, {organization:'operations'});
        }
        await f.accept();
        assert.equal(f.writes.length, 2);
        assert.equal(f.writes[1].path, '/api/architecture/editor/commands');
        assert.deepEqual(f.writes[1].command, preview.command);
    });

    test(`${kind} retries the same command after a lost response and gives a new preview a fresh identity`, async () => {
        const f = await fixture();
        await f.stage(kind);
        const original = f.writes[0].command;
        f.failNextExecution();
        await f.accept();
        await f.accept();
        assert.deepEqual(f.writes.map(write => write.path), [
            '/api/architecture/editor/preview', '/api/architecture/editor/commands', '/api/architecture/editor/commands'
        ]);
        assert.deepEqual(f.writes[1].command, original);
        assert.deepEqual(f.writes[2].command, original);
        await f.stage(kind, 'A separate reviewed change');
        assert.equal(f.writes.length, 4);
        assert.equal(f.writes[3].path, '/api/architecture/editor/preview');
        assert.notEqual(f.writes[3].command.metadata.commandId, original.metadata.commandId);
        assert.equal(f.writes[3].command.metadata.rationale, 'A separate reviewed change');
    });
}
