import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-recovery.js',import.meta.url),'utf8');
const analysisSource=readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis.js',import.meta.url),'utf8');
function element(tag='div',text='') {
 const listeners=new Map(),attributes=new Map();
 return {tagName:tag,textContent:text,children:[],style:{},value:'',hidden:false,disabled:false,checked:false,dataset:{},
  classList:{toggle(){},add(){},remove(){}},append(...items){this.children.push(...items);},prepend(...items){this.children.unshift(...items);},
  replaceChildren(...items){this.children=items;},setAttribute(k,v){attributes.set(k,String(v));},getAttribute(k){return attributes.get(k);},
  addEventListener(name,fn){listeners.set(name,fn);},fire(name){return listeners.get(name)?.();},querySelectorAll(){return[];},remove(){}};
}
const descendants=e=>[e,...(e.children||[]).flatMap(descendants)];
const content=e=>descendants(e).map(n=>n.textContent||'').join(' ');
const settle=async()=>{for(let n=0;n<8;n++)await new Promise(resolve=>setImmediate(resolve));};
function harness(language='en') {
 let pending,snapshot,options=[],followups=0,opens=0,requestHandler,realScoring=false;
 const S={},runtime={workspaceId:'work',analysisGeneration:0};
 const fields={businessText:Object.assign(element('textarea'),{value:'Record status'}),copilotBtn:element('button'),analyzeBtn:element('button'),copilotSpinner:element(),providerSelect:Object.assign(element('select'),{value:'CUSTOM_OPENAI'})};
 const writes=[],reads=[],panels=[],views=[],timers=new Map(),listeners=new Map(),storage=new Map();let timer=0;
 const ui={body:element(),message:'',opened:false,update(message,busy,canCancel){Object.assign(this,{message,busy,canCancel});},close(){this.opened=false;},error(message){this.errorText=message;},
  open(title,message,build,actions){opens++;this.opened=true;this.message=message;this.body=element();options=actions;build(this.body,element);
   for(const option of actions)fields[option.id]=Object.assign(element('button',option.label),{disabled:!!option.disabled});}};
 const document={getElementById:id=>fields[id]||descendants(ui.body).find(e=>e.id===id)||null,createElement:element,
  addEventListener:(name,fn)=>{if(!listeners.has(name))listeners.set(name,[]);listeners.get(name).push(fn);}};
 const window={TaxonomyState:S,__TaxonomyAnalysisSessionContext:{S,runtime,language:()=>language},TaxonomyRecoveryViewport:{mount:()=>ui},
  TaxonomyAnalysisSession:{state:()=>({ready:true,workspaceId:runtime.workspaceId}),saveNow:async()=>true},
  TaxonomyAnalysisSessionApi:{request:async(url,init,workspace)=>{reads.push({url,init,workspace});return requestHandler?requestHandler(url,init):{ok:true,json:async()=>snapshot};}},
  TaxonomyScoring:{runAnalysis({continuation}){writes.push(continuation);return new Promise(resolve=>{pending=resolve;});},renderArchitectureView:view=>views.push(view),renderSuggestedRelations(){}},
  TaxonomyAnalysis:{runCopilotFlow:async()=>{followups++;},renderPartialCopilot:message=>panels.push(message)}};
 const context={window,document,crypto:webcrypto,console,setTimeout:fn=>{timers.set(++timer,fn);return timer;},clearTimeout:id=>timers.delete(id),
  sessionStorage:{getItem:key=>storage.get(key)||null,setItem:(key,v)=>storage.set(key,v),removeItem:key=>storage.delete(key)}};
 vm.runInNewContext(source,context);
 function result(state='COMPLETED_WITH_GAPS',questions=[question('a')],architecture=true){
  const r={recovery:{id:S.recoveryContext.id,version:7,state,completedCalls:34,openQuestions:questions},rawScores:{'BP-1':70},scores:{'BP-1':70},status:state==='COMPLETED'?'SUCCESS':'PARTIAL',
   analysisCoverage:{failedOrBlockedNodes:0,nodes:{}},relationSearchReport:{progress:{verifiedRelations:17}},tree:[{code:'BP-1',nameEn:'Status records',nameDe:'Statusaufzeichnungen'}]};
  if(architecture)r.architectureView={includedElements:[{nodeCode:'BP-1'}],includedRelationships:[],analysisStatus:r.status};
  return r;
 }
 function complete(r){snapshot={request:S.recoveryContext.request,recovery:r.recovery,result:r};
  // The real scoring boundary normally hydrates result data before resolving the promise.
  S.lastAnalyzedText='Record status';S.lastAnalysisStatus=r.status;S.taxonomyData=r.tree;
  if(r.architectureView)S.currentArchView=r.architectureView;S.currentRelationSearchReport=r.relationSearchReport;
  pending(realScoring ? {ok:true,json:async()=>r} : r);
 }
 function loadScoring(){
  realScoring=true;
  for(const id of ['architectureViewPanel','architectureViewContent','architecturePlaceholder','includeArchitectureView'])fields[id]=element();
  fields.includeArchitectureView.checked=true;
  document.createTextNode=text=>element('#text',text);document.documentElement={lang:language};document.querySelectorAll=()=>[];
  window.TaxonomyBrowse={showStatus(){},clearStatus(){},renderView(){},refreshAnalysisDuration(){},switchView(){}};
  window.TaxonomyAnalysisProgress={start:()=>({acceptsResult:()=>runtime.workspaceId==='work',finish(){}})};
  context.TaxonomyI18n=window.TaxonomyI18n={t:key=>key,getLocale:()=>language};
  context.TaxonomyUtils=window.TaxonomyUtils={escapeHtml:value=>String(value??'')};
  context.fetch=async(url,options)=>{writes.push(JSON.parse(options.body));return new Promise(resolve=>{pending=resolve;});};
  const scoring=readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js',import.meta.url),'utf8');
  vm.runInNewContext(scoring,context);
 }
 function loadAnalysis(){for(const id of ['copilotContent','copilotPanel','gapAnalysisContent','gapAnalysisPanel','patternDetectionContent','patternDetectionPanel','recommendationContent','recommendationPanel'])fields[id]=element();
  context.TaxonomyI18n={t:key=>key};context.TaxonomyUtils={escapeHtml:v=>String(v??'')};context.fetch=async()=>{throw Error('unexpected global analysis request');};vm.runInNewContext(analysisSource,context);}
 return{S,runtime,fields,window,ui,document,writes,reads,views,panels,timers,result,complete,loadAnalysis,loadScoring,
  options:()=>options,followups:()=>followups,opens:()=>opens,act:id=>options.find(o=>o.id===id)?.handler(),
  request:fn=>{requestHandler=fn;},snapshot:r=>{snapshot={request:S.recoveryContext.request,recovery:r.recovery,result:r};},
  emit:(name,detail)=>(listeners.get(name)||[]).forEach(fn=>fn({detail})),recovery:window.TaxonomyAnalysisRecovery};
}
const question=(key,error='INVALID_RELATION_RESPONSE: invalid JSON')=>({key,nodes:['BP-1'],error,attempts:1,skipped:false,outcomeUncertain:false});
for(const lang of ['de','en']) {
 test(`${lang}: ordinary relation errors finish without a modal or unsafe global conclusions`,async()=>{
  const h=harness(lang);h.recovery.startCopilot();h.complete(h.result());await settle();
  assert.equal(h.opens(),0);assert.equal(h.writes.length,1);assert.equal(h.followups(),0);assert.equal(h.S.recoveryContext.followupState,'COMPLETED_WITH_GAPS');
  assert.equal(h.recovery.hasOpenEvaluations(),true);assert.equal(h.ui.canCancel,false);assert.equal(h.panels.length,1);
  assert.match(h.ui.message,lang==='de'?/1 Prüfungen offen/:/1 assessments open/);assert.equal(h.timers.size,0);
 });
 test(`${lang}: actual clarification and snapshot names are visible without raw JSON`,async()=>{
  const h=harness(lang);h.recovery.startCopilot();h.complete(h.result('COMPLETED_WITH_GAPS',[question('a','RELATION_UNRESOLVED: BP-1: Which retention period applies?')]));await settle();h.recovery.open();
  assert.match(content(h.ui.body),/Which retention period applies\?/);assert.match(content(h.ui.body),lang==='de'?/Statusaufzeichnungen/:/Status records/);
  assert.match(content(h.ui.body),lang==='de'?/Klärung/:/clarification/);assert.equal(h.reads.length,0);
 });
 test(`${lang}: one explicit retry is bound to the same operation and revision`,async()=>{
  const h=harness(lang);h.recovery.startCopilot();h.complete(h.result());await settle();const view=h.S.currentArchView;h.recovery.open();h.act('analysisRecoveryRetry');
  assert.equal(h.writes.length,2);assert.equal(h.writes[1].continuationId,h.writes[0].continuationId);assert.equal(h.writes[1].continuationVersion,7);
  assert.equal(h.writes[1].continuationQuestion,'a');assert.equal(h.S.currentArchView,view);h.complete(h.result('COMPLETED',[]));await settle();assert.equal(h.followups(),1);
 });
 test(`${lang}: selected retries do not authorize all open questions`,async()=>{
  const h=harness(lang);h.recovery.startCopilot();h.complete(h.result('COMPLETED_WITH_GAPS',[question('a'),question('b'),question('c')]));await settle();h.recovery.open();
  const multi=h.document.getElementById('analysisRecoveryMultiple');assert(multi);multi.open=true;await multi.fire('toggle');
  const boxes=descendants(multi).filter(e=>e.type==='checkbox');assert.equal(boxes.length,3);
  boxes[1].checked=true;await boxes[1].fire('change');h.act('analysisRecoveryRetry');
  assert.deepEqual(JSON.parse(JSON.stringify(h.writes[1].continuationQuestions)),['a','b']);assert.equal(h.writes[1].continuationQuestion,null);
  h.complete(h.result());await settle();assert.equal(h.writes.length,2);
 });
}
test('runtime stop still permits independent continuation when ordinary unassessed questions remain',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete(h.result('STOPPED'));await settle();h.act('analysisRecoveryContinue');
 assert.equal(h.writes.length,2);assert.equal(h.writes[1].continuationAction,'CONTINUE');assert.deepEqual(JSON.parse(JSON.stringify(h.writes[1].continuationQuestions)),[]);
 h.complete(h.result());await settle();
});
test('unknown physical outcome still opens the explicit decision dialog',async()=>{
 const h=harness();h.recovery.startCopilot();const q={...question('a','OUTCOME_UNCERTAIN: worker disappeared'),outcomeUncertain:true};h.complete(h.result('PAUSED',[q]));await settle();
 assert.equal(h.opens(),1);assert.equal(h.writes.length,1);assert.match(content(h.ui.body),/billed/);
});
test('refresh without a newer architecture preserves only the same scoped accepted view',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete(h.result());await settle();const view=h.S.currentArchView;h.snapshot(h.result('STOPPED',[question('a')],false));await h.recovery.refresh();
 assert.equal(h.S.currentArchView,view);assert.equal(h.views.at(-1),view);
});
test('late result cannot publish to a different workspace',async()=>{
 const h=harness();h.recovery.startCopilot();const r=h.result();h.runtime.workspaceId='another';h.complete(r);await settle();
 assert.equal(h.followups(),0);assert.equal(h.S.analysisRecovery.state,'RUNNING');assert.equal(h.opens(),0);
});
test('an empty view never claims that an assessed architecture exists',async()=>{
 const h=harness();h.recovery.startCopilot();const r=h.result();r.architectureView.includedElements=[];h.complete(r);await settle();
 assert.match(h.recovery.partialResultMessage(),/No assessed architecture/);assert.equal(h.opens(),0);
});
test('large error collections render a bounded selectable page',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete(h.result('COMPLETED_WITH_GAPS',Array.from({length:55},(_,i)=>question('q'+i))));await settle();h.recovery.open();
 const multi=h.document.getElementById('analysisRecoveryMultiple');assert(multi);multi.open=true;await multi.fire('toggle');
 assert.equal(descendants(multi).filter(e=>e.type==='checkbox').length,50);await h.document.getElementById('analysisRecoveryNextPage').fire('click');
 assert.equal(descendants(multi).filter(e=>e.type==='checkbox').length,5);
});
test('a late diagnostic error cannot replace the newly selected diagnostic body',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete(h.result('COMPLETED_WITH_GAPS',[question('a'),question('b')]));await settle();h.recovery.open();
 let reject;h.request(()=>new Promise((resolve,no)=>{reject=no;}));
 const details=descendants(h.ui.body).find(e=>e.tagName==='details' && e.id!=='analysisRecoveryMultiple');details.open=true;const loading=details.fire('toggle');
 const select=h.document.getElementById('analysisRecoveryQuestion');select.value='b';await select.fire('change');reject(Error('private old error'));await loading;
 assert.doesNotMatch(content(details),/private old error/);
});
test('distributed live progress does not start a polling retry loop',async()=>{
 const h=harness();h.recovery.startCopilot();h.emit('taxonomy:analysis-progress',{transport:'artemis',operationId:'worker-operation'});assert.equal(h.timers.size,0);
 h.complete(h.result());await settle();assert.equal(h.writes.length,1);assert.equal(h.reads.length,0);
});
test('public global analysis stays blocked with fully scored nodes and unresolved relations',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete(h.result());await settle();h.loadAnalysis();h.S.currentScores={'BP-1':70};
 await h.window.TaxonomyAnalysis.runCopilotFlow();assert.match(h.fields.copilotContent.innerHTML,/Partial|unassessed/);
});
test('a selected partial scope is not described as a completed scope',async()=>{
 const h=harness();h.window.TaxonomyAnalysisScope={read:()=>({taxonomyRoots:['BP'],mode:'TAXONOMIES_ONLY'}),restrictsGlobalAnalysis:()=>true};
 h.recovery.startCopilot();h.complete(h.result());await settle();assert.equal(h.S.recoveryContext.followupState,'COMPLETED_WITH_GAPS');assert.doesNotMatch(h.panels[0],/selected analysis scope is complete/i);
});
test('an older partial result without a view does not claim architecture availability',async()=>{
 const h=harness();h.recovery.startCopilot();h.complete(h.result('COMPLETED_WITH_GAPS',[question('a')],false));await settle();
 assert.match(h.recovery.partialResultMessage(),/No architecture view/);assert.equal(h.opens(),0);
});
test('a fresh requirement never shows relation counts from the previous run',async()=>{
 const h=harness();h.S.currentRelationSearchReport={progress:{verifiedRelations:999}};h.recovery.startCopilot();
 assert.doesNotMatch(h.ui.message,/999/);h.complete(h.result());await settle();
});

