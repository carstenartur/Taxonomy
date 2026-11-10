import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import canonicalFixture from './canonical-api-fixture.cjs';
const root=new URL('../../taxonomy-app/src/main/resources/static/js/',import.meta.url);
for(const basePath of ['', '/taxonomy'])for(const namedRouting of [false,true])test(`event streams retain exact workspace pins under ${basePath || '/'} (named routing ${namedRouting})`,async()=>{
    const calls=[],context=vm.createContext({URL,URLSearchParams});context.window=context;
    context.location={href:'https://example.test'+basePath+'/',origin:'https://example.test'};
    context.TaxonomyState={};
    context.TaxonomyI18n={getBasePath:()=>basePath,resolveUrl:value=>typeof value==='string'&&value.startsWith('/')&&!value.startsWith('//')
        &&basePath&&!value.startsWith(basePath+'/')?basePath+value:value};
    function NativeEventSource(url,options){this.url=String(url);this.options=options;calls.push(this);}
    Object.assign(NativeEventSource,{CONNECTING:0,OPEN:1,CLOSED:2});context.EventSource=NativeEventSource;
    vm.runInContext(await readFile(new URL('core/taxonomy-analysis-session-core.js',root),'utf8'),context);
    const C=context.__TaxonomyAnalysisSessionContext,install=C.installWorkspaceEventSourceRouting;
    if(namedRouting)vm.runInContext(await readFile(new URL('core/taxonomy-analysis-session-api-routing.js',root),'utf8'),context);
    assert.equal(C.installWorkspaceEventSourceRouting,install,'One module owns event-stream routing');
    C.runtime.workspaceId='analysis-A';install();install();
    const options={withCredentials:true};
    new context.EventSource('/api/analysis/events?last=4',options);
    new context.EventSource('/api/analysis/events?workspaceId=working-B');
    new context.EventSource('/api/analysis/events?workspaceId=');
    new context.EventSource('https://other.example/api/events');
    new context.EventSource('/health');
    const urls=calls.map(call=>new URL(call.url,context.location.href));
    assert.equal(urls[0].pathname,basePath+'/api/analysis/events');
    assert.equal(urls[0].searchParams.get('last'),'4');assert.equal(urls[0].searchParams.get('workspaceId'),'analysis-A');
    assert.equal(urls[1].searchParams.get('workspaceId'),'working-B');
    assert.equal(urls[2].searchParams.get('workspaceId'),'');
    assert.equal(urls[3].searchParams.has('workspaceId'),false);assert.equal(urls[4].searchParams.has('workspaceId'),false);
    assert.equal(calls[0].options,options);assert.equal(context.EventSource.OPEN,1);assert.ok(calls[0] instanceof NativeEventSource);
});
for(const selected of ['working-B','__shared__'])test(`architecture requests retain ${selected} while analysis stays in private A`,async()=>{
    const calls=[],context=vm.createContext({URL,URLSearchParams});context.window=context;
    context.location={href:'https://example.test/taxonomy/',origin:'https://example.test'};
    context.TaxonomyI18n={getBasePath:()=>'/taxonomy',resolveUrl:p=>p.startsWith('/taxonomy/')?p:'/taxonomy'+p,t:key=>key};
    context.__TaxonomyAnalysisSessionContext={runtime:{workspaceId:'analysis-A'}};
    context.TaxonomyApiClient={
        request:(url,options,policy)=>{calls.push({url,options,policy});return Promise.resolve({json:()=>Promise.resolve({})});},
        getJson:(url,options)=>{calls.push({url,options});return Promise.resolve({});},
        sendJson:(url,body)=>{calls.push({url,body});return Promise.resolve({});}
    };
    for(const file of ['core/taxonomy-analysis-session-api-routing.js','api/architecture-editor-api.js','api/architecture-lifecycle-api.js'])vm.runInContext(await readFile(new URL(file,root),'utf8'),context);
    const scope={repositoryId:'repo',workspaceScopeKey:selected,branch:'draft/b',revision:7};
    await context.ArchitectureEditorApi.load(scope,7);
    await context.ArchitectureEditorApi.preview({context:scope,kind:'CREATE_ELEMENT'});
    await context.ArchitectureEditorApi.createWorkspace(scope);
    await context.TaxonomyArchitectureLifecycleApi.overview(scope);
    await context.TaxonomyArchitectureLifecycleApi.previewReview({context:scope});
    for(const call of calls){
        const url=new URL(call.url,context.location.href);
        assert.equal(url.searchParams.get('workspaceId'),selected==='__shared__'?'':selected,call.url);
        assert.equal(url.searchParams.get('workspaceScopeKey'),selected,call.url);
    }
    assert.equal(context.__TaxonomyAnalysisSessionContext.runtime.workspaceId,'analysis-A');
});
for(const basePath of ['', '/taxonomy'])test(`snapshot fetch preserves explicit private/shared pins beneath ${basePath || '/'}`,async()=>{
    const calls=[],context=vm.createContext({URL,URLSearchParams,Headers,Request});context.window=context;
    context.location={href:'https://example.test'+basePath+'/',origin:'https://example.test'};
    context.TaxonomyState={};context.TaxonomyI18n={getBasePath:()=>basePath};
    context.fetch=async(url,options)=>{calls.push({url,headers:new Headers(options.headers)});return {ok:true};};
    vm.runInContext(await readFile(new URL('core/taxonomy-analysis-session-core.js',root),'utf8'),context);
    context.__TaxonomyAnalysisSessionContext.runtime.workspaceId='analysis-A';
    context.__TaxonomyAnalysisSessionContext.installWorkspaceFetchRouting();
    await context.fetch(basePath+'/api/projects/42/architecture-workbench/s-2?workspaceId=working-B');
    await context.fetch(basePath+'/api/projects/42/architecture-workbench/s-2?workspaceId=');
    await context.fetch(basePath+'/api/architecture/focus/suggestions',{headers:{'X-Taxonomy-Workspace-Id':'working-C'}});
    assert.deepEqual(calls.map(c=>c.headers.get('X-Taxonomy-Workspace-Id')),['working-B','','working-C']);
});


