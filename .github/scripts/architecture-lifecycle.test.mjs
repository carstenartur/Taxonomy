import test, {afterEach} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import canonicalFixture from './canonical-api-fixture.cjs';

afterEach(() => canonicalFixture.dispose());

const apiSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/architecture-lifecycle-api.js', import.meta.url), 'utf8');
const lifecycleSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-lifecycle.js', import.meta.url), 'utf8');
const copy = value => JSON.parse(JSON.stringify(value));
const originalContext = {repositoryId:'architecture', workspaceScopeKey:'copy', branch:'draft', commit:'a'.repeat(40), revision:2, actor:'owner', writeMode:'PRIVATE_WORKSPACE'};

async function fixture(firstOutcome='unknown', editor=false) {
    const writes = [];
    const handlers = new Map();
    let activeScope = copy(originalContext);
    let current = overview(originalContext);
    let chrome = {};
    const location = new URL('https://example.test/projects/1/requirements/2?repositoryId=architecture&workspaceId=copy&branch=draft');
    const document = {
        getElementById(id) { return editor&&id==='architectureEditor'?{}:null; },
        addEventListener(name, handler) {
            if (!handlers.has(name)) handlers.set(name, []);
            handlers.get(name).push(handler);
        },
        dispatchEvent(event) { for (const handler of handlers.get(event.type) || []) handler(event); }
    };
    const context = vm.createContext({URL, URLSearchParams, location, document, crypto:{randomUUID},
        CustomEvent:class { constructor(type, options) { this.type=type; this.detail=options?.detail; } },
        addEventListener() {},
        history:{replaceState(_state, _title, url) { location.href=String(url); }},
        TaxonomyI18n:{t:key=>key, ready:()=>Promise.resolve()},
        TaxonomyAppChrome:{getContext:()=>chrome, updateContext() {}, refreshNavigation() {}},
        TaxonomyArchitectureWorkflow:{getScope:()=>copy(activeScope), setScope:value=>{activeScope=copy(value);}},
        TaxonomyApiClient:{
            getJson:async()=>copy(current),
            request:async(url, options)=>{
                writes.push({url,options:copy(options)});
                if (writes.length===1) {
                    if (firstOutcome==='unknown') throw Object.assign(new Error('The accepted response was lost'), {status:503});
                    if (firstOutcome==='completed')return {json:async()=>({projectId:1,requirementId:2,publication:{context:copy(current.context),state:'CHECKPOINTED'}})};
                    const publicationState=firstOutcome==='reconciliation'?'RECONCILIATION_REQUIRED':'CHECKPOINT_PENDING';
                    current=overview({...originalContext,revision:3},publicationState);
                    return {json:async()=>({projectId:1,requirementId:2,publication:{context:copy(current.context),state:publicationState}})};
                }
                return {json:async()=>({projectId:1,requirementId:2,publication:{context:copy(current.context),state:'CHECKPOINTED'}})};
            }
        }
    });
    context.window=context;
    vm.runInContext(apiSource,context);
    vm.runInContext(lifecycleSource,context);
    await context.TaxonomyArchitectureLifecycle.refresh(originalContext,null);
    const command={choice:'CHANGE',projectId:1,requirementId:2,expectedVersionId:12,expectedContentHash:'original-text-hash',
        inputHash:'submitted-text-hash',requirement:{text:'The reviewed text',changeReason:'The original rationale'}};
    return {
        lifecycle:context.TaxonomyArchitectureLifecycle, command, writes,
        deferRead() { let resolve;context.TaxonomyApiClient.getJson=()=>new Promise(r=>resolve=r);return value=>resolve(value); },
        setScope:value=>{activeScope=copy(value);}, getScope:()=>copy(activeScope),
        async changeStand(contextValue, projection='READY', source='WORKSPACE_REVISION') {
            current=overview(contextValue,projection,source);
            await context.TaxonomyArchitectureLifecycle.refresh(contextValue, projection==='HISTORICAL'?contextValue.revision:null);
        },
        changeSubject() {
            chrome={project:{id:99,key:'OTHER'},requirement:{id:2,key:'REQ-2'}};
            document.dispatchEvent({type:'taxonomy:entity-context-changed'});
        }
    };
}

function overview(context, projection='READY', source='WORKSPACE_REVISION') {
    return {context:copy(context), source, projectionState:projection, mayEdit:projection==='READY', projects:[], requirements:[], dependencies:[]};
}

