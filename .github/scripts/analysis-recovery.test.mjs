import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
const analysisSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis.js', import.meta.url), 'utf8');
const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-recovery.js', import.meta.url), 'utf8');
function node() {
  return { textContent:'', hidden:false, disabled:false, style:{}, value:'', children:[], classList:{ toggle(){}, add(){}, remove(){} },
    append(...items){ this.children.push(...items); }, prepend(...items){ this.children.unshift(...items); },
    replaceChildren(...items){ this.children=items; }, setAttribute(){}, addEventListener(){},
    querySelectorAll(){ return []; }, remove(){} };
}
function harness() {
  const listeners = new Map(), storage = new Map(), timers = new Map(); let timer=0, pending, followups=0;
  const fields = { businessText: Object.assign(node(),{value:'Hospital communications'}),
    copilotBtn:node(),analyzeBtn:node(),copilotSpinner:node(),providerSelect:Object.assign(node(),{value:'CUSTOM_OPENAI'}) };
  const S = {}, runtime = {workspaceId:'ws-a', analysisGeneration:0};
  const requests=[], analyses=[], saved=[], panels=[], derivedRequests=[]; let openOptions=[], snapshot;
  const ui = { update(message){ this.message=message; }, close(){ this.opened=false; }, error(message){ this.errorText=message; },
    open(title,message,build,options){ this.opened=true; openOptions=options; build(node(),()=>node()); } };
  const window = { TaxonomyState:S,__TaxonomyAnalysisSessionContext:{S,runtime,language:()=> 'en'},
    TaxonomyRecoveryViewport:{mount:()=>ui},
    TaxonomyAnalysisSession:{state:()=>({ready:true,workspaceId:runtime.workspaceId}),saveNow:async()=>{saved.push(JSON.parse(JSON.stringify(S)));return true;}},
    TaxonomyAnalysisSessionApi:{request:async(url,options,workspace)=>{
      requests.push({url,method:options.method,workspace});
      if(options.method==='POST') snapshot={...snapshot,recovery:{...snapshot.recovery,state:'CANCELLED'}};
      return {ok:true,json:async()=>snapshot};
    }},
    TaxonomyScoring:{runAnalysis(options){analyses.push(options.continuation);return new Promise(resolve=>{pending=resolve;});}},
    TaxonomyAnalysis:{runCopilotFlow:async()=>{followups++;},renderPartialCopilot:message=>panels.push(message)} };
  const document={getElementById:id=>fields[id]||null,addEventListener:(name,fn)=>{if(!listeners.has(name))listeners.set(name,[]);listeners.get(name).push(fn);}};
  const context={window,document,crypto:webcrypto,sessionStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},
    setTimeout:fn=>{timers.set(++timer,fn);return timer;},clearTimeout:id=>timers.delete(id), console};
  vm.runInNewContext(source,context);
  function complete(state='PAUSED') {
    const q={key:'question-a',nodes:['BP-1327','BP-1481'],error:'Invalid JSON',attempts:1,skipped:state==='COMPLETED_WITH_GAPS'||state==='STOPPED'};
    const result={recovery:{id:S.recoveryContext.id,version:7,state,completedCalls:2,openQuestions:state==='COMPLETED'?[]:[q]},
      rawScores:{BP:100,'BP-1000':80},scores:{BP:100,'BP-1000':80},analysisCoverage:{failedOrBlockedNodes:state==='COMPLETED'?0:4,nodes:{}},status:state==='COMPLETED'?'SUCCESS':'PARTIAL'};
    snapshot={request:S.recoveryContext.request,recovery:result.recovery,result};pending(result);return result;
  }
  function loadAnalysis() {
    for (const id of ['copilotContent', 'copilotPanel', 'gapAnalysisContent', 'gapAnalysisPanel',
      'patternDetectionContent', 'patternDetectionPanel', 'recommendationContent', 'recommendationPanel']) fields[id] = node();
    context.TaxonomyI18n = { t:key=>key };
    context.TaxonomyUtils = { escapeHtml:value=>String(value ?? '') };
    context.fetch = async url => { derivedRequests.push(url); return {ok:true,json:async()=>({totalGaps:0,totalAnchors:1})}; };
    vm.runInNewContext(analysisSource,context);
  }
  return {S,runtime,ui,analyses,requests,saved,panels,fields,timers,window,document,complete,loadAnalysis,derivedRequests,
    followups:()=>followups,options:()=>openOptions,
    act:id=>openOptions.find(o=>o.id===id).handler(),emit:name=>(listeners.get(name)||[]).forEach(fn=>fn({detail:{}})),
    recovery:window.TaxonomyAnalysisRecovery};
}
async function settle(){for(let i=0;i<8;i++)await new Promise(resolve=>setImmediate(resolve));}
test('fresh Copilot opts into durable questions and prevents duplicate starts',async()=>{
 const h=harness();h.recovery.startCopilot();h.recovery.startCopilot();assert.equal(h.analyses.length,1);
 assert(h.analyses[0].resumable);assert.equal(h.analyses[0].continuationAction,'START');
 h.complete();await settle();assert(h.ui.opened);assert.equal(h.S.analysisRecovery.state,'PAUSED');assert.equal(h.followups(),0);assert.equal(h.timers.size,0);
});
test('scoped Copilot freezes selection and does not infer global gaps after selected work completes',async()=>{
 const h=harness();
 const selected={taxonomyRoots:['BP'],mode:'TAXONOMIES_ONLY'};
 let nextScope=selected;
 h.window.TaxonomyAnalysisScope={read:()=>nextScope,
   acceptResult:scope=>{h.S.lastAnalysisScope=scope;},
   restrictsGlobalAnalysis:scope=>scope?.mode==='TAXONOMIES_ONLY'||Boolean(scope?.taxonomyRoots?.length)};
 h.recovery.startCopilot();
 assert.deepEqual(JSON.parse(JSON.stringify(h.analyses[0].analysisScope)),selected);
 assert.equal(h.analyses[0].includeArchitectureView,false);
 nextScope={taxonomyRoots:['CP'],mode:'FULL'};
 h.complete('COMPLETED');await settle();
 // This harness stubs scoring's normal response hydration. Exercise the actual
 // recovery restore path to hydrate evidence from the frozen server request.
 h.window.TaxonomyScoring.renderArchitectureView=()=>{};
 h.window.TaxonomyScoring.renderSuggestedRelations=()=>{};
 await h.recovery.refresh();
 assert.equal(h.S.recoveryContext.observationError,null);
 assert.deepEqual(JSON.parse(JSON.stringify(h.S.lastAnalysisScope)),selected);
 assert.deepEqual(JSON.parse(JSON.stringify(h.S.recoveryContext.request.analysisScope)),selected);
 assert.equal(h.S.recoveryContext.followupState,'COMPLETED_SCOPED');
 assert.equal(h.followups(),0);assert.equal(h.panels.length,1);
 assert.match(h.panels[0],/selected analysis scope is complete/i);
});
test('rendered Retry continues the same operation and exact failed question revision',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();const id=h.analyses[0].continuationId;
 h.act('analysisRecoveryRetry');assert.equal(h.analyses.length,2);assert.equal(h.analyses[1].continuationId,id);
 assert.equal(h.analyses[1].continuationVersion,7);assert.equal(h.analyses[1].continuationQuestion,'question-a');
 assert.equal(h.analyses[1].continuationAction,'RETRY');h.complete('COMPLETED');await settle();assert.equal(h.followups(),1);
});
test('leave-open continues other work without authorizing global gap or recommendation claims',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();h.act('analysisRecoverySkip');
 assert.equal(h.analyses[1].continuationAction,'SKIP');h.complete('COMPLETED_WITH_GAPS');await settle();
 assert.equal(h.followups(),0);assert.equal(h.panels.length,1);assert(h.recovery.hasOpenEvaluations());
 assert.equal(h.S.recoveryContext.followupState,'COMPLETED_WITH_GAPS');assert.match(h.ui.message,/partial result/i);
});
test('cancel is an authenticated scoped command and retains stored valid scores',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();await h.recovery.cancel();
 assert.equal(h.requests.at(-1).method,'POST');assert.equal(h.requests.at(-1).workspace,'ws-a');
 assert(h.requests.at(-1).url.endsWith('/cancel'));assert.equal(h.S.currentScores.BP,100);
 assert.equal(h.S.analysisRecovery.state,'CANCELLED');assert.equal(h.analyses.length,1);
});
test('a late response after switching workspace never releases enrichment',async()=>{
 const h=harness();h.recovery.startCopilot();h.runtime.workspaceId='ws-b';h.complete('COMPLETED');await settle();
 assert.equal(h.followups(),0);assert.equal(h.S.analysisRecovery.state,'RUNNING');
});
test('an invalidated run cannot update the newly selected scope',async()=>{
 const h=harness();h.recovery.startCopilot();h.runtime.analysisGeneration++;h.emit('taxonomy:analysis-invalidated');
 h.complete('COMPLETED');await settle();assert.equal(h.followups(),0);assert.equal(h.fields.copilotBtn.disabled,false);
});
test('saved successful follow-up stages are not repeated after a later failed stage',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();let a=0,b=0;
 await h.recovery.stage('GAPS',async()=>{a++;return {totalGaps:1};});
 await assert.rejects(h.recovery.stage('PATTERNS',async()=>{b++;throw Error('HTTP 503');}));
 await h.recovery.stage('GAPS',async()=>{a++;return {};});
 await h.recovery.stage('PATTERNS',async()=>{b++;return {patterns:[]};});
 assert.equal(a,1);assert.equal(b,2);assert(h.saved.at(-1).recoveryContext.followups.GAPS);
});
test('scope changes while a follow-up is in flight discard its response',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();let resolve;
 const work=h.recovery.stage('GAPS',()=>new Promise(r=>{resolve=r;}));h.fields.businessText.value='Another requirement';resolve({totalGaps:0});
 await assert.rejects(work);assert.equal(h.S.recoveryContext.followups.GAPS,undefined);
});

