import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
const script = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-editor.js', import.meta.url), 'utf8');
function harness() {
  const calls = [], lifecycle = new EventTarget(), replacements = [];
  const doc = text => ({ length: text.length, toString: () => text });
  const view = { state: { doc: doc('element BP {\n taxonomy: BP;\n}\n') }, dispatch(transaction) {
    this.state.doc = doc(transaction.changes.insert);
  }};
  const fetch = (url, init, options) => new Promise(resolve => calls.push({url, init, options, resolve}));
  const window = { dslCmView: view, TaxonomyApiClient: {request: fetch},
    __TaxonomyAnalysisSessionContext: {runtime: {workspaceId:'qa-original', analysisGeneration:1}},
    addEventListener: lifecycle.addEventListener.bind(lifecycle),
    dslReadingTools: { replaceDocument(text, options) { replacements.push(options); view.dispatch({changes:{insert:text}}); } } };
  vm.runInNewContext(script.replace('}());', 'window.__qaLoadCurrent = loadCurrent; window.__qaLoadCommit = loadCommitById;}());'), { window, document: {addEventListener(){}}, TaxonomyI18n:{t:x=>x},
    TaxonomyUtils:{escapeHtml:x=>String(x)}, fetch, AbortController, setTimeout, clearTimeout, console });
  return { window, view, calls, replacements, doc, lifecycle, format: () => window.dslFormatContent() };
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
const response=(body,status=200,type='text/plain')=>new Response(body,{status,headers:{'Content-Type':type}});
test('a late formatting response must never overwrite input typed while waiting', async () => {
  const h=harness(); h.format(); h.view.state.doc=h.doc('new human text');
  h.calls[0].resolve(response('old formatted text')); await settle();
  assert.equal(h.view.state.doc.toString(),'new human text');
});
test('a newer formatting result wins even if an obsolete request ignores abort',async()=>{
  const h=harness(); h.format(); h.format();
  h.calls[1].resolve(response('new formatted text')); await settle();
  h.calls[0].resolve(response('old formatted text')); await settle();
  assert.equal(h.view.state.doc.toString(),'new formatted text');
});
for(const [name,change] of [
  ['workspace',h=>h.window.__TaxonomyAnalysisSessionContext.runtime.workspaceId='qa-other'],
  ['analysis generation',h=>h.window.__TaxonomyAnalysisSessionContext.runtime.analysisGeneration++],
  ['editor instance',h=>h.window.dslCmView={state:{doc:h.doc('different editor')},dispatch(){throw new Error('must not edit replacement');}}],
  ['page navigation',h=>h.lifecycle.dispatchEvent(new Event('pagehide'))]
])test(`a response from another ${name} never changes the draft`,async()=>{
  const h=harness();const original=h.view.state.doc; h.format();change(h);
  h.calls[0].resolve(response('foreign content'));await settle();assert.strictEqual(h.view.state.doc,original);
});
for(const [status,type] of [[503,'text/plain'],[401,'text/plain'],[200,'text/html'],[200,'application/json']])
 test(`HTTP ${status} ${type} must not replace DSL with an error/login body`,async()=>{
  const h=harness();const original=h.view.state.doc;h.format();
  h.calls[0].resolve(response('error or login body',status,type));await settle();assert.strictEqual(h.view.state.doc,original);
 });
test('valid formatting keeps reading-state restoration in the real replacement path',async()=>{
 const h=harness();h.format();h.calls[0].resolve(response('element BP {\n  taxonomy: BP;\n}\n'));await settle();
 assert.equal(h.replacements.length,1);assert.equal(h.replacements[0].preserve,true);
});
test('an absent editor does not issue a request against an invented empty document',()=>{
 const h=harness();h.window.dslCmView=null;h.format();assert.equal(h.calls.length,0);
});
test('an empty success must not erase a nonempty draft',async()=>{
 const h=harness();const original=h.view.state.doc;h.format();h.calls[0].resolve(response(''));await settle();assert.strictEqual(h.view.state.doc,original);
});

for (const kind of ['current architecture', 'history version']) {
 test(`loading ${kind} retains edits made while the read was pending`,async()=>{
  const h=harness();
  if(kind==='history version')h.window.__qaLoadCommit('abcdef0123');else h.window.__qaLoadCurrent();
  h.view.state.doc=h.doc('new human draft');
  h.calls[0].resolve(kind==='history version'?response(JSON.stringify({dslText:'old stored version'}),200,'application/json'):response('old architecture'));
  await settle();assert.equal(h.view.state.doc.toString(),'new human draft');
 });
}
test('loading an empty stored version is valid and resets only the reading presentation',async()=>{
 const h=harness();h.window.__qaLoadCommit('abcdef0123');
 h.calls[0].resolve(response(JSON.stringify({dslText:''}),200,'application/json'));await settle();
 assert.equal(h.view.state.doc.toString(),'');assert.equal(h.replacements[0].preserve,false);
});

test('the existing editor still accepts text without an optional reading-tools module', async()=>{
 const h=harness();delete h.window.dslReadingTools;
 h.format();h.calls[0].resolve(response('element BP {\n  taxonomy: BP;\n}\n'));await settle();
 assert.equal(h.view.state.doc.toString(),'element BP {\n  taxonomy: BP;\n}\n');
});

function deferred() {
 let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve };
}
function startupHarness({ loaderPending = false, editorPending = false } = {}) {
 const workspace = deferred(), context = deferred(), dom = new EventTarget(), lifecycle = new EventTarget();
 const editorContainer = new EventTarget(), loader = new EventTarget(), calls = [];
 const doc = text => ({ length: text.length, toString: () => text });
 const view = { state: { doc: doc('') }, dispatch(change) { this.state.doc = doc(change.changes.insert); } };
 const status = { classList: { add() {}, remove() {} }, textContent: '' };
 const branch = new EventTarget(); branch.value = '';
 branch.appendChild = option => { if (!branch.value) branch.value = option.value; };
 branch.insertBefore = option => branch.appendChild(option);
 const loadButton = new EventTarget();
 let currentContext = null, contextReads = 0;
 const request = (url, init, options) => {
  if (url === '/api/dsl/branches' || url.startsWith('/api/dsl/history')) return Promise.resolve(response('[]',200,'application/json'));
  return new Promise(resolve => calls.push({url, init, options, resolve}));
 };
 const window = { dslCmView: editorPending ? null : view, TaxonomyApiClient: { request },
  __TaxonomyAnalysisSessionContext: { runtime: { workspaceId: null, analysisGeneration: 0 } },
  addEventListener: lifecycle.addEventListener.bind(lifecycle),
  TaxonomyContextBar: { getCurrentContext: () => currentContext,
   fetchAndRender: () => { contextReads++; return context.promise.then(value => { currentContext = value; return value; }); } }
 };
 if (!loaderPending) window.TaxonomyAnalysisSessionReady = workspace.promise;
 const document = { addEventListener: dom.addEventListener.bind(dom),
  querySelector: selector => selector === 'script[data-taxonomy-analysis-session]' ? loader : null,
  getElementById: id => ({dslEditorContainer:editorContainer,dslBranchSelect:branch,dslStatusArea:status,dslLoadCurrentBtn:loadButton}[id] || null),
  createElement: () => ({}) };
 vm.runInNewContext(script, {window,document,TaxonomyI18n:{t:key=>key},TaxonomyUtils:{escapeHtml:String},fetch:request,
  AbortController,setTimeout:()=>1,clearTimeout(){},console});
 dom.dispatchEvent(new Event('DOMContentLoaded'));
 return {window,view,doc,calls,status,lifecycle,loadButton,editorContainer,loader,
  get contextReads(){return contextReads;},
  resolveWorkspace(ok=true){window.__TaxonomyAnalysisSessionContext.runtime.workspaceId='resolved-workspace';workspace.resolve(ok);},
  resolveContext(value={branch:'draft',commitId:'initial-commit'}){context.resolve(value);},
  loadModules(){window.TaxonomyAnalysisSessionReady=workspace.promise;loader.dispatchEvent(new Event('load'));},
  readyEditor(){window.dslCmView=view;editorContainer.dispatchEvent(new Event('cm-ready'));}
 };
}
test('initial DSL read waits for resolved workspace, context and branch selection', async()=>{
 const h=startupHarness();await settle();assert.equal(h.calls.length,0,'No unscoped initial export');
 h.resolveWorkspace();await settle();assert.equal(h.contextReads,1);assert.equal(h.calls.length,0,'Wait for context initialization');
 h.resolveContext();await settle();assert.equal(h.calls.length,1);
 h.calls[0].resolve(response('initial architecture'));await settle();assert.equal(h.view.state.doc.toString(),'initial architecture');
});
for(const stage of ['workspace','context'])test(`initialization preserves text typed while ${stage} is pending`,async()=>{
 const h=startupHarness();if(stage==='context'){h.resolveWorkspace();await settle();}
 h.view.state.doc=h.doc('human draft before initialization');h.resolveWorkspace();h.resolveContext();await settle();
 assert.equal(h.calls.length,0,'Abandoned initialization must not issue a replacement read');
 assert.equal(h.view.state.doc.toString(),'human draft before initialization');
});
test('manual load supersedes a pending automatic initial load',async()=>{
 const h=startupHarness();h.loadButton.dispatchEvent(new Event('click'));assert.equal(h.calls.length,1);
 h.resolveWorkspace();h.resolveContext();await settle();assert.equal(h.calls.length,1,'No automatic follow-up may overtake manual intent');
});
test('pagehide cancels pending initial loading even after pageshow',async()=>{
 const h=startupHarness();h.lifecycle.dispatchEvent(new Event('pagehide'));h.lifecycle.dispatchEvent(new Event('pageshow'));
 h.resolveWorkspace();h.resolveContext();await settle();assert.equal(h.calls.length,0);
});
test('workspace initialization failure never exports from an implicit fallback workspace',async()=>{
 const h=startupHarness();h.resolveWorkspace(false);h.resolveContext();await settle();
 assert.equal(h.calls.length,0);assert.equal(h.status.textContent,'dsl.load.failed');
});
test('initial loading waits even when the ordered session loader itself is still downloading',async()=>{
 const h=startupHarness({loaderPending:true});await settle();assert.equal(h.calls.length,0);
 h.loadModules();await settle();assert.equal(h.calls.length,0);
 h.resolveWorkspace();h.resolveContext();await settle();assert.equal(h.calls.length,1);
});
test('session loader failure leaves an explicit error and no implicit export',async()=>{
 const h=startupHarness({loaderPending:true});h.loader.dispatchEvent(new Event('error'));await settle();
 assert.equal(h.calls.length,0);assert.equal(h.status.textContent,'dsl.load.failed');
});
test('CodeMirror and asynchronous session readiness can complete in either order',async()=>{
 for(const editorFirst of [true,false]){
  const h=startupHarness({editorPending:true});if(editorFirst)h.readyEditor();
  h.resolveWorkspace();h.resolveContext();await settle();
  if(!editorFirst){assert.equal(h.calls.length,0);h.readyEditor();await settle();}
  assert.equal(h.calls.length,1);h.calls[0].resolve(response('ready architecture'));await settle();
  assert.equal(h.view.state.doc.toString(),'ready architecture');
 }
});


const contextSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/versioning/taxonomy-context-bar.js', import.meta.url), 'utf8');
function contextHarness() {
 const lifecycle = new EventTarget(), calls = [];
 const scope = { window: {addEventListener:lifecycle.addEventListener.bind(lifecycle)},
  document:{getElementById:()=>null}, TaxonomyI18n:{t:key=>key}, TaxonomyUtils:{escapeHtml:String},
  fetch:(url,options)=>new Promise(resolve=>calls.push({url,options,resolve})), AbortController, clearInterval, setInterval };
 vm.runInNewContext(contextSource, scope);
 return {api:scope.window.TaxonomyContextBar,calls,lifecycle};
}
test('context readiness returns the accepted context and ignores a superseded startup response',async()=>{
 const h=contextHarness(), first=h.api.fetchAndRender('contextBar'), second=h.api.fetchAndRender('contextBar');
 assert.equal(typeof second?.then,'function','Context initialization must be awaitable');
 h.calls[1].resolve(response(JSON.stringify({branch:'draft',commitId:'new'}),200,'application/json'));
 assert.equal((await second).commitId,'new');
 h.calls[0].resolve(response(JSON.stringify({branch:'draft',commitId:'old'}),200,'application/json'));
 assert.equal(await first,null);assert.equal(h.api.getCurrentContext().commitId,'new');
});
test('context readiness fails closed on HTTP failure and navigation',async()=>{
 const h=contextHarness(), failed=h.api.fetchAndRender('contextBar');
 assert.equal(typeof failed?.then,'function');h.calls[0].resolve(response('error',503));assert.equal(await failed,null);
 const cancelled=h.api.fetchAndRender('contextBar');h.lifecycle.dispatchEvent(new Event('pagehide'));
 h.calls[1].resolve(response(JSON.stringify({branch:'draft',commitId:'late'}),200,'application/json'));
 assert.equal(await cancelled,null);assert.equal(h.api.getCurrentContext(),null);
});

for (const change of ['workspaceId','analysisGeneration']) test(`startup does not export after ${change} changes during context resolution`,async()=>{
 const h=startupHarness();h.resolveWorkspace();await settle();
 h.window.__TaxonomyAnalysisSessionContext.runtime[change]='changed';h.resolveContext();await settle();
 assert.equal(h.calls.length,0);
});
test('failed context resolution reports an error without an initial export',async()=>{
 const h=startupHarness();h.resolveWorkspace();h.resolveContext(null);await settle();
 assert.equal(h.calls.length,0);assert.equal(h.status.textContent,'dsl.load.failed');
});
