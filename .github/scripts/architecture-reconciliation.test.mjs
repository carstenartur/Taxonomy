import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';

const root = '../../taxonomy-app/src/main/resources/static/js/';
const lifecycle = await readFile(new URL(root+'shared/architecture-lifecycle.js',import.meta.url),'utf8');
const api = await readFile(new URL(root+'api/architecture-lifecycle-api.js',import.meta.url),'utf8');
const comparison = await readFile(new URL(root+'shared/architecture-reconciliation.js',import.meta.url),'utf8');
const copy = value=>JSON.parse(JSON.stringify(value));
class Element {
    constructor(tag='div') { this.tagName=tag; this.children=[]; this.dataset={}; this.attributes={}; this.listeners=new Map(); this.value=''; this.hidden=false; this.disabled=false; this.style={}; this.text=''; }
    set textContent(value) { this.text=String(value); this.replaceChildren(); }
    get textContent() { return this.text+this.children.map(child=>child.textContent).join(''); }
    append(...children) { for(const child of children){child.parent=this;this.children.push(child);} }
    replaceChildren(...children) { for(const child of this.children)child.parent=null;this.children=[];this.append(...children); }
    setAttribute(key,value) { this.attributes[key]=String(value); }
    getAttribute(key) { return this.attributes[key] ?? null; }
    addEventListener(name,fn) { this.listeners.set(name,[...(this.listeners.get(name)||[]),fn]); }
    async dispatch(name,event={target:this,preventDefault(){},stopPropagation(){}}) { for(const listener of this.listeners.get(name)||[])await listener(event); if(this.parent && ['input','change'].includes(name))await this.parent.dispatch(name,event); }
    querySelectorAll(selector) { const matches = node=>selector.startsWith('[data-') ? Object.hasOwn(node.dataset,selector.slice(6,-1).replace(/-([a-z])/g,(_,letter)=>letter.toUpperCase())) : selector===node.tagName;
        return this.children.flatMap(child=>[...(matches(child)?[child]:[]),...child.querySelectorAll(selector)]); }
    querySelector(selector) { return this.querySelectorAll(selector)[0]||null; }
    focus() {} reportValidity() { return true; } scrollIntoView() {}
    showModal() { this.open=true; } close() { this.open=false;this.dispatch('close'); }
    remove() { if(this.parent)this.parent.children=this.parent.children.filter(child=>child!==this);this.parent=null; }
    get isConnected() { return Boolean(this.parent); }
}
async function fixture(unresolved=false) {
    const body=new Element('body'),anchor=new Element('section');anchor.id='architectureLifecycle';body.append(anchor);
    const ids=id=>{const visit=node=>node.id===id?node:node.children.map(visit).find(Boolean);return visit(body)||null;};
    const handlers=new Map();
    const document={body,createElement:tag=>new Element(tag),createElementNS:(_ns,tag)=>new Element(tag),getElementById:ids,
        addEventListener(name,fn){handlers.set(name,[...(handlers.get(name)||[]),fn]);},dispatchEvent(event){for(const fn of handlers.get(event.type)||[])fn(event);}};
    const original={repositoryId:'repo',workspaceScopeKey:'copy',branch:'draft',commit:'a'.repeat(40),revision:4,actor:'alice',writeMode:'PRIVATE_WORKSPACE'};
    let scope=copy(original),overview={context:copy(original),source:'WORKSPACE_REVISION',projectionState:'VERSION_CHANGED',mayEdit:false,projects:[],requirements:[],dependencies:[]};
    const scene={width:760,height:420,nodes:[{id:'one',label:'Original',type:'CoreService',x:52,y:52,width:238,height:82}],edges:[]};
    const value={context:copy(original),headCommit:'b'.repeat(40),fingerprint:'preview-one',canAccept:!unresolved,baseDsl:'base',localDsl:'local',incomingDsl:'incoming',mergedDsl:unresolved?null:'proposed',
        conflicts:unresolved?[{id:'element:one',reason:'Choose',base:'base',local:'local',incoming:'incoming'}]:[],changes:[],
        comparison:{typeRoots:{CoreService:'CR'},scenes:{base:copy(scene),local:{...copy(scene),nodes:[{...scene.nodes[0],label:'Local'}]},incoming:{...copy(scene),nodes:[]},...(unresolved?{}:{proposed:copy(scene)})},
            properties:[{blockId:'element:one',property:'title',category:'SEMANTIC',base:['Original'],local:['Local'],incoming:[],proposed:unresolved?null:['Local']}]}};
    const writes=[],renderers=[];
    let defer=false,resolve;
    const context=vm.createContext({document,URL,URLSearchParams,location:new URL('https://example.test/?repositoryId=repo&workspaceId=copy&branch=draft'),crypto:{randomUUID},
        CustomEvent:class{constructor(type,options){this.type=type;this.detail=options?.detail;}},addEventListener(){},history:{replaceState(){},pushState(){}},
        TaxonomyI18n:{ready:()=>Promise.resolve(),t:key=>key,resolveUrl:value=>value},TaxonomyAppChrome:{getContext:()=>({}),updateContext(){}},
        TaxonomyArchitectureWorkflow:{getScope:()=>copy(scope),setScope:next=>{scope=copy(next);}},
        TaxonomyApiClient:{getJson:async()=>copy(overview),request:async(url,options,policy)=>{
            writes.push({url,options:copy(options),policy:copy(policy),body:JSON.parse(options.body)});
            return {json:async()=>url.includes('/preview')?(defer?new Promise(done=>{resolve=()=>done(copy(value));}):copy(value)):{context:copy(original),state:'CHECKPOINTED'}};
        }},
        ArchitectureEditorRenderer:(svg,onSelect,onRelation)=>{const renderer={svg,onSelect,onRelation,destroyed:false,
            render(scene,selected,roots){this.scene=copy(scene);this.roots=copy(roots);},select(id){this.selected=id;},fit(){},destroy(){this.destroyed=true;}};renderers.push(renderer);return renderer;}
    });context.window=context;
    vm.runInContext(api,context);vm.runInContext(comparison,context);vm.runInContext(lifecycle,context);
    await context.TaxonomyArchitectureLifecycle.ready();
    await anchor.querySelector('[data-lifecycle-reconcile]').dispatch('click');
    ids('lifecycleReconciliationReason').value='Reviewed';
    return {ids,body,value,writes,renderers,context,
        preview:()=>ids('lifecycleReconciliationPreview').dispatch('click'),
        async changeScope(){scope={...scope,workspaceScopeKey:'other'};overview={...overview,context:copy(scope)};await context.TaxonomyArchitectureLifecycle.refresh(scope,null);},
        defer(){defer=true;},resolve:()=>resolve?.()};
}