test('import detaches the old journal pointer without discarding imported coverage',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();
 const coverage=h.S.analysisCoverage;h.emit('taxonomy:analysis-evidence-imported');
 assert.equal(h.S.recoveryContext,null);assert.equal(h.S.analysisRecovery,null);
 assert.equal(h.S.analysisCoverage,coverage);assert.equal(h.recovery.isManaged(),false);
 assert.equal(h.fields.copilotBtn.disabled,false);
});
test('translated progress labels do not change cached stage identities',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();let calls=0;
 await h.recovery.stage('gap',async()=>{calls++;return {};},'Lückenprüfung');
 await h.recovery.stage('gap',async()=>{calls++;return {};},'Gap analysis');
 assert.equal(calls,1);
});

test('a runtime stop can continue independent work while an earlier area remains skipped',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete('STOPPED');await settle();
 assert(h.options().some(o=>o.id==='analysisRecoveryContinue'));
 h.act('analysisRecoveryContinue');assert.equal(h.analyses[1].continuationAction,'CONTINUE');
 assert.equal(h.analyses[1].continuationQuestion,null);h.complete('COMPLETED_WITH_GAPS');await settle();
 assert.equal(h.followups(),0);
});


test('imported open evidence cannot authorize the public Copilot follow-up flow',async()=>{
 const h=harness();h.loadAnalysis();
 h.S.currentScores={BP:100};h.S.lastAnalysisStatus='PARTIAL';
 h.S.analysisCoverage={failedOrBlockedNodes:2,nodes:{}};
 h.emit('taxonomy:analysis-evidence-imported');
 assert.equal(h.recovery.isManaged(),false);
 await h.window.TaxonomyAnalysis.runCopilotFlow();
 assert.deepEqual(h.derivedRequests,[], 'Missing assessments must not become global absence claims after import');
 assert.match(h.fields.copilotContent.innerHTML,/partial|unassessed/i);
});
for(const [method,panel] of [['runGapAnalysis','gapAnalysisContent'],
 ['runPatternDetection','patternDetectionContent'],['runRecommendation','recommendationContent']]){
 test(`${method} explains why imported unknown evidence is insufficient, without a journal dialog`,async()=>{
  const h=harness();h.loadAnalysis();h.S.currentScores={BP:100};h.S.analysisCoverage={failedOrBlockedNodes:1};
  h.emit('taxonomy:analysis-evidence-imported');
  await h.window.TaxonomyAnalysis[method]();
  assert.deepEqual(h.derivedRequests,[]);
  assert.match(h.fields[panel].innerHTML || '',/partial|unassessed/i,
    'Clicking a blocked action must not silently do nothing');
 });
}
for (const [method, panel, urls] of [
 ['runGapAnalysis', 'gapAnalysisContent', ['/api/gap/analyze']],
 ['runPatternDetection', 'patternDetectionContent', ['/api/patterns/detect']],
 ['runRecommendation', 'recommendationContent', ['/api/recommend']],
 ['runCopilotFlow', 'copilotContent', ['/api/gap/analyze', '/api/patterns/detect', '/api/recommend']]
]) {
 for (const [label, coverage] of [
  ['missing', undefined], ['null', null], ['incomplete', {nodes:{}}],
  ['without known failures', {failedOrBlockedNodes:0, nodes:{}}]
 ]) {
  test(`${method} blocks legacy PARTIAL evidence with ${label} coverage`, async () => {
   const h = harness(); h.loadAnalysis();
   h.S.currentScores = {BP:100}; h.S.lastAnalysisStatus = 'PARTIAL';
   h.S.analysisCoverage = coverage;
   h.emit('taxonomy:analysis-evidence-imported');

   await h.window.TaxonomyAnalysis[method](); await settle();

   assert.deepEqual(h.derivedRequests, [], 'A partial status cannot authorize global conclusions');
   assert.match(h.fields[panel].innerHTML || '', /partial|unassessed/i);
   assert.deepEqual(h.S.currentScores, {BP:100}, 'Blocking enrichment retains valid local scores');
  });
 }
 test(`${method} retains completed legacy evidence without coverage`, async () => {
  const h = harness(); h.loadAnalysis();
  h.S.currentScores = {BP:100}; h.S.lastAnalysisStatus = 'SUCCESS';

  await h.window.TaxonomyAnalysis[method](); await settle();

  assert.deepEqual(h.derivedRequests, urls);
 });
}
test('a failed follow-up checkpoint pauses and retry persists the result without repeating the operation',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();let calls=0;
 h.window.TaxonomyAnalysisSession.saveNow=async()=>false;
 const operation=async()=>{calls++;return {totalGaps:0};};
 await assert.rejects(h.recovery.stage('gap',operation),/sav|persist/i);
 assert.equal(calls,1);
 h.window.TaxonomyAnalysisSession.saveNow=async()=>true;
 const result=await h.recovery.stage('gap',operation);
 assert.equal(result.totalGaps,0);assert.equal(calls,1);
});
test('changing the workspace during follow-up persistence never releases a result to the new workspace',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete();await settle();let release;
 h.window.TaxonomyAnalysisSession.saveNow=()=>new Promise(resolve=>{release=resolve;});
 const work=h.recovery.stage('gap',async()=>({totalGaps:0}));
 await settle();assert.equal(typeof release,'function');
 h.runtime.workspaceId='ws-b';release(true);
 await assert.rejects(work,/context|scope/i);
});

test('interrupted unvisited catalogue rows are explicitly labelled unassessed',()=>{
 const h=harness(),header=node(),attributes=new Map([['data-code','CP']]);
 const row={getAttribute:key=>attributes.get(key)||'',setAttribute:(key,value)=>attributes.set(key,value),
  querySelector:()=>header};
 header.querySelector=()=>header.children.find(child=>child.className==='analysis-coverage-node')||null;
 h.document.createElement=node;
 h.fields.taxonomyTree={querySelectorAll:()=>[row]};h.fields.analysisCoverageWarning=node();
 for(const state of ['PAUSED','STOPPED','CANCELLED']){
  h.S.analysisCoverage={failedOrBlockedNodes:1,nodes:{CP:{state:'UNKNOWN',descendants:'UNASSESSED',reason:'INTERRUPTED:'+state}}};
  h.recovery.renderCoverage();
  assert.equal(header.children[0]?.textContent,'Unassessed');
  assert.equal(attributes.get('aria-describedby'),'analysisCoverage-CP');
 }
 assert.equal(header.children.length,1,'Status refresh must retain the same accessible node badge');
});
