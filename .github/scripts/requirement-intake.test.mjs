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
function harness(review, lifecycle) {
  let scope={repositoryId:'repo',workspaceId:'work',branch:'draft'};
  const timers=new Map(); let nextTimer=0;
  const window={setTimeout:fn=>{timers.set(++nextTimer,fn);return nextTimer;},clearTimeout:id=>timers.delete(id),
    TaxonomyArchitectureWorkflow:{getScope:()=>scope},
    TaxonomyArchitectureLifecycleApi:{reviewRequirementIntake:review},TaxonomyArchitectureLifecycle:lifecycle,
    location:{href:'https://example.test/'}};
  const document=Object.assign(new Element('document'),{documentElement:{lang:'en'},createElement:tag=>new Element(tag)});
  vm.runInNewContext(source,{window,document,TextEncoder,Uint8Array,Uint32Array,DataView,URL,URLSearchParams,Promise,Set,Map,WeakMap,console});
  const form=new Element('form'),dialog=new Element('div');form.closest=()=>dialog;
  document.forms=[form];
  return {api:window.TaxonomyRequirementIntake,scope(value){scope=value;},
    notifyScope(){document.dispatch('taxonomy:entity-context-changed');},
    flushTimers(){const jobs=[...timers.values()];timers.clear();jobs.forEach(fn=>fn());},
    form,dialog,input:new Element('textarea')};
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
test('initial architecture scope resolves before intake review or the explicit save choice',async()=>{
  let resolveContext, calls=0;
  const ready=new Promise(resolve=>{resolveContext=resolve;});
  const h=harness(async()=>{
    calls++;
    return {status:'COMPLETE',inputHash:createHash('sha256').update('candidate').digest('hex'),
      scope:{repositoryId:'repo',workspaceId:'work',branch:'draft'},candidates:[]};
  },{ready:()=>ready});
  h.scope({});h.input.value='candidate';h.api.attach(h.form,{textElement:h.input,projectId:7});
  const pending=h.api.prepare(h.form,{text:'candidate'},7);let settled=false;pending.then(()=>{settled=true;});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls,0,'A project can render before its architecture context; no review may use that unresolved scope');
  assert.equal(settled,false);
  assert.equal(h.form.children[0].children.some(element=>element.dataset.intakeChoice),false);
  h.scope({repositoryId:'repo',workspaceId:'work',branch:'draft'});resolveContext({context:{repositoryId:'repo'}});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls,1,'Automatic review and submit share the same resolved-scope read');
  assert.equal(settled,false,'The original submit must still await its explicit choice');
  h.form.children[0].children.find(element=>element.dataset.intakeChoice==='NEW').dispatch('click');
  const command=await pending;assert.equal(command.choice,'NEW');assert.equal(command.projectId,7);
  assert.equal(h.api.isCurrent(h.form),true);
});
test('editing while the initial architecture scope resolves cancels only the old submission',async()=>{
  let resolveContext;
  const ready=new Promise(resolve=>{resolveContext=resolve;});
  const h=harness(async()=>{throw new Error('Old form data must not be reviewed');},{ready:()=>ready});
  h.scope({});h.input.value='old';h.api.attach(h.form,{textElement:h.input,projectId:7});
  const pending=h.api.prepare(h.form,{text:'old'},7);
  h.input.value='new';h.input.dispatch('input');
  h.scope({repositoryId:'repo',workspaceId:'work',branch:'draft'});resolveContext({context:{repositoryId:'repo'}});
  assert.equal(await pending,null);
  assert.equal(h.input.value,'new');
  assert.equal(h.form.children[0].children.some(element=>element.dataset.intakeChoice),false);
});
test('an explicit branch change during initial loading cannot rebind the pending requirement',async()=>{
  let resolveContext,calls=0;
  const ready=new Promise(resolve=>{resolveContext=resolve;});
  const h=harness(async()=>{calls++;throw new Error('Wrong branch must not be reviewed');},{ready:()=>ready});
  h.input.value='candidate';h.api.attach(h.form,{textElement:h.input,projectId:7});
  const pending=h.api.prepare(h.form,{text:'candidate'},7);
  h.scope({repositoryId:'repo',workspaceId:'work',branch:'another'});resolveContext({context:{repositoryId:'repo'}});
  assert.equal(await pending,null);assert.equal(calls,0);
  assert.equal(h.form.children[0].children.some(element=>element.dataset.intakeChoice),false);
});
test('closing the form during initial loading cancels its waiting submission and keeps the draft',async()=>{
  let resolveContext,calls=0;
  const ready=new Promise(resolve=>{resolveContext=resolve;});
  const h=harness(async()=>{calls++;return {status:'COMPLETE',inputHash:createHash('sha256').update('candidate').digest('hex'),
    scope:{repositoryId:'repo',workspaceId:'work',branch:'draft'},candidates:[]};},{ready:()=>ready});
  h.scope({});h.input.value='candidate';h.api.attach(h.form,{textElement:h.input,projectId:7});
  const pending=h.api.prepare(h.form,{text:'candidate'},7);let settled=false;pending.then(()=>{settled=true;});
  h.dialog.dispatch('hidden.bs.modal');
  h.scope({repositoryId:'repo',workspaceId:'work',branch:'draft'});resolveContext({context:{repositoryId:'repo'}});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(settled,true,'Closing the dialog must not leave an old submit waiting for a later opening');
  assert.equal(await pending,null);assert.equal(calls,0);assert.equal(h.input.value,'candidate');
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
test('reuse candidates retain model identities and open read-only source checkpoints', async () => {
  const sourceCommit = 'b'.repeat(40), input = 'Retain readings from the station';
  const source = {repositoryId:'central-repo', workspaceId:null, branch:'main', sourceCommit,
    classification:'ADDITION', quotes:['Retain readings', 'Retains sensor readings'], actions:['VIEW']};
  const h = harness(async () => ({status:'COMPLETE', inputHash:createHash('sha256').update(input).digest('hex'),
    scope:{repositoryId:'repo',workspaceId:'work',branch:'draft'}, candidates:[
      {...source, kind:'ARCHITECTURE_ELEMENT', sourceObjectId:'readings', title:'Reading archive'},
      {...source, kind:'ARCHITECTURE_RELATION', sourceObjectId:'readings SUPPORTS station', title:'readings SUPPORTS station'}
    ]}));
  h.input.value = input;
  h.api.attach(h.form, {textElement:h.input, projectId:null});
  await new Promise(resolve => setImmediate(resolve));
  const articles = h.form.children[0].children.filter(element => element.tagName === 'article');
  assert.deepEqual(articles.map(article => article.children[0].textContent),
    ['readings · Reading archive', 'readings SUPPORTS station']);
  for (const article of articles) {
    const anchor = article.children.find(element => element.tagName === 'a');
    const target = new URL(anchor.href);
    assert.equal(anchor.target, '_blank');
    assert.equal(target.pathname, '/architecture/editor');
    assert.equal(target.searchParams.get('commit'), sourceCommit);
    assert.equal(target.searchParams.get('element'), 'readings');
    assert.equal(article.children.some(element => element.dataset.intakeChoice), false);
  }
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

const central = {repositoryId:'repo',workspaceId:'',workspaceScopeKey:'__shared__',branch:'draft'};
const sharedNamedCopy = {...central,workspaceId:'__shared__'};
function completedReview(scope) {
  return {status:'COMPLETE',inputHash:createHash('sha256').update('candidate').digest('hex'),scope,candidates:[]};
}
test('initial review waits for the same exact workspace even when the display key is unchanged',async()=>{
  let resolveContext, calls=0;
  const ready=new Promise(resolve=>{resolveContext=resolve;});
  const h=harness(async()=>{calls++;return completedReview(central);},{ready:()=>ready});
  h.scope(sharedNamedCopy);h.input.value='candidate';
  h.api.attach(h.form,{textElement:h.input,projectId:7});
  const pending=h.api.prepare(h.form,{text:'candidate'},7);
  h.scope(central);resolveContext({context:central});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls,0);
  assert.equal(await pending,null);
});
test('a choice from the central review is discarded when reviewing the same form in a personal copy',async()=>{
  let selectedScope=central;
  const h=harness(async()=>completedReview(selectedScope));
  h.scope(central);h.input.value='candidate';h.api.attach(h.form,{textElement:h.input,projectId:7});
  await new Promise(resolve=>setImmediate(resolve));
  const section=h.form.children[0];
  const chooseNew=()=>section.children.find(element=>element.dataset.intakeChoice==='NEW').dispatch('click');
  chooseNew();
  selectedScope=sharedNamedCopy;h.scope(selectedScope);
  let completed=false;
  const pending=h.api.prepare(h.form,{text:'candidate'},7).then(value=>{completed=true;return value;});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(completed,false,'The new review needs its own explicit choice');
  chooseNew();
  assert.equal((await pending).choice,'NEW');
  assert.equal(h.api.isCurrent(h.form),true);
  h.scope(central);
  assert.equal(h.api.isCurrent(h.form),false);
});

test('leaving a reviewed workspace clears the displayed choices and cancels a waiting submit even after returning',async()=>{
  const h=harness(async()=>completedReview(sharedNamedCopy));
  h.scope(sharedNamedCopy);h.input.value='candidate';h.api.attach(h.form,{textElement:h.input,projectId:7});
  const pending=h.api.prepare(h.form,{text:'candidate'},7);
  await new Promise(resolve=>setImmediate(resolve));
  const section=h.form.children[0];
  assert.ok(section.children.some(element=>element.dataset.intakeChoice));
  h.scope(central);h.notifyScope();
  assert.equal(section.children.some(element=>element.dataset.intakeChoice),false);
  h.scope(sharedNamedCopy);h.notifyScope();
  assert.equal(await pending,null);
});

for (const publishEvent of [true,false]) test('queued intake text stays in its original repository, context event '+publishEvent,async()=>{
  const other={...central,repositoryId:'other-repository'}, calls=[];
  let selected=central;
  const h=harness(async request=>{
    calls.push([selected.repositoryId, request.text]);
    return {...completedReview(selected),inputHash:createHash('sha256').update(request.text).digest('hex')};
  });
  h.scope(selected);h.api.attach(h.form,{textElement:h.input,projectId:7});
  h.input.value='Original draft';h.input.dispatch('input');
  selected=other;h.scope(selected);
  if(publishEvent) h.notifyScope();
  h.flushTimers();await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(calls,[],'An old edit must never trigger a review against the newly selected repository');
  h.input.value='New edit';h.input.dispatch('input');h.flushTimers();
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(calls,[['other-repository','New edit']]);
});
