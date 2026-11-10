import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';

const resources = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const read = path => readFile(new URL(path, resources), 'utf8').catch(() => '');
const [focus, api, lens, guidance] = await Promise.all([
    read('static/js/architecture-focus.js'), read('static/js/api/architecture-source-focus-api.js'),
    read('static/js/shared/architecture-source-lens.js'), read('static/js/core/taxonomy-analysis-guidance.js')]);
const plain = value => JSON.parse(JSON.stringify(value));
const flush = async () => { for (let i=0;i<8;i++) await new Promise(resolve => setImmediate(resolve)); };
const draft = {kind:'ANALYSIS_DRAFT',repositoryId:'repo',workspaceScopeKey:'alice',branch:'feature',version:7};
const snapshot = {kind:'ANALYSIS_SNAPSHOT',repositoryId:'repo',workspaceScopeKey:'alice',branch:'feature',
    projectId:1,requirementId:2,requirementVersionId:3,snapshotId:'saved-A'};
const proposal = {id:'hypothesis',sourceId:'arch-a',targetId:'arch-b',relationType:'SUPPORTS',
    rationale:'A hypothesis over retained source text',evidence:[{elementId:'arch-a',field:'description',quote:'Literal <evidence>'}],properties:{}};
const response = (source, sourceId='arch-a') => ({source,sourceId,sourceHash:'a'.repeat(64),method:'LOCAL_TEXT_UNASSESSED',
    outcome:'LOCAL_CANDIDATES',modelCalls:0,candidateCount:1,suggestions:[proposal]});

function fixture() {
    const ids=new Map(), handlers=new Map(); let document;
    class Element {
        constructor(tag='div') { this.tagName=tag;this.children=[];this.dataset={};this.attributes={};this.checked=false;this.hidden=false;this.disabled=false;this.value='';this.text=''; }
        set textContent(value) { this.text=String(value);this.children=[]; }
        get textContent() { return this.text+this.children.map(c=>c.textContent).join(''); }
        append(...nodes) { this.children.push(...nodes); }
        replaceChildren(...nodes) { this.text='';this.children=nodes; }
        contains(other) { return this===other||this.children.some(c=>c.contains(other)); }
        setAttribute(name,value) { this.attributes[name]=String(value); }
        getAttribute(name) { return this.attributes[name]??null; }
        addEventListener(name,fn) { this[name]=fn; }
        querySelectorAll(selector) { return this.children.flatMap(c=>[...(c.tagName==='button'?[c]:[]),...c.querySelectorAll(selector)]); }
        querySelector(selector) { return selector[0]==='#'?node(selector.slice(1)):this.querySelectorAll(selector).find(c=>!c.disabled); }
        focus() { document.activeElement=this; }
        scrollIntoView() { this.scrolled=true; }
    }
    const node=id=>{if(!ids.has(id))ids.set(id,new Element());return ids.get(id);};
    document={readyState:'complete',documentElement:{lang:'en'},body:new Element('body'),getElementById:node,createElement:tag=>new Element(tag),
        querySelector:()=>null,addEventListener:(name,fn)=>handlers.set(name,[...(handlers.get(name)||[]),fn]),
        dispatchEvent:event=>(handlers.get(event.type)||[]).forEach(fn=>fn(event))};document.activeElement=document.body;
    const calls=[],opens=[],presented=[];let current={kind:'analysis',repositoryId:'repo',workspaceScopeKey:'alice',branch:'feature'};
    let session={ready:true,workspaceId:'alice',version:7};let saves=0;
    const context=vm.createContext({document,console,URL,URLSearchParams,AbortController,setTimeout,clearTimeout,
        location:{search:'?repositoryId=repo&workspaceScopeKey=alice&branch=feature',assign:()=>{throw new Error('Lens must remain in this page');}},
        MutationObserver:class{observe(){}},CustomEvent:class{constructor(type,options){this.type=type;this.detail=options?.detail;}},
        TaxonomyI18n:{t:(key,...args)=>[key,...args].join(' '),getLocale:()=> 'en',resolveUrl:path=>'/taxonomy'+path,ready:()=>Promise.resolve()},
        TaxonomyState:{currentArchView:{elements:[{id:'arch-a'},{id:'arch-b'}]},lastAnalysisStatus:'SUCCESS',lastAnalyzedText:'Requirement'},
        TaxonomyAnalysisSession:{state:()=>({...session}),saveNow:async()=>{saves++;return true;},addAsRequirement:options=>opens.push(['save',options])},
        TaxonomyApiClient:{request:(url,options,policy)=>new Promise((resolve,reject)=>calls.push({url,options,policy,resolve:body=>resolve({json:async()=>body}),reject}))}});
    context.window=context;
    const workspace={getSource:()=>({...current}),getData:()=>context.TaxonomyState.currentArchView,
        getDiagram:()=>({presentFocus:value=>presented.push(value)}),select:id=>opens.push(['select',id]),
        open:async(kind,options)=>{opens.push([kind,options]);current={...current,kind};return true;},openLens:()=>opens.push(['lens'])};
    context.TaxonomyArchitectureWorkspace=workspace;
    for(const code of [focus,api,lens])vm.runInContext(code,context);
    return {context,node,calls,opens,presented,workspace,setSource:value=>{current=value;},setSession:value=>{session={...session,...value};},saves:()=>saves,
        create(){assert.equal(typeof context.TaxonomyArchitectureSourceLens?.create,'function','The displayed-source lens factory must exist');
            return context.TaxonomyArchitectureSourceLens.create({root:node('lensRoot'),workspace,idPrefix:'sourceLens'});}};
}

