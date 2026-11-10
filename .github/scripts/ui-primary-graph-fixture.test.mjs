import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { usingComparisonBranch } from './ui-primary-version-workflow.mjs';
import { seedSavedGraph, removeSavedGraph } from './ui-primary-basic-workflows.mjs';

const privateCommit = 'a'.repeat(40), publishedCommit = 'c'.repeat(40);
const repositoryId = 'fixture-repository', workspaceId = 'fixture-workspace', workspaceBranch = 'actual-private-branch';
function fixture(overrides = {}) {
  const requests = [];
  const branch = overrides.branch ?? 'qa-isolated';
  const catalogue = [{code:'CR',children:[{code:'CR-321',nameEn:'Catalogue "service"'}]},
    {code:'BP',children:[{code:'BP-654',nameEn:'Catalogue process'}]}];
  const persisted = new Map([['active-workspace/qa-isolated','unrelated-private-head']]);
  const workspaces = new Set(['active-workspace']);
  const response = (value,status = 200) => ({ok:()=>status>=200&&status<300,status:()=>status,
    text:async()=>JSON.stringify(value),json:async()=>value});
  const query = url => new URL(url,'https://example.test').searchParams;
  const scope = (url,options) => {
    const selected = query(url);
    const selectedWorkspace = selected.has('workspaceId') ? selected.get('workspaceId') : 'active-workspace';
    assert.equal(options.headers['X-Taxonomy-Workspace-Id'],selectedWorkspace);
    assert.equal(selected.get('repositoryId'),repositoryId);
    return {selected,selectedWorkspace,key:(selectedWorkspace||'central')+'/'+selected.get('branch')};
  };
  const centralState = () => ({repositoryId,workspaceId:null,branch,
    currentHeadCommit:persisted.get('central/'+branch)});
  const api = {
    get:async(url,options)=>{
      requests.push({method:'GET',url,options});
      if(url==='taxonomy') return response(overrides.catalogue??catalogue);
      assert.ok(url.startsWith('architecture/relations/projection/readiness?'));
      assert.equal(scope(url,options).selectedWorkspace,'');
      return response({...centralState(),...overrides.readiness});
    },
    post:async(url,options)=>{
      requests.push({method:'POST',url,options});
      if(url==='repositories') {
        assert.equal(options.data.visibility,'PUBLIC');
        assert.equal(options.data.defaultBranch,branch);
        persisted.set('central/'+branch,'b'.repeat(40));
        return response({repositoryId});
      }
      const selected = scope(url,options);
      if(url.startsWith('architecture/editor/workspaces?')) {
        assert.equal(selected.selectedWorkspace,'');
        assert.equal(options.data.repositoryId,repositoryId);
        assert.equal(options.data.branch,branch);
        workspaces.add(workspaceId);
        persisted.set(workspaceId+'/'+workspaceBranch,'b'.repeat(40));
        return response({workspaceId,currentBranch:workspaceBranch,provisioningStatus:'READY',
          sourceRepositoryId:repositoryId,sourceBranch:branch});
      }
      if(url.startsWith('dsl/commit?')) {
        if(selected.selectedWorkspace==='') return response({error:'Central selection is read-only'},403);
        persisted.set(selected.key,overrides.commit??privateCommit);
        if(overrides.lostCommitResponse) return response({error:'Commit response lost after storage'},503);
        return response({valid:true,commitId:overrides.commit??privateCommit});
      }
      if(url.startsWith('workspace/publish?')) {
        assert.equal(selected.selectedWorkspace,workspaceId);
        assert.equal(selected.selected.get('userBranch'),workspaceBranch);
        assert.equal(persisted.get(selected.key),privateCommit);
        if(overrides.publishFailure) return response({error:'Publication failed'},409);
        persisted.set('central/'+branch,publishedCommit);
        if(overrides.lostPublishResponse) return response({error:'Publication response lost after storage'},503);
        return response({success:true,mergeCommit:overrides.publishedCommit??publishedCommit});
      }
      assert.ok(url.startsWith('architecture/relations/projection/rebuild?'));
      assert.equal(selected.selectedWorkspace,'');
      if(options.headers['If-Match']!=='"'+persisted.get(selected.key)+'"')
        return response({operationStatus:'PRECONDITION_FAILED',actualHeadCommit:persisted.get(selected.key)??null},412);
      return response({...centralState(),readinessState:'READY',projectedCommit:publishedCommit,
        relationCount:1,...overrides.projection});
    },
    delete:async(url,options)=>{
      requests.push({method:'DELETE',url,options});
      assert.ok(url.startsWith('workspace/'+workspaceId+'?'));
      assert.equal(scope(url,options).selectedWorkspace,workspaceId);
      assert.equal(workspaces.delete(workspaceId),true);
      for(const key of persisted.keys()) if(key.startsWith(workspaceId+'/')) persisted.delete(key);
      return response({success:true,deleted:workspaceId});
    }
  };
  return {api,requests,persisted,workspaces};
}

