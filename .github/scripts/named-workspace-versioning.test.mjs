import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
import fixture from './canonical-api-fixture.cjs';
const root = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const settle = () => new Promise(resolve => setImmediate(resolve));
function harness(fetch) {
  const nodes = new Map(), events = new EventTarget(), documentEvents = new EventTarget(), alerts = [];
  const node = id => {if (!nodes.has(id)) nodes.set(id, {value:'', innerHTML:'', classList:{remove(){},add(){}}, setAttribute(){}, addEventListener(){}}); return nodes.get(id);};
  const context = {window:{fetch, location:{href:'https://example.test/taxonomy/',origin:'https://example.test',pathname:'/taxonomy/'},
    __TaxonomyAnalysisSessionContext:{runtime:{workspaceId:'original',analysisGeneration:1}},
    addEventListener:events.addEventListener.bind(events)}, document:{getElementById:node,querySelector(){return null;},
      addEventListener:documentEvents.addEventListener.bind(documentEvents),dispatchEvent:documentEvents.dispatchEvent.bind(documentEvents)},
    TaxonomyI18n:{t:key=>key},TaxonomyUtils:{escapeHtml:String}, console, alert:value=>alerts.push(value),confirm:()=>true,
    bootstrap:{Modal:class {show(){} static getOrCreateInstance(){return new this();} static getInstance(){return {hide(){}};}}}};
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
test('provisioned projection carries its inspected repository, workspace and branch into the rebuild', async () => {
 const calls=[]; const h=harness(async(url,init)=>{calls.push({url,init});return Response.json({readinessState:'READY'});});
 const api=h.context.window.TaxonomyWorkspaceProvisioningApi;
 const status={repositoryId:'reviewed', workspaceId:'original', branch:'team/review', currentHeadCommit:'a'.repeat(40)};
 h.change();
 await api.rebuildProjection(status.currentHeadCommit, status);
 const url=new URL(calls[0].url,'https://example.test');
 assert.equal(url.searchParams.get('repositoryId'),'reviewed');
 assert.equal(url.searchParams.get('workspaceId'),'original');
 assert.equal(url.searchParams.get('branch'),'team/review');
 assert.equal(calls[0].init.headers.get('X-Taxonomy-Workspace-Id'),'original');
 assert.equal(calls[0].init.headers.get('If-Match'),'"'+status.currentHeadCommit+'"');
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
test('a repository switch retires the open transfer and its delayed preview, including after returning', async () => {
  const requests = [];
  const h = harness(() => new Promise(resolve => requests.push(resolve)));
  h.load('versioning/taxonomy-context-transfer.js');
  const transfer = h.context.window.TaxonomyContextTransfer;
  const changed = selectionChanged => {
    const event = new Event('taxonomy:repository-context-changed');
    event.detail = {selectionChanged};
    h.context.document.dispatchEvent(event);
  };
  transfer.showDialog('source-A', 'target-A', 'ctx-A');
  h.node('transferElementIds').value = 'shared-id';
  changed(false);
  const delayed = transfer.previewTransfer();
  await settle();
  assert.equal(requests.length, 1);
  changed(true);
  changed(true);
  requests[0](Response.json({selectedElements: 1, selectedRelations: 0, conflicts: []}));
  await delayed;
  transfer.applyTransfer();
  await settle();
  assert.equal(requests.length, 1, 'the obsolete dialog must not submit a mutation after A–B–A');
  assert.equal(h.node('transferPreviewResults').innerHTML, '');
  assert.equal(h.node('transferElementIds').value, '');
  assert.deepEqual(h.alerts, []);
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

test('an acknowledged switch replaces private source pins and retains the mounted page and locale', async () => {
  const destination = {workspaceId:'work-b', sourceRepositoryId:'repo-b', currentBranch:'review/b'};
  const h = harness(async () => Response.json(destination));
  const win = h.context.window;
  const retained = new URL('https://example.test/taxonomy/?lang=de&architectureSource=snapshot&repositoryId=repo-a&workspaceId=work-a&workspaceScopeKey=work-a&branch=draft&commit=old&snapshotId=old&sourceSnapshotId=old&projectId=7&element=shared#versions');
  win.location.href = retained.href;
  let navigation;
  win.location.assign = value => { navigation = new URL(value, retained); };
  win.location.reload = () => { navigation = new URL(retained); };
  win.setTimeout = callback => callback();
  win.__TaxonomyAnalysisSessionContext.rememberWorkspaceId = id => assert.equal(id, 'work-b');
  const originalLookup = h.context.document.getElementById;
  h.context.document.getElementById = id => id === 'architectureWorkspace' ? null : originalLookup(id);
  h.load('shared/architecture-workspace.js');
  await win.TaxonomyWorkspaceApi.switchWorkspace('work-b');
  assert.equal(navigation.pathname, '/taxonomy/');
  assert.equal(navigation.hash, '#versions');
  assert.equal(navigation.searchParams.get('lang'), 'de');
  assert.equal(navigation.searchParams.get('repositoryId'), 'repo-b');
  assert.equal(navigation.searchParams.get('workspaceId'), 'work-b');
  assert.equal(navigation.searchParams.get('workspaceScopeKey'), 'work-b');
  assert.equal(navigation.searchParams.get('branch'), 'review/b');
  for (const key of ['commit','snapshotId','sourceSnapshotId','projectId','element']) assert.equal(navigation.searchParams.has(key), false, key);
});

test('a mismatched switch acknowledgement cannot change storage or navigate', async () => {
  const h = harness(async () => Response.json({workspaceId:'other'}));
  const effects = [];
  const win = h.context.window;
  win.__TaxonomyAnalysisSessionContext.rememberWorkspaceId = id => effects.push(id);
  win.setTimeout = callback => callback();
  win.location.reload = () => effects.push('reload');
  await assert.rejects(win.TaxonomyWorkspaceApi.switchWorkspace('requested'));
  assert.deepEqual(effects, []);
});
test('out-of-order workspace replies retain only the latest acknowledged selection', async () => {
  const pending = new Map(), stored = [], timers = [], navigations = [];
  const h = harness(url => new Promise(resolve => pending.set(new URL(url, "https://example.test").pathname.split('/').at(-2), resolve)));
  const win = h.context.window;
  win.__TaxonomyAnalysisSessionContext.rememberWorkspaceId = id => stored.push(id);
  win.setTimeout = callback => timers.push(callback);
  win.location.reload = () => navigations.push(stored.at(-1));
  const first = win.TaxonomyWorkspaceApi.switchWorkspace('first');
  const last = win.TaxonomyWorkspaceApi.switchWorkspace('last');
  await settle();
  pending.get('last')(Response.json({workspaceId:'last'})); await last;
  pending.get('first')(Response.json({workspaceId:'first'}));
  await assert.rejects(first, error => error.code === 'ABORTED');
  for (const callback of timers) callback();
  assert.deepEqual(stored, ['last']);
  assert.deepEqual(navigations, ['last']);
});
test('legacy and named switch requests share ordering and callers retain the legacy response body', async () => {
  const pending = new Map(), stored = [], navigations = [];
  const h = harness(url => new Promise(resolve => pending.set(new URL(url, 'https://example.test').pathname.split('/').at(-2), resolve)));
  const win = h.context.window;
  delete win.__TaxonomyAnalysisSessionContext;
  win.TaxonomyState = {};
  win.sessionStorage = {setItem: (_key, id) => stored.push(id)};
  win.setTimeout = callback => callback();
  win.location.reload = () => navigations.push(stored.at(-1));
  h.load('core/taxonomy-analysis-session-core.js');
  win.__TaxonomyAnalysisSessionContext.installWorkspaceFetchRouting();
  const legacy = win.fetch('https://example.test/api/workspace/first/switch', {method:'POST'});
  const named = win.TaxonomyWorkspaceApi.switchWorkspace('last');
  await settle();
  pending.get('last')(Response.json({workspaceId:'last'})); await named;
  pending.get('first')(Response.json({workspaceId:'first'}));
  assert.deepEqual(await (await legacy).json(), {workspaceId:'first'});
  await settle();
  assert.deepEqual(stored, ['last']);
  assert.deepEqual(navigations, ['last']);
});

for (const changeAt of ['while loading', 'before confirmation', 'unchanged']) {
  test('restore dialog retains the authorizing workspace and selection: ' + changeAt, async () => {
    const h = harness(async () => { throw new Error('Unexpected network request'); });
    const doc = h.context.document, win = h.context.window;
    const confirm = new EventTarget(), body = {innerHTML:''};
    const modal = Object.assign(new EventTarget(), {
      isConnected:false,
      querySelector: selector => selector === '#versionsConfirmBtn' ? confirm : body,
      remove() { this.isConnected = false; }
    });
    doc.body = {insertAdjacentHTML() { modal.isConnected = true; }};
    doc.getElementById = id => id === 'versionsConfirmModal' && modal.isConnected ? modal : null;
    h.context.bootstrap.Modal = class {
      show() {}
      hide() { modal.dispatchEvent(new Event('hide.bs.modal')); }
      dispose() {}
    };
    let release, generation = 1, writes = 0;
    const pending = new Promise(resolve => { release = resolve; });
    win.TaxonomyContextBar = {getSelectionGeneration: () => generation};
    win.TaxonomyContextCompare = {fromDocumentDiff: () => null};
    win.TaxonomyVersioningApi = {
      state: async () => Response.json({headCommit:'a'.repeat(40)}),
      diff: () => pending,
      restore: async () => { writes++; return Response.json({error:'fixture complete'}); }
    };
    h.load('versioning/taxonomy-versions.js');
    const preview = win.TaxonomyVersions.restoreVersion('b'.repeat(40));
    await settle();
    assert.equal(confirm.disabled, true);
    if (changeAt === 'while loading') generation += 2; // including a return to the same workspace
    release(Response.json({}));
    await preview;
    assert.equal(confirm.disabled, changeAt === 'while loading');
    if (changeAt === 'before confirmation') h.change();
    confirm.dispatchEvent(new Event('click'));
    await settle();
    assert.equal(writes, changeAt === 'unchanged' ? 1 : 0);
  });
}
