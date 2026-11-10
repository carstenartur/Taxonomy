import canonicalFixture from './canonical-api-fixture.cjs';
import test, {afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const source=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-projects.js',import.meta.url),'utf8');
const detailSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js',import.meta.url),'utf8');
const portfolioSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/taxonomy-portfolio.js',import.meta.url),'utf8');
const chromeSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/application-chrome.js',import.meta.url),'utf8');
const reformulationSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-reformulation.js',import.meta.url),'utf8');
const copilotSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-copilot.js',import.meta.url),'utf8');
afterEach(()=>canonicalFixture.dispose());

function detailStartup({holdOffers=false,forbidden=false,copilot=false}={}) {
  const nodes=new Map(),listeners=new Map(),windowListeners=new Map(),requests=[],auth=[];
  let finishLifecycle,finishOffers;
  const lifecycle=new Promise(resolve=>{finishLifecycle=resolve;});
  const offersGate=new Promise(resolve=>{finishOffers=resolve;});
  function node(id='') {
    const values=new Set();
    const value={id,children:[],dataset:{},style:{},value:'',textContent:'',classList:{
      add(...names){names.forEach(name=>values.add(name));},remove(...names){names.forEach(name=>values.delete(name));},
      contains:name=>values.has(name),toggle(name,force){if(force)values.add(name);else values.delete(name);},[Symbol.iterator]:()=>values.values()},
      append(...children){this.children.push(...children);},appendChild(child){this.append(child);return child;},
      replaceChildren(...children){this.children=children;},addEventListener(){},focus(){},remove(){},
      setAttribute(name,text){this[name]=text;},removeAttribute(name){delete this[name];},
      querySelectorAll(selector){
        const descendants=this.children.flatMap(child=>[child,...(child.querySelectorAll?.('*')||[])]);
        if(selector==='*')return descendants;
        const key=selector.match(/^\[data-([a-z-]+)\]$/)?.[1]?.replace(/-([a-z])/g,(_,letter)=>letter.toUpperCase());
        return key?descendants.filter(child=>child.dataset?.[key]!==undefined):[];
      },querySelector(selector){return this.querySelectorAll(selector)[0]||null;}};
    if(id)nodes.set(id,value);
    return value;
  }
  const get=id=>{
    if(id.startsWith('application') || id==='architectureWorkspace' || id==='projectFilter' || id==='importMain' || id==='matrixMain' || id==='reportsMain')return null;
    return nodes.get(id)||node(id);
  };
  const document={readyState:'loading',documentElement:{lang:'en'},head:{appendChild(){}},getElementById:get,
    querySelector:selector=>selector.startsWith('meta')?null:node(),querySelectorAll:selector=>selector==='#textPane h2'?[node(),node()]:[],createElement:()=>node(),
    addEventListener(type,listener){if(!listeners.has(type))listeners.set(type,[]);listeners.get(type).push(listener);},
    dispatchEvent(event){if(event.type==='taxonomy-api-auth-failure')auth.push(event);for(const listener of listeners.get(event.type)||[])listener(event);}};
  const window={location:{pathname:'/projects/7/requirements/11',search:'?lang=en&proposal=offer',href:'https://example.test/projects/7/requirements/11?lang=en&proposal=offer',origin:'https://example.test'},
    addEventListener(type,listener){if(!windowListeners.has(type))windowListeners.set(type,[]);windowListeners.get(type).push(listener);},
    TaxonomyI18n:{t:key=>key,ready:async()=>{},resolveUrl:path=>path,getBasePath:()=>'',getLocale:()=> 'en',formatEnum:String},
    TaxonomyUtils:{escapeHtml:String},TaxonomyRequirementCopilot:{},TaxonomyArchitectureLifecycle:{ready:()=>lifecycle}};
  const requirement={id:11,requirementKey:'REQ',title:'Requirement',currentVersionId:44,currentVersion:{id:44,versionNumber:1,text:'Original'},status:'APPROVED',reviewStatus:'CONFIRMED'};
  const offer={id:'offer',baseline:{originalText:'Original',sourceVersionId:44},currentRevision:{number:1,text:'Retained draft',questions:[],statements:[],validation:{findings:[]}}};
  const context=canonicalFixture.install({window,document,location:window.location,fetch:async(input,init)=>{
    const pathname=new URL(String(input),window.location.href).pathname;
    requests.push({pathname,init});
    if(forbidden && pathname==='/api/projects/7')return Response.json({detail:'Access revoked'},{status:403});
    if(pathname.endsWith('/reformulations') && holdOffers)await offersGate;
    const value=pathname==='/api/ai-automation'?{enabled:false}
      :pathname.endsWith('/copilot/latest')?null
      :pathname==='/api/account/me'?{architectureMutationAllowed:false}
      :pathname==='/api/projects/7'?{id:7,projectKey:'P',title:'Project'}
      :pathname.endsWith('/requirements/11')?requirement
      :pathname.endsWith('/portfolio')?{solutions:[]}
      :pathname.endsWith('/reformulations/offer')?offer
      :pathname.endsWith('/reformulations')?[{id:'offer',currentRevision:1}]:[];
    return Response.json(value);
  }},['api/portfolio-api.js','api/copilot-api.js']);
  for(const [source,name] of [[chromeSource,'application-chrome.js'],[detailSource,'requirement-detail.js'],[reformulationSource,'requirement-reformulation.js']])vm.runInContext(source,context,{filename:name});
  if(copilot)vm.runInContext(copilotSource,context,{filename:'requirement-copilot.js'});
  return {window,context,requests,auth,get,
    start:()=>Promise.all((listeners.get('DOMContentLoaded')||[]).map(listener=>listener({}))),
    releaseLifecycle(){window.TaxonomyAppChrome.updateContext({architecture:{repositoryId:'repository-a',branch:'draft',modelStand:'initial'}});finishLifecycle(null);},
    releaseOffers:()=>finishOffers(),pagehide(){for(const listener of windowListeners.get('pagehide')||[])listener({});}};
}
const settleStartup=async()=>{for(let i=0;i<6;i++)await new Promise(resolve=>setImmediate(resolve));};

test('detail and reformulation startup wait for actual lifecycle and route identity before rendering an offer',async()=>{
  const h=detailStartup();
  assert.equal(h.requests.length,0,'The add-on must not race DOMContentLoaded detail initialization');
  const pending=h.start();await settleStartup();
  assert.equal(h.requests.length,0,'Initial architecture hydration owns scope before detail reads');
  h.releaseLifecycle();await pending;await settleStartup();
  assert.deepEqual(JSON.parse(JSON.stringify(await h.window.TaxonomyRequirementDetail.whenReady())),{projectId:7,requirementId:11});
  const editor=h.get('reformulationList').querySelector('[data-reformulation-editor]');
  assert.equal(editor?.value,'Retained draft','The actual offer editor survives expected startup identity hydration');
  assert.doesNotMatch(h.get('reformulationStatus').textContent,/superseded|failed/i);
  assert.equal(h.window.TaxonomyAppChrome.getContext().requirement.id,11);
});

test('later requirement navigation still aborts actual offer reads without repainting obsolete errors',async()=>{
  const h=detailStartup({holdOffers:true});
  const pending=h.start();h.releaseLifecycle();await pending;await settleStartup();
  const request=h.requests.find(value=>value.pathname.endsWith('/reformulations'));
  assert.ok(request,'The offer read starts after detail readiness');
  h.get('reformulationStatus').textContent='New selection owns this view';
  h.window.TaxonomyAppChrome.updateContext({requirement:{id:99,version:2}});
  h.releaseOffers();await settleStartup();
  assert.equal(request.init.signal.aborted,true);
  assert.equal(h.get('reformulationStatus').textContent,'New selection owns this view');
  assert.equal(h.get('reformulationList').querySelector('[data-reformulation-editor]'),null);
  await assert.rejects(h.window.TaxonomyRequirementDetail.whenReady(),error=>error.code==='ABORTED');
});

test('failed detail authentication cannot resolve readiness or start dependent offer reads',async()=>{
  const h=detailStartup({forbidden:true});
  const pending=h.start();h.releaseLifecycle();await pending;await settleStartup();
  // Header-time auth invalidation can reject a parallel sibling before the 403 body finishes.
  await assert.rejects(h.window.TaxonomyRequirementDetail.whenReady(),error=>error.status===403 || error.code==='ABORTED');
  assert.equal(h.auth.length,1);
  assert.equal(h.auth[0].detail.status,403);
  assert.equal(h.requests.some(value=>value.pathname.includes('/reformulations')),false);
});

test('pagehide invalidates completed detail readiness',async()=>{
  const h=detailStartup();const pending=h.start();h.releaseLifecycle();await pending;await settleStartup();
  await h.window.TaxonomyRequirementDetail.whenReady();h.pagehide();
  await assert.rejects(h.window.TaxonomyRequirementDetail.whenReady(),error=>error.code==='ABORTED');
});

test('saved Copilot startup also waits for hydrated detail before its actual latest request',async()=>{
  const h=detailStartup({copilot:true});const pending=h.start();await settleStartup();
  assert.equal(h.requests.length,0,'Neither saved add-on may race initial architecture hydration');
  h.releaseLifecycle();await pending;await settleStartup();
  const latest=h.requests.find(value=>value.pathname.endsWith('/copilot/latest'));
  assert.ok(latest,'The real named Copilot latest request must run after readiness');
  assert.equal(latest.init.signal.aborted,false,'Expected route hydration must not cancel Copilot startup');
});
function harness(reset){
  const events=[];let payload={businessText:'original'};
  const C={S:{},runtime:{},text:key=>key,language:()=> 'en',currentPayload:()=>payload,
    comparable:value=>JSON.stringify(value),resetDraft:reset,showActionAlert(){},queueSave(){events.push('save');},invalidate(){events.push('invalidate');}};
  const window={__TaxonomyAnalysisSessionContext:C,location:{assign(){events.push('navigate');}}};
  vm.runInNewContext(source,{window,document:{readyState:'loading',addEventListener(){}},Promise,Date,String,Object,JSON,Math,encodeURIComponent});
  return{C,events,edit(){payload={businessText:'new edit'};}};
}
test('failed reset after a successful promotion leaves the draft and page intact',async()=>{
  const h=harness(async()=>{throw new Error('reset failed');});
  await assert.rejects(h.C.discardDraftAndNavigate('/saved'),/reset failed/);
  assert.deepEqual(h.events,[]);
});
test('typing while the reset is in flight keeps the new draft and schedules its save',async()=>{
  let finish;const h=harness(()=>new Promise(resolve=>{finish=resolve;}));
  const result=h.C.discardDraftAndNavigate('/saved');h.edit();finish({version:2});
  assert.equal(await result,false);assert.deepEqual(h.events,['save']);
});

test('version review remains interactive until an explicit choice starts the save',async()=>{
  const elements=new Map();
  function element(id){
    if(!elements.has(id)) {
      const classes=new Set(['d-none']);
      elements.set(id,{value:'',textContent:'Create version',dataset:{},focus(){},
        classList:{add:className=>classes.add(className),remove:className=>classes.delete(className),
          contains:className=>classes.has(className),toggle(className,force){if(force)classes.add(className);else classes.delete(className);}}});
    }
    return elements.get(id);
  }
  let choose,rejectSave,saveCalls=0;
  const choice=new Promise(resolve=>{choose=resolve;});
  const saving=new Promise((resolve,reject)=>{rejectSave=reject;});
  const window={location:{pathname:'/projects/1/requirements/2',search:''},
    TaxonomyRequirementIntake:{attach(){},prepareVersion:()=>choice},
    TaxonomyArchitectureLifecycle:{saveRequirement(){saveCalls++;return saving;}}};
  const document={documentElement:{lang:'en'},addEventListener(){},getElementById:element,
    querySelector:()=>element('submit')};
  const instrumented=detailSource.replace(/\}\)\(\);\s*$/,'window.detailProbe={state,createVersion,captureVersionDraftBase};})();');
  vm.runInNewContext(instrumented,{window,document,URLSearchParams,Date,JSON,Number});
  const {state,createVersion,captureVersionDraftBase}=window.detailProbe;
  state.requirement={requirementKey:'PUMP',title:'Pump control'};
  captureVersionDraftBase({id:12,contentHash:'a'.repeat(64)});
  element('versionText').value='Draft text';element('changeReason').value='Reviewed change';
  const pending=createVersion({preventDefault(){}});
  assert.equal(saveCalls,0);
  assert.equal(element('detailBusy').classList.contains('d-none'),true,'The busy overlay must not cover the choice controls');
  choose({choice:'CHANGE',requirementId:2,expectedVersionId:12});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(saveCalls,1);
  assert.equal(element('detailBusy').classList.contains('d-none'),false,'The busy overlay starts for the mutation');
  rejectSave(new Error('The saved response was lost'));
  await pending;
  assert.equal(element('detailBusy').classList.contains('d-none'),true);
  assert.equal(element('versionText').value,'Draft text');
  assert.equal(element('newVersionError').textContent,'The saved response was lost');
  assert.equal(state.versionAttempt.command.expectedVersionId,12);
});