test('graph fixture publishes a private catalogue model and rebuilds the returned central merge commit',async()=>{
  const f=fixture(), captured=[];
  const saved=await seedSavedGraph(f.api,'qa-isolated',resources=>captured.push(resources));
  assert.equal(saved.code,'BP-654');
  assert.equal(saved.serviceCode,'CR-321');
  assert.equal(saved.repositoryId,repositoryId);
  assert.equal(saved.workspaceScopeKey,'__shared__');
  assert.equal(saved.commit,publishedCommit);
  assert.notEqual(saved.commit,privateCommit);
  assert.deepEqual(captured,[{repositoryId,branch:'qa-isolated'},{repositoryId,branch:'qa-isolated',workspaceId}]);
  const writes=f.requests.filter(request=>request.method==='POST');
  assert.equal(writes.length,5);
  const save=writes.find(request=>request.url.startsWith('dsl/commit?'));
  assert.ok(save.options.data.includes('title: '+JSON.stringify('Catalogue "service"')+';\n'));
  assert.match(save.options.data,/relation CR-321 SUPPORTS BP-654 \{\n  status: accepted;\n\}/);
  const privateScope=new URL(save.url,'https://example.test').searchParams;
  assert.equal(privateScope.get('workspaceId'),workspaceId);
  assert.equal(privateScope.get('branch'),workspaceBranch);
  const rebuild=writes.at(-1), centralScope=new URL(rebuild.url,'https://example.test').searchParams;
  assert.equal(rebuild.options.headers['If-Match'],'"'+publishedCommit+'"');
  assert.equal(centralScope.get('repositoryId'),repositoryId);
  assert.equal(centralScope.get('workspaceId'),'');
  assert.equal(centralScope.get('workspaceScopeKey'),'__shared__');
  assert.equal(centralScope.get('branch'),'qa-isolated');
  await removeSavedGraph(f.api,saved);
  assert.equal(f.workspaces.has(workspaceId),false);
  assert.equal(f.persisted.get('active-workspace/qa-isolated'),'unrelated-private-head');
  assert.equal(f.persisted.get('central/qa-isolated'),publishedCommit,
    'Central fixture lifetime belongs to the disposable application; no public repository delete exists');
});

for(const projection of [{readinessState:'STALE'},{projectedCommit:'d'.repeat(40)},
    {currentHeadCommit:'e'.repeat(40)},{relationCount:0},{repositoryId:'other'},
    {workspaceId:'other'},{branch:'other'}])
  test('graph fixture refuses unproved saved graph '+JSON.stringify(projection),async()=>{
    await assert.rejects(seedSavedGraph(fixture({projection}).api,'qa-isolated'),/does not prove/);
  });

test('missing real catalogue nodes prevent graph fixture writes',async()=>{
  const f=fixture({catalogue:[]});
  await assert.rejects(seedSavedGraph(f.api,'qa-isolated'),/Real service and process/);
  assert.equal(f.requests.filter(request=>request.method==='POST').length,0);
});

test('an invalid private commit never reaches publication',async()=>{
  const f=fixture({commit:'invented'});
  await assert.rejects(seedSavedGraph(f.api,'qa-isolated'),/not persisted/);
  assert.equal(f.requests.some(request=>request.url.startsWith('workspace/publish?')),false);
});

