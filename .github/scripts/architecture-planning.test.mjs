import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = fs.readFileSync(path.join(root, 'taxonomy-app/src/main/resources/static/js/architecture-planning.js'), 'utf8');
class Element {
    constructor(tag = 'div') { this.tagName = tag.toUpperCase(); this.children = []; this.dataset = {}; this.events = {}; this.disabled = false; this.hidden = false; this._value = ''; this.textContent = ''; this.attributes = {}; }
    set value(v) { this._value = v; }
    get value() { return this._value || (this.tagName === 'SELECT' && this.children.length ? this.children[0].value : ''); }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = [...nodes]; this._value = ''; }
    addEventListener(type, callback) { this.events[type] = callback.bind(this); }
    setAttribute(key, value) { this.attributes[key] = value; }
    querySelectorAll() { return this.children.filter(c => c.dataset.planningField); }
    reportValidity() { return true; }
    setCustomValidity(value) { this.validationMessage = value; }
    focus() { this.focused = true; }
}
function fixture() {
    const ids = {};
    for (const [id, tag] of Object.entries({ editorPlanningRequirement:'select', editorPlanningEntry:'select', editorPlanningProfile:'select',
        editorPlanningFields:'div', editorPlanningValues:'dl', editorPlanningForm:'form', editorPlanningActions:'fieldset', editorPlanningReason:'textarea',
        editorPlanningDelete:'button', editorPlanningSave:'button', editorPlanningStatus:'p', editorPlanningNew:'button' })) ids[id] = new Element(tag);
    const intents = [];
    const window = { confirm: () => true };
    vm.runInNewContext(source, { window, document: { getElementById: id => ids[id], createElement: tag => new Element(tag) },
        crypto: { randomUUID: () => '00000000-0000-4000-8000-000000000001' } });
    const panel = window.TaxonomyPlanningEditor.create({ t: key => key, stage: (intent) => intents.push(intent) });
    const profile = { id:'go-live', version:'1', fields:[{key:'precision',required:true,choices:['YEAR','DATE']}, {key:'value',required:true,choices:[]}] };
    const view = { mayEdit:true, schema:{planningProfiles:[profile]}, model:{requirements:[{id:'R',title:'Original'}]}, planningInformation:{R:[]} };
    panel.render(view); panel.setEnabled(true); ids.editorPlanningReason.value = 'Test reason';
    return { ids, panel, view, intents };
}
test('creates a precise profile intent through the existing review callback', () => {
    const f = fixture();
    for (const field of f.ids.editorPlanningFields.querySelectorAll()) field.value = field.dataset.planningField === 'precision' ? 'YEAR' : '2030';
    f.ids.editorPlanningForm.events.submit({preventDefault(){}});
    assert.equal(f.intents.length, 1);
    assert.equal(f.intents[0].kind, 'SET_PLANNING'); assert.equal(f.intents[0].id, 'R');
    assert.deepEqual(JSON.parse(JSON.stringify(f.intents[0].planning.values)), {precision:'YEAR',value:'2030'});
    assert.ok(!JSON.stringify(f.intents[0]).includes('2030-01-01'));
});
test('read-only state cannot submit or delete', () => {
    const f = fixture(); f.panel.setEnabled(false);
    f.ids.editorPlanningForm.events.submit({preventDefault(){}});
    f.ids.editorPlanningDelete.events.click();
    assert.equal(f.intents.length, 0); assert.equal(f.ids.editorPlanningActions.disabled, true);
    assert.equal(f.ids.editorPlanningRequirement.disabled, false);
});
test('unknown profile versions remain visible and non-editable', () => {
    const f = fixture();
    f.view.planningInformation.R = [{ entry:{id:'future',profile:'go-live',version:'99',origin:'remote',values:{value:'not interpreted'}},supported:false,problem:'UNSUPPORTED_PROFILE_VERSION' }];
    f.panel.render(f.view); f.ids.editorPlanningEntry.value = 'future'; f.ids.editorPlanningEntry.events.change();
    f.ids.editorPlanningForm.events.submit({preventDefault(){}});
    assert.equal(f.intents.length, 0); assert.equal(f.ids.editorPlanningSave.disabled, true);
    assert.ok(f.ids.editorPlanningValues.children.some(e => e.textContent === 'not interpreted'));
});
test('removal is an explicit separate command, not an empty update', () => {
    const f = fixture();
    f.view.planningInformation.R = [{entry:{id:'known',profile:'go-live',version:'1',origin:'MANUAL',values:{precision:'YEAR',value:'2030'}},supported:true}];
    f.panel.render(f.view); f.ids.editorPlanningEntry.value = 'known'; f.ids.editorPlanningEntry.events.change();
    f.ids.editorPlanningReason.value = 'Remove this target'; f.ids.editorPlanningDelete.events.click();
    assert.equal(f.intents[0].kind, 'DELETE_PLANNING'); assert.equal(f.intents[0].planning.entryId, 'known');
});
test('selects profile by identity AND version', () => {
    const f = fixture();
    f.view.schema.planningProfiles.push({...f.view.schema.planningProfiles[0],version:'2'});
    f.panel.render(f.view); f.ids.editorPlanningProfile.value = 'go-live@2'; f.ids.editorPlanningProfile.events.change();
    for (const field of f.ids.editorPlanningFields.querySelectorAll()) field.value = field.dataset.planningField === 'precision' ? 'YEAR' : '2030';
    f.ids.editorPlanningForm.events.submit({preventDefault(){}});
    assert.equal(f.intents[0]?.planning.version,'2');
});
test('malformed namespace is visible and cannot be overwritten through the profile form', () => {
    const f = fixture(); f.view.planningInformation.R = [{entry:null,supported:false,problem:'INVALID_PLANNING_NAMESPACE'}];
    f.panel.render(f.view); f.ids.editorPlanningForm.events.submit({preventDefault(){}});
    assert.equal(f.intents.length,0); assert.equal(f.ids.editorPlanningSave.disabled,true);
    assert.equal(f.ids.editorPlanningStatus.textContent,'invalid');
});

