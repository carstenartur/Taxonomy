import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';

const apiSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/architecture-lifecycle-api.js', import.meta.url), 'utf8');
const lifecycleSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-lifecycle.js', import.meta.url), 'utf8');
const copy = value => JSON.parse(JSON.stringify(value));
const originalContext = {repositoryId:'architecture', workspaceScopeKey:'copy', branch:'draft', commit:'a'.repeat(40), revision:2, actor:'owner', writeMode:'PRIVATE_WORKSPACE'};

async function fixture(firstOutcome='unknown') {
    const writes = [];
    const handlers = new Map();
    let activeScope = copy(originalContext);
    let current = overview(originalContext);
    let chrome = {};
    const location = new URL('https://example.test/projects/1/requirements/2?repositoryId=architecture&workspaceId=copy&branch=draft');
    const document = {
        getElementById() { return null; },
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
