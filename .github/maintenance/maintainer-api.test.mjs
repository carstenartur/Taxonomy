import test from 'node:test';
import assert from 'node:assert/strict';
import { mergeReviewed, approveReviewed, mergeHead, repository } from './maintainer-api.mjs';
function fake(change={}) {
  const writes=[];
  const names=['CI / CD','Database Compatibility','JGit Storage Consumer Contract','Security Scan','Civilian architecture acceptance','Kubernetes Constrained Smoke','Document Template Report E2E'];
  const data={
    '/pulls/1125':{head:{sha:mergeHead,repo:{full_name:repository}},base:{ref:'main'},state:'open',draft:false,stack:{number:1126}},
    '/stacks/1126':{base:{ref:'main'},pull_requests:[{number:1124,merged_at:'2026-09-26'}, {number:1125,merged_at:null}]},
    '/pulls/1125/files?per_page=100': ['.github/scripts/stage-core-quality-reports.sh','taxonomy-build/src/test/java/com/taxonomy/workspace/storage/JgitStorageDocumentationContractTest.java','taxonomy-workspace/pom.xml','taxonomy-workspace/src/test/java/com/taxonomy/workspace/service/WorkspaceManagerConcurrencyTest.java'].map(filename=>({filename})),
    ['/actions/runs?event=pull_request&head_sha='+mergeHead+'&per_page=100']:{total_count:7,workflow_runs:names.map((name,id)=>({id,name,status:'completed',conclusion:'success'}))},
    ['/commits/'+mergeHead+'/check-runs?per_page=100']:{total_count:1,check_runs:[{status:'completed',conclusion:'success'}]},
    '/pulls/1125/reviews?per_page=100':[],
    '/pulls/1125/merge-async':{status:'pending',details:{uuid:'test'}},
    '/pulls/1125/merge-async/test':{status:'merged',details:{sha:'done'}}
  };
  Object.assign(data, change);
  return {writes, data, request:async(path,method='GET',body)=>{if(method!=='GET')writes.push({path,method,body});assert(path in data,path);return data[path];}};
}
test('only exact reviewed head with merged lower stack can use default merge',async()=>{
 const f=fake();assert.equal((await mergeReviewed(f.request)).status,'merged');
 assert.deepEqual(f.writes.map(w=>[w.path,w.method,w.body.sha,w.body.merge_action]),[['/pulls/1125/merge-async','PUT',mergeHead,'default']]);
});
for(const defect of ['head','lower','workflow','check','review','files']) test('no mutation for '+defect,async()=>{
 const f=fake();
 if(defect==='head')f.data['/pulls/1125'].head.sha='changed';
 if(defect==='lower')f.data['/stacks/1126'].pull_requests[0].merged_at=null;
 if(defect==='workflow')f.data['/actions/runs?event=pull_request&head_sha='+mergeHead+'&per_page=100'].workflow_runs[0].conclusion='failure';
 if(defect==='check')f.data['/commits/'+mergeHead+'/check-runs?per_page=100'].check_runs[0].status='in_progress';
 if(defect==='review')f.data['/pulls/1125/reviews?per_page=100'].push({user:{login:'reviewer'},state:'CHANGES_REQUESTED'});
 if(defect==='files')f.data['/pulls/1125/files?per_page=100'].push({filename:'other'});
 await assert.rejects(mergeReviewed(f.request));assert.deepEqual(f.writes,[]);
});
test('approval only acts on the reviewed head and known pending runs',async()=>{
 const writes=[];const head='613df366714e677f8b57b43d65cf1ffd6db6dfeb';
 await approveReviewed(async(path,method='GET')=>{
  if(method!=='GET'){writes.push(path);return {};}
  if(path==='/pulls/1129')return{head:{sha:head,repo:{full_name:repository}},state:'open'};
  if(path.includes('/files?'))return ['ReformulationAdoptionSchemaContract.java','ReformulationCheckpointIndexContract.java'].map(s=>({filename:'taxonomy-app/src/test/java/com/taxonomy/portfolio/reformulation/'+s}));
  return {head_sha:head,event:'pull_request',conclusion:'action_required'};
 });
 assert.equal(writes.length,7);assert(writes.every(p=>/^\/actions\/runs\/\d+\/approve$/.test(p)));
});
