import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
import fixture from './canonical-api-fixture.cjs';
const root = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const settle = () => new Promise(resolve => setImmediate(resolve));
function harness(fetch) {
  const nodes = new Map(), events = new EventTarget(), alerts = [];
  const node = id => {if (!nodes.has(id)) nodes.set(id, {value:'', innerHTML:'', classList:{remove(){},add(){}}, setAttribute(){}, addEventListener(){}}); return nodes.get(id);};
  const context = {window:{fetch, location:{href:'https://example.test/taxonomy/',origin:'https://example.test',pathname:'/taxonomy/'},
    __TaxonomyAnalysisSessionContext:{runtime:{workspaceId:'original',analysisGeneration:1}},
    addEventListener:events.addEventListener.bind(events)}, document:{getElementById:node,querySelector(){return null;},addEventListener(){},dispatchEvent(){return true;}},
    TaxonomyI18n:{t:key=>key},TaxonomyUtils:{escapeHtml:String}, console, alert:value=>alerts.push(value),confirm:()=>true,
    bootstrap:{Modal:class {show(){} static getInstance(){return {hide(){}};}}}};
  fixture.install(context, ['api/workspace-api.js','api/workspace-provisioning-api.js']);
  context.fetch = (...args) => context.window.fetch(...args);
  return {context, node, alerts, load(path){vm.runInContext(readFileSync(new URL(path,root),'utf8'),context);},
    change(){context.window.__TaxonomyAnalysisSessionContext.runtime.workspaceId='other';}, hide(){events.dispatchEvent(new Event('pagehide'));}};
}
for(const status of [401,403]) test(`provisioning routes central HTTP ${status} through authentication`,async()=>{
 const h=harness(async()=>new Response('{}',{status}));
 await assert.rejects(h.context.window.TaxonomyWorkspaceProvisioningApi.provisionWorkspace(),error=>error.status===status);
});
test('provisioning preserves domain status/body and exact optimistic concurrency',async()=>{
 let seen;const h=harness(async(url,init)=>{seen={url,init};return new Response('{"message":"conflict"}',{status:412});});
 const result=await h.context.window.TaxonomyWorkspaceProvisioningApi.rebuildProjection('abc123');
 assert.equal(result.status,412);assert.equal(result.body.message,'conflict');assert.equal(new Headers(seen.init.headers).get('If-Match'),'"abc123"');
 assert.equal(seen.init.headers.get('X-Taxonomy-Workspace-Id'),'original');
});
test('named versioning owns encoded routes and preserves domain conflict statuses',async()=>{
 const calls=[];const h=harness(async(url,init)=>{calls.push({url,init});return new Response('{}',{status:409});});
 const api=h.context.window.TaxonomyVersioningApi;
 await api.restore('commit/a','branch/b');await api.copyBackApply({selectedElementIds:['A']},'context/c');
 await api.resolveMerge('from/a','into/b','plain DSL');
 assert.equal(new URL(calls[0].url,'https://example.test').searchParams.get('commitId'),'commit/a');
 assert.equal(new URL(calls[1].url,'https://example.test').searchParams.get('expectedContextId'),'context/c');
 assert.equal(calls[2].init.body,'plain DSL');assert.equal(calls[2].init.headers.get('Content-Type'),'text/plain');
});
test('variant integration binds both branch names accepted by the merge controller',async()=>{
 const calls=[];const h=harness(async(url,init)=>{
  const request=new URL(url,'https://example.test');calls.push({request,init});
  const fromBranch=request.searchParams.get('fromBranch'),intoBranch=request.searchParams.get('intoBranch');
  return new Response(JSON.stringify({fromBranch,intoBranch,commitId:'merged'}),{status:fromBranch&&intoBranch?200:400});
 });
 const response=await h.context.window.TaxonomyVersioningApi.merge('variant/a & b','target/review');
 assert.equal(response.status,200,'DslApiController requires fromBranch and binds intoBranch');
 assert.deepEqual(await response.json(),{fromBranch:'variant/a & b',intoBranch:'target/review',commitId:'merged'});
 assert.equal(calls.length,1);assert.equal(calls[0].init.method,'POST');
 assert.equal(calls[0].request.pathname,'/api/dsl/merge');
 assert.equal(calls[0].request.searchParams.get('workspaceId'),'original');
 assert.equal(calls[0].request.searchParams.has('from'),false);
 assert.equal(calls[0].request.searchParams.has('into'),false);
});
for(const status of [200,500]) test(`copy-back preview never paints an obsolete workspace HTTP ${status}`,async()=>{
 let respond;const h=harness(()=>new Promise(resolve=>respond=resolve));
 h.load('versioning/taxonomy-context-transfer.js');const api=h.context.window.TaxonomyContextTransfer;
 api.showDialog('source','target','ctx');api.previewTransfer();await settle();h.change();
 respond(new Response('{"selectedElements":1,"selectedRelations":0,"conflicts":[]}',{status}));await settle();await settle();
 assert.equal(h.node('transferPreviewResults').innerHTML,'');assert.deepEqual(h.alerts,[]);
});
test('a newer copy-back dialog owns preview even within the same workspace',async()=>{
 const pending=[];const h=harness(()=>new Promise(resolve=>pending.push(resolve)));h.load('versioning/taxonomy-context-transfer.js');
 const api=h.context.window.TaxonomyContextTransfer;api.showDialog('s','t','ctx1');api.previewTransfer();await settle();
 api.showDialog('s2','t2','ctx2');pending[0](new Response('{"selectedElements":1,"selectedRelations":0,"conflicts":[]}'));await settle();await settle();
 assert.equal(h.node('transferPreviewResults').innerHTML,'');
});
for(const status of [401,403]) test(`copy-back authorization HTTP ${status} leaves feature error surface alone`,async()=>{
 const h=harness(async()=>new Response('{}',{status}));h.load('versioning/taxonomy-context-transfer.js');
 h.context.window.TaxonomyContextTransfer.showDialog('s','t','ctx');
 await h.context.window.TaxonomyContextTransfer.applyTransfer();await settle();assert.deepEqual(h.alerts,[]);
});
test('named state read carries explicit workspace pin ahead of live runtime',async()=>{
 let init;const h=harness(async(url,options)=>{init=options;return new Response('{}');});
 await h.context.window.TaxonomyWorkspaceApi.current({pinnedWorkspaceId:'explicit'});
 assert.equal(init.headers.get('X-Taxonomy-Workspace-Id'),'explicit');
});
for(const status of [200,500,403]) test(`newer version timeline keeps its busy state when obsolete HTTP ${status} arrives`,async()=>{
 const pending=[];const h=harness((url)=>new Promise(resolve=>pending.push({url,resolve})));
 h.load('versioning/taxonomy-versions.js');const api=h.context.window.TaxonomyVersions;
 api.loadTimeline();await settle();api.loadTimeline();await settle();
 const histories=pending.filter(x=>new URL(x.url,'https://example.test').pathname.endsWith('/dsl/history'));
 const busy=h.node('versionsTimeline').innerHTML;
 histories[0].resolve(new Response('{"commits":[]}',{status}));await settle();await settle();
 assert.equal(h.node('versionsTimeline').innerHTML,busy);
 histories[1].resolve(new Response('{"commits":[]}'));await settle();await settle();
 if(status===403) assert.equal(h.node('versionsTimeline').innerHTML,busy, 'Authorization invalidates every pending read');
 else assert.ok(h.node('versionsTimeline').innerHTML.includes('versions.history.none'));
 // Finish companion branch reads so the fixture retains no dangling deadlines.
 for(const call of pending.filter(x=>!histories.includes(x)))call.resolve(new Response('{"branches":[]}'));
 await settle();
});
for(const status of [200,500,403]) test(`an obsolete merge-conflict read HTTP ${status} cannot replace current conflict UI`,async()=>{
 const pending=[],messages=[];const h=harness(()=>new Promise(resolve=>pending.push(resolve)));
 h.context.window.TaxonomyOperationResult={showWarning:(...x)=>messages.push(x),showError:(...x)=>messages.push(x)};
 h.load('workspace/taxonomy-merge-resolution.js');const api=h.context.window.TaxonomyMergeResolution;
 api.showMergeConflict('old','draft');await settle();api.showMergeConflict('new','draft');await settle();
 pending[0](new Response('{"conflict":false}',{status}));await settle();await settle();assert.deepEqual(messages,[]);
 pending[1](new Response('{"conflict":false}'));await settle();await settle();assert.equal(messages.length,status===403?0:1, 'Authorization invalidates other pending reads too');
});

test('a completed save never erases newer title and description edits',async()=>{
 const pending=[];const h=harness((url)=>new Promise(resolve=>pending.push({url,resolve})));
 h.load('versioning/taxonomy-versions.js');h.node('versionTitle').value='Original title';h.node('versionDescription').value='Original description';
 try {
  h.context.window.TaxonomyVersions.saveVersion();await settle();
  pending[0].resolve(new Response('{"dslText":"element BP {}"}'));await settle();await settle();
  h.node('versionTitle').value='New human title';h.node('versionDescription').value='New human description';
  pending[1].resolve(new Response('{"commitId":"abcdef012345"}'));await settle();await settle();
  assert.equal(h.node('versionTitle').value,'New human title');assert.equal(h.node('versionDescription').value,'New human description');
 } finally {h.hide();}
});