test('portfolio intake keeps one interactive choice, releases cancellation and preserves newer text after save',async()=>{
  const elements=new Map();
  function element(id){
    if(!elements.has(id)) {
      const classes=new Set(['d-none']);
      elements.set(id,{value:'',textContent:'',disabled:false,scrollIntoView(){},
        classList:{add:className=>classes.add(className),remove:className=>classes.delete(className),
          contains:className=>classes.has(className),toggle(className,force){if(force)classes.add(className);else classes.delete(className);}}});
    }
    return elements.get(id);
  }
  const reviews=[],saves=[];let finishSave,resetCount=0;
  const textField=element('text');textField.value=' Raw draft\n';
  const form={querySelector:selector=>selector==='[name="text"]'?textField:null,reset(){resetCount++;}};
  const window={location:{search:''},setTimeout(){},
    TaxonomyRequirementIntake:{prepare(form,payload,projectId){
      return new Promise(resolve=>reviews.push({payload,projectId,resolve}));
    },isCurrent:()=>textField.value===reviews.at(-1).payload.text},
    TaxonomyArchitectureLifecycle:{saveRequirement(command){saves.push(command);return new Promise(resolve=>{finishSave=resolve;});}}};
  const document={documentElement:{lang:'en'},addEventListener(){},getElementById:element,querySelectorAll:()=>[element('submit')]};
  class FormData {forEach(callback){callback('PUMP','requirementKey');callback('Pump control','title');callback(textField.value,'text');}}
  const context=canonicalFixture.install({window,document,URLSearchParams});
  context.FormData=FormData; // This DOM fixture supplies its form-field iterator.
  vm.runInContext(portfolioSource.replace(/\}\)\(\);\s*$/,'window.portfolioProbe={state,createRequirement};})();'),context);
  const {state,createRequirement}=window.portfolioProbe;state.selectedProjectId=7;
  const event={currentTarget:form,preventDefault(){}};
  const cancelled=createRequirement(event);
  void createRequirement(event);
  assert.equal(reviews.length,1,'Repeated submit must retain the original explicit-choice waiter');
  assert.equal(element('portfolioBusy').classList.contains('d-none'),true,'The overlay cannot cover the pending choice');
  assert.equal(saves.length,0);
  reviews[0].resolve(null);await cancelled;
  const pending=createRequirement(event);
  assert.equal(reviews.length,2,'Cancellation releases the submit guard');
  assert.equal(reviews[1].payload.text,' Raw draft\n');
  reviews[1].resolve({choice:'NEW',projectId:7,requirement:reviews[1].payload});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(saves.length,1);
  assert.equal(element('portfolioBusy').classList.contains('d-none'),false);
  textField.value='Newer unsaved text';
  finishSave({projectId:7,requirementId:2,requirement:{id:2},publication:{state:'CHECKPOINTED'}});
  await pending;
  assert.equal(element('portfolioBusy').classList.contains('d-none'),true);
  assert.equal(textField.value,'Newer unsaved text');assert.equal(resetCount,0);
});