test('a frozen preview passes each version and authoritative types to the existing renderer',async()=>{
    const f=await fixture();await f.preview();
    assert.equal(f.renderers.length,4);
    assert.deepEqual(f.renderers.map(renderer=>renderer.scene.nodes.map(node=>node.label)),[['Original'],['Local'],[],['Original']]);
    assert.deepEqual(f.renderers[0].roots,{CoreService:'CR'});
    const evidence=f.body.querySelector('[data-reconciliation-preview]');
    assert.equal(evidence.dataset.previewFingerprint,'preview-one');
    assert.match(evidence.textContent,/Original.*Local/);
    f.renderers[1].onSelect('one');
    assert.equal(f.body.querySelector('[data-reconciliation-properties]').querySelector('tr').getAttribute('aria-selected'),'true');
    await f.ids('lifecycleReconciliationAccept').dispatch('click');
    const accepted=f.writes.find(write=>write.url.includes('/accept'));
    assert.equal(accepted.body.previewFingerprint,'preview-one');
    assert.equal(accepted.body.headCommit,'b'.repeat(40));
    assert.equal(accepted.options.headers['If-Match'],'"workspace-revision-4"');
    assert.equal(accepted.policy.pinnedWorkspaceId,'copy');
    assert.ok(f.renderers.every(renderer=>renderer.destroyed));
});

test('editing a reviewed intent destroys its comparison and requires a newly fingerprinted preview',async()=>{
    const f=await fixture();await f.preview();
    await f.ids('lifecycleReconciliationReason').dispatch('input');
    assert.ok(f.renderers.length>0);assert.ok(f.renderers.every(renderer=>renderer.destroyed));
    assert.equal(f.body.querySelector('[data-reconciliation-preview]'),null);
    assert.equal(f.ids('lifecycleReconciliationAccept').hidden,true);
    f.value.fingerprint='preview-two';await f.preview();
    assert.equal(f.body.querySelector('[data-reconciliation-preview]').dataset.previewFingerprint,'preview-two');
});

test('a context change destroys displayed proof and a late preview cannot restore it',async()=>{
    for(const late of [false,true]){
        const f=await fixture();if(late)f.defer();const pending=f.preview();
        if(late)await new Promise(resolve=>setImmediate(resolve));else await pending;
        await f.changeScope();f.resolve();await pending;
        assert.equal(f.body.querySelector('[data-reconciliation-preview]'),null);
        assert.equal(f.ids('lifecycleReconciliationAccept').hidden,true);
        assert.equal(f.ids('lifecycleReconciliationPreview').disabled,true);
        assert.ok(f.renderers.every(renderer=>renderer.destroyed));
    }
});

test('unresolved conflicts preserve source diagrams and mark the proposed document unavailable',async()=>{
    const f=await fixture(true);await f.preview();
    assert.equal(f.renderers.length,3);
    assert.match(f.body.querySelector('[data-reconciliation-preview]').textContent,/lifecycle.reconcile.proposedUnavailable/);
    assert.equal(f.ids('lifecycleReconciliationAccept').hidden,true);
    await f.body.querySelector('[data-conflict-id]').dispatch('change');
    assert.equal(f.body.querySelector('[data-reconciliation-preview]'),null);
});

test('closing while a preview is loading discards late evidence without creating detached renderers',async()=>{
    const f=await fixture();f.defer();const pending=f.preview();
    await new Promise(resolve=>setImmediate(resolve));
    f.ids('lifecycleReconciliationDialog').close();
    f.resolve();await pending;
    assert.equal(f.renderers.length,0);
    assert.equal(f.writes.filter(write=>write.url.includes('/accept')).length,0);
});

test('a response for another semantic revision cannot enable acceptance or display its diagrams',async()=>{
    const f=await fixture();f.value.context.revision=5;await f.preview();
    assert.equal(f.renderers.length,0);
    assert.equal(f.ids('lifecycleReconciliationAccept').hidden,true);
    assert.match(f.ids('lifecycleReconciliationDialog').textContent,/lifecycle.stale/);
});
