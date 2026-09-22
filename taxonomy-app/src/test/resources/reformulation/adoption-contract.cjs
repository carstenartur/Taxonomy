'use strict';
const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
class Element {
 constructor(tag){this.tagName=tag;this.children=[];this.dataset={};this.listeners={};this.value='';this.checked=false;this.disabled=false;this.open=false;this.textContent='';this.attrs={};}
 append(...nodes){this.children.push(...nodes);nodes.forEach(n=>{if(typeof n==='object')n.parent=this;});}
 replaceChildren(...nodes){this.children=[];this.append(...nodes);}
 setAttribute(k,v){this.attrs[k]=v;} addEventListener(k,f){(this.listeners[k]??=[]).push(f);}
 focus(){} showModal(){this.open=true;} close(){this.open=false;this.fire('close');}
 remove(){if(this.parent)this.parent.children=this.parent.children.filter(x=>x!==this);}
 fire(type){for(const f of this.listeners[type]||[])f({preventDefault(){}});}
 click(){if(!this.disabled)this.fire('click');}
}
const body=new Element('body'), requests=[];
const document={body,createElement:tag=>new Element(tag),querySelector:s=>s.includes('_csrf_header')?{content:'X-CSRF-TOKEN'}:{content:'test-token'}};
const context=vm.createContext({window:{location:{pathname:'/'}},document,URLSearchParams,crypto:{randomUUID:()=> 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'},console});
let previewResolve,confirmResolve,confirmFailure=false;
const preview={hash:'b'.repeat(64),content:{id:'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',proposalId:'p',sourceVersionId:1,
 originalText:'Original <img src=x onerror=alert(1)>',currentRequirement:{currentVersionId:1,currentVersion:{text:'Current text'}},
 revision:{number:2,questions:[{id:'q',wording:'Browser oder Terminal?',state:'OPEN',answerSchema:{options:['Browser','Terminal']},discoveries:[{rationale:'Open in original'}]}]},
 finalText:'Proposed text',warnings:['UNRESOLVED_QUESTIONS'],blockingReasons:[],unresolvedQuestionIds:['q']}};
context.fetch=async(url,init)=>{requests.push({url,init});
 if(url.endsWith('/adoption-previews'))return new Promise(r=>{previewResolve=()=>r({ok:true,status:201,json:async()=>preview});});
 if(init.method==='POST')return new Promise(r=>{confirmResolve=()=>r(confirmFailure?{ok:false,status:503,json:async()=>({detail:'Receipt may already exist'})}:{ok:true,status:200,json:async()=>({targetVersionId:2,analysisNeedsRefresh:true})});});
 return {ok:true,status:200,json:async()=>[]};};
vm.runInContext(fs.readFileSync(process.argv[2],'utf8'),context);
if(fs.existsSync(process.argv[3]))vm.runInContext(fs.readFileSync(process.argv[3],'utf8'),context);
const api=context.window.TaxonomyReformulationAdoption;
assert.equal(typeof api?.open,'function','Separate adoption dialog entrypoint is missing');
const all=n=>[n,...n.children.filter(x=>typeof x==='object').flatMap(all)];
const action=key=>all(body).find(n=>n.dataset.adoptionAction===key);
const text=n=>n.textContent+' '+n.children.map(x=>typeof x==='string'?x:text(x)).join(' ');
const tick=async()=>{for(let i=0;i<10;i++)await Promise.resolve();};
(async()=>{
 let current=true,adopted=0,detailRefreshes=0;
 context.window.TaxonomyRequirementDetail={refreshAfterAdoption:async()=>{detailRefreshes++;}};
 const options={projectId:7,requirementId:11,proposalId:'p',revision:2,language:'de',isCurrent:()=>current,onAdopted:()=>{adopted++;}};
 api.open(options); await tick();assert.equal(requests.length,1);assert(!requests.some(r=>r.url.endsWith('/adoptions')&&r.init.method==='POST'));
 action('close').click();previewResolve();await tick();assert.equal(body.children.length,0,'Closed preview was reopened by late response');
 api.open(options);await tick();previewResolve();await tick();
 assert(text(body).includes(preview.content.originalText));assert(text(body).includes('Browser oder Terminal?'));
 assert.equal(action('confirm').disabled,true,'Must explicitly confirm first');
 assert(!all(body).some(n=>n.tagName==='img'),'Untrusted text became markup');
 action('confirmed').checked=true;action('confirmed').fire('change');assert(action('confirm').disabled,'Warnings need separate acknowledgment');
 action('warnings').checked=true;action('warnings').fire('change');action('rationale').value='Adopt as draft';action('rationale').fire('input');
 action('confirm').click();action('confirm').click();await tick();
 const writes=()=>requests.filter(r=>r.url.endsWith('/adoptions')&&r.init.method==='POST');assert.equal(writes().length,1,'Double-click sent two commands');
 assert.equal(writes()[0].init.headers['If-Match'],'"2"');assert.equal(writes()[0].init.headers['X-CSRF-TOKEN'],'test-token');
 const first=JSON.parse(writes()[0].init.body);assert.equal(first.previewHash,preview.hash);assert.equal(first.confirmed,true);assert.equal(first.acknowledgeWarnings,true);
 confirmFailure=true;confirmResolve();await tick();assert(!action('confirm').disabled);action('confirm').click();await tick();
 assert.equal(writes().length,2);assert.equal(writes()[0].init.body,writes()[1].init.body,'Uncertain retry changed command identity');
 confirmFailure=false;confirmResolve();await tick();assert.equal(adopted,1);assert.equal(detailRefreshes,1,'Active requirement detail was not refreshed');assert(text(body).includes('2'));action('close').click();
 api.open(options);await tick();current=false;previewResolve();await tick();assert(!action('confirm')||action('confirm').disabled,'Stale local draft became adoptable');
 assert.equal(writes().length,2,'Opening or invalidating sent adoption');
 action('close').click();current=true;
 api.open({...options,onAdopted:()=>{throw new Error('Post-adoption refresh failed');}});await tick();previewResolve();await tick();
 action('confirmed').checked=true;action('warnings').checked=true;action('rationale').value='Reviewed';action('rationale').fire('input');
 action('confirm').click();await tick();confirmResolve();await tick();
 assert.equal(action('confirm').disabled,true,'A failed refresh must not re-enable an already adopted command');
 assert(text(body).includes('Übernahme dokumentiert'),'A failed refresh hid successful adoption');
 console.log('REFORMULATION_ADOPTION_CONTROLS_OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
