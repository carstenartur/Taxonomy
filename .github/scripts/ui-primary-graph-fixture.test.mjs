import test from 'node:test';
import assert from 'node:assert/strict';
import { seedSavedGraph } from './ui-primary-basic-workflows.mjs';

const commit='c'.repeat(40);
function fixture(overrides={}) {
  const requests=[];
  const catalogue=[{code:'CR',children:[{code:'CR-321',nameEn:'Catalogue "service"'}]},
    {code:'BP',children:[{code:'BP-654',nameEn:'Catalogue process'}]}];
  const response=value=>({ok:()=>true,json:async()=>value});
  const api={get:async url=>{requests.push({method:'GET',url});return response(url==='/api/taxonomy'
    ? overrides.catalogue || catalogue : {repositoryId:'real-repository'});},
  post:async(url,options)=>{requests.push({method:'POST',url,options});return response(url.startsWith('/api/dsl/commit')
    ? {valid:true,commitId:overrides.commit ?? commit}
    : {readinessState:'READY',currentHeadCommit:commit,projectedCommit:commit,relationCount:1,...overrides.projection});}};
  return {api,requests};
}
test('graph acceptance fixture commits catalogue identities and rebuilds only the exact saved head',async()=>{
  const f=fixture();const saved=await seedSavedGraph(f.api,'qa-isolated');
  assert.equal(saved.code,'BP-654');assert.equal(saved.serviceCode,'CR-321');
  assert.equal(saved.repositoryId,'real-repository');assert.equal(saved.workspaceScopeKey,'__shared__');assert.equal(saved.commit,commit);
  const writes=f.requests.filter(request=>request.method==='POST');
  assert.equal(writes.length,2);
  assert.match(writes[0].options.data,/title: "Catalogue \\"service\\""/);
  assert.match(writes[0].options.data,/relation CR-321 SUPPORTS BP-654 \{ status: accepted; \}/);
  assert.equal(writes[1].options.headers['If-Match'],`"${commit}"`);
  const query=new URL(writes[1].url,'https://example.test').searchParams;
  assert.equal(query.get('repositoryId'),'real-repository');assert.equal(query.get('workspaceId'),'');
  assert.equal(query.get('workspaceScopeKey'),'__shared__');assert.equal(query.get('branch'),'qa-isolated');
});
for(const projection of [{readinessState:'STALE'},{projectedCommit:'d'.repeat(40)},{currentHeadCommit:'e'.repeat(40)},{relationCount:0}])
  test(`graph fixture refuses unproved saved graph ${JSON.stringify(projection)}`,async()=>{
    await assert.rejects(seedSavedGraph(fixture({projection}).api,'qa-isolated'),/does not prove/);
  });
test('missing real catalogue nodes prevent graph fixture writes',async()=>{
  const f=fixture({catalogue:[]});await assert.rejects(seedSavedGraph(f.api,'qa-isolated'),/Real service and process/);
  assert.equal(f.requests.filter(request=>request.method==='POST').length,0);
});
test('an invalid commit never reaches projection recovery',async()=>{
  const f=fixture({commit:'invented'});await assert.rejects(seedSavedGraph(f.api,'qa-isolated'),/not persisted/);
  assert.equal(f.requests.length,2);
});