test('analysis guidance opens the in-page lens on analysis and preserves the draft and selected node',async()=>{
    const f=fixture();f.node('businessText').value='Requirement';vm.runInContext(guidance,f.context);
    assert.equal(f.node('analysisLensAction').href,'#architecture');
    await f.node('analysisLensAction').click({preventDefault(){}});await flush();
    assert.equal(f.opens[0][0],'analysis');assert.equal(f.opens[1][0],'lens');
    assert.equal(f.node('businessText').value,'Requirement');assert.equal(f.calls.length,0,'Opening is not a provider request');
});

test('read API pins source without editor If-Match, browser graph, retries or mutable active scope',async()=>{
    const f=fixture();assert.equal(typeof f.context.ArchitectureSourceFocusApi?.suggest,'function');
    const operation=f.context.ArchitectureSourceFocusApi.suggest({source:draft,sourceId:'arch-a',candidateLimit:8,useAi:false,language:'de'});
    assert.equal(f.calls.length,1);const call=f.calls[0];
    assert.match(call.url,/^\/api\/architecture\/focus\/suggestions\?/);
    assert.equal(call.options.headers['If-Match'],undefined);assert.equal(call.policy.retries,0);
    assert.equal(call.policy.pinnedWorkspaceId,'alice');assert.deepEqual(plain(JSON.parse(call.options.body).source),draft);
    call.resolve(response(draft));await operation;
});

test('read-only panel scopes controls and never exposes editor accept or undo commands',async()=>{
    const f=fixture(),controller=f.create();f.setSource({...snapshot,kind:'snapshot'});await controller.activate(f.workspace.getSource());
    controller.select('arch-a');f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();
    f.node('sourceLensInspect').onclick();await flush();f.calls[0].resolve(response(snapshot));await flush();
    const actions=f.node('sourceLensReview').querySelectorAll('button').map(n=>n.dataset.focusAction);
    assert.ok(actions.includes('dismiss'));assert.ok(actions.includes('locate'));assert.ok(actions.includes('adopt'));
    assert.ok(!actions.includes('accept'));assert.ok(!actions.includes('undo'));
    assert.match(f.node('sourceLensReview').textContent,/saved-A|ANALYSIS_SNAPSHOT/);
    assert.equal(f.node('editorLensEnabled').onchange,undefined,'The writable panel is independently scoped');
});

test('analysis investigation saves explicitly and sends exact acknowledged draft version only',async()=>{
    const f=fixture(),controller=f.create();await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    assert.ok(f.saves()>=1);assert.deepEqual(JSON.parse(f.calls[0].options.body),{source:draft,sourceId:'arch-a',candidateLimit:8,useAi:false,language:'en'});
    assert.equal(f.node('sourceLensUseAi').checked,false,'Provider use requires explicit opt in');
    f.calls[0].resolve(response(draft));await flush();assert.match(f.node('sourceLensReview').textContent,/Literal <evidence>/);
});

