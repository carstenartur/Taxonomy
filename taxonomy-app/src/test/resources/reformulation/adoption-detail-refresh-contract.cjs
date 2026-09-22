'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const text=fs.readFileSync(process.argv[2],'utf8');
const fn=text.slice(text.indexOf('    async function loadAll('),text.indexOf('    function renderAll()'));
const inputs=[{value:'unsaved text',checked:false},{value:'unsaved reason',checked:false}];
let pending=[],renders=0;
const state={busy:0,readGeneration:0};
const api=()=>({getProject:async()=>({id:1}),getRequirement:()=>new Promise(resolve=>pending.push(resolve)),
 listRequirementVersions:async()=>[],listRequirementSnapshots:async()=>[],getProjectPortfolio:async()=>({}),getAccount:async()=>({})});
const context=vm.createContext({state,projectId:1,requirementId:2,api,
 document:{querySelectorAll:()=>inputs},setBusy:flag=>{state.busy+=flag?1:-1;},showError:e=>{throw e;},
 renderAll:()=>{renders++;inputs.forEach(n=>n.value='rendered default');}});
vm.runInContext(fn,context);
(async()=>{
 const first=vm.runInContext('loadAll(true)',context);
 inputs[0].value='newer unsaved edit while loading';
 pending.shift()({currentVersion:{id:7,text:'adopted'}});await first;
 assert.equal(inputs[0].value,'newer unsaved edit while loading','Read-only adoption refresh erased unsaved form input');
 assert.equal(inputs[1].value,'unsaved reason');assert.equal(state.requirement.currentVersion.id,7);
 const old=vm.runInContext('loadAll(true)',context),oldResolve=pending.shift();
 const next=vm.runInContext('loadAll(true)',context),nextResolve=pending.shift();
 nextResolve({currentVersion:{id:9}});await next;oldResolve({currentVersion:{id:8}});await old;
 assert.equal(state.requirement.currentVersion.id,9,'Older refresh overwrote newer version');assert.equal(renders,2);
 assert.equal(state.busy,0);assert.match(text,/refreshAfterAdoption:\s*\(\)\s*=>\s*loadAll\(true\)/,'Read-only refresh must be exposed to adoption');
 console.log('REFORMULATION_ADOPTION_DETAIL_REFRESH_OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