test('an invalid publication commit never reaches central projection recovery',async()=>{
  const f=fixture({publishedCommit:'invented'});
  await assert.rejects(seedSavedGraph(f.api,'qa-isolated'),/publication was not persisted/);
  assert.equal(f.requests.some(request=>request.url.startsWith('architecture/relations/')),false);
});

test('a different central head cannot be silently adopted for the saved graph',async()=>{
  const f=fixture({readiness:{currentHeadCommit:'e'.repeat(40)}});
  await assert.rejects(seedSavedGraph(f.api,'qa-isolated'),/not the selected central head/);
  assert.equal(f.requests.some(request=>request.url.startsWith('architecture/relations/projection/rebuild?')),false);
});

for(const failure of [{projection:{readinessState:'STALE'}},{lostCommitResponse:true},
    {publishFailure:true},{lostPublishResponse:true}])
  test('partial setup retains its owned working copy for cleanup '+JSON.stringify(failure),async()=>{
    const f=fixture(failure);
    let resources;
    await assert.rejects(seedSavedGraph(f.api,'qa-isolated',value=>{resources=value;}));
    assert.equal(f.workspaces.has(workspaceId),true);
    assert.equal(resources.workspaceId,workspaceId);
    await removeSavedGraph(f.api,resources);
    assert.equal(f.workspaces.has(workspaceId),false);
    assert.equal(f.persisted.has(workspaceId+'/'+workspaceBranch),false);
    assert.equal(f.persisted.get('active-workspace/qa-isolated'),'unrelated-private-head');
    assert.equal(f.requests.filter(item=>item.method==='DELETE').length,1);
  });

test('cleanup requires the exact owned resource and makes no central deletion request',async()=>{
  for(const resources of [undefined,{}, {repositoryId,workspaceId:''}]) {
    const f=fixture();
    await assert.rejects(removeSavedGraph(f.api,resources),/required for cleanup/);
    assert.equal(f.requests.length,0);
  }
  const f=fixture();
  await removeSavedGraph(f.api,{repositoryId,branch:'qa-isolated'});
  assert.equal(f.requests.length,0);
});


const workflowSource = await readFile(new URL('./ui-primary-basic-workflows.mjs', import.meta.url), 'utf8');

async function runScopedGraphWorkflow(basePath, {rejectProjection = false, rejectBrowser = false} = {}) {
  const origin = 'https://taxonomy.example', requests = [], inspected = [];
  const returnUrl = `${origin}${basePath}/?workspaceId=reader-workspace&lang=de#analyze`;
  let currentUrl = returnUrl, disposed = false, savedFixture = fixture();
  const window = {TaxonomyI18n: {resolveUrl: path => basePath + path},
    location: {href: currentUrl}, TaxonomyRoleSurface: {getContext: () => ({roles: ['USER']})}};
  const sandbox = vm.createContext({window, URL, URLSearchParams, process: {env: {}},
    document: {getElementById: () => ({textContent: publishedCommit})},
    basicAuthorization: () => 'Basic fixture-admin', ROLE_ACCOUNTS: {ADMIN: {password: 'fixture'}},
    usingComparisonBranch, navigateToPage: async () => {},
    request: {newContext: async options => {
      async function perform(method, path, init = {}) {
        const url = new URL(path, options.baseURL);
        const headers = new Headers({...options.extraHTTPHeaders, ...init.headers});
        const workspace = url.searchParams.has('workspaceId') ? url.searchParams.get('workspaceId')
          : headers.has('X-Taxonomy-Workspace-Id') ? headers.get('X-Taxonomy-Workspace-Id') : 'admin-private';
        requests.push({method, url, headers, workspace});
        assert.ok(url.pathname.startsWith(basePath + '/api/'), 'Every fixture request must retain its application prefix');
        const endpoint = url.pathname.slice((basePath + '/api/').length);
        if (endpoint === 'repositories' && method === 'POST') savedFixture = fixture({branch: init.data.defaultBranch});
        if (endpoint.endsWith('/projection/rebuild') && rejectProjection)
          return {ok: () => false, status: () => 412, text: async () => 'Expected head changed'};
        return savedFixture.api[method.toLowerCase()](endpoint + url.search, {...init,
          headers: {...options.extraHTTPHeaders, ...init.headers}});
      }
      return {get: (url, init) => perform('GET', url, init), post: (url, init) => perform('POST', url, init),
        delete: (url, init) => perform('DELETE', url, init), dispose: async () => {disposed = true;}};
    }}
  });
  vm.runInContext(workflowSource.replace(/^import .*;\n/gm, '').replace(/^export /gm, ''), sandbox);
  // Exercise the real HTTP producer, selected URL and cleanup; native acceptance
  // independently retains graph rendering, accepted evidence and keyboard focus.
  sandbox.verifySelectedGraphEntry = async (_workflow, saved) => {
    inspected.push(saved);
    if (rejectBrowser) throw new Error('Graph rendering failed');
  };
  const page = {url: () => currentUrl,
    evaluate: async callback => callback(),
    goto: async url => {currentUrl = url; window.location.href = url;},
    locator: () => ({waitFor: async () => {}}),
    waitForFunction: async (callback, argument) => {assert.ok(callback(argument));}};
  let error;
  try {await sandbox.verifyGraphEntry({page});} catch (caught) {error = caught;}
  return {requests, savedFixture, inspected, currentUrl, returnUrl, disposed, error};
}