// Run both production UI modules together: do not replace the panel's stage callback.
// Only browser rendering and the remote API boundary are fixtures, not retry logic.
async function editorFixture(conflictAt) {
    const html = fs.readFileSync(path.join(root, 'taxonomy-app/src/main/resources/templates/architecture-editor.html'), 'utf8');
    const ids = Object.fromEntries([...html.matchAll(/<([a-z0-9]+)\b[^>]*\bid="([^"]+)"/gi)]
        .map(([, tag, id]) => [id, new Element(tag)]));
    for (const node of Object.values(ids)) {
        node.scrollIntoView = () => {};
        node.showModal = function () { this.open = true; };
        node.close = function () { this.open = false; };
    }
    const context = { repositoryId:'test-repository', workspaceScopeKey:'test-workspace', branch:'main',
        commit:null, revision:1, actor:'test-user', writeMode:'PRIVATE_WORKSPACE' };
    const view = { mayEdit:true, document:{context, dsl:'', projectionState:'READY', history:[], versions:[], source:'WORKSPACE_REVISION'},
        schema:{elementTypes:[], relationTypes:[], relationStatuses:[], planningProfiles:[{id:'go-live', version:'1',
            fields:[{key:'precision',required:true,choices:['YEAR','DATE']}, {key:'value',required:true,choices:[]}]}]},
        model:{requirements:[{id:'R',title:'Original'}], elements:[], relations:[], evidence:[], packages:[]},
        scene:{nodes:[],edges:[]}, searchIndex:[], planningInformation:{R:[{supported:true, entry:{id:'launch',
            profile:'go-live', version:'1', origin:'MANUAL', values:{precision:'YEAR',value:'2030'}}}]} };
    const previews = [], executions = [], loads = [];
    let conflicted = false, sequence = 0;
    function conflict() {
        conflicted = true;
        context.revision = 2;
        throw Object.assign(new Error('Workspace revision moved'), {status:412,
            responseBody:{code:'REVISION_MOVED',expectedRevision:1,currentRevision:2}});
    }
    const api = {
        async load(scope, revision) { loads.push({scope:structuredClone(scope),revision}); return structuredClone(view); },
        async preview(command) {
            previews.push(structuredClone(command));
            if (conflictAt === 'preview' && !conflicted) conflict();
            assert.equal(command.context.revision, context.revision);
            return {context:structuredClone(context), change:{changes:[{id:'requirement:R',before:'old planning',after:'new planning'}]}};
        },
        async execute(command) {
            executions.push(structuredClone(command));
            if (conflictAt === 'execute' && !conflicted) conflict();
            assert.equal(command.context.revision, context.revision);
            context.revision++;
            return {context:structuredClone(context),projectionState:'READY'};
        }
    };
    const document = {getElementById:id => {
        assert.ok(ids[id], `Production template is missing ${id}`);
        return ids[id];
    }, createElement:tag => new Element(tag), querySelectorAll:() => []};
    const window = {confirm:() => true, addEventListener:() => {}, ArchitectureEditorApi:api,
        TaxonomyI18n:{t:key => key, ready:() => Promise.resolve()},
        ArchitectureEditorRenderer:() => ({render(){},select(){},focus(){},fit(){}})};
    const sandbox = vm.createContext({window,document,URL,URLSearchParams,AbortController,
        crypto:{randomUUID:() => `00000000-0000-4000-8000-${String(++sequence).padStart(12,'0')}`},
        location:{href:'https://example.invalid/architecture/editor',search:''},
        history:{replaceState(){},pushState(){}}});
    vm.runInContext(source, sandbox);
    vm.runInContext(fs.readFileSync(path.join(root, 'taxonomy-app/src/main/resources/static/js/architecture-editor.js'), 'utf8'), sandbox);
    await settleUi();
    assert.equal(loads.length, 1);
    assert.equal(ids.editorPlanningActions.disabled, false);
    ids.editorPlanningEntry.value = 'launch';
    ids.editorPlanningEntry.events.change();
    return {ids, previews, executions, loads};
}

