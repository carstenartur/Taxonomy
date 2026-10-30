import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const source=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-projects.js',import.meta.url),'utf8');
const detailSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js',import.meta.url),'utf8');
const portfolioSource=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/taxonomy-portfolio.js',import.meta.url),'utf8');
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
  const form={querySelector:()=>textField,reset(){resetCount++;}};
  const window={location:{search:''},setTimeout(){},
    TaxonomyRequirementIntake:{prepare(form,payload,projectId){
      return new Promise(resolve=>reviews.push({payload,projectId,resolve}));
    },isCurrent:()=>textField.value===reviews.at(-1).payload.text},
    TaxonomyArchitectureLifecycle:{saveRequirement(command){saves.push(command);return new Promise(resolve=>{finishSave=resolve;});}}};
  const document={documentElement:{lang:'en'},addEventListener(){},getElementById:element,querySelectorAll:()=>[element('submit')]};
  class FormData {forEach(callback){callback('PUMP','requirementKey');callback('Pump control','title');callback(textField.value,'text');}}
  vm.runInNewContext(portfolioSource.replace(/\}\)\(\);\s*$/,'window.portfolioProbe={state,createRequirement};})();'),
    {window,document,URLSearchParams,FormData});
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
