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
