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