test('leaving a source aborts and ignores late source replies including overlays',async()=>{
    const f=fixture(),controller=f.create();f.setSource({...snapshot,kind:'snapshot'});await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    const call=f.calls[0];controller.suspend();call.resolve(response(snapshot));await flush();
    assert.equal(call.options.signal.aborted,true);assert.equal(f.node('sourceLensSuggestions').textContent,'');
    assert.ok(!f.presented.at(-1)?.enabled);assert.equal(f.calls.length,1);
});

test('wrong-source reply and unavailable durable draft remain honest failures without editor fallback',async()=>{
    const f=fixture(),controller=f.create();f.setSource({...snapshot,kind:'snapshot'});await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    f.calls[0].resolve(response({...snapshot,snapshotId:'different'}));await flush();
    assert.equal(f.node('sourceLensSuggestions').textContent,'');assert.match(f.node('sourceLensStates').textContent,/failed/);
    f.setSource({kind:'analysis',repositoryId:'repo',workspaceScopeKey:'alice',branch:'feature'});await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    f.calls[1].reject(Object.assign(new Error('No canonical retained source'),{status:409,responseBody:{code:'SOURCE_UNAVAILABLE',detail:'No canonical retained source'}}));await flush();
    assert.match(f.node('sourceLensStates').textContent,/No canonical retained source/);assert.equal(f.opens.length,0);
});

test('snapshot adoption is an explicit handoff to existing preview with exact frozen source pins',async()=>{
    const f=fixture(),controller=f.create();f.setSource({...snapshot,kind:'snapshot'});await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    f.calls[0].resolve(response(snapshot));await flush();
    f.node('sourceLensReview').querySelectorAll('button').find(n=>n.dataset.focusAction==='adopt').onclick();await flush();
    assert.equal(f.calls.length,1,'Preview and acceptance remain owned by existing editor flow');
    assert.equal(f.opens[0][0],'working');const options=plain(f.opens[0][1]);
    assert.equal(options.adoption,'preview');assert.equal(options.sourceSnapshotId,'saved-A');assert.equal(options.sourceRequirementVersionId,3);
    assert.equal(options.sourceWorkspaceScopeKey,'alice');assert.equal(options.sourceBranch,'feature');
});

test('a draft saved to a newer version while an investigation runs cannot receive its old hypotheses',async()=>{
    const f=fixture(),controller=f.create();await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    f.setSession({version:8});f.calls[0].resolve(response(draft));await flush();
    assert.equal(f.node('sourceLensSuggestions').textContent,'');assert.match(f.node('sourceLensError').textContent,/CONTEXT_CHANGED/);
    assert.equal(f.node('sourceLensEnabled').disabled,true);
});

test('readonly diagram callbacks investigate hovered regions and teardown follows the departing diagram',async()=>{
    const f=fixture();let callbacks;const first={presentFocus(){},setLensCallbacks:(region,review)=>{callbacks={region,review};}};
    f.workspace.getDiagram=()=>first;const controller=f.create();await controller.activate(f.workspace.getSource());
    assert.equal(typeof callbacks?.region,'function');assert.equal(typeof callbacks?.review,'function');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();callbacks.region(['arch-a']);
    controller.suspend();assert.equal(callbacks.region,null);assert.equal(callbacks.review,null);
    await flush();assert.equal(f.calls.length,0,'Departing before dwell cancels inference');
});

test('a shared unsaved analysis stays unavailable without inventing a durable version or calling the server',async()=>{
    const f=fixture();f.setSource({kind:'analysis',repositoryId:'repo',workspaceScopeKey:'__shared__',branch:'feature'});f.setSession({workspaceId:null,version:null});
    const controller=f.create();assert.equal(await controller.activate(f.workspace.getSource()),false);
    assert.equal(f.node('sourceLensEnabled').disabled,true);assert.equal(f.calls.length,0);assert.equal(f.saves(),0);
    assert.match(f.node('sourceLensError').textContent,/SOURCE_UNAVAILABLE/);
    assert.match(f.node('sourceLensSummary').textContent,/sourceLens\.unavailable/,'Unavailable retained evidence must not direct users to another model');
});