// Real canonical delivery and the real domain fence must agree: a completed exact
// command still has a receipt after navigation, without applying it to the new stand.
async function receiptFixture() {
    const events = [], handlers = new Map(), windowHandlers = new Map(), writes = [], timers = new Map();
    let timerId = 0, release, current = overview(originalContext), activeScope = copy(originalContext);
    let chrome = {architecture:{repositoryId:'architecture',workspaceScopeKey:'copy',branch:'draft',modelStand:'revision 2'}};
    const location = new URL('https://example.test/projects/1/requirements/2?repositoryId=architecture&workspaceId=copy&branch=draft');
    const document = {
        getElementById: () => null,
        addEventListener(name, handler) {
            if (!handlers.has(name)) handlers.set(name, []);
            handlers.get(name).push(handler);
        },
        dispatchEvent(event) { events.push(event); for (const handler of handlers.get(event.type) || []) handler(event); return true; }
    };
    const context = {
        URL, URLSearchParams, location, document, console, ReadableStream,
        crypto:{randomUUID}, __TaxonomyAnalysisSessionContext:{runtime:{workspaceId:'copy',analysisGeneration:0}},
        history:{replaceState(_state, _title, url) {location.href=String(url);}},
        TaxonomyI18n:{t:key=>key,ready:()=>Promise.resolve()},
        TaxonomyAppChrome:{getContext:()=>chrome,updateContext() {},refreshNavigation() {}},
        TaxonomyArchitectureWorkflow:{getScope:()=>copy(activeScope),setScope:value=>{activeScope=copy(value);}},
        addEventListener(name, handler) {
            if (!windowHandlers.has(name)) windowHandlers.set(name, []);
            windowHandlers.get(name).push(handler);
        },
        setTimeout(callback, delay) {const id=++timerId;timers.set(id,{callback,delay});return id;},
        clearTimeout(id) {timers.delete(id);},
        fetch: async (input, init) => {
            const url = new URL(String(input), location);
            if (url.pathname === '/api/account/me') return Response.json({error:'Authentication required'}, {status:403});
            if (init.method === 'POST') {
                writes.push({url,init});
                return new Promise(resolve=>{release=resolve;});
            }
            return Response.json(copy(current));
        }
    };
    context.window=context;
    canonicalFixture.install(context);
    vm.runInContext(apiSource,context);
    vm.runInContext(lifecycleSource.replace(/\}\(\)\);\s*$/, 'window.receiptDomain={captureStand,intentFence};}());'),context);
    await context.TaxonomyArchitectureLifecycle.refresh(originalContext,null);
    const shell={message:{textContent:'',querySelector:()=>null},dialog:{addEventListener() {},close(){shell.closed++;}},closed:0};
    const fence=context.receiptDomain.intentFence(context.receiptDomain.captureStand(),shell,{}, {},true);
    const request={context:copy(originalContext),metadata:{commandId:'00000000-0000-4000-8000-000000000123',correlationId:'00000000-0000-4000-8000-000000000123',causationId:'00000000-0000-4000-8000-000000000123',rationale:'Reviewed'},
        choice:'CHANGE',projectId:1,requirementId:2,requirement:{text:'The reviewed requirement',changeReason:'Reviewed'}};
    return {
        context,events,writes,timers,shell,fence,request,location,
        api:context.TaxonomyArchitectureLifecycleApi,lifecycle:context.TaxonomyArchitectureLifecycle,
        release:response=>release(response),
        async selectHistorical() {
            location.searchParams.set('revision','1');
            chrome={architecture:{...chrome.architecture,modelStand:'revision 1'}};
            document.dispatchEvent({type:'taxonomy:entity-context-changed'});
            current=overview({...originalContext,revision:1},'HISTORICAL');
            await context.TaxonomyArchitectureLifecycle.refresh(current.context,1);
        },
        pagehide() {for (const listener of windowHandlers.get('pagehide') || []) listener({type:'pagehide'});}
    };
}
const flushReceipt = () => new Promise(resolve=>setImmediate(resolve));
const publishedReceipt = () => ({requirementId:2,requirement:{id:2},publication:{state:'CHECKPOINTED',context:{...copy(originalContext),revision:3,commit:'b'.repeat(40)}}});

