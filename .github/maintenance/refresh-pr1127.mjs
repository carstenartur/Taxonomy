import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
const main='24a7e32fd22717022f3f32980dfef8c2c21e30be';
const head='4b0e5e2cad8e886c1ab2dac7e59c6384b50e5b85';
const anchor='7ba81eb3b3937808f50dddc1268242537e80a935';
const targets=JSON.parse(readFileSync('.github/maintenance/pr1127-renames.json','utf8'));
assert.equal(targets.length,17); assert.equal(new Set(targets).size,17);
const run=(cwd,...args)=>execFileSync(args[0],args.slice(1),{cwd,encoding:'utf8',maxBuffer:8*1024*1024,stdio:['ignore','pipe','inherit']}).trim();
if(process.argv[2]==='prepare') {
 const dir=resolve(process.env.RUNNER_TEMP,'pr1127-reviewed-refresh');mkdirSync(dir,{recursive:true});
 run(dir,'git','init','-q');run(dir,'git','remote','add','origin','https://github.com/carstenartur/Taxonomy.git');
 run(dir,'git','fetch','--depth=100','origin',main,head);
 const intended=run(dir,'git','diff','--name-status','--find-renames=100%',anchor,head).split('\n').map(s=>s.split('\t'));
 assert.deepEqual(intended,targets.map(p=>['R100',p.replace(/^taxonomy-(build|portfolio)\//,'taxonomy-app/'),p]),'Feature delta is not exactly the reviewed 17 moves');
 const merge=spawnSync('git',['merge-tree','--write-tree',head,main],{cwd:dir,encoding:'utf8',maxBuffer:8*1024*1024});
 writeFileSync('pr1127-automatic-merge.txt',merge.stdout+'\n'+merge.stderr+'\nEXIT='+merge.status+'\n');
 assert([0,1].includes(merge.status),'Automatic merge failed for an unexpected reason');
 run(dir,'git','checkout','--detach','-q',main);
 for(const path of targets) {
  const old=path.replace(/^taxonomy-(build|portfolio)\//,'taxonomy-app/');
  assert(!existsSync(resolve(dir,path)), 'Target already present: '+path);
  assert.equal(run(dir,'git','rev-parse',main+':'+old),run(dir,'git','rev-parse',head+':'+path),'Main has newer test edits: '+path);
  mkdirSync(resolve(dir,path,'..'),{recursive:true});run(dir,'git','mv',old,path);
 }
 for(const name of ['cancellation-api-contract.cjs','cancellation-contract.cjs']) {
  const old='taxonomy-app/src/test/resources/reformulation/'+name;
  const next='taxonomy-build/src/test/resources/reformulation/'+name;
  const callers=run(dir,'git','grep','-l','--',name).split('\n');
  assert(callers.every(p=>p.startsWith('taxonomy-build/src/test/java/')),'Test resource still has another owner');
  mkdirSync(resolve(dir,next,'..'),{recursive:true});run(dir,'git','mv',old,next);
 }
 const path='taxonomy-build/src/test/java/com/taxonomy/portfolio/PortfolioTenantMigrationContractTest.java';
 let source=readFileSync(resolve(dir,path),'utf8');
 assert(source.includes('String sql = Files.readString(MIGRATION);'));
 assert(source.includes('private static final Path MIGRATION = Path.of('));
 source=source.replace('import java.nio.file.Files;\nimport java.nio.file.Path;', 'import org.springframework.core.io.ClassPathResource;\n\nimport java.nio.charset.StandardCharsets;')
  .replace('private static final Path MIGRATION = Path.of(\n            "src/main/resources/db/migration/taxonomy/postgresql/"\n                    + "V12__scope_portfolio_by_repository_branch.sql");','private static final String MIGRATION =\n            "db/migration/taxonomy/postgresql/V12__scope_portfolio_by_repository_branch.sql";')
  .replace('String sql = Files.readString(MIGRATION);','String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);');
 assert(!source.includes('Path.of('));writeFileSync(resolve(dir,path),source);
 assert(existsSync(resolve(dir,'taxonomy-app/src/main/resources/db/migration/taxonomy/postgresql/V12__scope_portfolio_by_repository_branch.sql')));
 for(const [test,input] of [['cancellation-api-contract.cjs','js/api/portfolio-api.js'],['cancellation-contract.cjs','js/portfolio/requirement-reformulation.js']])
  console.log(run(dir,'node','taxonomy-build/src/test/resources/reformulation/'+test,'taxonomy-app/src/main/resources/static/'+input));
 run(dir,'git','add','--',path);run(dir,'git','diff','--cached','--check');
 const files=run(dir,'git','diff','--cached','--name-only','--no-renames',main).split('\n').map(path=>{
  const entry=run(dir,'git','ls-files','--stage','--',path).split(/\s+/);
  return entry[0]?{path,mode:entry[0],sha:entry[1],content:readFileSync(resolve(dir,path),'utf8')}:{path,mode:'100644',sha:null};
 });
 assert.equal(files.length,38,'Only 17 test moves and 2 test-resource moves may change main');
 const data={head,main,anchor,mainTree:run(dir,'git','rev-parse',main+'^{tree}'),tree:run(dir,'git','write-tree'),files};
 writeFileSync('pr1127-refresh-candidate.json',JSON.stringify(data,null,2));
 writeFileSync('pr1127-refresh.patch',execFileSync('git',['diff','--cached','--binary',main],{cwd:dir}));
 console.log('Candidate tree: '+data.tree+'; parent head: '+head+'; main: '+main);
} else if(process.argv[2]==='publish') {
 assert(process.env.GH_TOKEN);
 const request=async(path,body)=>{
  const response=await fetch('https://api.github.com/repos/carstenartur/Taxonomy'+path,{method:body?'POST':'GET',redirect:'error',signal:AbortSignal.timeout(60000),headers:{Accept:'application/vnd.github+json',Authorization:'Bearer '+process.env.GH_TOKEN,'X-GitHub-Api-Version':'2026-03-10','Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
  const result=await response.json();assert(response.ok,path+': '+response.status+' '+result.message);return result;
 };
 const data=JSON.parse(readFileSync('pr1127-refresh-candidate.json','utf8'));
 assert.equal(data.head,head);assert.equal(data.main,main);
 const pr=await request('/pulls/1127');assert.equal(pr.state,'open');assert.equal(pr.head.sha,head);
 assert.equal((await request('/git/ref/heads/main')).object.sha,main,'Main changed during refresh');
 const tree=[];
 for(const f of data.files) {
  if(f.sha!==null)assert.equal((await request('/git/blobs',{content:f.content,encoding:'utf-8'})).sha,f.sha);
  tree.push({path:f.path,mode:f.mode,type:'blob',sha:f.sha});
 }
 const created=await request('/git/trees',{base_tree:data.mainTree,tree});assert.equal(created.sha,data.tree,'Whole tree differs from tested candidate');
 const commit=await request('/git/commits',{parents:[head,main],tree:created.sha,message:'fix(test): refresh module ownership against main and retain test inputs\n\nPreserve all main changes through merged PR1125. Apply only the reviewed 17 test relocations, carry both cancellation CJS resources with their build-owned callers, and read the V12 SQL from the application classpath instead of the former module-relative path. Keep physical schema helpers in the application module.\n\nThe two production-JavaScript cancellation contracts pass; full current-head CI remains required before main merge.'});
 const receipt={pr:1127,head,main,tree:created.sha,commit:commit.sha};console.log(JSON.stringify(receipt));
 writeFileSync('pr1127-refresh-receipt.json',JSON.stringify(receipt,null,2)+'\n');
} else throw Error('Expected prepare or publish; no refs are changed');
