import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';

async function boot() {
    const context = vm.createContext({URL, URLSearchParams, console, document: {getElementById: () => null}});
    context.window = context;
    vm.runInContext(await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-workspace.js', import.meta.url), 'utf8'), context);
    return context.TaxonomyArchitectureWorkspaceController;
}
test('legacy source links retain private scope, exact version, selection and mounted path', async () => {
    const api = await boot();
    for (const [route, kind] of [['editor','working'], ['workbench','snapshot']]) {
        const original = new URL(`https://example.test/taxonomy/architecture/${route}?repositoryId=repo&workspaceId=private-a&branch=draft%2Fa&revision=17&commit=abc&projectId=42&snapshotId=s-2&element=UA-1574&layout=stable&sourceSnapshotId=s-1&lang=de`);
        const source = api.parseSource(original);
        assert.equal(source.kind, kind);
        assert.equal(source.workspaceScopeKey, 'private-a');
        const result = new URL(api.urlFor(original, source, '/taxonomy/'));
        assert.equal(result.pathname, '/taxonomy/');
        assert.equal(result.hash, '#architecture');
        for (const [key,value] of original.searchParams) assert.equal(result.searchParams.get(key), value, key);
        assert.equal(result.searchParams.get('architectureSource'), kind);
    }
});
test('source switches retain mounted adapters and keep selection local to its source', async () => {
    const api = await boot(), calls = [], drafts = {text: 'unaccepted requirement'};
    const analysis = {activate: async source => calls.push(['analysis',source.element]), suspend: () => calls.push(['suspend']), getData: () => drafts};
    const working = {activate: async source => calls.push(['working',source.element]), suspend: () => {}, getData: () => ({model:true})};
    const workspace = api.create({initial:{kind:'analysis',element:'UA-1574'},adapters:{analysis,working},onChange:()=>{}});
    await workspace.open('analysis');
    await workspace.open('working', {source:{kind:'working',element:'CP-1030'}});
    await workspace.open('analysis');
    assert.equal(workspace.getData(), drafts);
    assert.equal(workspace.getSource().element, 'UA-1574');
    assert.deepEqual(calls.filter(c=>c[0]==='working'), [['working','CP-1030']]);
});
test('a departed async source cannot publish a late completion', async () => {
    const api = await boot(), published = []; let finish;
    const workspace = api.create({initial:{kind:'analysis'},adapters:{
        analysis:{activate:()=>new Promise(resolve=>finish=resolve),suspend:()=>{}},
        snapshot:{activate:async()=>{},suspend:()=>{}}
    },onChange:source=>published.push(source.kind)});
    const old = workspace.open('analysis');
    await Promise.resolve();
    await workspace.open('snapshot',{source:{kind:'snapshot',projectId:'42',snapshotId:'s-2'}});
    finish(); await old;
    assert.equal(workspace.getSource().kind,'snapshot');
    assert.deepEqual(published,['snapshot']);
});
test('a pending command guard refuses source change before hiding or changing source', async () => {
    const api = await boot(); let loaded = false, hidden = false;
    const workspace = api.create({initial:{kind:'working'},adapters:{
        working:{activate:async()=>{},canLeave:async()=>false,suspend:()=>hidden=true},
        snapshot:{activate:async()=>loaded=true}
    }});
    await workspace.open('working');
    assert.equal(await workspace.open('snapshot'),false);
    assert.equal(workspace.getSource().kind,'working');
    assert.equal(hidden,false); assert.equal(loaded,false);
});
test('unavailable snapshot remains explicit and never loads the working model', async () => {
    const api = await boot(); let editorLoads=0;
    const workspace = api.create({initial:{kind:'analysis'},adapters:{
        analysis:{activate:async()=>{},suspend:()=>{}},
        snapshot:{activate:async()=>{throw new Error('SOURCE_UNAVAILABLE');}},
        working:{activate:async()=>editorLoads++}
    }});
    await workspace.open('analysis');
    await assert.rejects(workspace.open('snapshot',{source:{kind:'snapshot',snapshotId:'gone'}}),/SOURCE_UNAVAILABLE/);
    assert.equal(workspace.getSource().kind,'snapshot');
    assert.equal(editorLoads,0);
});
test('suspension invalidates pending source completions and preserves cached inputs', async () => {
    const api = await boot(); let finish, published=0;
    const workspace = api.create({initial:{kind:'analysis',branch:'draft/a'},adapters:{analysis:{activate:()=>new Promise(r=>finish=r),suspend:()=>{}}},onChange:()=>published++});
    const opening=workspace.open('analysis'); await Promise.resolve(); workspace.suspend(); finish(); await opening;
    assert.equal(published,0); assert.equal(workspace.isActive(),false);
    assert.equal(workspace.getSource().branch,'draft/a');
});

test('background analysis updates cannot replace the active working architecture context', async () => {
    const document={getElementById:()=>null,querySelectorAll:()=>[],addEventListener(){},dispatchEvent(){}};
    const context=vm.createContext({document,URL,URLSearchParams,CustomEvent:class {constructor(type,options){this.type=type;this.detail=options.detail;}}});
    context.window=context;context.addEventListener=()=>{};
    context.TaxonomyI18n={t:key=>key,getLocale:()=> 'en'};
    context.TaxonomyArchitectureWorkspace={isActive:()=>true,getSource:()=>({kind:'working'})};
    vm.runInContext(await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/application-chrome.js',import.meta.url),'utf8'),context);
    context.TaxonomyAppChrome.updateContext({architecture:{repositoryId:'work-repo',branch:'work'},editable:true},'working');
    context.TaxonomyAppChrome.updateContext({architecture:{repositoryId:'analysis-repo',branch:'analysis'},editable:false});
    assert.equal(context.TaxonomyAppChrome.getContext().architecture.repositoryId,'work-repo');
    context.TaxonomyAppChrome.activateContext('analysis');
    assert.equal(context.TaxonomyAppChrome.getContext().architecture.repositoryId,'analysis-repo');
    assert.equal(context.TaxonomyAppChrome.getContext().editable,false);
});

test('an explicit source replaces retained checkpoint, view and adoption pins',async()=>{
    const api=await boot(),seen=[];
    const workspace=api.create({initial:{kind:'working'},adapters:{working:{activate:async source=>seen.push(source),suspend(){}}}});
    await workspace.open('working',{source:{kind:'working',workspaceScopeKey:'A',branch:'a',commit:'a'.repeat(40),viewId:'old-view',sourceSnapshotId:'old-source'}});
    await workspace.open('working',{source:{kind:'working',workspaceScopeKey:'A',branch:'a',revision:'7'}});
    assert.equal(seen[1].commit,undefined);assert.equal(seen[1].viewId,undefined);assert.equal(seen[1].sourceSnapshotId,undefined);
    assert.equal(seen[1].revision,'7');
});
test('a suspended target protects its retained draft before another source can replace it',async()=>{
    const api=await boot();let dirty=false,loads=0,guarded=0;
    const workspace=api.create({initial:{kind:'working'},adapters:{
        working:{activate:async()=>loads++,suspend(){},canLeave:(_next,options)=>{if(options.retain)return true;guarded++;return !dirty;}},
        snapshot:{activate:async()=>{},suspend(){}}
    }});
    await workspace.open('working',{source:{kind:'working',workspaceScopeKey:'A'}});dirty=true;
    await workspace.open('snapshot');
    assert.equal(await workspace.open('working',{source:{kind:'working',workspaceScopeKey:'B'}}),false);
    assert.equal(loads,1);assert.equal(guarded,1);assert.equal(workspace.getSource().kind,'snapshot');
});

test('workspaceId aliases are normalized before a retained dirty editor decides whether it may be replaced',async()=>{
    const api=await boot();let loads=0;const guarded=[];
    const workspace=api.create({initial:{kind:'working'},adapters:{
        working:{activate:async()=>loads++,suspend(){},canLeave:(next,settings)=>{if(settings.retain)return true;guarded.push(next.workspaceScopeKey);return next.workspaceScopeKey==='A';}},
        snapshot:{activate:async()=>{},suspend(){}}
    }});
    await workspace.open('working',{source:{kind:'working',workspaceId:'A'}});
    await workspace.open('snapshot');
    assert.equal(await workspace.open('working',{source:{kind:'working',workspaceId:'B'}}),false);
    assert.equal(await workspace.open('working',{workspaceId:'C'}),false);
    assert.deepEqual(guarded,['B','C']);assert.equal(loads,1);assert.equal(workspace.getSource().kind,'snapshot');
});

async function mountedWorkspace(initialKind='analysis',loaderOnly=false,options={}){
    const events=new Map(),windowEvents=new Map(),nodes=new Map(),scopes=[],metadata=[],headers=[];let ready;
    const readyPromise=new Promise(resolve=>ready=resolve),loaderEvents=new Map();
    const node=id=>{if(!nodes.has(id))nodes.set(id,{hidden:false,value:'',textContent:'',addEventListener(){}});return nodes.get(id);};
    const document={getElementById:node,querySelector:selector=>selector==='script[data-taxonomy-analysis-session]'&&loaderOnly?{
        addEventListener:(name,callback)=>loaderEvents.set(name,callback),removeEventListener:(name)=>loaderEvents.delete(name)
    }:null,addEventListener:(name,callback)=>events.set(name,callback),dispatchEvent(){}};
    const context=vm.createContext({document,URL,URLSearchParams,console,CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}},
        location:new URL('https://example.test/?architectureSource='+initialKind+(options.query || '')+'#'+(options.page || 'architecture')),history:{}});
    context.history.replaceState=context.history.pushState=(_state,_title,url)=>{context.location=new URL(String(url),context.location.href);};
    context.window=context;context.addEventListener=(name,callback)=>windowEvents.set(name,callback);
    context.TaxonomyState={currentArchView:{nodes:[{nodeCode:'A-evidence'}],requestPayloadHash:'retained-analysis-hash'}};
    context.TaxonomyI18n={t:key=>key,ready:()=>Promise.resolve(),resolveUrl:path=>path};
    context.TaxonomyAppChrome={updateContext:(value,owner)=>headers.push({value,owner}),activateContext(){},refreshNavigation(){}};
    context.TaxonomyArchitectureWorkflow={getScope:()=>({}),replaceScope:value=>scopes.push({...value})};
    context.TaxonomyArchitectureLifecycleApi={overview:async scope=>{metadata.push({...scope});return options.overview ? options.overview(scope) : {context:{repositoryId:'analysis-repo',branch:'analysis-branch',workspaceScopeKey:scope.workspaceScopeKey}};}};
    context.TaxonomyArchitectureEditor={mount:()=>({activate:async()=>{},suspend(){},canLeave:()=>context.editorCanLeave!==false,getData:()=>null,getDiagram:()=>null,getSource:()=>({workspaceId:'B',workspaceScopeKey:'B',repositoryId:'working-repo',branch:'working-branch'})})};
    if(!loaderOnly)context.TaxonomyAnalysisSessionReady=readyPromise;
    vm.runInContext(await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-workspace.js',import.meta.url),'utf8'),context);
    const settle=async()=>{for(let i=0;i<20;i++)await Promise.resolve();};
    await settle();
    return {context,scopes,metadata,headers,settle,activatePage:page=>events.get('taxonomy:page-activated')({detail:{page}}),
        restoreHistory:async url=>{context.location=new URL(url,context.location.href);windowEvents.get('popstate')();await settle();},
        ready:async()=>{context.TaxonomyAnalysisSession={whenInitialized:()=>Promise.resolve(true),state:()=>({workspaceId:'A',version:7})};
            context.TaxonomyAnalysisSessionReady=readyPromise;loaderEvents.get('load')?.();ready(true);await settle();}};
}
for(const loaderOnly of [false,true])test('analysis waits for '+(loaderOnly?'the dynamic session loader':'the session readiness promise')+' before resolving its private source',async()=>{
    const fixture=await mountedWorkspace('analysis',loaderOnly);
    assert.equal(fixture.scopes.some(scope=>scope.workspaceScopeKey==='__shared__'),false,'A pending session is not a confirmed shared source');
    await fixture.ready();
    assert.equal(fixture.context.TaxonomyArchitectureWorkspace.getSource().workspaceScopeKey,'A');
    assert.equal(fixture.metadata.length,1);assert.equal(fixture.metadata[0].workspaceScopeKey,'A');
    assert.equal(fixture.scopes.at(-1).branch,'analysis-branch');
});
test('opening Analyze from an initial working source resolves the independent retained analysis session',async()=>{
    const fixture=await mountedWorkspace('working');
    fixture.activatePage('analyze');await fixture.ready();
    assert.equal(fixture.metadata.length,1);
    assert.equal(fixture.scopes.at(-1).workspaceScopeKey,'A');
    assert.equal(fixture.scopes.at(-1).repositoryId,'analysis-repo');
});

test('cached metadata completed after leaving analysis restores repository and branch on return',async()=>{
    let complete;
    const fixture=await mountedWorkspace('analysis',false,{overview:()=>new Promise(resolve=>complete=resolve)});
    await fixture.ready();assert.equal(fixture.metadata.length,1);
    await fixture.context.TaxonomyArchitectureWorkspace.open('working',{source:{workspaceScopeKey:'B'}});
    complete({context:{repositoryId:'analysis-repo',branch:'analysis-branch',workspaceScopeKey:'A'}});await fixture.settle();
    await fixture.context.TaxonomyArchitectureWorkspace.open('analysis');
    assert.equal(fixture.metadata.length,1,'Retained metadata can be reused without another request');
    assert.equal(fixture.context.TaxonomyArchitectureWorkspace.getSource().repositoryId,'analysis-repo');
    assert.equal(fixture.context.TaxonomyArchitectureWorkspace.getSource().branch,'analysis-branch');
    assert.equal(fixture.scopes.at(-1).workspaceScopeKey,'A');
});

test('analysis business-source pins survive returning from its architecture to Analyze',async()=>{
    const fixture=await mountedWorkspace('analysis',false,{query:'&projectId=42&requirementId=7&requirementVersionId=9'});
    await fixture.ready();fixture.activatePage('analyze');await fixture.settle();
    assert.equal(fixture.scopes.at(-1).projectId,'42');
    assert.equal(fixture.scopes.at(-1).requirementId,'7');
    assert.equal(fixture.scopes.at(-1).requirementVersionId,'9');
    assert.equal(fixture.scopes.at(-1).branch,'analysis-branch');
});

async function importTransport(fixture){
    const requests=[];
    fixture.context.TaxonomyApiClient={request:async(path,options,policy)=>{
        requests.push({url:new URL(path,fixture.context.location.href),options,policy});return {json:async()=>({})};
    }};
    fixture.context.__TaxonomyAnalysisSessionContext={runtime:{workspaceId:'A'}};
    vm.runInContext(await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-api-routing.js',import.meta.url),'utf8'),fixture.context);
    vm.runInContext(await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/import-api.js',import.meta.url),'utf8'),fixture.context);
    return {requests,preview:()=>fixture.context.ImportApi.previewArchiReturn({original:'catalogue',returned:'Archi'}),
        apply:()=>fixture.context.ImportApi.applyArchiReturn({original:'catalogue',returned:'Archi',accepted:true})};
}
function assertImportDestination(request,{repositoryId,workspaceId,branch}){
    for(const [key,value] of Object.entries({repositoryId,workspaceId,branch})){
        assert.equal(request.url.searchParams.has(key),value!==undefined,'Explicit presence of '+key);
        if(value!==undefined)assert.equal(request.url.searchParams.get(key),value,key);
    }
    assert.equal(request.options.headers['X-Taxonomy-Workspace-Id'],workspaceId);
    assert.equal(request.policy.pinnedWorkspaceId,workspaceId);
}
for(const workspaceId of ['', 'destination-private'])test('Archi return keeps the explicit '+(workspaceId||'shared')+' destination while retained analysis keeps its own source',async()=>{
    const destination={repositoryId:'destination-repo',workspaceId,branch:'destination-branch'};
    const query='&'+new URLSearchParams({...destination,projectId:'42',requirementId:'7',requirementVersionId:'9',lang:'de'});
    const fixture=await mountedWorkspace('analysis',false,{page:'analyze',query});
    const transport=await importTransport(fixture),evidence=fixture.context.TaxonomyState.currentArchView;
    await fixture.ready();await transport.preview();
    fixture.activatePage('architecture');await fixture.settle();
    await transport.preview();await transport.apply();
    const workspace=fixture.context.TaxonomyArchitectureWorkspace;
    assert.equal(workspace.getSource().workspaceScopeKey,'A');
    assert.equal(workspace.getSource().repositoryId,'analysis-repo');
    assert.equal(workspace.getData(),evidence);
    assert.equal(fixture.scopes.at(-1).workspaceScopeKey,'A','Workflow links must describe the displayed analysis');
    assert.equal(fixture.headers.at(-1).owner,'analysis');
    assert.equal(fixture.headers.at(-1).value.architecture.workspaceScopeKey,'A');
    assert.equal(fixture.headers.at(-1).value.architecture.repositoryId,'analysis-repo');
    await workspace.open('working');await workspace.open('analysis');
    await transport.preview();
    assert.equal(workspace.getData(),evidence);
    assert.equal(workspace.getSource().workspaceScopeKey,'A');
    assert.equal(fixture.context.TaxonomyAnalysisSession.state().version,7);
    assert.equal(workspace.getData().requestPayloadHash,'retained-analysis-hash');
    fixture.activatePage('analyze');await fixture.settle();await transport.preview();
    for(const request of transport.requests)assertImportDestination(request,destination);
    assert.equal(fixture.context.location.searchParams.get('lang'),'de');
    assert.equal(fixture.context.location.searchParams.get('projectId'),'42');
    assert.equal(fixture.context.location.searchParams.get('requirementId'),'7');
    assert.equal(fixture.context.location.searchParams.get('requirementVersionId'),'9');
    assert.equal(fixture.metadata.every(scope=>scope.workspaceScopeKey==='A'),true,'Import destinations must not relabel or reload the retained analysis');
});
test('an unpinned shell resolves its private analysis and never invents a shared import destination',async()=>{
    const fixture=await mountedWorkspace('analysis',false,{page:'analyze'}),transport=await importTransport(fixture);
    await fixture.ready();await transport.preview();
    assertImportDestination(transport.requests[0],{repositoryId:'analysis-repo',workspaceId:'A',branch:'analysis-branch'});
});
test('an explicit repository-only destination leaves the missing workspace pin to existing session routing',async()=>{
    const fixture=await mountedWorkspace('analysis',false,{page:'analyze',query:'&repositoryId=destination-repo&branch=main'}),transport=await importTransport(fixture);
    await fixture.ready();await transport.preview();
    const request=transport.requests[0];
    assert.equal(request.url.searchParams.get('repositoryId'),'destination-repo');
    assert.equal(request.url.searchParams.get('branch'),'main');
    assert.equal(request.url.searchParams.get('workspaceId'),'A','Absent pins retain ordinary private-session routing');
    assert.equal(request.policy.pinnedWorkspaceId,undefined);
    assert.equal(request.options.headers['X-Taxonomy-Workspace-Id'],undefined);
    assert.equal(fixture.context.location.searchParams.has('workspaceId'),false);
    assert.equal(fixture.context.location.searchParams.has('workspaceScopeKey'),false);
});
test('accepted history targets replace explicit import pins and clearing them restores the actual analysis source',async()=>{
    const fixture=await mountedWorkspace('analysis',false,{query:'&repositoryId=first-repo&workspaceId=first&branch=first'}),transport=await importTransport(fixture);
    await fixture.ready();
    for(const workspaceId of ['', 'next-private']){
        await fixture.restoreHistory('/?architectureSource=analysis&repositoryId=next-repo&workspaceId='+workspaceId+'&branch=next#architecture');
        await transport.preview();
        assertImportDestination(transport.requests.at(-1),{repositoryId:'next-repo',workspaceId,branch:'next'});
        assert.equal(fixture.context.TaxonomyArchitectureWorkspace.getSource().workspaceScopeKey,'A');
        assert.equal(fixture.scopes.at(-1).workspaceScopeKey,'A');
    }
    await fixture.restoreHistory('/?architectureSource=analysis#architecture');await transport.preview();
    assertImportDestination(transport.requests.at(-1),{repositoryId:'analysis-repo',workspaceId:'A',branch:'analysis-branch'});
});
test('a declined source change cannot replace the retained import destination',async()=>{
    const fixture=await mountedWorkspace('analysis',false,{query:'&repositoryId=destination-repo&workspaceId=&branch=main'}),transport=await importTransport(fixture);
    await fixture.ready();const workspace=fixture.context.TaxonomyArchitectureWorkspace;
    await workspace.open('working');fixture.context.editorCanLeave=false;
    await fixture.restoreHistory('/?architectureSource=analysis&repositoryId=declined-repo&workspaceId=declined&branch=declined#architecture');
    assert.equal(workspace.getSource().kind,'working');
    assert.equal(fixture.context.location.searchParams.get('workspaceId'),'B');
    fixture.context.editorCanLeave=true;await workspace.open('analysis');await transport.preview();
    assertImportDestination(transport.requests[0],{repositoryId:'destination-repo',workspaceId:'',branch:'main'});
});
test('same-page Analyze history adopts its explicit destination and can clear that selection',async()=>{
    const fixture=await mountedWorkspace('analysis',false,{page:'analyze',query:'&repositoryId=first-repo&workspaceId=first&branch=first'}),transport=await importTransport(fixture);
    await fixture.ready();
    await fixture.restoreHistory('/?architectureSource=analysis&repositoryId=next-repo&workspaceId=&branch=next#analyze');
    fixture.activatePage('analyze');await fixture.settle();await transport.preview();
    assertImportDestination(transport.requests.at(-1),{repositoryId:'next-repo',workspaceId:'',branch:'next'});
    fixture.activatePage('architecture');await fixture.settle();await transport.preview();
    assertImportDestination(transport.requests.at(-1),{repositoryId:'next-repo',workspaceId:'',branch:'next'});
    assert.equal(fixture.context.TaxonomyArchitectureWorkspace.getSource().workspaceScopeKey,'A');
    await fixture.restoreHistory('/?architectureSource=analysis#analyze');
    fixture.activatePage('analyze');await fixture.settle();await transport.preview();
    assertImportDestination(transport.requests.at(-1),{repositoryId:'analysis-repo',workspaceId:'A',branch:'analysis-branch'});
});
test('a rejected history departure from Working to Analyze cannot replace the previous import destination',async()=>{
    const fixture=await mountedWorkspace('analysis',false,{query:'&repositoryId=destination-repo&workspaceId=&branch=main'}),transport=await importTransport(fixture);
    await fixture.ready();const workspace=fixture.context.TaxonomyArchitectureWorkspace;
    await workspace.open('working');fixture.context.editorCanLeave=false;
    await fixture.restoreHistory('/?architectureSource=analysis&repositoryId=declined-repo&workspaceId=declined&branch=declined#analyze');
    assert.equal(await workspace.canLeave(),false);
    workspace.restoreUrl();
    assert.equal(workspace.getSource().kind,'working');
    assert.equal(fixture.context.location.searchParams.get('workspaceId'),'B');
    fixture.context.editorCanLeave=true;await workspace.open('analysis');await transport.preview();
    assertImportDestination(transport.requests[0],{repositoryId:'destination-repo',workspaceId:'',branch:'main'});
});