test('editor-owned completed commands do not require standalone publication proof for the next admitted intent',async()=>{
    const h=await fixture('completed',true);
    await h.lifecycle.saveRequirement(h.command);
    const observed={...originalContext,revision:3,commit:'b'.repeat(40)};
    await h.changeStand(observed);
    const next={...h.command,context:observed,metadata:{...h.command.metadata,commandId:randomUUID()}};
    await h.lifecycle.saveRequirement(next);
    assert.equal(h.writes.length,2,'The editor owns its source confirmation; a completed command is not a pending standalone operation');
    assert.notEqual(JSON.parse(h.writes[0].options.body).metadata.commandId,JSON.parse(h.writes[1].options.body).metadata.commandId);
    assert.equal(h.writes[1].options.headers['If-Match'],'"workspace-revision-3"');
});

for (const method of ['acceptReview','acceptReconciliation','saveRequirement']) {
    test(`admitted ${method} returns its exact receipt after historical navigation without applying the old stand`,async()=>{
        const h=await receiptFixture();
        const operation=method==='saveRequirement' ? h.lifecycle.saveRequirement(h.request) : h.api[method](h.request);
        const settled=operation.then(value=>({value}),error=>({error}));
        await flushReceipt();
        assert.equal(h.writes.length,1);
        await h.selectHistorical();
        const selectedUrl=h.location.href;
        h.release(Response.json(publishedReceipt()));
        const result=await settled;
        assert.ifError(result.error);
        if(method!=='saveRequirement')h.fence.afterSuccess(result.value,false);
        assert.equal(result.value.publication.context.revision,3);
        assert.equal(h.lifecycle.state().context.revision,1);
        assert.equal(h.lifecycle.state().projectionState,'HISTORICAL');
        assert.equal(h.location.href,selectedUrl);
        assert.equal(h.shell.closed,0);
        if(method!=='saveRequirement')assert.equal(h.shell.message.textContent,'lifecycle.saved lifecycle.stale');
        assert.equal(h.events.filter(event=>event.type==='taxonomy:lifecycle-changed').length,0);
        const sent=h.writes[0];
        assert.equal(sent.init.signal.aborted,false,'Navigation does not cancel an already admitted exact mutation acknowledgement');
        assert.equal(sent.url.searchParams.get('workspaceId'),'copy');
        assert.equal(new Headers(sent.init.headers).get('X-Taxonomy-Workspace-Id'),'copy');
        assert.equal(new Headers(sent.init.headers).get('If-Match'),'"workspace-revision-2"');
        assert.deepEqual(JSON.parse(sent.init.body).context,originalContext);
        assert.equal(JSON.parse(sent.init.body).metadata.commandId,h.request.metadata.commandId);
        assert.equal(h.writes.length,1,'A non-idempotent command must never be automatically retried');
    });
}

test('exact mutation acknowledgement keeps the guard through a delayed response body',async()=>{
    const h=await receiptFixture();let body;
    const operation=h.api.acceptReview(h.request).then(value=>({value}),error=>({error}));
    await flushReceipt();
    h.release(new Response(new ReadableStream({start(controller){body=controller;}}),{headers:{'Content-Type':'application/json'}}));
    await flushReceipt();await h.selectHistorical();
    body.enqueue(new TextEncoder().encode(JSON.stringify(publishedReceipt())));body.close();
    const result=await operation;
    assert.ifError(result.error);assert.equal(result.value.publication.context.revision,3);
    h.fence.afterSuccess(result.value,false);
    assert.equal(h.shell.message.textContent,'lifecycle.saved lifecycle.stale');
    assert.equal(h.lifecycle.state().context.revision,1);
});

for(const method of ['previewReview','previewReconciliation']) {
    test(`${method} remains source-sensitive while an exact command acknowledgement may survive navigation`,async()=>{
        const h=await receiptFixture();
        const operation=h.api[method](h.request);
        const rejection=assert.rejects(operation,error=>error.code==='ABORTED');rejection.catch(()=>{});
        await flushReceipt();await h.selectHistorical();h.release(Response.json({canAccept:true}));
        await rejection;
        assert.equal(h.writes[0].init.signal.aborted,true);
    });
}