for (const nodes of [undefined, null]) {
 test(`missing per-node coverage (${nodes}) keeps the partial result readable`,()=>{
  const h=harness(); const row=element(), header=element(); row.setAttribute('data-code','BP-1');
  row.querySelector=()=>header; header.querySelector=()=>null;
  h.fields.taxonomyTree={querySelectorAll:()=>[row]}; h.fields.analysisCoverageWarning=element();
  h.S.analysisCoverage={failedOrBlockedNodes:1,nodes};
  assert.doesNotThrow(()=>h.recovery.renderCoverage(), 'Incomplete old/imported metadata must not crash rendering');
  assert.match(h.fields.analysisCoverageWarning.textContent,/Partial result/);
  assert.equal(header.children.length,0,'Do not invent a node-specific assessment when its metadata is absent');
  assert.equal(h.recovery.hasOpenEvaluations(),true,'Missing detail must not authorize global conclusions');
 });
}

for(const language of ['de','en']){
 test(`${language}: actual scoring preserves the visible architecture throughout a selected retry`,async()=>{
  const h=harness(language);h.loadScoring();h.recovery.startCopilot();h.complete(h.result());await settle();
  const view=h.S.currentArchView, markup=h.fields.architectureViewContent.innerHTML;
  assert.equal(h.fields.architectureViewPanel.style.display,'');
  h.fields.architectureViewContent.scrollTop=87;
  h.recovery.open();h.act('analysisRecoveryRetry');
  assert.equal(h.writes.length,2);
  assert.equal(h.fields.architectureViewPanel.style.display,'','The previous architecture must stay visible while the request is pending');
  assert.equal(h.fields.architectureViewContent.innerHTML,markup,'Do not rebuild the reading content');
  h.complete(h.result('STOPPED',[question('a')],false));await settle();
  assert.equal(h.fields.architectureViewPanel.style.display,'','A response without a view must not erase the accepted view');
  assert.equal(h.S.currentArchView,view);assert.equal(h.fields.architectureViewContent.innerHTML,markup);
  assert.equal(h.fields.architectureViewContent.scrollTop,87);
 });
}
test('actual scoring never restores an old architecture after the workspace changed',async()=>{
 const h=harness();h.loadScoring();h.recovery.startCopilot();h.complete(h.result());await settle();
 h.recovery.open();h.act('analysisRecoveryRetry');h.runtime.workspaceId='other';
 h.fields.architectureViewPanel.style.display='none';h.S.currentArchView=null;
 h.complete(h.result('STOPPED',[question('a')],false));await settle();
 assert.equal(h.fields.architectureViewPanel.style.display,'none');assert.equal(h.S.currentArchView,null);
});
test('a fresh actual scoring request without an architecture never revives a previous requirement',async()=>{
 const h=harness();h.loadScoring();h.S.currentArchView={includedElements:[{nodeCode:'OLD'}]};h.S.lastAnalyzedText='Old requirement';
 h.recovery.startCopilot();h.complete(h.result('STOPPED',[question('a')],false));await settle();
 assert.equal(h.fields.architectureViewPanel.style.display,'none');assert.equal(h.S.currentArchView,null);
});