test('a conflicted draft cannot receive an earlier response even when its numeric version is unchanged',async()=>{
    const f=fixture(),controller=f.create();await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    f.setSession({ready:false,conflict:true});f.calls[0].resolve(response(draft));await flush();
    assert.equal(f.node('sourceLensSuggestions').textContent,'');assert.match(f.node('sourceLensError').textContent,/CONTEXT_CHANGED/);
    assert.equal(f.node('sourceLensEnabled').disabled,true);
});

test('focus uses the newly acknowledged draft revision after live recovery migration is saved',async()=>{
    const f=fixture();let saved=false;
    f.context.TaxonomyAnalysisSession.saveNow=async()=>{saved=true;f.setSession({version:8});return true;};
    const controller=f.create();await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    assert.equal(saved,true);assert.equal(JSON.parse(f.calls[0].options.body).source.version,8);
    f.calls[0].resolve(response({...draft,version:8}));await flush();assert.match(f.node('sourceLensReview').textContent,/Literal <evidence>/);
});

async function reviewedDraft() {
    const f=fixture(),controller=f.create();await controller.activate(f.workspace.getSource());controller.select('arch-a');
    f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
    f.calls[0].resolve(response(draft));await flush();
    const adopt=f.node('sourceLensReview').querySelectorAll('button').find(n=>n.dataset.focusAction==='adopt');
    return {...f,controller,adopt};
}

for(const [name,changed] of [['version',{version:8}],['conflict',{ready:false,conflict:true}],['workspace',{workspaceId:'bob'}]]) {
    test(`a reviewed draft cannot be promoted after its ${name} changes`,async()=>{
        const f=await reviewedDraft();f.setSession(changed);
        f.context.document.dispatchEvent({type:'taxonomy:analysis-draft-saved'});
        await f.controller.refresh();await f.adopt.onclick();await flush();
        assert.equal(f.opens.length,0,'Old reviewed evidence cannot promote the newly current draft');
        assert.equal(f.node('sourceLensEnabled').disabled,true);
        assert.ok(f.node('sourceLensReview').querySelectorAll('button').every(n=>n.dataset.focusAction!=='adopt'||n.disabled));
    });
}

test('adopting a reviewed draft saves unsaved input and refuses an advanced acknowledgement',async()=>{
    const f=await reviewedDraft();let saves=0;
    f.context.TaxonomyAnalysisSession.saveNow=async()=>{saves++;f.setSession({version:8});return true;};
    await f.adopt.onclick();await flush();
    assert.equal(saves,1);assert.equal(f.opens.length,0,'Saving newer input does not make an old hypothesis current');
    assert.match(f.node('sourceLensError').textContent,/CONTEXT_CHANGED/);
});

for(const change of ['object replacement','in-place edit']) {
    test(`a graph ${change} before autosave fences old replies and overlays`,async()=>{
        const f=fixture(),controller=f.create();await controller.activate(f.workspace.getSource());controller.select('arch-a');
        f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();f.node('sourceLensInspect').onclick();await flush();
        const call=f.calls[0];
        if(change==='object replacement')f.context.TaxonomyState.currentArchView={elements:[{id:'new-graph'}]};
        else f.context.TaxonomyState.currentArchView.elements[0].description='Changed source evidence';
        await controller.refresh();call.resolve(response(draft));await flush();
        assert.equal(call.options.signal.aborted,true);assert.equal(f.node('sourceLensSuggestions').textContent,'');
        assert.ok(!f.presented.at(-1)?.enabled);assert.equal(f.node('sourceLensEnabled').disabled,true);
    });
}

