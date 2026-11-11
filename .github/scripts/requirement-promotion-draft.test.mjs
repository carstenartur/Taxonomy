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
const lifecycleApiSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/architecture-lifecycle-api.js',import.meta.url),'utf8');
const lifecycleSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-lifecycle.js',import.meta.url),'utf8');
afterEach(()=>canonicalFixture.dispose());

function detailStartup({holdOffers=false,forbidden=false,copilot=false,probe=false,lifecycleFetch=null,route='/projects/7/requirements/11'}={}) {
  const nodes=new Map(),listeners=new Map(),windowListeners=new Map(),requests=[],auth=[],all=[];
  let finishLifecycle,finishOffers;
  const lifecycle=new Promise(resolve=>{finishLifecycle=resolve;});
  const offersGate=new Promise(resolve=>{finishOffers=resolve;});
  function node(id='',tag='div') {
    const values=new Set(),events=new Map();
    const value={id,tagName:tag.toUpperCase(),children:[],dataset:{},style:{},value:'',textContent:'',classList:{
      add(...names){names.forEach(name=>values.add(name));},remove(...names){names.forEach(name=>values.delete(name));},
      contains:name=>values.has(name),toggle(name,force){if(force)values.add(name);else values.delete(name);},[Symbol.iterator]:()=>values.values()},
      append(...children){this.children.push(...children);},appendChild(child){this.append(child);return child;},
      replaceChildren(...children){this.children=children;},focus(){},remove(){this.removed=true;},reportValidity:()=>true,
      addEventListener(type,listener){if(!events.has(type))events.set(type,[]);events.get(type).push(listener);},
      fire(type){return Promise.all((events.get(type)||[]).map(listener=>listener({preventDefault(){},target:this})));},
      showModal(){this.open=true;},close(){this.open=false;this.closed=(this.closed||0)+1;return this.fire('close');},
      setAttribute(name,text){this[name]=text;},removeAttribute(name){delete this[name];},
      querySelectorAll(selector){
        const descendants=this.children.flatMap(child=>[child,...(child.querySelectorAll?.('*')||[])]);
        if(selector==='*')return descendants;
        const key=selector.match(/^\[data-([a-z-]+)\]$/)?.[1]?.replace(/-([a-z])/g,(_,letter)=>letter.toUpperCase());
        return key?descendants.filter(child=>child.dataset?.[key]!==undefined):descendants.filter(child=>child.tagName===selector.toUpperCase());
      },querySelector(selector){return this.querySelectorAll(selector)[0]||null;}};
    if(id)nodes.set(id,value);all.push(value);
    return value;
  }
  const get=id=>{
    if(id.startsWith('application') || id==='architectureWorkspace' || id==='projectFilter' || id==='importMain' || id==='matrixMain' || id==='reportsMain')return null;
    return nodes.get(id)||node(id);
  };
  const document={readyState:'loading',documentElement:{lang:'en'},head:{appendChild(){}},getElementById:get,
    body:node('body','body'),querySelector:selector=>selector.startsWith('meta')?null:node(),querySelectorAll:selector=>selector==='#textPane h2'?[node(),node()]:[],createElement:tag=>node('',tag),
    addEventListener(type,listener){if(!listeners.has(type))listeners.set(type,[]);listeners.get(type).push(listener);},
    dispatchEvent(event){if(event.type==='taxonomy-api-auth-failure')auth.push(event);for(const listener of listeners.get(event.type)||[])listener(event);}};
  const window={location:{pathname:route,search:'?lang=en&proposal=offer',href:'https://example.test'+route+'?lang=en&proposal=offer',origin:'https://example.test'},
    addEventListener(type,listener){if(!windowListeners.has(type))windowListeners.set(type,[]);windowListeners.get(type).push(listener);},
    TaxonomyI18n:{t:key=>key,ready:async()=>{},resolveUrl:path=>path,getBasePath:()=>'',getLocale:()=> 'en',formatEnum:String},
    TaxonomyUtils:{escapeHtml:String},TaxonomyRequirementCopilot:{},TaxonomyArchitectureLifecycle:{ready:()=>lifecycle}};
  const requirement={id:11,requirementKey:'REQ',title:'Requirement',currentVersionId:44,currentVersion:{id:44,versionNumber:1,text:'Original'},status:'APPROVED',reviewStatus:'CONFIRMED'};
  const offer={id:'offer',baseline:{originalText:'Original',sourceVersionId:44},currentRevision:{number:1,text:'Retained draft',questions:[],statements:[],validation:{findings:[]}}};
  const context=canonicalFixture.install({window,document,location:window.location,fetch:async(input,init)=>{
    const pathname=new URL(String(input),window.location.href).pathname;
    requests.push({pathname,init});
    if(lifecycleFetch) {
      const result=await lifecycleFetch(new URL(String(input),window.location.href),init);
      if(result)return result;
    }
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
  const detail=probe?detailSource.replace(/\}\)\(\);\s*$/,'window.publicationProbe={state,createVersion,captureVersionDraftBase,setObservers:function(reload,saved){loadAll=reload;const renderSaved=showSavedRequirement;showSavedRequirement=function(value,message){saved(value,message);renderSaved(value,message);};}};})();'):detailSource;
  for(const [source,name] of [[chromeSource,'application-chrome.js'],[detail,'requirement-detail.js'],[reformulationSource,'requirement-reformulation.js']])vm.runInContext(source,context,{filename:name});
  if(copilot)vm.runInContext(copilotSource,context,{filename:'requirement-copilot.js'});
  return {window,context,document,requests,auth,get,all,
    start:()=>Promise.all((listeners.get('DOMContentLoaded')||[]).map(listener=>listener({}))),
    releaseLifecycle(){window.TaxonomyAppChrome.updateContext({architecture:{repositoryId:'repository-a',branch:'draft',modelStand:'initial'}});finishLifecycle(null);},
    releaseOffers:()=>finishOffers(),pagehide(){for(const listener of windowListeners.get('pagehide')||[])listener({});}};
}
const settleStartup=async()=>{for(let i=0;i<6;i++)await new Promise(resolve=>setImmediate(resolve));};

test('a version draft opened during the first detail read retains its text and binds the first authoritative base',async()=>{
  let releaseRequirement, prepared;
  const contentHash='a'.repeat(64);
  const h=detailStartup({probe:true,lifecycleFetch:async url=>{
    if(url.pathname==='/api/projects/7/requirements/11')return new Promise(resolve=>{releaseRequirement=resolve;});
  }});
  const started=h.start();h.releaseLifecycle();await settleStartup();
  assert.equal(typeof releaseRequirement,'function');
  h.get('newVersionModal').classList.add('show');
  h.get('versionText').value='An authored clarification entered while the current version loads';
  h.get('changeReason').value='Clarify the retained evidence';
  await h.get('newVersionForm').fire('input');
  releaseRequirement(Response.json({id:11,requirementKey:'REQ',title:'Requirement',currentVersionId:44,
    currentVersion:{id:44,versionNumber:1,text:'Original',contentHash},status:'APPROVED',reviewStatus:'CONFIRMED'}));
  await started;await settleStartup();
  h.window.TaxonomyRequirementIntake={attach(){},prepareVersion:async(...args)=>{prepared=args;return null;}};
  await h.window.publicationProbe.createVersion({preventDefault(){}});
  assert.equal(h.get('versionText').value,'An authored clarification entered while the current version loads');
  assert.equal(h.get('changeReason').value,'Clarify the retained evidence');
  assert.ok(prepared,'The loaded detail must allow an explicit new-version review');
  assert.deepEqual(prepared.slice(2),[7,11,44,contentHash],
    'Review must use the first loaded version rather than leaving the early draft without a base');
  assert.equal(h.requests.some(request=>request.init.method==='POST'),false,'No choice or mutation is inferred from loading');
});

for(const transition of ['own-publication','changed-draft','history','branch','pagehide','authentication','foreign-publication',
  'CHECKPOINT_PENDING','RECONCILIATION_REQUIRED','failed-confirmation']) {
  test(`actual version save applies only its own current publication across ${transition}`,async()=>{
    const base={repositoryId:'repository-a',workspaceScopeKey:'workspace-a',branch:'draft',revision:1,
      commit:'1'.repeat(40),actor:'owner',writeMode:'PRIVATE_WORKSPACE'};
    let current={...base},projection='READY',scope={...base},release,admitted,deny=false,confirmationUnavailable=false;
    let writeCount=0,preparations=0;
    const incomplete=['CHECKPOINT_PENDING','RECONCILIATION_REQUIRED'].includes(transition);
    const admission=new Promise(resolve=>{admitted=resolve;});
    const value=()=>({context:{...current},source:'WORKSPACE_REVISION',projectionState:projection,mayEdit:projection==='READY',
      projects:[{id:7,projectKey:'P',title:'Project'}],requirements:[{projectId:7,requirementId:11,requirementKey:'REQ',title:'Requirement',
        version:current.revision,versionId:current.revision===1?44:45,realization:{state:'OPEN'}}],dependencies:[]});
    const h=detailStartup({probe:true,lifecycleFetch:async(url,init)=>{
      if(deny&&url.pathname==='/api/account/me')return Response.json({error:'Authentication required'},{status:403});
      if(url.pathname==='/api/architecture/lifecycle')return confirmationUnavailable
        ?Response.json({detail:'Publication confirmation unavailable'},{status:503}):Response.json(value());
      if(url.pathname==='/api/architecture/editor/requirements') {
        writeCount++;
        const receipt=()=>{
          current={...base,revision:2,commit:'2'.repeat(40)};
          projection=incomplete&&writeCount===1?transition:'READY';
          return Response.json({projectId:7,requirementId:11,requirement:{id:11,currentVersion:{id:45,contentHash:'b'.repeat(64)}},
            publication:{state:incomplete&&writeCount===1?transition:'CHECKPOINTED',context:{...base,revision:2,commit:'2'.repeat(40)}}});
        };
        if(writeCount>1)return receipt();
        admitted({url,init});
        return new Promise(resolve=>{release=()=>{
          if(transition==='history'||transition==='foreign-publication') {
            resolve(Response.json({projectId:7,requirementId:11,requirement:{id:11,currentVersion:{id:45,contentHash:'b'.repeat(64)}},
              publication:{state:'CHECKPOINTED',context:{...base,revision:2,commit:'2'.repeat(40)}}}));
          } else resolve(receipt());
        };});
      }
    }});
    const started=h.start();h.releaseLifecycle();await started;await settleStartup();
    const get=h.document.getElementById;
    h.document.getElementById=id=>id==='architectureEditor'?null:get(id);
    h.window.TaxonomyArchitectureWorkflow={getScope:()=>({...scope}),setScope:value=>{scope={...value};},scopeUrl:url=>url};
    h.window.history={replaceState(_state,_title,url){
      const next=new URL(url,h.window.location.href);
      Object.assign(h.window.location,{href:next.href,pathname:next.pathname,search:next.search});
    }};
    vm.runInContext(lifecycleApiSource,h.context);vm.runInContext(lifecycleSource,h.context);
    await h.window.TaxonomyArchitectureLifecycle.ready();
    assert.ok(h.window.TaxonomyArchitectureLifecycle.state(),h.all.find(node=>node.dataset.lifecycleStatus!==undefined)?.textContent);
    const command={choice:'CHANGE',projectId:7,requirementId:11,expectedVersionId:44,
      expectedContentHash:'a'.repeat(64),requirement:{text:'Retained draft',changeReason:'Reviewed'}};
    h.window.TaxonomyRequirementIntake={attach(){},prepareVersion:async()=>{preparations++;return command;}};
    const probe=h.window.publicationProbe;probe.captureVersionDraftBase({id:44,contentHash:'a'.repeat(64)});
    h.get('versionText').value='Retained draft';h.get('changeReason').value='Reviewed';
    let hidden=0,receipts=0;const reloads=[];
    h.window.TaxonomyUtils.hideModal=()=>{hidden++;};
    probe.setObservers(async preserve=>{reloads.push(preserve);},()=>{receipts++;});
    const pending=probe.createVersion({preventDefault(){}});
    await settleStartup();
    assert.equal(writeCount,1,h.get('newVersionError').textContent||'Detail submission must reach its admitted write');
    const request=await admission,attempt=probe.state.versionAttempt;
    h.get('newVersionError').textContent='The current view owns this message';
    if(transition==='history') {
      projection='HISTORICAL';h.window.location.search='?revision=1';
      await h.window.TaxonomyArchitectureLifecycle.refresh(current,1);
    } else if(transition==='branch') {
      scope={...base,branch:'other'};h.window.TaxonomyArchitectureLifecycle.beginSelection(scope,null);
    } else if(transition==='pagehide')h.pagehide();
    else if(transition==='changed-draft') {
      h.get('versionText').value='Newer unsaved draft';probe.state.versionFormDirty=true;
    } else if(transition==='failed-confirmation')confirmationUnavailable=true;
    else if(transition==='authentication') {
      deny=true;await assert.rejects(h.window.TaxonomyApiClient.getJson('/api/account/me'),error=>error.status===403);
    } else if(transition==='foreign-publication') {
      current={...base,revision:9,commit:'9'.repeat(40)};
      await h.window.TaxonomyArchitectureLifecycle.refresh(current,null);
    }
    release();await pending;
    assert.equal(JSON.parse(request.init.body).context.revision,1);
    assert.equal(request.init.headers.get('If-Match'),'"workspace-revision-1"');
    assert.equal(writeCount,1,'One admitted attempt performs one write');
    if(transition==='own-publication'||transition==='changed-draft') {
      assert.equal(hidden,transition==='changed-draft'?0:1);
      assert.deepEqual(reloads,[transition==='changed-draft']);assert.equal(receipts,1);
      assert.equal(probe.state.versionAttempt,null);
      assert.equal(probe.state.versionDraftBase.requirementVersionId,45);
      if(transition==='changed-draft')assert.equal(probe.state.versionFormDirty,true);
    } else {
      assert.equal(hidden,0);assert.deepEqual(reloads,[]);assert.equal(receipts,0);
      assert.equal(probe.state.versionAttempt,attempt,'A departed receipt must not discard its original attempt');
      assert.equal(probe.state.versionDraftBase.requirementVersionId,44);
      if(incomplete) {
        assert.equal(h.window.TaxonomyArchitectureLifecycle.state().mayEdit,false);
        assert.equal(h.window.TaxonomyArchitectureLifecycle.state().projectionState,transition);
        assert.ok(h.get('newVersionError').querySelector('[data-lifecycle-publication-recovery]'),
          'The actual pending publication must retain its visible recovery action');
      } else assert.equal(h.get('newVersionError').textContent,'The current view owns this message');
    }
    assert.equal(h.get('versionText').value,transition==='changed-draft'?'Newer unsaved draft':'Retained draft');
    if(incomplete||transition==='failed-confirmation') {
      confirmationUnavailable=false;
      await probe.createVersion({preventDefault(){}});
      assert.equal(preparations,1,'Retry must retain the original explicit intake decision');
      assert.equal(writeCount,incomplete?2:1,'A known completed write retries confirmation only');
      const writes=h.requests.filter(value=>value.pathname==='/api/architecture/editor/requirements');
      for(const write of writes) {
        assert.equal(write.init.body,request.init.body,'Retry must preserve the exact command body and UUID');
        assert.equal(write.init.headers.get('If-Match'),'"workspace-revision-1"');
      }
      assert.equal(hidden,1);assert.deepEqual(reloads,[false]);assert.equal(receipts,1);
      assert.equal(probe.state.versionAttempt,null);
      assert.equal(probe.state.versionDraftBase.requirementVersionId,45);
    }
    if(receipts) {
      const link=h.get('detailInfo').children.at(-1),url=new URL(link.href);
      assert.equal(url.pathname,'/projects/7/requirements/11');
      assert.equal(url.searchParams.get('repositoryId'),base.repositoryId);
      assert.equal(url.searchParams.get('workspaceId'),base.workspaceScopeKey);
      assert.equal(url.searchParams.get('branch'),base.branch);
      assert.equal(link.target,'_blank');assert.equal(link.rel,'noopener');
    }
  });
}

for(const [dialogKind,transition] of [['existing','current'],['new','current'],['existing','history'],['new','source'],
  ['existing','authentication'],['new','pagehide'],['existing','source-during-reset'],
  ['existing','source-during-reset-rejection'],['existing','reconciliation']]) {
  test(`analysis ${dialogKind}-project dialog retains its source ownership across ${transition}`,async()=>{
    const base={repositoryId:'repository-a',workspaceScopeKey:'workspace-a',branch:'draft',revision:1,
      commit:'1'.repeat(40),actor:'owner',writeMode:'PRIVATE_WORKSPACE'};
    let projection='READY',scope={...base},selectedSource={kind:'analysis',workspaceId:'workspace-a'},release,admit,deny=false;
    let writes=0,preparations=0,saves=0,resets=0,invalidations=0,resetStarted,finishReset,failReset;
    const sourceDuringReset=transition.startsWith('source-during-reset');
    const admission=new Promise(resolve=>{admit=resolve;}),resetting=new Promise(resolve=>{resetStarted=resolve;});
    const resetGate=new Promise((resolve,reject)=>{finishReset=resolve;failReset=reject;});
    const navigations=[];
    const h=detailStartup({route:'/',lifecycleFetch:async(url,init)=>{
      if(deny&&url.pathname==='/api/account/me')return Response.json({detail:'Authentication revoked'},{status:403});
      if(url.pathname==='/api/architecture/lifecycle')return Response.json({context:base,source:'WORKSPACE_REVISION',
        projectionState:projection,mayEdit:projection==='READY',projects:[],requirements:[],dependencies:[]});
      if(url.pathname==='/api/projects')return Response.json([{id:7,projectKey:'P',title:'Project'}]);
      if(url.pathname==='/api/architecture/editor/requirements') {
        writes++;admit({url,init});
        const receipt=()=>Response.json({projectId:7,requirementId:11,requirement:{id:11},publication:{
          state:transition==='reconciliation'&&writes===1?'RECONCILIATION_REQUIRED':'CHECKPOINTED',context:{...base,revision:2}}});
        if(writes>1)return receipt();
        return new Promise(resolve=>{release=()=>resolve(receipt());});
      }
    }});
    h.window.location.assign=url=>navigations.push(url);
    h.window.TaxonomyArchitectureWorkflow={getScope:()=>({...scope}),setScope:value=>{scope={...value};},scopeUrl:url=>url};
    h.window.TaxonomyArchitectureWorkspace={isActive:()=>selectedSource.kind!=='analysis',getSource:()=>({...selectedSource})};
    const C={S:{},runtime:{workspaceId:'workspace-a',analysisGeneration:1},text:key=>key,language:()=> 'en',
      currentPayload:()=>({businessText:'Original draft'}),comparable:JSON.stringify,meaningful:()=>true,
      jsonRequest:url=>h.window.TaxonomyApiClient.getJson(url),showActionAlert(){},queueSave(){},
      saveDraft:async()=>{saves++;return true;},resetDraft:()=>{resets++;resetStarted();return sourceDuringReset?resetGate:Promise.resolve({version:2});},
      invalidate(){invalidations++;}};
    h.window.__TaxonomyAnalysisSessionContext=C;
    h.window.TaxonomyRequirementIntake={attach(){},isCurrent:()=>true,async prepare(_form,payload,projectId){
      preparations++;return {choice:'NEW',projectId,requirement:payload};
    }};
    vm.runInContext(lifecycleApiSource,h.context);vm.runInContext(lifecycleSource,h.context);
    await h.window.TaxonomyArchitectureLifecycle.ready();
    await h.window.TaxonomyArchitectureLifecycle.refresh(base,null);
    assert.ok(h.document.getElementById('architectureEditor'),'The unified Analysis page also contains the inactive working editor');
    vm.runInContext(source.replace(/\}\(\)\);\s*$/,'window.analysisProbe={promotionAttempts};}());'),h.context);
    if(dialogKind==='new')C.openNewProjectDialog({seedRequirementText:'Original draft'});
    else {C.openRequirementDialog('Original draft');await settleStartup();}
    const dialog=h.all.filter(node=>node.tagName==='DIALOG').at(-1),form=dialog.querySelector('form');
    const select=form.querySelector('select');if(select)select.value='7';
    await form.fire('submit');
    await settleStartup();
    assert.equal(writes,1,form.children[2].textContent||'The explicit dialog submission must reach its admitted write');
    const request=await admission,attempt=h.window.analysisProbe.promotionAttempts.get(form);
    const message=form.querySelector('[role="alert"]')||form.children[2];
    message.textContent='The current selection owns this dialog';
    if(transition==='history') {
      projection='HISTORICAL';h.window.location.search='?revision=1';
      h.window.location.href='https://example.test/?revision=1';
      await h.window.TaxonomyArchitectureLifecycle.refresh(base,1);
    } else if(transition==='source')selectedSource={kind:'snapshot',snapshotId:99,projectId:7};
    else if(transition==='pagehide')h.pagehide();
    else if(transition==='authentication') {
      deny=true;await assert.rejects(h.window.TaxonomyApiClient.getJson('/api/account/me'),error=>error.status===403);
    }
    release();
    if(sourceDuringReset) {
      await settleStartup();assert.equal(resets,1,'The current receipt must reach the held draft reset');
      await resetting;selectedSource={kind:'snapshot',snapshotId:99,projectId:7};
      if(transition==='source-during-reset-rejection')failReset(new Error('Reset failed after source departure'));
      else finishReset({version:2});
    }
    await settleStartup();
    assert.equal(writes,1);assert.equal(preparations,1);assert.equal(saves,1);
    assert.equal(JSON.parse(request.init.body).context.revision,1);
    assert.equal(request.init.headers.get('If-Match'),'"workspace-revision-1"');
    if(transition==='current') {
      assert.equal(dialog.closed,1);assert.equal(resets,1);assert.equal(invalidations,1);
      assert.deepEqual(navigations,['/projects/7/requirements/11']);
      assert.equal(h.window.analysisProbe.promotionAttempts.has(form),false);
    } else if(sourceDuringReset) {
      assert.equal(resets,1);assert.equal(invalidations,0);assert.deepEqual(navigations,[]);
      assert.equal(message.textContent,'The current selection owns this dialog');
    } else {
      assert.equal(dialog.closed||0,0);assert.equal(resets,0);assert.equal(invalidations,0);assert.deepEqual(navigations,[]);
      assert.equal(h.window.analysisProbe.promotionAttempts.get(form),attempt,'Departure must retain the original admitted command');
      if(transition==='reconciliation') {
        assert.ok(message.querySelector('[data-lifecycle-publication-recovery]'));
        await form.fire('submit');await settleStartup();
        assert.equal(preparations,1);assert.equal(saves,1);assert.equal(writes,2);
        const replay=h.requests.filter(value=>value.pathname==='/api/architecture/editor/requirements')[1];
        assert.equal(replay.init.body,request.init.body);assert.equal(replay.init.headers.get('If-Match'),request.init.headers.get('If-Match'));
        assert.equal(dialog.closed,1);assert.equal(resets,1);assert.deepEqual(navigations,['/projects/7/requirements/11']);
      } else assert.equal(message.textContent,'The current selection owns this dialog');
    }
  });
}

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

