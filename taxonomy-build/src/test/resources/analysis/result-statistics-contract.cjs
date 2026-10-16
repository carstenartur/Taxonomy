'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const handlers = new Map(), elements = new Map(), pending = [];
function element(tag) {
    return { tag, textContent: '', hidden: false, setAttribute() {},
        insertAdjacentElement(_, value) { elements.set(value.id, value); } };
}
elements.set('statusArea', element('div'));
const document = { documentElement: {lang:'de'}, getElementById: id => elements.get(id),
    createElement: element, addEventListener: (name, handler) => handlers.set(name, handler) };
const state = {taxonomyData: [], currentScores:null, currentArchView:null};
const window = {TaxonomyState:state};
vm.runInNewContext(fs.readFileSync(process.argv[2], 'utf8'), {window, document, queueMicrotask: callback => pending.push(callback)});
function flush() { while (pending.length) pending.shift()(); }
function render() { handlers.get('taxonomy:view-rendered')(); flush(); return elements.get('taxonomyResultStatistics').textContent; }
flush();
assert.equal(elements.get('taxonomyResultStatistics').hidden, true);
const leaf = {code:'BP-1', taxonomyRoot:'BP', level:1, children:[]};
const root = {code:'BP', taxonomyRoot:'BP', level:0, children:[leaf,leaf]};
leaf.children.push(root); // malformed cycle must not hang or double count
state.taxonomyData = [root];
state.currentScores = {'BP':1,'BP-1':0.125};
let text = render();
assert.match(text, /Geladene Knoten: 2/);
assert.match(text, /BP: 2/);
assert.match(text, /Navigationsknoten: 1/);
assert.match(text, /Positive Bewertungen: 2/);
assert.doesNotMatch(text, /Beziehungen: 0/); // no architecture != empty confirmed graph
const relation = {sourceCode:'BP-1', targetCode:'CP-1', relationType:'REALIZES', origin:'LLM_SUPPORTED'};
state.currentArchView = {includedElements:[{nodeCode:'BP-1'},{nodeCode:'CP-1'},{nodeCode:'BP-1'}],
    includedRelationships:[relation,{...relation}, {sourceCode:'CP-1',targetCode:'BP-1',relationType:'CONTEXT',origin:'TAXONOMY_SEED'}]};
text = render();
assert.match(text, /Architektur: 2 Knoten/);
assert.match(text, /Beziehungen: 2/);
assert.match(text, /KI-Vorschläge: 1/);
assert.match(text, /Fachliche Bestätigung: nicht aufgezeichnet/);
assert.doesNotMatch(text, /bestätigt: 2/);
// Duplicate endpoint/type with mixed provenance must not erase AI evidence.
state.currentArchView.includedRelationships.unshift({...relation,origin:'PROPAGATED_TRACE'});
assert.match(render(), /KI-Vorschläge: 1/);
// State is read after the current rendering transaction, not from an old event payload.
handlers.get('taxonomy:view-rendered')();
state.currentArchView = null; state.currentScores = null;
flush();
assert.doesNotMatch(elements.get('taxonomyResultStatistics').textContent, /KI-Vorschläge|Positive Bewertungen/);
document.documentElement.lang = 'en';
text = render();
assert.match(text, /Loaded nodes: 2/);
assert.doesNotMatch(text, /Geladene/);
state.taxonomyData = [{code:'<img src=x onerror=alert(1)>',taxonomyRoot:'<b>data</b>',children:[]}];
text = render();
assert.match(text, /<b>data<\/b>: 1/);
assert.equal(elements.get('taxonomyResultStatistics').innerHTML, undefined); // textContent only
state.taxonomyData=[]; render();
assert.equal(elements.get('taxonomyResultStatistics').hidden, true);
console.log('RESULT_STATISTICS_OK');
