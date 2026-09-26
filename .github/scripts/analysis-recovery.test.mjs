import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
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
  const requests=[], analyses=[], saved=[], panels=[]; let openOptions=[], snapshot;
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
  return {S,runtime,ui,analyses,requests,saved,panels,fields,timers,window,complete,
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