for (const [operation, change, announce, status, expected] of [
    ['execute', 'modelStand', false, 412, 'REVISION_MOVED'],
    ['execute', 'modelStand', true, 412, 'REVISION_MOVED'],
    ['execute', 'modelStand', true, 200, 'ABORTED'],
    ['execute', 'modelStand', true, 409, 'ABORTED'],
    ['execute', 'branch', false, 412, 'ABORTED'],
    ['execute', 'pagehide', true, 412, 'ABORTED'],
    ['execute', 'authentication', true, 412, 'ABORTED'],
    ['execute', 'workspaceScopeKey', true, 412, 'ABORTED'],
    ['preview', 'modelStand', false, 412, 'ABORTED'],
    ['previewAdoption', 'branch', true, 200, 'ABORTED']
]) test(`${operation} after ${change} change (announced ${announce}, HTTP ${status}) yields ${expected}`, async t => {
    const scope = {repositoryId:'repo', workspaceScopeKey:'private-a', branch:'draft', revision:7};
    const architecture = {...scope, modelStand:'revision 7'};
    const listeners = new Map(), windowListeners = new Map();
    let complete, sent;
    const context = {
        location:new URL('https://example.test/architecture/editor'),
        document:{
            addEventListener(type, handler) { listeners.set(type, handler); },
            dispatchEvent(event) { listeners.get(event.type)?.(event); }
        },
        addEventListener(type, handler) { windowListeners.set(type, handler); },
        TaxonomyAppChrome:{getContext:() => ({architecture})},
        fetch(url, init) {
            if (new URL(url, context.location).pathname === '/api/account/me') {
                return Promise.resolve(Response.json({message:'Authentication required'}, {status:403}));
            }
            sent = {url:new URL(url, context.location), init};
            return new Promise(resolve => {complete=resolve;});
        }
    };
    canonicalFixture.install(context, ['api/architecture-editor-api.js']);
    t.after(() => canonicalFixture.dispose());
    const result = context.ArchitectureEditorApi[operation]({context:scope, kind:'UPDATE_ELEMENT'})
        .then(value => ({value}), error => ({error}));
    assert.equal(sent.url.searchParams.get('workspaceId'), scope.workspaceScopeKey);
    assert.equal(new Headers(sent.init.headers).get('If-Match'), '"workspace-revision-7"');
    if (change === 'pagehide') {
        windowListeners.get('pagehide')({type:'pagehide'});
    } else if (change === 'authentication') {
        await assert.rejects(context.TaxonomyApiClient.getJson('/api/account/me'), error => error.status === 403);
    } else {
        architecture[change] = change === 'modelStand' ? 'revision 8' : 'departed';
        if (announce) context.document.dispatchEvent({type:'taxonomy:entity-context-changed'});
    }
    complete(Response.json(status === 412 ? {code:'REVISION_MOVED'} : {accepted:true}, {status}));
    const {error} = await result;
    assert.ok(error);
    if (expected === 'REVISION_MOVED') {
        assert.equal(error.status, 412);
        assert.equal(error.responseBody.code, expected);
    } else {
        assert.equal(error.code, expected);
    }
});