test('a draft already open on the first detail load retains its edits and original version identity', () => {
  const fields = new Map();
  const get = id => {
    if (!fields.has(id)) fields.set(id, {textContent:'', value:''});
    return fields.get(id);
  };
  const window = {location:{pathname:'/projects/7/requirements/9', search:''}};
  const document = {documentElement:{lang:'en'}, addEventListener(){}, getElementById:get};
  const exposed = detailSource.replace(/\}\)\(\);\s*$/, 'window.detailProbe={state,renderCurrentText};})();');
  vm.runInNewContext(exposed, {window, document, URLSearchParams});
  const {state,renderCurrentText} = window.detailProbe;
  const original = {id:44, text:'Original', contentHash:'a'.repeat(64)};
  get('versionText').value = 'Clarification entered while loading';
  get('changeReason').value = 'Clarify evidence';
  state.requirement = {currentVersion:original};

  renderCurrentText(true);
  assert.equal(state.versionDraftBase?.requirementVersionId, original.id);
  assert.equal(state.versionDraftBase?.contentHash, original.contentHash);
  state.requirement.currentVersion = {id:45, text:'Newer server version', contentHash:'b'.repeat(64)};
  renderCurrentText(true);
  assert.equal(get('versionText').value, 'Clarification entered while loading');
  assert.equal(get('changeReason').value, 'Clarify evidence');
  assert.equal(state.versionDraftBase.requirementVersionId, original.id);
  assert.equal(state.versionDraftBase.contentHash, original.contentHash);
  assert.equal(get('currentText').textContent, 'Newer server version');
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
  vm.runInContext(instrumented,canonicalFixture.install({window,document,URLSearchParams,Date,JSON,Number}));
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
    TaxonomyArchitectureLifecycle:{isCurrentRequirementPublication:()=>true,
      saveRequirement(command){saves.push(command);return new Promise(resolve=>{finishSave=resolve;});}}};
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
      TaxonomyArchitectureLifecycle:{isCurrentRequirementPublication:()=>true,async saveRequirement(value){
        value.context={revision:2};value.metadata={commandId:'original-command'};
        return {projectId:1,requirementId:2,requirement:{id:2,currentVersion:{id:13,contentHash:'new-hash'}},publication:{state:'RECONCILIATION_REQUIRED'}};
      },showPublicationRecovery(target,value){recoveries.push(value.publication.state);}}};
    const document={documentElement:{lang:'en'},addEventListener(){},getElementById:element,querySelector:()=>element('submit'),querySelectorAll:()=>[element('submit')]};
    const bootstrap={Modal:{getOrCreateInstance:()=>({hide(){hidden++;}})}};
    class FormData {forEach(callback){callback('REQ-2','requirementKey');callback('Requirement','title');callback(draft.value,'text');}}
    if(surface==='detail') {
      const instrumented=detailSource.replace(/\}\)\(\);\s*$/,'window.detailProbe={state,createVersion,captureVersionDraftBase,setObservers:function(reload,saved){loadAll=reload;showSavedRequirement=saved;}};})();');
      vm.runInContext(instrumented,canonicalFixture.install({window,document,bootstrap,URLSearchParams,Date,JSON,Number}));
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
  vm.runInContext(source.replace(/\}\(\)\);\s*$/,'window.analysisProbe={createRequirement};}());'),
    canonicalFixture.install({window,document:{readyState:'loading',addEventListener(){}},Promise,Date,String,Object,JSON,Math,encodeURIComponent}));
  const form={},values={requirementKey:'REQ-1',title:'Requirement',text:'Draft'};
  const first=await window.analysisProbe.createRequirement(1,values,form,{});
  assert.equal(first.saved.publication.state,'RECONCILIATION_REQUIRED');
  await window.analysisProbe.createRequirement(1,values,form,{});
  assert.equal(preparations,1,'Unpublished acceptance must not become a new intake decision');
  assert.equal(commands[1],commands[0]);
});