for (const surface of ['detail','portfolio']) {
  test(`${surface} keeps its draft and original attempt when accepted text still requires reconciliation`,async()=>{
    const elements=new Map(),recoveries=[],savedMessages=[];let hidden=0,resets=0,reloads=0;
    function element(id){
      if(!elements.has(id)) {
        const classes=new Set(['d-none']);
        elements.set(id,{value:'',textContent:'',dataset:{},disabled:false,focus(){},scrollIntoView(){},
          classList:{add(...values){values.forEach(value=>classes.add(value));},remove(...values){values.forEach(value=>classes.delete(value));},
            contains:value=>classes.has(value),toggle(value,force){if(force)classes.add(value);else classes.delete(value);}}});
      }
      return elements.get(id);
    }
    const draft=element(surface==='detail'?'versionText':'text');draft.value='Unpublished draft\nKeep the exact text';
    const form=element(surface==='detail'?'newVersionForm':'requirementForm');
    form.querySelector=selector=>selector==='[name="text"]'?draft:null;form.reset=()=>{resets++;draft.value='';};
    const command={choice:'CHANGE',projectId:1,requirementId:2,expectedVersionId:12,expectedContentHash:'original-hash',requirement:{text:draft.value}};
    const window={location:{pathname:'/projects/1/requirements/2',search:''},setTimeout(){},
      TaxonomyI18n:{t:key=>key},TaxonomyRequirementIntake:{attach(){},prepareVersion:async()=>command,prepare:async()=>command,isCurrent:()=>true},
      TaxonomyArchitectureLifecycle:{async saveRequirement(value){
        value.context={revision:2};value.metadata={commandId:'original-command'};
        return {projectId:1,requirementId:2,requirement:{id:2,currentVersion:{id:13,contentHash:'new-hash'}},publication:{state:'RECONCILIATION_REQUIRED'}};
      },showPublicationRecovery(target,value){recoveries.push(value.publication.state);}}};
    const document={documentElement:{lang:'en'},addEventListener(){},getElementById:element,querySelector:()=>element('submit'),querySelectorAll:()=>[element('submit')]};
    const bootstrap={Modal:{getOrCreateInstance:()=>({hide(){hidden++;}})}};
    class FormData {forEach(callback){callback('REQ-2','requirementKey');callback('Requirement','title');callback(draft.value,'text');}}
    if(surface==='detail') {
      const instrumented=detailSource.replace(/\}\)\(\);\s*$/,'window.detailProbe={state,createVersion,captureVersionDraftBase,setObservers:function(reload,saved){loadAll=reload;showSavedRequirement=saved;}};})();');
      vm.runInNewContext(instrumented,{window,document,bootstrap,URLSearchParams,Date,JSON,Number});
      const probe=window.detailProbe;probe.state.requirement={requirementKey:'REQ-2',title:'Requirement'};
      probe.captureVersionDraftBase({id:12,contentHash:'original-hash'});
      probe.setObservers(async()=>{reloads++;},(_saved,message)=>savedMessages.push(message));
      await probe.createVersion({preventDefault(){}});
      assert.equal(probe.state.versionAttempt?.command,command,'The exact accepted but unpublished attempt must remain available');
      assert.equal(probe.state.versionDraftBase.requirementVersionId,12);
    } else {
      const instrumented=portfolioSource.replace(/\}\)\(\);\s*$/,'window.portfolioProbe={state,createRequirement,setRefresh:function(reload){refreshSelectedProject=reload;}};})();');
      const context=canonicalFixture.install({window,document,bootstrap,URLSearchParams});
      context.FormData=FormData;
      vm.runInContext(instrumented,context);
      const probe=window.portfolioProbe;probe.state.selectedProjectId=1;probe.setRefresh(async()=>{reloads++;});
      await probe.createRequirement({preventDefault(){},currentTarget:form});
    }
    assert.equal(hidden,0);assert.equal(resets,0);assert.equal(reloads,0);assert.deepEqual(savedMessages,[]);
    assert.equal(draft.value,'Unpublished draft\nKeep the exact text');
    assert.deepEqual(recoveries,['RECONCILIATION_REQUIRED'],'The existing reconciliation workflow must be offered explicitly');
  });
}