for(const cancellation of ['authentication','pagehide','deadline']) {
    test(`admitted mutation receipt still rejects ${cancellation} invalidation`,async()=>{
        const h=await receiptFixture();
        const operation=h.api.acceptReview(h.request);
        const rejection=assert.rejects(operation,error=>error.code===(cancellation==='deadline'?'TIMEOUT':'ABORTED'));rejection.catch(()=>{});
        await flushReceipt();
        if(cancellation==='authentication')await assert.rejects(h.context.TaxonomyApiClient.getJson('/api/account/me'),error=>error.status===403);
        else if(cancellation==='pagehide')h.pagehide();
        else for(const timer of [...h.timers.values()])timer.callback();
        h.release(Response.json(publishedReceipt()));await rejection;
        assert.equal(h.writes[0].init.signal.aborted,true);
        assert.equal(h.shell.closed,0);
    });
}

test('suspending working lifecycle prevents a late overview from restoring departed scope',async()=>{
    const value=await fixture();const resolve=value.deferRead();
    const pending=value.lifecycle.refresh(originalContext,null);
    value.lifecycle.suspend();
    value.setScope({repositoryId:'snapshot',workspaceScopeKey:'snapshot-B',branch:'b'});
    resolve(overview(originalContext));await pending;
    assert.equal(value.getScope().workspaceScopeKey,'snapshot-B');
    assert.equal(await value.lifecycle.refresh(originalContext,null),null,'Hidden lifecycle must not start another request');
});

test('an unknown accepted requirement outcome replays its original command after a current stand read', async () => {
    const {lifecycle,command,writes,changeStand}=await fixture();
    await assert.rejects(lifecycle.saveRequirement(command), /accepted response was lost/);
    assert.equal(command.context.revision,2);
    assert.ok(command.metadata.commandId);
    await changeStand({...originalContext,revision:3,commit:'b'.repeat(40)});
    await lifecycle.saveRequirement(command);
    assert.equal(writes.length,2);
    assert.equal(writes[1].options.body,writes[0].options.body);
    assert.equal(writes[1].url,writes[0].url);
    for (const write of writes) assert.equal(write.options.headers['If-Match'],'"workspace-revision-2"');
});

test('a confirmed pending requirement replays its original command after a later current checkpoint', async () => {
    const {lifecycle,command,writes,changeStand}=await fixture('pending');
    const first=await lifecycle.saveRequirement(command);
    assert.equal(first.publication.state,'CHECKPOINT_PENDING');
    await changeStand({...originalContext,revision:4,commit:'c'.repeat(40)});
    await lifecycle.saveRequirement(command);
    assert.equal(writes.length,2);
    assert.equal(writes[1].options.body,writes[0].options.body);
    assert.equal(writes[1].url,writes[0].url);
    for (const write of writes) assert.equal(write.options.headers['If-Match'],'"workspace-revision-2"');
});

test('an accepted requirement awaiting reconciliation retains its exact command until publication is confirmed', async () => {
    const {lifecycle,command,writes,changeStand}=await fixture('reconciliation');
    const first=await lifecycle.saveRequirement(command);
    assert.equal(first.publication.state,'RECONCILIATION_REQUIRED');
    await changeStand({...originalContext,revision:4,commit:'c'.repeat(40)});
    await lifecycle.saveRequirement(command);
    assert.equal(writes.length,2);
    assert.equal(writes[1].options.body,writes[0].options.body);
    assert.equal(writes[1].url,writes[0].url);
    for (const write of writes) assert.equal(write.options.headers['If-Match'],'"workspace-revision-2"');
});

for (const outcome of ['unknown','pending']) {
  for (const selection of ['historical','repository','workspace','branch','actor','source','subject']) {
    test(`a ${outcome} requirement outcome cannot replay after changing ${selection}`, async () => {
        const value=await fixture(outcome);
        if (outcome==='unknown') await assert.rejects(value.lifecycle.saveRequirement(value.command), /accepted response was lost/);
        else assert.equal((await value.lifecycle.saveRequirement(value.command)).publication.state,'CHECKPOINT_PENDING');
        const next={...originalContext,revision:3};
        if (selection==='repository') next.repositoryId='another-architecture';
        if (selection==='workspace') next.workspaceScopeKey='another-copy';
        if (selection==='branch') next.branch='another-branch';
        if (selection==='actor') next.actor='another-owner';
        if (selection==='historical') next.revision=1;
        await value.changeStand(next,selection==='historical'?'HISTORICAL':'READY',selection==='source'?'GIT_CHECKPOINT':'WORKSPACE_REVISION');
        if (selection==='subject') value.changeSubject();
        await assert.rejects(value.lifecycle.saveRequirement(value.command), /lifecycle.stale/);
        assert.equal(value.writes.length,1);
    });
  }
}
