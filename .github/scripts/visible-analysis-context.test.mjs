import test, {afterEach} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
import canonicalFixture from './canonical-api-fixture.cjs';
afterEach(() => canonicalFixture.dispose());
const src=name=>readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/'+name,import.meta.url),'utf8');
const A={repositoryId:'working-a',workspaceScopeKey:'private-a',branch:'design-a',commit:'a'.repeat(40)};
const B={repositoryId:'working-b',workspaceScopeKey:'private-b',branch:'design-b',commit:'b'.repeat(40)};
const plain=value=>JSON.parse(JSON.stringify(value));
const settle=async()=>{for(let i=0;i<30;i++)await Promise.resolve();};
function fixture() {
    let visible=A, active=A;
    const context=vm.createContext({URL,URLSearchParams,console,document:{addEventListener(){},getElementById(){return null;}},
        TaxonomyUtils:{escapeHtml:String},TaxonomyI18n:{t:key=>key},
        fetch:async url=>{throw new Error('Unexpected request: '+url);},
        TaxonomyAppChrome:{getContext:()=>({architecture:visible})},
        TaxonomyArchitectureLifecycle:{ready:async()=>({context:A}),selectedContext:()=>active}});
    context.window=context;
    canonicalFixture.install(context,['api/analysis-api.js']);
    vm.runInContext(src('shared/taxonomy-graph.js'),context);
    return {context,api:context.AnalysisApi,setVisible:value=>visible=value,suspend:()=>active=null};
}
test('suspended working A never substitutes for visible analysis or uncommitted snapshot B',async()=>{
    for(const visible of [{...B,commit:null},{...B,commit:undefined},undefined]) {
        const f=fixture();f.suspend();f.setVisible(visible);
        await assert.rejects(()=>f.api.captureContext());
    }
});
test('explicit committed visible B is captured after A suspends and rejects stale A proof',async()=>{
    const f=fixture();f.suspend();f.setVisible(B);
    assert.deepEqual(plain(await f.api.captureContext()),B);
    assert.equal(await f.context.TaxonomyGraph.isCurrentEvidence(A,{...A,commitId:A.commit}),false);
    assert.equal(await f.context.TaxonomyGraph.isCurrentEvidence(B,{...B,commitId:B.commit}),true);
});
test('an unsuspended disagreeing lifecycle cannot certify a different visible model',async()=>{
    const f=fixture();f.setVisible(B);await assert.rejects(()=>f.api.captureContext());
});
test('an exact private graph pin survives the shared display key', async () => {
    const context = vm.createContext({URL, URLSearchParams, TaxonomyI18n:{t:key=>key}});
    const scope = {...A, workspaceScopeKey:'__shared__', workspaceId:'__shared__'};
    const calls = [];
    context.window = context;
    context.TaxonomyAppChrome = {getContext:() => ({architecture:scope})};
    context.TaxonomyApiClient = {getJson:async (url, options) => {calls.push({url,options});}};
    vm.runInContext(src('api/analysis-api.js'), context);
    await context.AnalysisApi.graphUpstream('UA-1574', 2, await context.AnalysisApi.captureContext());
    assert.equal(calls[0].options.pinnedWorkspaceId, '__shared__');
    assert.equal(new URL(calls[0].url,'https://example.test').searchParams.get('commitId'), scope.commit);
});
for (const [label, workspace] of [
    ['central display key with a nullable workspace ID', {workspaceId:null, workspaceScopeKey:'__shared__'}],
    ['explicit empty workspace pin', {workspaceId:''}]
]) test(`graph transport pins central with the ${label}`, async () => {
    const context = vm.createContext({URL, URLSearchParams, TaxonomyI18n:{t:key=>key}});
    const calls = [];
    context.window = context;
    context.TaxonomyApiClient = {getJson:async (url, options) => {calls.push({url,options});}};
    vm.runInContext(src('api/analysis-api.js'), context);
    await context.AnalysisApi.graphUpstream('UA-1574', 2, {
        repositoryId:A.repositoryId, branch:A.branch, commit:A.commit, ...workspace
    });
    assert.equal(calls[0].options.pinnedWorkspaceId, '');
    assert.equal(new URL(calls[0].url,'https://example.test').searchParams.get('workspaceId'), '');
});
test('current graph evidence distinguishes central from the same-named private workspace', async () => {
    const app = fixture();
    app.suspend();
    const scope = {...A, workspaceScopeKey:'__shared__', workspaceId:'__shared__'};
    app.setVisible(scope);
    assert.equal(await app.context.TaxonomyGraph.isCurrentEvidence(scope, {...scope, workspaceId:null}), false);
    assert.equal(await app.context.TaxonomyGraph.isCurrentEvidence(scope, {...scope}), true);
});
test('lifecycle exposes only the active selected context and hides it on suspend',async()=>{
    const context=vm.createContext({URL,URLSearchParams,location:new URL('https://fixture.test/'),
        document:{getElementById:()=>null,addEventListener(){}},TaxonomyI18n:{ready:async()=>{},t:key=>key},
        TaxonomyArchitectureWorkflow:{getScope:()=>A,setScope(){}},
        TaxonomyArchitectureLifecycleApi:{overview:async()=>({context:A})}});
    context.window=context;context.addEventListener=()=>{};
    vm.runInContext(src('shared/architecture-lifecycle.js'),context);
    await context.TaxonomyArchitectureLifecycle.ready();
    await context.TaxonomyArchitectureLifecycle.observe({context:A,source:'GIT_CHECKPOINT'},false);
    assert.deepEqual(plain(context.TaxonomyArchitectureLifecycle.selectedContext()),A);
    context.TaxonomyArchitectureLifecycle.suspend();
    assert.equal(context.TaxonomyArchitectureLifecycle.selectedContext(),null);
});
async function workspaceFixture(savedSource=A) {
    const handlers=new Map(), nodes=new Map(), owners={};let current={},scope={},suspended=false;
    const runtime={workspaceId:B.workspaceScopeKey,analysisGeneration:0};
    const node=id=>{if(!nodes.has(id))nodes.set(id,{hidden:false,value:'',textContent:'',addEventListener(){}});return nodes.get(id);};
    const context=vm.createContext({URL,URLSearchParams,console,location:new URL('https://fixture.test/?architectureSource=working#architecture'),
        document:{getElementById:node,querySelector:()=>null,addEventListener:(name,cb)=>handlers.set(name,cb),dispatchEvent(){}},
        CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}},history:{},
        TaxonomyI18n:{ready:async()=>{},t:key=>key,resolveUrl:value=>value},
        __TaxonomyAnalysisSessionContext:{runtime},
        TaxonomyAnalysisSession:{whenInitialized:async()=>true,state:()=>runtime},
        TaxonomyArchitectureLifecycleApi:{overview:async()=>({context:B})},
        TaxonomyArchitectureLifecycle:{ready:async()=>({context:A}),selectedContext:()=>suspended?null:A,suspend:()=>suspended=true},
        fetch:async url=>{throw new Error('Unexpected request: '+url);}});
    context.window=context;context.addEventListener=()=>{};
    context.history.replaceState=context.history.pushState=(_a,_b,url)=>context.location=new URL(url,context.location);
    context.TaxonomyAppChrome={getContext:()=>current,updateContext:(value,owner)=>owners[owner]=value,
        activateContext:owner=>current=owners[owner]||{},refreshNavigation(){}};
    context.TaxonomyArchitectureWorkflow={getScope:()=>scope,replaceScope:value=>scope=value};
    context.TaxonomyArchitectureEditor={mount:()=>({activate:async()=>{owners.working={architecture:savedSource};},suspend(){},canLeave:()=>true,
        getSource:()=>({...savedSource,kind:'working'}),getData:()=>null,getDiagram:()=>null})};
    canonicalFixture.install(context,['api/analysis-api.js']);
    vm.runInContext(src('shared/architecture-workspace.js'),context);
    assert.equal(await context.TaxonomyArchitectureWorkspace.ready(),true);
    return {context,page:async page=>{
        const url=new URL(context.location.href);url.hash=page;
        context.history.replaceState(null,'',url);
        handlers.get('taxonomy:page-activated')({detail:{page}});await settle();
    }};
}
test('working selection remains explicit in Chrome and URL when navigating to Graph',async()=>{
    const f=await workspaceFixture();await f.page('graph');
    assert.deepEqual(plain(f.context.TaxonomyAppChrome.getContext().architecture),A);
    assert.equal(f.context.location.hash,'#graph');
    assert.equal(f.context.location.searchParams.get('architectureSource'),'working');
    assert.deepEqual(plain(await f.context.AnalysisApi.captureContext()),A);
});
test('leaving working for Analyze then Graph cannot resurrect suspended working A',async()=>{
    const f=await workspaceFixture();await f.page('analyze');await f.page('graph');
    assert.equal(f.context.TaxonomyAppChrome.getContext().architecture.repositoryId,B.repositoryId);
    assert.equal(f.context.TaxonomyAppChrome.getContext().architecture.commit,null);
    await assert.rejects(()=>f.context.AnalysisApi.captureContext());
});
test('Analyze requires explicit useSelectedModel action before a retained saved working source becomes current',async()=>{
    const f=await workspaceFixture();await f.page('analyze');
    await assert.rejects(()=>f.context.AnalysisApi.captureContext());
    assert.equal(await f.context.TaxonomyArchitectureWorkspace.useSelectedModel(),true);
    assert.equal(f.context.location.hash,'#analyze');
    assert.deepEqual(plain(await f.context.AnalysisApi.captureContext()),A);
});

test('useSelectedModel opens the existing working selector without certifying an uncommitted revision',async()=>{
    const f=await workspaceFixture({...A,commit:null,revision:7});await f.page('analyze');
    assert.equal(await f.context.TaxonomyArchitectureWorkspace.useSelectedModel(),false);
    assert.equal(f.context.TaxonomyArchitectureWorkspace.isActive(),true);
    assert.equal(f.context.TaxonomyArchitectureWorkspace.getSource().kind,'working');
    await assert.rejects(()=>f.context.AnalysisApi.captureContext());
});
test('repeated Graph activation preserves only the explicitly visible saved selection',async()=>{
    const f=await workspaceFixture();await f.page('graph');await f.page('graph');
    assert.deepEqual(plain(await f.context.AnalysisApi.captureContext()),A);
});
