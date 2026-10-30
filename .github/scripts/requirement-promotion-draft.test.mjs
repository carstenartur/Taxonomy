import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const source=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-projects.js',import.meta.url),'utf8');
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