for (const transition of ['stay', 'pagehide', 'other requirement']) {
  test('detail preserves lifecycle hydration before publishing its project on ' + transition, async () => {
    const page = new EventTarget(), publications = [], reads = [];
    const location = new URL('https://example.test/projects/7/requirements/3');
    let initialize, confirm;
    const overview = new Promise(resolve => {confirm = resolve;});
    const pending = new Promise(() => {});
    const node = {addEventListener(){}, classList:{toggle(){}}};
    const document = {documentElement:{lang:'en'}, getElementById:() => node, querySelector:() => node,
      querySelectorAll:() => [node,node],
      addEventListener(type, fn) {if (type === 'DOMContentLoaded') initialize = fn;}};
    const window = {location, addEventListener:page.addEventListener.bind(page),
      TaxonomyI18n:{ready:() => Promise.resolve()}, TaxonomyArchitectureLifecycle:{ready:() => overview},
      TaxonomyAppChrome:{updateContext(value) {publications.push(value.project.id);}},
      TaxonomyPortfolioApi:{getProject(id) {reads.push(id);return pending;}, getRequirement:() => pending,
        listRequirementVersions:() => pending, listRequirementSnapshots:() => pending,
        getProjectPortfolio:() => pending, getAccount:() => pending}};
    vm.runInNewContext(detailSource,{window,document,URLSearchParams});
    const startup = initialize();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(publications, []);
    assert.deepEqual(reads, []);
    if (transition === 'pagehide') page.dispatchEvent(new Event('pagehide'));
    if (transition === 'other requirement') location.pathname = '/projects/7/requirements/4';
    confirm({context:{repositoryId:'selected-repository'}});
    if (transition === 'stay') await new Promise(resolve => setImmediate(resolve));
    else await startup;
    assert.deepEqual(publications, transition === 'stay' ? [7] : []);
    assert.deepEqual(reads, transition === 'stay' ? [7] : []);
  });
}
