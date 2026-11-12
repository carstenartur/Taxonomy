import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';

const script = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/responsibility-display.js', import.meta.url), 'utf8');

function fixture() {
    const requests = [], events = [], storage = new Map(), listeners = new Map();
    const state = {currentScores:{}, currentModelBinding:null};
    const session = {ready:true, workspaceId:'mine'};
    const scope = {repositoryId:'repo', workspaceScopeKey:'mine', branch:'draft'};
    const document = {dispatchEvent:e=>{ events.push(e.type); listeners.get(e.type)?.(e); },
        addEventListener:(type,fn)=>listeners.set(type,fn), querySelectorAll:()=>[]};
    const window = {TaxonomyState:state, TaxonomyAnalysisSession:{state:()=>session},
        TaxonomyArchitectureWorkspace:{analysisScope:async()=>scope},
        TaxonomyAppChrome:{getContext:()=>({architecture:scope})},
        TaxonomyI18n:{t:key=>key},
        sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v)},
        ArchitectureEditorApi:{analysisResponsibilities:(context,pin)=>new Promise(resolve=>requests.push({context,pin,resolve}))}};
    vm.runInNewContext(script, {document, window, CustomEvent:class {constructor(type){this.type=type;}}});
    return {display:window.TaxonomyResponsibilityDisplay, state, session, requests, events, scope,
        switchRepository:selectionChanged=>document.dispatchEvent({type:'taxonomy:repository-context-changed',detail:{selectionChanged}})};
}
const assignments = {BR:{unitId:'unit-1',name:'Medical Services',sourceId:'BR'}};
function binding(scope, hash) {
    return {...scope, commit:'a'.repeat(40), semanticRevision:2, modelSource:'WORKSPACE_REVISION', modelSha256:hash.repeat(64)};
}

test('only the frozen analysis can supply ownership; slow prior answers cannot replace it', async () => {
    const f=fixture(), a=binding(f.scope,'a'), b=binding(f.scope,'b');
    f.state.currentModelBinding=a;
    await f.display.bindAnalysis();
    assert.equal(f.requests.length,0);
    f.display.setEnabled(true);
    const first=f.display.bindAnalysis();
    await Promise.resolve();
    f.state.currentModelBinding=b;
    const second=f.display.bindAnalysis();
    await Promise.resolve();
    assert.equal(f.requests.length,2);
    assert.equal(f.requests[1].pin.modelSha256,b.modelSha256);
    f.requests[1].resolve(assignments); await second;
    f.requests[0].resolve({BR:{unitId:'other',name:'Wrong',sourceId:'BR'}}); await first;
    assert.equal(f.display.entry('BR').name,'Medical Services');
    f.display.setEnabled(false);
    assert.equal(f.display.entry('BR'),null);
});

test('unbound historical scores and foreign workspaces never fall back to live assignments', async () => {
    const f=fixture(); f.display.setEnabled(true);
    f.state.currentScores={BR:82}; await f.display.bindAnalysis();
    assert.equal(f.requests.length,0);
    f.state.currentModelBinding=binding({...f.scope,workspaceScopeKey:'other'},'a');
    await f.display.bindAnalysis(); assert.equal(f.requests.length,0);
});

test('editor and analysis use the same unit color; a rename preserves its color', () => {
    const f=fixture(), view={model:{organizations:[{id:'unit-1',title:'Medical Services'}]},hierarchy:{
        BR:{organizationId:'unit-1',sourceElementId:'BR'}, child:{organizationId:'unit-1',sourceElementId:'BR'},
        old:{legacyOrganization:'Former unit',sourceElementId:'old'}}};
    const before=f.display.fromEditor(view);
    assert.equal(before.get('BR').color,before.get('child').color);
    assert.equal(before.get('child').sourceId,'BR');
    assert.equal(before.get('old').unitId,'legacy:Former unit');
    view.model.organizations[0].title='Health';
    assert.equal(f.display.fromEditor(view).get('BR').color,before.get('BR').color);
});

test('fresh browsing requests live responsibility in the selected workspace', async () => {
    const f = fixture();
    f.display.setEnabled(true);
    const pending = f.display.bindAnalysis();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.requests.length, 1);
    assert.equal(f.requests[0].pin.live, true);
    assert.equal(f.requests[0].context.workspaceScopeKey, 'mine');
    f.requests[0].resolve(assignments);
    await pending;
    assert.equal(f.display.entry('BR').sourceId, 'BR');
});

test('a selection change during a request discards stale units even ahead of the next render', async () => {
    const f = fixture();
    f.display.setEnabled(true);
    f.state.currentModelBinding = binding(f.scope, 'a');
    const pending = f.display.bindAnalysis();
    f.state.currentModelBinding = binding(f.scope, 'b');
    f.requests[0].resolve(assignments);
    await pending;
    assert.equal(f.display.entry('BR'), null);
    assert.equal(f.requests.length, 2);
    const second = f.display.bindAnalysis();
    f.display.setEnabled(false);
    f.requests[1].resolve(assignments);
    await second;
    assert.equal(f.display.entry('BR'), null);
});

test('changing the active branch within the same workspace clears cached ownership', async () => {
    const f = fixture();
    f.state.currentModelBinding = binding(f.scope, 'a');
    f.display.setEnabled(true);
    const pending = f.display.bindAnalysis();
    f.requests[0].resolve(assignments);
    await pending;
    assert.equal(f.display.entry('BR').name, 'Medical Services');
    f.scope.branch = 'another-branch';
    await f.display.bindAnalysis();
    assert.equal(f.display.entry('BR'), null);
    assert.equal(f.requests.length, 1);
});

test('a repository selection event invalidates assignments before another render; polling preserves them', async () => {
    const f = fixture();
    f.state.currentModelBinding = binding(f.scope, 'a');
    f.display.setEnabled(true);
    const pending = f.display.bindAnalysis();
    f.requests[0].resolve(assignments);
    await pending;
    f.switchRepository(false);
    assert.equal(f.display.entry('BR').name, 'Medical Services');
    f.switchRepository(true);
    assert.equal(f.display.entry('BR'), null);
    const recheck = f.display.bindAnalysis();
    assert.equal(f.requests.length, 2);
    f.requests[1].resolve({});
    await recheck;
    assert.equal(f.display.entry('BR'), null);
});