test('explicit local to AI reassessment starts one bounded request and preserves assessed provenance',async()=>{
    const f=await reviewedDraft();f.node('sourceLensBudget').value='2';f.node('sourceLensBudget').onchange();
    f.node('sourceLensUseAi').checked=true;f.node('sourceLensUseAi').onchange();
    f.node('sourceLensInspect').onclick();f.node('sourceLensInspect').onclick();await flush();
    assert.equal(f.calls.length,2);assert.equal(JSON.parse(f.calls[1].options.body).useAi,true);
    f.calls[1].resolve({...response(draft),outcome:'ASSESSED',method:'AI:fixture',modelCalls:1});await flush();
    assert.match(f.node('sourceLensReview').textContent,/AI:fixture/);
    f.node('sourceLensInspect').onclick();await flush();assert.equal(f.calls.length,2,'The budget remains a hard limit');
});


test('initial analysis lens settles without a readiness cycle and resumes after its matching source is ready',async()=>{
    const f=fixture();f.setSession({ready:false});const controller=f.create();
    assert.equal(await controller.activate(f.workspace.getSource()),false,'Workspace publication must not wait for its own later source-ready event');
    assert.equal(f.saves(),0);assert.equal(f.node('sourceLensEnabled').disabled,true);
    f.context.document.dispatchEvent({type:'taxonomy:analysis-source-ready',detail:{workspaceId:'alice'}});await flush();
    assert.equal(f.saves(),0,'An event cannot bypass the actual session readiness guard');
    f.setSession({ready:true});
    f.context.document.dispatchEvent({type:'taxonomy:analysis-source-ready',detail:{workspaceId:'another-workspace'}});await flush();
    assert.equal(f.saves(),0,'Readiness from another workspace cannot activate this lens');
    f.context.document.dispatchEvent({type:'taxonomy:analysis-source-ready',detail:{workspaceId:'alice'}});await flush();
    assert.equal(f.saves(),1,'The deferred activation must acknowledge its exact durable draft');
    assert.equal(await controller.refresh(),true,'Refresh must expose the completed retry rather than the old unavailable result');
    assert.equal(f.node('sourceLensEnabled').disabled,false);assert.equal(f.node('sourceLensError').hidden,true);
    f.context.document.dispatchEvent({type:'taxonomy:analysis-source-ready',detail:{workspaceId:'alice'}});await flush();
    assert.equal(f.saves(),1,'Readiness notifications cannot activate an already acknowledged source twice');
    controller.select('arch-a');f.node('sourceLensEnabled').checked=true;f.node('sourceLensEnabled').onchange();
    f.node('sourceLensInspect').onclick();await flush();
    assert.deepEqual(JSON.parse(f.calls[0].options.body).source,draft);
    f.calls[0].resolve(response(draft));await flush();
});

for(const departure of ['deactivation','source change']) {
    test(`deferred analysis activation cannot publish after ${departure}`,async()=>{
        const f=fixture();f.setSession({ready:false});const controller=f.create();
        await controller.activate(f.workspace.getSource());
        let acknowledge;f.context.TaxonomyAnalysisSession.saveNow=()=>new Promise(resolve=>{acknowledge=resolve;});
        f.setSession({ready:true});
        f.context.document.dispatchEvent({type:'taxonomy:analysis-source-ready',detail:{workspaceId:'alice'}});await flush();
        assert.equal(typeof acknowledge,'function','The real lens must resume its deferred acknowledged-save path');
        if(departure==='deactivation')controller.suspend();
        else {f.setSource({...snapshot,kind:'snapshot'});await controller.activate(f.workspace.getSource());}
        acknowledge(true);await flush();
        assert.equal(f.calls.length,0,'Readiness never starts provider work');
        assert.equal(f.node('sourceLensEnabled').disabled,departure==='deactivation');
        if(departure==='deactivation')assert.equal(f.node('lensRoot').hidden,true);
        else assert.match(f.node('sourceLensSource').textContent,/saved-A/,'Late draft acknowledgement cannot replace current snapshot pins');
    });
}

test('a departed deferred source ignores a later readiness event before starting any save',async()=>{
    const f=fixture();f.setSession({ready:false});const controller=f.create();await controller.activate(f.workspace.getSource());
    controller.suspend();f.setSession({ready:true});
    f.context.document.dispatchEvent({type:'taxonomy:analysis-source-ready',detail:{workspaceId:'alice'}});await flush();
    assert.equal(f.saves(),0);assert.equal(f.node('sourceLensEnabled').disabled,true);assert.equal(f.node('lensRoot').hidden,true);
});