test('analysis promotion retains the same accepted attempt while reconciliation is still required',async()=>{
  let preparations=0;const commands=[];
  const C={S:{},runtime:{},text:key=>key,language:()=> 'en',currentPayload:()=>({businessText:'Draft'}),
    comparable:JSON.stringify,meaningful:()=>true,saveDraft:async()=>true};
  const window={__TaxonomyAnalysisSessionContext:C,
    TaxonomyRequirementIntake:{async prepare(){preparations++;return {choice:'NEW',projectId:1,requirement:{text:'Draft'}};}},
    TaxonomyArchitectureLifecycle:{async saveRequirement(command){commands.push(command);return {publication:{state:commands.length===1?'RECONCILIATION_REQUIRED':'CHECKPOINTED'}};}}};
  vm.runInNewContext(source.replace(/\}\(\)\);\s*$/,'window.analysisProbe={createRequirement};}());'),
    {window,document:{readyState:'loading',addEventListener(){}},Promise,Date,String,Object,JSON,Math,encodeURIComponent});
  const form={},values={requirementKey:'REQ-1',title:'Requirement',text:'Draft'};
  const first=await window.analysisProbe.createRequirement(1,values,form,{});
  assert.equal(first.saved.publication.state,'RECONCILIATION_REQUIRED');
  await window.analysisProbe.createRequirement(1,values,form,{});
  assert.equal(preparations,1,'Unpublished acceptance must not become a new intake decision');
  assert.equal(commands[1],commands[0]);
});
