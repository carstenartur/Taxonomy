import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import vm from 'node:vm';

const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/requirement-intake.js', import.meta.url), 'utf8');
class Element {
  constructor(tag='div') { this.tagName=tag; this.children=[]; this.listeners=new Map(); this.value=''; this.dataset={}; this.classList={add(){},remove(){}}; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children=[...children]; }
  setAttribute() {} focus() {} closest() { return null; }
  addEventListener(name,fn) { this.listeners.set(name, [...(this.listeners.get(name)||[]),fn]); }
  dispatch(name) { for (const listener of this.listeners.get(name)||[]) listener({target:this,preventDefault(){}}); }
}
function harness(review) {
  let scope={repositoryId:'repo',workspaceId:'work',branch:'draft'};
  const window={setTimeout:()=>1,clearTimeout(){},TaxonomyArchitectureWorkflow:{getScope:()=>scope},
    TaxonomyArchitectureLifecycleApi:{reviewRequirementIntake:review},location:{href:'https://example.test/'}};
  const document={documentElement:{lang:'en'},createElement:tag=>new Element(tag)};
  vm.runInNewContext(source,{window,document,TextEncoder,Uint8Array,Uint32Array,DataView,URL,URLSearchParams,Promise,Set,Map,WeakMap,console});
  return {api:window.TaxonomyRequirementIntake,scope(value){scope=value;},form:new Element('form'),input:new Element('textarea')};
}
test('portable digest preserves raw whitespace and Unicode when WebCrypto is absent',async()=>{
  const h=harness(()=>{throw new Error('No transport expected');});
  for(const text of ['abc',' a ','🧭\r\nÄ']) assert.equal(await h.api.inputHash(text),createHash('sha256').update(text,'utf8').digest('hex'));
});
test('review alone never chooses a mutation; prepare awaits an explicit choice',async()=>{
  let calls=0;
  const h=harness(async()=>{calls++;return {status:'COMPLETE',inputHash:createHash('sha256').update('candidate').digest('hex'),scope:{repositoryId:'repo',workspaceId:'work',branch:'draft'},candidates:[]};});
  h.input.value='candidate'; h.api.attach(h.form,{textElement:h.input,projectId:7});
  const prepared=h.api.prepare(h.form,{text:'candidate'},7); let settled=false;prepared.then(()=>{settled=true;});
  await new Promise(resolve=>setImmediate(resolve)); assert.equal(calls,1); assert.equal(settled,false);
  const section=h.form.children[0]; const buttons=section.children.filter(child=>child.tagName==='button');
  buttons.find(button=>button.dataset.intakeChoice==='NEW').dispatch('click');
  const result=await prepared; assert.equal(result.choice,'NEW');assert.equal(result.projectId,7);
});
test('editing while review is in flight cancels preparation and cannot reuse old evidence',async()=>{
  let resolve; const h=harness(()=>new Promise(done=>{resolve=done;}));
  h.input.value='old'; h.api.attach(h.form,{textElement:h.input,projectId:7});
  const pending=h.api.prepare(h.form,{text:'old'},7);
  h.input.value='new';h.input.dispatch('input');
  resolve({status:'COMPLETE',inputHash:'old',candidates:[]});
  assert.equal(await pending,null);
});
test('manual new version remains available when AI finds no relationship and retains the opened base',async()=>{
  const hash=createHash('sha256').update('edited').digest('hex');
  const baseHash=createHash('sha256').update('Stored text').digest('hex');
  const h=harness(async()=>({status:'COMPLETE',inputHash:hash,scope:{repositoryId:'repo',workspaceId:'work',branch:'draft'},candidates:[]}));
  h.input.value='edited';
  h.api.attach(h.form,{textElement:h.input,projectId:7,versionTarget:{projectId:7,requirementId:2,requirementVersionId:12,contentHash:baseHash}});
  const pending=h.api.prepareVersion(h.form,{text:'edited'},7,2,12,baseHash);
  await new Promise(resolve=>setImmediate(resolve));
  h.form.children[0].children.find(element=>element.dataset.intakeChoice==='CHANGE').dispatch('click');
  const command=await pending;assert.equal(command.choice,'CHANGE');assert.equal(command.expectedVersionId,12);assert.equal(command.expectedContentHash,baseHash);
});
test('version preparation without a captured base never substitutes a newly fetched version',async()=>{
  const h=harness(()=>{throw new Error('No read expected');});h.input.value='edited';
  await assert.rejects(h.api.prepareVersion(h.form,{text:'edited'},7,2,null,null),/original requirement version/);
});
test('canonical candidate view opens the exact assessed commit and displays its source version',async()=>{
  const sourceCommit='a'.repeat(40);
  const h=harness(async()=>({status:'COMPLETE',inputHash:createHash('sha256').update('incoming').digest('hex'),
    scope:{repositoryId:'repo',workspaceId:'work',branch:'draft'},candidates:[{
      kind:'ARCHITECTURE_REQUIREMENT',repositoryId:'central-repo',workspaceId:null,branch:'main',
      requirementKey:'PUMP',title:'Pump stop',classification:'CHANGE',sourceCommit,sourceVersion:'3',
      contentHash:createHash('sha256').update('Stored central text').digest('hex'),quotes:[],actions:['VIEW','NEW']
    }]}));
  h.input.value='incoming';h.api.attach(h.form,{textElement:h.input,projectId:null});
  await new Promise(resolve=>setImmediate(resolve));
  const article=h.form.children[0].children.find(element=>element.tagName==='article');
  const link=article.children.find(element=>element.tagName==='a');
  const target=new URL(link.href);
  assert.equal(target.pathname,'/architecture/editor');
  assert.equal(target.searchParams.get('repositoryId'),'central-repo');
  assert.equal(target.searchParams.get('workspaceId'),'');
  assert.equal(target.searchParams.get('branch'),'main');
  assert.equal(target.searchParams.get('commit'),sourceCommit);
  assert.equal(link.target,'_blank');
  assert.ok(article.children.some(element=>element.textContent?.includes('Source version 3')&&element.textContent.includes(sourceCommit.slice(0,12))));
  assert.equal(article.children.some(element=>['CHANGE','LINK'].includes(element.dataset.intakeChoice)),false);
});
test('a response for another branch cannot display evidence or offer a change despite matching input text',async()=>{
  const h=harness(async()=>({status:'COMPLETE',inputHash:createHash('sha256').update('incoming').digest('hex'),
    scope:{repositoryId:'repo',workspaceId:'work',workspaceScopeKey:'work',branch:'other-branch'},candidates:[{
      kind:'REQUIREMENT',repositoryId:'repo',workspaceId:'work',branch:'other-branch',projectId:7,requirementId:2,
      requirementVersionId:12,contentHash:'a'.repeat(64),title:'Foreign draft',classification:'CHANGE',quotes:[]
    }]}));
  h.input.value='incoming';h.api.attach(h.form,{textElement:h.input,projectId:7});
  await new Promise(resolve=>setImmediate(resolve));
  const section=h.form.children[0];
  assert.equal(section.children.some(element=>element.tagName==='article'),false);
  assert.ok(section.children.some(element=>element.textContent?.includes('incomplete or unavailable')));
  assert.ok(section.children.some(element=>element.dataset.intakeChoice==='NEW'));
});
test('central null workspace and canonical shared scope key describe the same reviewed scope',async()=>{
  const h=harness(async()=>({status:'COMPLETE',inputHash:createHash('sha256').update('incoming').digest('hex'),
    scope:{repositoryId:'repo',workspaceId:null,workspaceScopeKey:'__shared__',branch:'main'},candidates:[]}));
  h.scope({repositoryId:'repo',workspaceId:'',branch:'main'});
  h.input.value='incoming';h.api.attach(h.form,{textElement:h.input,projectId:null});
  await new Promise(resolve=>setImmediate(resolve));
  assert.ok(h.form.children[0].children.some(element=>element.textContent?.startsWith('Choose how to use this text.')));
});
