import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { gunzipSync } from 'node:zlib';
const knownHeads={1131:'34203aaa63931a0d3bdc9ee68d0b87d6a70e0da2',1133:'7c48db1936089fa6933a0dbe9ca65a10f575597a'};
const spec=JSON.parse(readFileSync('.github/maintenance/fixes.json','utf8'));
const sha=(b)=>createHash('sha1').update('blob '+b.length+'\0').update(b).digest('hex');
const run=(cwd,...args)=>execFileSync(args[0],args.slice(1),{cwd,encoding:'utf8',stdio:['ignore','pipe','inherit']}).trim();
if(process.argv[2]==='prepare') {
  const prepared=[];
  for(const [id,head] of Object.entries(knownHeads)) {
    const dir=resolve(process.env.RUNNER_TEMP,'pr-fix-'+id);mkdirSync(dir,{recursive:true});
    run(dir,'git','init','-q');run(dir,'git','remote','add','origin','https://github.com/carstenartur/Taxonomy.git');
    run(dir,'git','fetch','--depth=1','origin',head);run(dir,'git','checkout','--detach','-q','FETCH_HEAD');
    assert.equal(run(dir,'git','rev-parse','HEAD'),head);
    const files=spec.manifest[id];
    for(const [path,v] of Object.entries(files)) assert.equal(sha(readFileSync(resolve(dir,path))),v.before,path+' source');
    for(const key of ['copilot',...(id==='1133'?['migration']:[])]) {
      const patch=gunzipSync(Buffer.from(spec[key].base64,'base64'));
      assert.equal(createHash('sha256').update(patch).digest('hex'),spec[key].sha256);
      const name=resolve(process.env.RUNNER_TEMP,key+'-'+id+'.patch');writeFileSync(name,patch);
      run(dir,'git','apply','--check',name);run(dir,'git','apply',name);
    }
    assert.deepEqual(run(dir,'git','diff','--name-only').split('\n').sort(),Object.keys(files).sort());
    for(const [path,v] of Object.entries(files)) assert.equal(sha(readFileSync(resolve(dir,path))),v.after,path+' result');
    const tests=['.github/scripts/copilot-operation-lifecycle.test.mjs','.github/scripts/copilot-terminal-state-regression.test.mjs','.github/scripts/analysis-workflow-regression.test.mjs'];
    if(id==='1133')tests.push('.github/scripts/analysis-recovery.test.mjs','.github/scripts/analysis-recovery-viewport.test.mjs','.github/scripts/analysis-session-startup.test.mjs');
    console.log('PR '+id+' focused JavaScript verification\n'+run(dir,'node','--test',...tests));
    run(dir,'git','diff','--check');run(dir,'git','add','--',...Object.keys(files));
    prepared.push({id,head,tree:run(dir,'git','write-tree'),files:Object.entries(files).map(([path,v])=>({path,sha:v.after,content:readFileSync(resolve(dir,path),'utf8')}))});
  }
  writeFileSync('prepared-fixes.json',JSON.stringify(prepared));
} else if(process.argv[2]==='publish') {
  assert(process.env.GH_TOKEN);
  const request=async(path,body)=>{
    const response=await fetch('https://api.github.com/repos/carstenartur/Taxonomy'+path,{method:body?'POST':'GET',redirect:'error',signal:AbortSignal.timeout(60000),headers:{Accept:'application/vnd.github+json',Authorization:'Bearer '+process.env.GH_TOKEN,'X-GitHub-Api-Version':'2026-03-10','Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
    const result=await response.json();assert(response.ok, path+': '+response.status+' '+result.message);return result;
  };
  const receipts=[];
  for(const target of JSON.parse(readFileSync('prepared-fixes.json','utf8'))) {
    assert.equal(target.head,knownHeads[target.id]);
    const pr=await request('/pulls/'+target.id);assert.equal(pr.state,'open');assert.equal(pr.head.sha,target.head);
    const parent=await request('/git/commits/'+target.head);
    const tree=[];
    for(const file of target.files) {
      assert.equal(file.sha,spec.manifest[target.id][file.path].after);
      const blob=await request('/git/blobs',{encoding:'utf-8',content:file.content});assert.equal(blob.sha,file.sha);
      tree.push({path:file.path,mode:'100644',type:'blob',sha:blob.sha});
    }
    const resultTree=await request('/git/trees',{base_tree:parent.tree.sha,tree});assert.equal(resultTree.sha,target.tree,'Exact whole tree identity');
    const message=target.id==='1131'
      ? 'fix(analysis): require explicit authoritative status before Copilot enrichment\n\nResolve the review fallback gap with nine lifecycle regressions; preserve SUCCESS/IMPORTED authority and MANUAL provider preflight. The same tested fix is carried into PR1133. Full CI remains the merge gate.'
      : 'fix(analysis): verify V30 recovery schema and close Copilot status fallback\n\nKeep the exact PostgreSQL migration sequence through V30 and assert recovery columns, indexes and run foreign key for fresh and upgraded schemas. Carry the reviewed PR1131 guard fix and regressions. Local full Maven/npm attempts remain dependency-download blocked; require current-head CI before merge.';
    const commit=await request('/git/commits',{message,tree:resultTree.sha,parents:[target.head]});
    receipts.push({pr:Number(target.id),parent:target.head,tree:resultTree.sha,commit:commit.sha});
  }
  console.log('VERIFIED_COMMIT_RECEIPTS='+JSON.stringify(receipts));
  writeFileSync('fix-receipts.json',JSON.stringify(receipts,null,2)+'\n');
} else throw Error('Expected prepare or publish; no refs are changed by this transport');
