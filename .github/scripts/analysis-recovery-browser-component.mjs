import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
const root = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const output = process.env.TAXONOMY_QA_OUTPUT || fileURLToPath(new URL('../../target/analysis-recovery-component/', import.meta.url));
await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{})});
const page=await browser.newPage({viewport:{width:1280,height:800}});
const errors=[],report=[];
page.on('pageerror',e=>errors.push(e.message));
async function inside(selector) {
 const box=await page.locator(selector).evaluate(e=>{const r=e.getBoundingClientRect(),v=visualViewport;return {x:r.x,y:r.y,right:r.right,bottom:r.bottom,width:r.width,height:r.height,
  view:{x:v.offsetLeft,y:v.offsetTop,width:v.width,height:v.height},active:document.activeElement?.id,scroll:scrollY};});
 assert(box.width>0&&box.height>0,`${selector} has no visible area`);
 assert(box.x>=box.view.x-1&&box.y>=box.view.y-1&&box.right<=box.view.x+box.view.width+1&&box.bottom<=box.view.y+box.view.height+1,JSON.stringify(box));
 return box;
}
try {
 await page.setContent(`<html lang="de"><head><style>body{margin:0;font:16px system-ui}.d-none{display:none}button,input,textarea{font:inherit}.btn{padding:.3em;border:1px solid}h3{font-size:1em}#taxonomyTree{height:4200px}</style></head><body>
 <label>Anforderung<textarea id="businessText">Hospital communications</textarea></label><input id="includeArchitectureView" type="checkbox" checked>
 <select id="providerSelect"><option>CUSTOM_OPENAI</option></select><button id="copilotBtn">Copilot</button><span id="copilotSpinner" class="d-none"></span><button id="analyzeBtn">Analyse</button>
 <div id="statusArea"></div><section><div id="taxonomyTree"><div class="tax-node" data-code="BP"><div class="tax-node-header">Business processes<button data-code="BP">Action</button></div><div class="tax-node" data-code="BP-1327"><div class="tax-node-header">Child<button data-code="BP-1327">Action</button></div></div></div></div></section><button id="lastControl">Letztes Bedienelement</button></body></html>`);
 await page.addStyleTag({content:await readFile(new URL('static/css/taxonomy-analysis-recovery.css',root),'utf8')});
 await page.evaluate(()=>{
  window.TaxonomyI18n={t:(key,...args)=>key+' '+args.join(' ')};
  window.TaxonomyUtils={escapeHtml:s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))};
  const storage=new Map();Object.defineProperty(window,'sessionStorage',{value:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}});
  if(!crypto.randomUUID)crypto.randomUUID=()=> 'cb2a3d71-e849-4a50-9855-1f9cb8f81402';
  const S=window.TaxonomyState={taxonomyData:[],currentScores:null,currentRawScores:{},currentView:'list'};
  const runtime={workspaceId:'ws-a',analysisGeneration:0};window.__TaxonomyAnalysisSessionContext={S,runtime,language:()=>document.documentElement.lang};
  window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:runtime.workspaceId}),saveNow:async()=>true};
  window.TaxonomyBrowse={showStatus:(kind,msg)=>{document.getElementById('statusArea').textContent=msg;},clearStatus(){},ensureNodeRendered(){},
   renderView(){document.dispatchEvent(new CustomEvent('taxonomy:view-rendered'));}};
  window.TaxonomyAnalysisProgress={start:()=>({acceptsResult:()=>true,finish(){},transportFailed(){}})};
  window.__requests=[];
  window.TaxonomyAnalysis={runCopilotFlow:async()=>{window.__followups=(window.__followups||0)+1;},renderPartialCopilot:()=>{window.__partial=true;}};
  window.TaxonomyAnalysisSessionApi={request:async(url,options)=>{
   const result=window.__snapshot;if(options.method==='POST'){result.recovery={...result.recovery,state:'CANCELLED'};result.result={...result.result,recovery:result.recovery,status:'CANCELLED'};}
   return {ok:true,json:async()=>result};}};
  window.fetch=async(url,opts)=>{window.__requests.push(JSON.parse(opts.body));return new Promise(resolve=>{window.__resolve=resolve;});};
  window.__finish=function(state){
   const q={key:'a'.repeat(64),nodes:['BP-1327','BP-1481'],error:'Ungültiges JSON – Antwort unvollständig',attempts:1,skipped:state==='COMPLETED_WITH_GAPS'};
   const request=window.__requests.at(-1);
   const recovery={id:request.continuationId,version:7,state,completedCalls:2,openQuestions:state==='COMPLETED'?[]:[q]};
   const result={status:state==='COMPLETED'?'SUCCESS':'PARTIAL',recovery,rawScores:{BP:100,'BP-1000':100},scores:{BP:100,'BP-1000':100},reasons:{},warnings:[],
    tree:[],analysisCoverage:{failedOrBlockedNodes:state==='COMPLETED'?0:4,nodes:{BP:{state:'RELEVANT',descendants:'PARTIAL'},'BP-1327':{state:'UNKNOWN',descendants:'UNASSESSED',reason:'LEFT_OPEN:q'}}}};
   window.__snapshot={request,recovery,result};window.__resolve(new Response(JSON.stringify(result),{headers:{'content-type':'application/json'}}));
  };
 });
 for(const name of ['taxonomy-scoring.js','taxonomy-recovery-viewport.js','taxonomy-analysis-recovery.js','taxonomy-operation-coordinator.js'])
  await page.addScriptTag({content:await readFile(new URL('static/js/core/'+name,root),'utf8')});
 await page.locator('#copilotBtn').click();
 await page.waitForFunction(()=>window.__requests.length===1);
 await page.waitForTimeout(40);
 report.push({name:'running-initial',...await inside('#analysisRecoveryBar')});
 await page.evaluate(()=>{const input=document.createElement('input');input.id='focusedQaControl';input.style='position:fixed;bottom:12px;left:8px;width:220px;height:44px';document.body.append(input);input.focus({preventScroll:true});});
 await page.waitForTimeout(40);
 const barBox=await inside('#analysisRecoveryBar');const focusBox=await page.locator('#focusedQaControl').boundingBox();
 assert(barBox.bottom<=focusBox.y || barBox.y>=focusBox.y+focusBox.height,'status bar covers focused control');
 report.push({name:'focused-control-not-covered',...barBox});await page.locator('#focusedQaControl').evaluate(e=>e.remove());
 await page.evaluate(()=>scrollTo(0,3000));await page.waitForTimeout(40);
 const scroll=await page.evaluate(()=>scrollY);
 report.push({name:'running-after-scroll',...await inside('#analysisRecoveryBar')});
 await page.evaluate(()=>document.dispatchEvent(new CustomEvent('taxonomy:analysis-progress',{detail:{node:'BP-1000'}})));
 await page.waitForTimeout(40);assert.equal(await page.evaluate(()=>scrollY),scroll,'heartbeat moved the page');
 await page.evaluate(()=>window.__finish('PAUSED'));
 await page.locator('#analysisRecoveryDialog[open]').waitFor();
 report.push({name:'paused-desktop',...await inside('#analysisRecoveryDialog')});
 assert.equal(await page.evaluate(()=>window.TaxonomyState.currentRawScores.BP),100);
 await page.screenshot({path:output+'/paused-desktop.png'});
 assert.equal(await page.locator('.tax-node > .tax-node-header > .analysis-coverage-node').count(),2);
 assert.equal(await page.locator('button .analysis-coverage-node').count(),0);
 await page.evaluate(()=>{window.__badge=document.getElementById('analysisCoverage-BP-1327');window.TaxonomyAnalysisRecovery.renderCoverage();});
 assert(await page.evaluate(()=>window.__badge===document.getElementById('analysisCoverage-BP-1327')),'heartbeat recreated the same badge');
 report.push({name:'coverage-badges-stable-and-outside-actions'});
 for(const viewport of [{width:320,height:480},{width:640,height:240},{width:768,height:220}]) {
  await page.setViewportSize(viewport);await page.waitForTimeout(60);
  report.push({name:'paused-'+viewport.width+'x'+viewport.height,...await inside('#analysisRecoveryDialog')});
  if(viewport.height>300) assert(await page.locator('#analysisRecoverySummary').evaluate(e=>e.scrollHeight<=e.clientHeight+1),'paused summary was clipped');
  for(const id of ['#analysisRecoveryRetry','#analysisRecoverySkip','#analysisRecoveryCancelDialog']) await inside(id);
  await page.screenshot({path:output+'/paused-'+viewport.width+'x'+viewport.height+'.png'});
 }
 await page.setViewportSize({width:1280,height:800});
 const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setPageScaleFactor',{pageScaleFactor:4});
 await page.waitForTimeout(70);report.push({name:'paused-pinch-400',...await inside('#analysisRecoveryDialog')});
 for(const id of ['#analysisRecoveryRetry','#analysisRecoverySkip','#analysisRecoveryCancelDialog']) await inside(id);
 await page.screenshot({path:output+'/paused-400-percent.png'});
 await cdp.send('Emulation.setPageScaleFactor',{pageScaleFactor:1});await page.waitForTimeout(50);
 await page.locator('#analysisRecoveryClose').click();await page.waitForTimeout(40);
 report.push({name:'closed-dialog-still-paused',...await inside('#analysisRecoveryBar')});
 assert.equal(await page.evaluate(()=>window.TaxonomyState.analysisRecovery.state),'PAUSED');
 await page.locator('#analysisRecoveryDetails').click();await page.locator('#analysisRecoveryRetry').click();
 await page.waitForFunction(()=>window.__requests.length===2);
 assert.equal(await page.evaluate(()=>window.__requests[1].continuationId===window.__requests[0].continuationId),true);
 assert.equal(await page.evaluate(()=>window.__requests[1].continuationAction),'RETRY');
 await page.evaluate(()=>window.__finish('PAUSED'));await page.locator('#analysisRecoveryDialog[open]').waitFor();
 await page.locator('#analysisRecoverySkip').click();await page.waitForFunction(()=>window.__requests.length===3);
 await page.evaluate(()=>window.__finish('COMPLETED_WITH_GAPS'));
 await page.waitForFunction(()=>window.__partial===true);
 assert.equal(await page.evaluate(()=>window.__followups||0),0);
 report.push({name:'left-open-partial',...await inside('#analysisRecoveryBar')});
 assert.deepEqual(errors,[],'uncaught browser errors');
 console.log('Recovery DOM/scoring/component scenarios passed:',report.length);
} catch(error){ console.error(error);process.exitCode=1;report.push({failure:error.stack}); }
finally{
 await writeFile(output+'/result.json',JSON.stringify({report,errors,browser:browser.version(),transport:'offline component fixtures; production scoring, recovery, coordinator and viewport scripts'},null,2));
 await browser.close();
}
