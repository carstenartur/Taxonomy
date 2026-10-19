import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
const analysisSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis.js', import.meta.url), 'utf8');
const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-recovery.js', import.meta.url), 'utf8');
function node() {
  const listeners = new Map();
  return { textContent:'', hidden:false, disabled:false, style:{}, value:'', children:[], classList:{ toggle(){}, add(){}, remove(){} },
    append(...items){ this.children.push(...items); }, prepend(...items){ this.children.unshift(...items); },
    replaceChildren(...items){ this.children=items; }, setAttribute(){},
    addEventListener(name,fn){listeners.set(name,fn);}, fire(name){return listeners.get(name)?.();},
    querySelectorAll(){ return []; }, remove(){} };
}
function harness(language = 'en') {
  const listeners = new Map(), storage = new Map(), timers = new Map(); let timer=0, pending, rejectPending, followups=0;
  const fields = { businessText: Object.assign(node(),{value:'Hospital communications'}),
    copilotBtn:node(),analyzeBtn:node(),copilotSpinner:node(),providerSelect:Object.assign(node(),{value:'CUSTOM_OPENAI'}) };
  const S = {}, runtime = {workspaceId:'ws-a', analysisGeneration:0};
  const requests=[], analyses=[], saved=[], panels=[], derivedRequests=[], readResponses=[]; let openOptions=[], snapshot;
  const ui = { update(message){ this.message=message; }, close(){ this.opened=false; }, error(message){ this.errorText=message; },
    isOpen(){return Boolean(this.opened);}, isDismissed(){return false;},
    open(title,message,build,options){
      this.opened=true; this.errorText=null; this.message=message; openOptions=options; this.body=node();
      build(this.body,(tag,text)=>Object.assign(node(),{tagName:tag,textContent:text||''}));
      for(const option of options) fields[option.id]=Object.assign(node(),{disabled:Boolean(option.disabled)});
    } };
  const window = { TaxonomyState:S,__TaxonomyAnalysisSessionContext:{S,runtime,language:()=> language},
    TaxonomyRecoveryViewport:{mount:()=>ui},
    TaxonomyAnalysisSession:{state:()=>({ready:true,workspaceId:runtime.workspaceId}),saveNow:async()=>{saved.push(JSON.parse(JSON.stringify(S)));return true;}},
    TaxonomyAnalysisSessionApi:{request:async(url,options,workspace)=>{
      requests.push({url,method:options.method,workspace});
      if(options.method==='GET' && readResponses.length){const next=readResponses.shift();if(next instanceof Error)throw next;return next;}
      if(options.method==='POST') snapshot={...snapshot,recovery:{...snapshot.recovery,state:'CANCELLED'}};
      return {ok:true,json:async()=>snapshot};
    }},
    TaxonomyScoring:{renderArchitectureView(){},renderSuggestedRelations(){},
      runAnalysis(options){analyses.push(options.continuation);return new Promise((resolve,reject)=>{pending=resolve;rejectPending=reject;});}},
    TaxonomyAnalysis:{runCopilotFlow:async()=>{followups++;},renderPartialCopilot:message=>panels.push(message)} };
  const document={getElementById:id=>fields[id]||null,addEventListener:(name,fn)=>{if(!listeners.has(name))listeners.set(name,[]);listeners.get(name).push(fn);}};
  const context={window,document,crypto:webcrypto,sessionStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},
    setTimeout:fn=>{timers.set(++timer,fn);return timer;},clearTimeout:id=>timers.delete(id), console};
  vm.runInNewContext(source,context);
  function complete(state='PAUSED') {
    const q={key:'question-a',nodes:['BP-1327','BP-1481'],error:'Invalid JSON',attempts:1,skipped:state==='COMPLETED_WITH_GAPS'||state==='STOPPED'};
    const result={recovery:{id:S.recoveryContext.id,version:7,state,completedCalls:2,openQuestions:state==='COMPLETED'?[]:[q]},
      rawScores:{BP:100,'BP-1000':80},scores:{BP:100,'BP-1000':80},analysisCoverage:{failedOrBlockedNodes:state==='COMPLETED'?0:4,nodes:{}},status:state==='COMPLETED'?'SUCCESS':'PARTIAL',reasons:{BP:'Original reason'}};
    S.currentRawScores=result.rawScores;S.currentEffectiveScores=result.scores;S.currentScores=result.scores;S.currentReasons=result.reasons;
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
  function loadRelationsExtension() {
    for(const id of ['analysisRelationsExtension','extendAnalysisRelationsBtn','analysisRelationsExtensionHint'])fields[id]=node();
    vm.runInNewContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-relations-extension.js',import.meta.url),'utf8'),context);
  }
  return {S,runtime,ui,analyses,requests,saved,panels,fields,timers,window,document,complete,loadAnalysis,loadRelationsExtension,derivedRequests,readResponses,
    snapshot:()=>snapshot,
    followups:()=>followups,options:()=>openOptions,
    act:id=>openOptions.find(o=>o.id===id).handler(),emit:(name,detail={})=>(listeners.get(name)||[]).forEach(fn=>fn({detail})),
    reject: error=>rejectPending(error), recovery:window.TaxonomyAnalysisRecovery};
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

const renderedText = element => [element.textContent, ...element.children.map(renderedText)].join(' ');
const descendants = element => [element, ...element.children.flatMap(descendants)];
test('an unknown node-only source cannot allocate a relation extension identity', async()=>{
 const h=harness();h.window.TaxonomyAnalysisScope={read:()=>({taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'}),restrictsGlobalAnalysis:()=>true};
 h.recovery.startCopilot();h.complete('COMPLETED');await settle();h.loadRelationsExtension();
 assert.equal(h.fields.extendAnalysisRelationsBtn.disabled,false);
 const id=h.S.recoveryContext.id;h.readResponses.push(new TypeError('Failed to fetch'));await h.recovery.refresh();
 h.window.TaxonomyRelationsExtension.render();
 assert.equal(h.fields.extendAnalysisRelationsBtn.disabled,true);
 assert.match(h.fields.analysisRelationsExtensionHint.textContent,/status/i);
 h.recovery.extendRelations();assert.equal(h.S.recoveryContext.id,id);assert.equal(h.analyses.length,1);
});
test('unknown status also blocks direct follow-up work on a previously complete result',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete('COMPLETED');await settle();
 h.readResponses.push(new TypeError('Failed to fetch'));await h.recovery.refresh();let calls=0;
 assert.equal(h.recovery.hasOpenEvaluations(),true);
 await assert.rejects(h.recovery.stage('EXTRA',async()=>{calls++;return {};}));assert.equal(calls,0);
});
test('a processing failure in a newer status response is reported without claiming a network failure',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete('STOPPED');await settle();
 const snapshot=JSON.parse(JSON.stringify(h.snapshot()));snapshot.recovery.version=9;snapshot.result.recovery=snapshot.recovery;
 h.readResponses.push({ok:true,json:async()=>snapshot});
 h.window.TaxonomyScoring.renderArchitectureView=()=>{throw new Error('Cannot render retained model');};
 await h.recovery.refresh();
 assert.equal(h.S.recoveryContext.syncState,'UNAVAILABLE');
 assert.match(h.S.recoveryContext.observationError,/Cannot render retained model/);
 assert.match(renderedText(h.ui.body),/status response/i);
 assert.doesNotMatch(h.ui.message,/Connection interrupted/i);
 assert(!h.options().some(option=>option.id==='analysisRecoveryContinue'));
});
test('successful cancellation confirms the final status after a failed observation',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete('STOPPED');await settle();
 h.readResponses.push(new TypeError('Failed to fetch'));await h.recovery.refresh();await h.recovery.cancel();
 h.recovery.open();assert.equal(h.S.analysisRecovery.state,'CANCELLED');
 assert.equal(h.S.recoveryContext.syncState,null);assert.equal(h.S.recoveryContext.observationError,null);
 assert.match(h.ui.message,/Cancelled/);assert.doesNotMatch(h.ui.message,/status unknown/i);
});
test('a read already in flight cannot replace synchronization after a rejected decision', async()=>{
 const h=harness();h.recovery.startCopilot();h.complete('STOPPED');await settle();
 const old=JSON.parse(JSON.stringify(h.snapshot())), latest=JSON.parse(JSON.stringify(old));
 latest.recovery.version=9;latest.result.recovery=latest.recovery;
 h.act('analysisRecoveryRetry');
 let release;h.readResponses.push(new Promise(resolve=>{release=resolve;}));
 const priorRead=h.recovery.refresh({background:true});await settle();
 h.readResponses.push({ok:true,json:async()=>latest});
 h.reject(Object.assign(new Error('A newer operation revision exists; refresh before deciding'),{status:409}));
 await settle();
 assert(!h.options().some(option=>option.id==='analysisRecoveryRetry'));
 release({ok:true,json:async()=>old});await priorRead;await settle();
 assert.equal(h.requests.filter(request=>request.method==='GET').length,2,'The outcome requires a read sent after the rejected write');
 assert.equal(h.S.analysisRecovery.version,9);assert.equal(h.analyses.length,2);
 assert.equal(h.ui.errorText,null);
});
for(const late of ['old response','old connection failure']) {
 test(`a ${late} cannot overwrite a newer confirmed analysis result`,async()=>{
  const h=harness();h.recovery.startCopilot();h.complete('STOPPED');await settle();
  const old=JSON.parse(JSON.stringify(h.snapshot()));h.act('analysisRecoveryRetry');
  let release,reject;h.readResponses.push(new Promise((a,b)=>{release=a;reject=b;}));
  const read=h.recovery.refresh({background:true});await settle();
  const result=h.complete('COMPLETED');result.recovery.version=11;result.rawScores.BP=75;result.scores.BP=75;await settle();
  if(late==='old response')release({ok:true,json:async()=>old});else reject(new TypeError('Failed to fetch'));
  await read;await settle();
  assert.equal(h.S.analysisRecovery.version,11);assert.equal(h.S.analysisRecovery.state,'COMPLETED');
  assert.equal(h.S.currentScores.BP,75);assert.equal(h.S.recoveryContext.syncState,null);
  assert.equal(h.analyses.length,2);
 });
}
for (const language of ['de', 'en']) {
 test(`${language} an explicitly cleared relation report cannot revive an old stop reason`, async () => {
  const h=harness(language); h.recovery.startCopilot();
  const result=h.complete('STOPPED'); result.recovery.openQuestions=[];
  result.relationSearchReport=null;
  result.architectureView={relationSearchReport:{stopReason:'TIME_LIMIT: obsolete nested report'}};
  await settle();
  assert.doesNotMatch(renderedText(h.ui.body),/obsolete nested report/);
  assert.match(renderedText(h.ui.body),language==='de' ? /Kein konkreter Unterbrechungsgrund gespeichert/ : /No specific interruption reason was recorded/);
 });
 test(`${language} progress summaries are separate from the recorded interruption`, async () => {
  for(const stopReason of ['', 'TIME_LIMIT: completed evidence retained']) {
   const h=harness(language); h.recovery.startCopilot();
   const result=h.complete('STOPPED'); result.recovery.openQuestions=[];
   result.errorMessage='RELATION_SEARCH_PARTIAL: 24/24 evaluation attempts; 12 unfinished search batches. '+stopReason;
   result.relationSearchReport={stopReason,warnings:[],result:{unfinished:[]}};
   await settle();
   const body=renderedText(h.ui.body);
   if(stopReason) assert.equal(body.split(stopReason).length-1,1);
   else assert.match(body,language==='de' ? /Kein konkreter Unterbrechungsgrund gespeichert/ : /No specific interruption reason was recorded/);
  }
 });
 test(`${language} a rejected stale retry loads the new revision without repeating the decision`, async () => {
  const h=harness(language); h.recovery.startCopilot(); h.complete('STOPPED'); await settle();
  const before=h.analyses.length, oldRetry=h.options().find(option=>option.id==='analysisRecoveryRetry').handler;
  oldRetry();
  const snapshot=JSON.parse(JSON.stringify(h.snapshot()));
  snapshot.recovery.version=9; snapshot.recovery.completedCalls=97;
  snapshot.recovery.openQuestions=[{key:'new-question',nodes:['IP-2051'],attempts:1,error:'RELATION_UNRESOLVED: Which time data?'}];
  snapshot.result.recovery=snapshot.recovery;
  h.readResponses.push({ok:true,json:async()=>snapshot});
  h.reject(Object.assign(new Error('A newer operation revision exists; refresh before deciding'),{httpStatus:409}));
  await settle();
  assert.equal(h.S.analysisRecovery.version,9);
  assert.equal(h.analyses.length,before+1,'Only the rejected explicit action was sent');
  assert.match(renderedText(h.ui.body),language==='de' ? /aktuellen Stand geladen/ : /loaded the current state/);
  assert.equal(h.ui.errorText,null,'A resolved conflict must not remain as a raw error banner');
  oldRetry(); assert.equal(h.analyses.length,before+1,'A control from the old revision cannot authorize a new write');
  h.act('analysisRecoveryContinue');
  assert.equal(h.analyses.at(-1).continuationVersion,9);
  assert.equal(h.analyses.at(-1).continuationAction,'CONTINUE');
  h.complete('COMPLETED_WITH_GAPS'); await settle();
 });
 test(`${language} an unreachable status offers connection recovery and blocks stale model decisions`, async () => {
  const h=harness(language); h.recovery.startCopilot(); h.complete('STOPPED'); await settle();
  const retry=h.options().find(option=>option.id==='analysisRecoveryRetry').handler;
  retry(); h.readResponses.push(new TypeError('Failed to fetch')); h.reject(new TypeError('Failed to fetch'));
  await settle();
  assert.match(h.ui.message,language==='de' ? /Status unbekannt/ : /status unknown/i);
  assert.match(renderedText(h.ui.body),language==='de' ? /Verbindung zur Anwendung/ : /connection to the application/i);
  for(const id of ['analysisRecoveryRetry','analysisRecoverySkip','analysisRecoveryContinue'])
   assert(!h.options().some(option=>option.id===id),'Unsafe action must be unavailable: '+id);
  assert(h.options().some(option=>option.id==='analysisRecoveryRefresh'));
  retry(); h.recovery.startCopilot(); h.recovery.startManagedAnalysis(); assert.equal(h.analyses.length,2,'Unknown outcome cannot create another provider request or identity');
  const snapshot=JSON.parse(JSON.stringify(h.snapshot()));
  snapshot.recovery.version=10; snapshot.recovery.state='RUNNING';
  h.readResponses.push({ok:true,json:async()=>snapshot});
  await h.act('analysisRecoveryRefresh');
  assert.equal(h.S.analysisRecovery.version,10);
  assert.match(h.ui.message,language==='de' ? /läuft/ : /running/i);
  assert(!h.options().some(option=>option.id==='analysisRecoveryRetry'));
  assert.equal(h.analyses.length,2);
 });
 test(`${language} an unconfirmed cancellation is explained and never resubmitted automatically`, async () => {
  const h=harness(language);h.recovery.startCopilot();h.complete('STOPPED');await settle();
  const request=h.window.TaxonomyAnalysisSessionApi.request;let cancels=0;
  h.window.TaxonomyAnalysisSessionApi.request=async(url,options,workspace)=>{
   if(options.method==='POST'){cancels++;throw new TypeError('Failed to fetch');}
   return request(url,options,workspace);
  };
  h.readResponses.push(new TypeError('Failed to fetch'));await h.recovery.cancel();await settle();
  assert.match(h.ui.message,language==='de' ? /Status unbekannt/ : /status unknown/i);
  assert(h.options().some(option=>option.id==='analysisRecoveryRefresh'));
  assert(!h.options().some(option=>option.id==='analysisRecoveryRetry'));
  assert.equal(cancels,1);assert.equal(h.analyses.length,1);
 });
 test(`${language} status refresh remains available for a stopped run without a transport error`, async () => {
  const h=harness(language); h.recovery.startCopilot(); h.complete('STOPPED'); await settle();
  assert(h.options().some(option=>option.id==='analysisRecoveryRefresh'));
  h.readResponses.push(new Error('Network request failed'));
  await h.act('analysisRecoveryRefresh');
  assert(!h.options().some(option=>option.id==='analysisRecoveryContinue'));
 });
 test(`${language} a valid semantic question explains why another model request may not resolve it`, async () => {
  const h=harness(language); h.recovery.startCopilot();
  const result=h.complete('STOPPED');
  result.recovery.openQuestions=[{key:'relation-a',nodes:['CP-1122'],attempts:2,error:'RELATION_UNRESOLVED: Does time tracking belong here?'}];
  await settle();
  assert.match(renderedText(h.ui.body),language==='de' ? /fachliche Zuordnung/ : /semantic assignment/i);
  assert.match(renderedText(h.ui.body),language==='de' ? /gleichen? Unsicherheit/ : /same uncertainty/);
  assert.equal(h.options()[0].id,'analysisRecoveryContinue','Independent saved work is the first action');
 });
 test(`${language} a source clarification does not hide the recorded runtime stop`, async () => {
  const h=harness(language); h.recovery.startCopilot();
  const result=h.complete('STOPPED');
  result.errorMessage='TIME_LIMIT: completed evidence retained; no further model evaluations.';
  result.recovery.openQuestions=[{key:'relation-a',nodes:['CP-1000','CP-1023','CP-1122'],attempts:1,
   error:'RELATION_UNRESOLVED: CP-1122: Does IT Services include administrative time tracking?'}];
  await settle();
  const body=renderedText(h.ui.body);
  assert.match(body,/TIME_LIMIT/);
  assert.match(body,/Does IT Services include administrative time tracking/);
  assert.match(body,language==='de' ? /Grund der Unterbrechung/ : /Reason for interruption/);
  assert.doesNotMatch(body,/LLM-Bewertung fehlgeschlagen|LLM assessment failed/);
  h.act('analysisRecoveryContinue');
  assert.equal(h.analyses[1].continuationAction,'CONTINUE');
  assert.equal(h.analyses[1].continuationQuestion,null);
  assert.equal(h.analyses[1].continuationQuestions.length,0);
  h.complete('COMPLETED_WITH_GAPS'); await settle();
  assert.equal(h.followups(),0);
 });
 test(`${language} relation budget evidence survives result hydration and refresh`, async () => {
  for (const report of [
   {stopReason:'MEMORY_PRESSURE: completed evidence retained'},
   {warnings:['SOURCE_UNRESOLVED CP-1122: Catalogue scope?', 'SOURCE_LIMIT: 9 sources remain unassessed.']},
   {result:{unfinished:[{reason:'CALL_BUDGET',question:''}]}},
   {warnings:['WORK_LIMIT: remaining searches retained']},
   {warnings:['TOTAL_CALL_BUDGET: cumulative budget exhausted']}
  ]) {
   const h=harness(language); h.recovery.startCopilot();
   const result=h.complete('STOPPED'); result.relationSearchReport=report;
   await settle();
   const reason=report.stopReason || report.warnings?.at(-1) || 'CALL_BUDGET';
   assert(renderedText(h.ui.body).includes(reason),reason);
   h.S.recoveryContext.pauseReason=null;
   await h.recovery.refresh(); h.recovery.open();
   assert.equal(h.S.recoveryContext.observationError,null,renderedText(h.ui.body));
   assert(renderedText(h.ui.body).includes(reason),'refresh: '+reason);
   assert.equal(h.analyses.length,1,'reading diagnostics cannot authorize another provider call');
  }
 });
 test(`${language} budget-stop navigation closes the dialog, focuses server settings and never starts work`, async () => {
  for (const code of ['CALL_BUDGET', 'TOTAL_CALL_BUDGET', 'SOURCE_LIMIT', 'WORK_LIMIT']) {
   const h=harness(language), actions=[]; let administrator=true;
   h.window.TaxonomyRoleSurface={isAdministrator:()=>administrator};
   h.window.navigateToPage=page=>{assert.equal(h.ui.opened,false);actions.push(page);};
   h.fields['pref-relations-section']=Object.assign(node(),{
    scrollIntoView(){actions.push('scroll');},focus(){actions.push('focus');}
   });
   h.recovery.startCopilot();const result=h.complete('STOPPED');
   result.relationSearchReport={stopReason:code};await settle();
   assert(h.options().some(option=>option.id==='analysisRecoveryBudgetSettings'),code);
   h.act('analysisRecoveryBudgetSettings');
   assert.deepEqual(actions,['preferences','scroll','focus']);
   assert.equal(h.analyses.length,1);assert.equal(h.requests.length,0);
   h.recovery.open();administrator=false;
   h.act('analysisRecoveryBudgetSettings');assert.equal(actions.length,3,'role is checked again at activation');
   h.recovery.open();assert(!h.options().some(option=>option.id==='analysisRecoveryBudgetSettings'));
  }
 });
 test(`${language} recovery separates the saved cumulative budget from new assessments and incomplete work`, async () => {
  const h=harness(language);
  h.window.TaxonomyScoring.acceptRelationSearchReport=result=>{h.S.currentRelationSearchReport=result.relationSearchReport;};
  h.recovery.startCopilot();const result=h.complete('STOPPED');
  result.relationSearchReport={stopReason:'TOTAL_CALL_BUDGET',budget:{callsPerSlice:256,
   maxTotalCalls:4096,newCalls:512,spentCalls:4096,automatic:true},
   progress:{completedSearches:28,totalSearches:80,unresolvedSearches:4,pendingSearches:48,verifiedRelations:12}};
  await settle();
  // The fixture stubs scoring's normal response hydration. A real read exercises
  // the recovery module's hydration of the same saved relation report.
  await h.recovery.refresh();h.recovery.open();
  const body=renderedText(h.ui.body);
  assert.match(body,language==='de' ? /Gesamtbudget.*4096.*4096/ : /Total budget.*4096.*4096/);
  assert.match(body,language==='de' ? /Neue Prüfungen.*512/ : /New evaluations.*512/);
  assert.match(body,language==='de' ? /Startbudget.*256/ : /Initial budget.*256/);
  assert.match(body,language==='de' ? /Sucharbeit.*offen/ : /search work.*open/i);
  assert(!h.options().some(option=>option.id==='analysisRecoveryBudgetSettings'),'ordinary users do not receive an admin action');
  assert.equal(h.analyses.length,1);assert.equal(h.followups(),0);
 });
 test(`${language} missing stop evidence is explicit and never inferred from a semantic question`, async () => {
  const h=harness(language); h.recovery.startCopilot();
  const result=h.complete('STOPPED');
  result.recovery.openQuestions=[{key:'relation-a',nodes:['CP-1122'],attempts:1,
   error:'RELATION_UNRESOLVED: Which catalogue scope?'}];
  await settle();
  assert.match(renderedText(h.ui.body),language==='de' ? /Kein konkreter Unterbrechungsgrund gespeichert/ : /No specific interruption reason was recorded/);
  assert.doesNotMatch(renderedText(h.ui.body),/TIME_LIMIT|MEMORY_PRESSURE|CALL_BUDGET/);
 });
 test(`${language} completed semantic gaps are not presented as an interrupted run`, async () => {
  const h=harness(language); h.recovery.startCopilot();
  const result=h.complete('COMPLETED_WITH_GAPS');
  result.errorMessage='RELATION_SEARCH_PARTIAL: semantic scope remains open';
  result.recovery.openQuestions=[{key:'relation-a',nodes:['CP-1122'],attempts:1,
   error:'RELATION_UNRESOLVED: Which catalogue scope?'}];
  await settle(); h.recovery.open();
  assert.match(renderedText(h.ui.body),/Which catalogue scope/);
  assert.doesNotMatch(renderedText(h.ui.body),/Reason for interruption|Grund der Unterbrechung/);
 });
 test(`${language} a stop without an open question shows its recorded reason once`, async () => {
  const h=harness(language); h.recovery.startCopilot();
  const result=h.complete('STOPPED'); result.recovery.openQuestions=[];
  result.architectureView={relationSearchReport:{stopReason:'TIME_LIMIT: retained answers'}};
  await settle();
  assert.equal(renderedText(h.ui.body).split('TIME_LIMIT: retained answers').length-1,1);
  assert(h.options().some(option=>option.id==='analysisRecoveryContinue'));
 });
 test(`${language} recovery identifies an LLM assessment failure and explains retry and skip`, async () => {
  const h=harness(language); h.recovery.startCopilot(); h.complete(); await settle();
  h.S.analysisRecovery.openQuestions=[
   {key:'product-a',nodes:['IP-1365','IP-1676','IP-1775'],error:'Invalid independent product JSON',attempts:1,skipped:false},
   {key:'product-b',nodes:['IP-1800'],error:'HTTP 503',attempts:2,skipped:true,outcomeUncertain:true}
  ];
  h.recovery.open();
  const body=renderedText(h.ui.body), label=descendants(h.ui.body).find(n=>n.tagName==='label');
  assert.match(h.ui.message, language==='de' ? /Pausiert – Prüfung erforderlich/ : /Paused – review required/);
  assert.match(body,language==='de' ? /LLM-Bewertung fehlgeschlagen/ : /LLM assessment failed/);
  assert.match(label.textContent, language==='de' ? /Offene LLM-Bewertung.*betroffene Knoten/ : /Open LLM assessment.*affected nodes/);
  assert.doesNotMatch(body,/Open question|Offene Abfrage/);
  assert.equal(body.split('IP-1365').length-1,1,'Affected IDs appear once, in the selector');
  assert.match(body,language==='de' ? /interne LLM-Anfrage/ : /internal LLM request/);
  assert.match(body,language==='de' ? /neue KI-Anfrage/ : /new AI request/);
  assert.match(body,language==='de' ? /nicht bewertet/ : /unassessed/);
  const select=descendants(h.ui.body).find(n=>n.tagName==='select');
  select.value='product-b'; await select.fire('change');
  const changed=renderedText(h.ui.body);
  assert.match(changed,/HTTP 503/); assert.doesNotMatch(changed,/Invalid independent product JSON/);
  assert.match(changed,language==='de' ? /Versuche: 2/ : /Attempts: 2/);
  assert.match(changed,language==='de' ? /offengelassen/ : /left unassessed/);
  assert.match(changed,language==='de' ? /berechnet/ : /billed/);
  assert.match(changed,language==='de' ? /explizite Entscheidung erforderlich/ : /explicit decision is required/);
  assert.match(changed,language==='de' ? /Gespeicherte gültige Antworten werden ohne erneute Anbieteranfrage wiederverwendet/ : /Saved validated answers are reused without another provider request/);
  assert.equal(h.fields.analysisRecoverySkip.disabled,true);
  select.value='product-a'; await select.fire('change');
  assert.equal(h.fields.analysisRecoverySkip.disabled,false);
  assert.doesNotMatch(renderedText(h.ui.body),/billed|berechnet/);
  h.act('analysisRecoveryRetry');
  assert.equal(h.analyses[1].continuationQuestion,'product-a');
  assert.equal(h.analyses[1].continuationAction,'RETRY');
  h.complete('COMPLETED'); await settle();
 });
 test(`${language} unresolved relation evidence requires clarification without claiming a failed request`, async () => {
  const h=harness(language); h.recovery.startCopilot(); h.complete(); await settle();
  h.S.analysisRecovery.openQuestions=[
   {key:'relation-a',nodes:['BP-1327','BP-1481'],error:'RELATION_UNRESOLVED: review the retained response',attempts:1,skipped:false}
  ];
  h.recovery.open();
  const body=renderedText(h.ui.body);
  assert.match(h.ui.message,language==='de' ? /Prüfung erforderlich/ : /review required/);
  assert.match(body,language==='de' ? /Beziehungsbewertung erfordert Klärung/ : /Relation assessment needs clarification/);
  assert.doesNotMatch(body,/LLM-Bewertung fehlgeschlagen|LLM assessment failed|keine Frage an Sie|no question for you to answer/);
  assert.match(body,language==='de' ? /interne LLM-Anfrage/ : /internal LLM request/);
  assert(h.options().some(o=>o.id==='analysisRecoveryRetry'));
  assert(h.options().some(o=>o.id==='analysisRecoverySkip'));
 });
 test(`${language} a single failed assessment labels the affected nodes`, async () => {
  const h=harness(language); h.recovery.startCopilot(); h.complete(); await settle();
  const body=renderedText(h.ui.body);
  assert.match(body,language==='de' ? /Betroffene Knoten: BP-1327, BP-1481/ : /Affected nodes: BP-1327, BP-1481/);
  assert.equal(body.split('BP-1327').length-1,1);
 });
}
test('durable Artemis question recovery uses progress events and explicit decisions without a fixed observation timer',async()=>{
 const h=harness();h.recovery.startCopilot();assert.equal(h.analyses.length,1);assert.equal(h.timers.size,1);
 h.emit('taxonomy:analysis-progress',{transport:'artemis',operationId:'durable-operation',status:'RUNNING',node:'CP'});
 assert.equal(h.timers.size,0);assert.equal(h.S.recoveryContext.distributed,true);
 assert.equal(h.S.recoveryContext.operationId,'durable-operation');
 h.complete();await settle();assert.equal(h.analyses.length,1);assert.equal(h.requests.length,0);
 h.act('analysisRecoveryRetry');assert.equal(h.analyses.length,2);assert.equal(h.timers.size,0);
 assert.equal(h.analyses[1].continuationVersion,7);assert.equal(h.analyses[1].continuationQuestion,'question-a');
 h.complete('COMPLETED');await settle();assert.equal(h.timers.size,0);
});

test('normal managed analysis retains a durable node-only source without Copilot followups',async()=>{
 const h=harness();
 h.window.TaxonomyAnalysisScope={read:()=>({taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'}),restrictsGlobalAnalysis:()=>false};
 assert.equal(typeof h.recovery.startManagedAnalysis,'function','normal node-only analysis needs a durable managed start');
 h.recovery.startManagedAnalysis();
 assert.equal(h.analyses.length,1); assert.equal(h.analyses[0].resumable,true);
 const sourceId=h.analyses[0].continuationId;
 h.complete('COMPLETED'); await settle();
 assert.equal(h.S.recoveryContext.id,sourceId); assert.equal(h.followups(),0);
});

test('explicit relations extension freezes the original requirement, source revision and selected evidence',async()=>{
 const h=harness(); const selected={taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'};
 h.window.TaxonomyAnalysisScope={read:()=>selected,restrictsGlobalAnalysis:()=>true};
 h.recovery.startCopilot(); const sourceId=h.S.recoveryContext.id;
 h.complete('COMPLETED');await settle();
 assert.equal(typeof h.recovery.extendRelations,'function','completed node-only evidence needs an explicit extension action');
 h.fields.providerSelect.value='GEMINI';
 h.recovery.extendRelations();h.recovery.extendRelations();
 assert.equal(h.analyses.length,2);
 const extension=h.analyses[1];
 assert.equal(extension.relationSourceContinuationId,sourceId);assert.equal(extension.relationSourceVersion,7);
 assert.notEqual(extension.continuationId,sourceId);assert.equal(extension.businessText,'Hospital communications');
 assert.equal(extension.provider,'CUSTOM_OPENAI');assert.equal(extension.analysisScope.mode,'FULL');
 assert.deepEqual(Array.from(extension.analysisScope.taxonomyRoots),['CP']);
 assert.equal(extension.rawScores,undefined);assert.equal(extension.scores,undefined);
 h.complete('COMPLETED');await settle();assert.equal(h.followups(),0);
});

test('edited requirement cannot extend a retained node-only source',async()=>{
 const h=harness();h.window.TaxonomyAnalysisScope={read:()=>({taxonomyRoots:[],mode:'TAXONOMIES_ONLY'}),restrictsGlobalAnalysis:()=>true};
 h.recovery.startCopilot();h.complete('COMPLETED');await settle();
 assert.equal(typeof h.recovery.extendRelations,'function');
 h.fields.businessText.value='Edited requirement';h.recovery.extendRelations();
 assert.equal(h.analyses.length,1);
});


test('an explicitly rejected extension preserves the source context and explains the rejection',async()=>{
 const h=harness();h.window.TaxonomyAnalysisScope={read:()=>({taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'}),restrictsGlobalAnalysis:()=>true};
 h.recovery.startCopilot();h.complete('COMPLETED');await settle();const sourceId=h.S.recoveryContext.id;
 h.recovery.extendRelations();h.reject(Object.assign(new Error('Saved catalogue changed'),{httpStatus:409}));await settle();
 assert.equal(h.S.recoveryContext.id,sourceId);assert.equal(h.S.analysisRecovery.state,'COMPLETED');
 assert.match(h.ui.errorText,/Saved catalogue changed/);assert.equal(h.analyses.length,2);
});

for (const change of ['raw score','effective score','reason','new assessment']) {
 test(`visible ${change} edits cannot be silently replaced by an older relations source`,async()=>{
  const h=harness();h.window.TaxonomyAnalysisScope={read:()=>({taxonomyRoots:['BP'],mode:'TAXONOMIES_ONLY'}),restrictsGlobalAnalysis:()=>true};
  h.recovery.startCopilot();h.complete('COMPLETED');await settle();
  if(change==='raw score')h.S.currentRawScores.BP=99;
  else if(change==='effective score')h.S.currentScores.BP=99;
  else if(change==='reason')h.S.currentReasons.BP='Manually revised explanation';
  else h.S.currentRawScores['BP-new']=84;
  const visible=JSON.stringify([h.S.currentRawScores,h.S.currentScores,h.S.currentReasons]);
  h.recovery.extendRelations();await settle();
  assert.equal(h.analyses.length,1);assert.equal(JSON.stringify([h.S.currentRawScores,h.S.currentScores,h.S.currentReasons]),visible);
 });
}

// Reload the real versioned draft lifecycle, not a copy of the recovery module's
// private state. The provider request remains pending in the page being left.
function persistedRecovery(shared = {}) {
 const copy = value => JSON.parse(JSON.stringify(value));
 const selected = {taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'};
 const id = '5da958bf-e34c-4320-a036-ae0f794789dc';
 const request = {businessText:'Hospital communications',provider:'CUSTOM_OPENAI',analysisScope:selected,includeArchitectureView:false};
 function result(version, state, score, reason) {
  return {recovery:{id,version,state,completedCalls:version,openQuestions:state==='PAUSED'?[{key:'question-a',nodes:['CP']}]:[]},
   rawScores:{CP:score},effectiveScores:{CP:score-5},scores:{CP:score-5},reasons:{CP:reason},analysisScope:selected,
   analysisCoverage:{failedOrBlockedNodes:state==='PAUSED'?1:0,nodes:{}},status:state==='PAUSED'?'PARTIAL':'SUCCESS'};
 }
 if (!shared.snapshot) {
  const paused=result(7,'PAUSED',80,'Accepted paused explanation');
  shared.snapshot={request,recovery:paused.recovery,result:paused};
  shared.storage=new Map([['taxonomy.recovery.active.v1',JSON.stringify({id,workspaceId:'ws-a'})]]);
 }
 const listeners=new Map(), timers=new Map(), fields={}, analyses=[];let sequence=0, options=[];
 for(const name of ['businessText','copilotBtn','analyzeBtn','providerSelect','analysisRelationsExtension',
  'extendAnalysisRelationsBtn','analysisRelationsExtensionHint']) fields[name]=Object.assign(node(),{dataset:{}});
 fields.businessText.value=shared.draft?'':'Hospital communications';fields.providerSelect.value='CUSTOM_OPENAI';
 const S={taxonomyData:[{code:'CP',children:[]}],currentView:'list',evaluatedNodes:new Set()};
 const document={documentElement:{lang:'en'},getElementById:name=>fields[name]||null,querySelectorAll:()=>[],querySelector:()=>null,
  addEventListener(name,fn){if(!listeners.has(name))listeners.set(name,[]);listeners.get(name).push(fn);},
  dispatchEvent(event){for(const fn of listeners.get(event.type)||[])fn(event);}};
 const ui={update(){},close(){},error(){},isDismissed:()=>true,open(title,message,build,actions){options=actions;}};
 const window={TaxonomyState:S,TaxonomyRecoveryViewport:{mount:()=>ui},
  TaxonomyBrowse:{renderView(){},updateExportGroupVisibility(){}},
  TaxonomyScoring:{renderArchitectureView(){},renderSuggestedRelations(){},runAnalysis(value){analyses.push(copy(value.continuation));return new Promise(()=>{});}},
  TaxonomyAnalysisScope:{read:()=>selected,acceptResult(){},restrictsGlobalAnalysis:()=>true},
  setTimeout(fn){timers.set(++sequence,fn);return sequence;},clearTimeout:key=>timers.delete(key),
  TaxonomyAnalysisSessionApi:{async request(url,configuration={}){
   if(url.startsWith('/api/analysis-drafts/')) {
    if(configuration.method==='PUT') {
     const body=JSON.parse(configuration.body);
     assert.equal(body.expectedVersion,shared.draft?.version??null);
     shared.draft={version:(shared.draft?.version||0)+1,payload:copy(body.payload)};
    }
    return {ok:true,status:200,json:async()=>copy(shared.draft||null)};
   }
   assert.equal(url,'/api/analysis-continuations/'+id);
   if(shared.readGate)return shared.readGate;
   return {ok:true,json:async()=>copy(shared.snapshot)};
  }}};
 const context=vm.createContext({window,document,crypto:webcrypto,console,Set,
  sessionStorage:{getItem:key=>shared.storage.get(key)||null,setItem:(key,value)=>shared.storage.set(key,value),removeItem:key=>shared.storage.delete(key)},
  setTimeout:window.setTimeout,clearTimeout:window.clearTimeout,
  CustomEvent:class {constructor(type,configuration={}){this.type=type;this.detail=configuration.detail;}}});
 const load=name=>vm.runInContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/'+name,import.meta.url),'utf8'),context);
 load('taxonomy-analysis-session-core.js');const C=window.__TaxonomyAnalysisSessionContext;
 C.runtime.workspaceId='ws-a';C.runtime.analysisGeneration=0;C.showActionAlert=()=>{};
 load('taxonomy-analysis-session-draft.js');
 window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:C.runtime.workspaceId}),saveNow:()=>C.saveDraft()};
 load('taxonomy-analysis-recovery.js');load('taxonomy-relations-extension.js');
 return {S,C,window,fields,analyses,shared,
  async restore(){if(shared.draft)await C.loadDraft();else document.dispatchEvent({type:'taxonomy:analysis-draft-restored'});await settle();},
  retry(){window.TaxonomyAnalysisRecovery.open();options.find(option=>option.id==='analysisRecoveryRetry').handler();},
  running(){shared.snapshot={...shared.snapshot,recovery:{...shared.snapshot.recovery,state:'RUNNING',version:8}};},
  completed(){const finished=result(9,'COMPLETED',95,'New completed explanation');shared.snapshot={request,recovery:finished.recovery,result:finished};},
  refresh:()=>window.TaxonomyAnalysisRecovery.refresh(),save:()=>C.saveDraft(),
  eligible(){window.TaxonomyRelationsExtension.render();return !fields.extendAnalysisRelationsBtn.disabled;}};
}

test('reload during a retried node assessment accepts newer server evidence and enables relations',async()=>{
 const before=persistedRecovery();await before.restore();
 assert.equal(before.S.currentRawScores.CP,80);before.retry();before.running();await before.save();
 assert.equal(before.analyses[0].continuationAction,'RETRY');assert.equal(before.analyses[0].continuationVersion,7);
 const after=persistedRecovery(before.shared);await after.restore();
 assert.equal(after.S.analysisRecovery.state,'RUNNING');assert.equal(after.S.currentRawScores.CP,80);
 after.completed();await after.refresh();await after.refresh();
 assert.equal(after.S.currentRawScores.CP,95,'A newer completed result is not a local edit');
 assert.equal(after.S.currentScores.CP,90);assert.equal(after.S.currentReasons.CP,'New completed explanation');
 assert.equal(after.S.analysisRecovery.version,9);assert.equal(after.eligible(),true);
 after.window.TaxonomyAnalysisRecovery.extendRelations();
 assert.equal(after.analyses[0].relationSourceVersion,9);
 assert.equal(after.analyses[0].relationSourceContinuationId,before.S.recoveryContext.id);
});

for(const edit of ['raw score','effective score','reason','new assessment']) {
 test(`reload during retry preserves a saved ${edit} edit until it is explicitly reverted`,async()=>{
  const before=persistedRecovery();await before.restore();before.retry();before.running();
  const original=JSON.stringify([before.S.currentRawScores,before.S.currentScores,before.S.currentReasons]);
  if(edit==='raw score')before.S.currentRawScores.CP=99;
  else if(edit==='effective score')before.S.currentScores.CP=99;
  else if(edit==='reason')before.S.currentReasons.CP='User explanation';
  else before.S.currentRawScores['CP-new']=84;
  const edited=JSON.stringify([before.S.currentRawScores,before.S.currentScores,before.S.currentReasons]);await before.save();
  const after=persistedRecovery(before.shared);await after.restore();after.completed();await after.refresh();await after.refresh();
  assert.equal(JSON.stringify([after.S.currentRawScores,after.S.currentScores,after.S.currentReasons]),edited);
  assert.equal(after.eligible(),false);after.window.TaxonomyAnalysisRecovery.extendRelations();assert.equal(after.analyses.length,0);
  [after.S.currentRawScores,after.S.currentScores,after.S.currentReasons]=JSON.parse(original);
  after.S.currentEffectiveScores=after.S.currentScores;
  assert.equal(after.eligible(),false,'The old evidence does not belong to the completed server revision');
  await after.refresh();assert.equal(after.S.currentRawScores.CP,95);assert.equal(after.eligible(),true);
 });
}

for(const switchScope of ['workspace','generation']) {
 test(`a late completed read after a ${switchScope} switch cannot attach its evidence to a new analysis`,async()=>{
  const h=persistedRecovery();await h.restore();h.completed();let release;
  h.shared.readGate=new Promise(resolve=>{release=resolve;});const read=h.refresh();
  if(switchScope==='workspace')h.C.runtime.workspaceId='ws-b';else h.C.runtime.analysisGeneration++;
  h.window.TaxonomyAnalysisRecovery.startManagedAnalysis();const next=h.S.recoveryContext;
  release({ok:true,json:async()=>h.shared.snapshot});await read;
  assert.equal(h.S.recoveryContext,next);assert.equal(h.S.analysisRecovery.state,'RUNNING');assert.equal(h.S.currentRawScores.CP,80);
  assert.equal(h.eligible(),false);
 });
}

test('persisting accepted evidence does not duplicate a long provider explanation in the bounded draft',async()=>{
 const h=persistedRecovery();const explanation='Retained provider explanation. '.repeat(20000);
 h.shared.snapshot.result.reasons.CP=explanation;await h.restore();
 assert.equal(h.shared.draft.payload.reasons.CP,explanation);
 assert.ok(JSON.stringify(h.shared.draft).length<explanation.length+5000,'The baseline must have bounded persistence overhead');
});

for(const variant of ['matching same revision','edited same revision','newer revision']) {
 test(`legacy recovery drafts without an accepted baseline conservatively handle ${variant}`,async()=>{
  const before=persistedRecovery();await before.restore();await before.save();
  const payload=before.shared.draft.payload;
  const {id,workspaceId,request,followups,noFollowups}=payload.recoveryContext;
  payload.recoveryContext={id,workspaceId,request,followups,noFollowups};
  if(variant==='edited same revision')payload.reasons.CP='Legacy user edit';
  if(variant==='newer revision')before.completed();
  else {
   before.shared.snapshot.recovery={...before.shared.snapshot.recovery,state:'COMPLETED',openQuestions:[]};
   before.shared.snapshot.result={...before.shared.snapshot.result,recovery:before.shared.snapshot.recovery,status:'SUCCESS'};
  }
  const after=persistedRecovery(before.shared);await after.restore();await after.refresh();
  assert.equal(after.S.currentRawScores.CP,80);
  assert.equal(after.S.currentReasons.CP,variant==='edited same revision'?'Legacy user edit':'Accepted paused explanation');
  assert.equal(after.eligible(),variant==='matching same revision');
 });
}