for (const basePath of ['', '/taxonomy', '/teams/blue/taxonomy']) {
  test(`the real graph workflow publishes and cleans its private working copy at ${basePath || '/'}`, async () => {
    const result = await runScopedGraphWorkflow(basePath);
    assert.equal(result.error, undefined, 'The authorized private publication must preserve its application prefix');
    assert.equal(result.inspected.length, 1);
    assert.equal(result.inspected[0].commit, publishedCommit);
    assert.notEqual(result.inspected[0].commit, privateCommit);
    assert.equal(result.inspected[0].workspaceScopeKey, '__shared__');
    assert.equal(result.currentUrl, result.returnUrl);
    assert.equal(result.disposed, true);
    assert.deepEqual([...result.savedFixture.workspaces], ['active-workspace']);
    assert.equal(result.savedFixture.persisted.get('active-workspace/qa-isolated'), 'unrelated-private-head');
    assert.equal(result.savedFixture.persisted.get('central/'+result.inspected[0].branch), publishedCommit);
    for (const request of result.requests) {
      assert.ok(request.url.pathname.startsWith(basePath + '/api/'));
      if (request.url.pathname.endsWith('/api/dsl/commit') || request.url.pathname.endsWith('/api/workspace/publish')
          || request.method === 'DELETE') {
        assert.equal(request.workspace, workspaceId, 'Only the owned private working copy may be mutated or deleted');
        assert.equal(request.headers.get('X-Taxonomy-Workspace-Id'), workspaceId);
        assert.equal(request.url.searchParams.get('repositoryId'), repositoryId);
      }
      if (request.url.pathname.includes('/projection/')) {
        assert.equal(request.workspace, '');
        assert.equal(request.headers.get('X-Taxonomy-Workspace-Id'), '');
        assert.equal(request.url.searchParams.get('repositoryId'), repositoryId);
      }
    }
  });
}

for (const failure of [{rejectProjection: true}, {rejectBrowser: true}]) {
  test('failed graph setup or rendering still removes only its owned private working copy '+JSON.stringify(failure), async () => {
    const result = await runScopedGraphWorkflow('/taxonomy', failure);
    assert.match(String(result.error), failure.rejectProjection ? /412/ : /Graph rendering failed/);
    assert.equal(result.currentUrl, result.returnUrl);
    assert.equal(result.disposed, true);
    assert.deepEqual([...result.savedFixture.workspaces], ['active-workspace']);
    assert.equal(result.savedFixture.persisted.get('active-workspace/qa-isolated'), 'unrelated-private-head');
    const cleanup = result.requests.filter(request => request.method === 'DELETE');
    assert.equal(cleanup.length, 1);
    assert.equal(cleanup[0].url.pathname, '/taxonomy/api/workspace/'+workspaceId);
    assert.equal(cleanup[0].workspace, workspaceId);
    assert.equal(cleanup[0].url.searchParams.get('repositoryId'), repositoryId);
  });
}