// All fixture promises resolve immediately; drain the real handlers without timed sleeps.
async function settleUi() { await new Promise(resolve => setImmediate(resolve)); }

for (const conflictAt of ['preview','execute']) {
    for (const generalReason of ['', 'Unrelated architecture edit']) {
        test(`planning ${conflictAt} conflict preserves its frozen reason with ${generalReason ? 'another' : 'an empty'} general reason`, async () => {
            await checkPlanningRetry(conflictAt, generalReason, false);
        });
    }
}
test('planning deletion preserves its frozen reason through an execute conflict', async () => {
    await checkPlanningRetry('execute', 'Unrelated architecture edit', true);
});

async function checkPlanningRetry(conflictAt, generalReason, deletion) {
    const f = await editorFixture(conflictAt);
    const reason = deletion ? 'Remove the superseded launch target' : 'Use the approved launch year';
    f.ids.editorRationale.value = generalReason;
    f.ids.editorPlanningReason.value = reason;
    if (deletion) f.ids.editorPlanningDelete.events.click();
    else {
        const value = f.ids.editorPlanningFields.querySelectorAll().find(field => field.dataset.planningField === 'value');
        value.value = '2031'; value.events.input();
        f.ids.editorPlanningForm.events.submit({preventDefault(){}});
    }
    await settleUi();
    assert.equal(f.previews.length, 1);
    const original = f.previews[0];
    assert.equal(original.kind, deletion ? 'DELETE_PLANNING' : 'SET_PLANNING');
    assert.equal(original.metadata.rationale, reason);
    if (conflictAt === 'execute') await f.ids.editorAccept.onclick();
    assert.equal(f.ids.editorReapply.hidden, false);
    assert.equal(f.ids.editorPreviewDialog.open, true);

    // Retry must use the accepted intent, not reread either mutable form field.
    f.ids.editorPlanningReason.value = 'An unsubmitted later draft';
    await f.ids.editorReapply.onclick();
    assert.equal(f.previews.length, 2);
    const retried = f.previews[1];
    assert.equal(retried.metadata.rationale, reason);
    assert.equal(retried.context.revision, 2);
    assert.equal(retried.context.workspaceScopeKey, original.context.workspaceScopeKey);
    assert.equal(retried.metadata.correlationId, original.metadata.correlationId);
    assert.equal(retried.metadata.causationId, original.metadata.commandId);
    assert.notEqual(retried.metadata.commandId, original.metadata.commandId);
    assert.deepEqual(retried.planning, original.planning);
    assert.equal(f.ids.editorAccept.disabled, false);
    assert.ok(f.ids.editorPreviewContext.textContent.includes(reason));
    assert.equal(f.executions.length, conflictAt === 'execute' ? 1 : 0, 'Reapply must not auto-accept');
    await f.ids.editorAccept.onclick();
    assert.deepEqual(f.executions.at(-1), retried, 'Acceptance must use exactly the re-reviewed command');
    assert.equal(f.ids.editorPreviewDialog.open, false);
    assert.equal(f.loads.length, 3, 'Initial load, conflict refresh and accepted-revision reload');
}
