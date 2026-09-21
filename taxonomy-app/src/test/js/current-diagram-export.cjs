const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = readFileSync(path.resolve(__dirname, '../../main/resources/static/js/shared/taxonomy-export.js'), 'utf8');
function fixture(view = {viewTitle: 'Existing result', includedElements: [{nodeCode: 'A'}], includedRelationships: []}) {
    const elements = new Map();
    function element(tag) {
        const e = {tagName: tag, children: [], attributes: {}, disabled: false,
            classList: {add() {}, remove() {}}, textContent: '',
            appendChild(child) { this.children.push(child); if (child.id) elements.set(child.id, child); return child; },
            setAttribute(k,v) { this.attributes[k]=String(v); }, removeAttribute(k) { delete this.attributes[k]; },
            getAttribute(k) { return this.attributes[k] || null; },
            remove() {}, click() {}, querySelector() {return null;}, querySelectorAll() {return [];} };
        return e;
    }
    for (const id of ['exportGroup','exportVisio','exportArchiMate','exportMermaid','exportStructurizr']) {
        const el=element('button');el.id=id;elements.set(id,el);
    }
    const requests=[]; let resolve; let reject;
    const pending=new Promise((yes,no)=>{resolve=yes;reject=no;});
    const document={documentElement:{lang:'en'},body:element('body'),
        getElementById:id=>elements.get(id)||null,createElement:element,
        querySelector:()=>null,addEventListener(){}};
    const state={currentArchView:view,lastAnalyzedText:'original',currentScores:{A:90}};
    const window={TaxonomyState:state,setTimeout() {}};
    const context={window,document,TaxonomyI18n:{t:key=>key},Blob,URL:{createObjectURL:()=> 'blob:test',revokeObjectURL(){}},
        fetch:(url,options)=>{requests.push({url,options});return pending;},alert(){},Element:class {}};
    vm.runInNewContext(source,context);
    return {elements,requests,state,resolve,reject,run:()=>window.TaxonomyExport.exportVisio('original')};
}
test('exports a frozen copy of the current architecture with visible busy state',async()=>{
    const f=fixture();const original=JSON.stringify(f.state);const pending=f.run();await Promise.resolve();
    assert.equal(f.requests[0].url,'/api/diagram/current/visio');
    assert.deepEqual(JSON.parse(f.requests[0].options.body),f.state.currentArchView);
    assert.equal(f.elements.get('exportVisio').disabled,true);
    assert.equal(f.elements.get('diagramExportStatus').attributes['aria-busy'],'true');
    assert.match(f.elements.get('diagramExportStatusText').textContent,/Creating/);
    f.resolve({ok:true,blob:async()=>new Blob(['vsdx'])});await pending;
    assert.equal(f.elements.get('exportVisio').disabled,false);
    assert.equal(f.elements.get('diagramExportStatus').attributes['aria-busy'],'false');
    assert.match(f.elements.get('diagramExportStatusText').textContent,/ready/i);
    assert.equal(JSON.stringify(f.state),original);
});
test('missing architecture never triggers a new analysis',async()=>{
    const f=fixture(null);f.resolve({ok:true,blob:async()=>new Blob(['unexpected'])});await f.run();assert.equal(f.requests.length,0);
    assert.match(f.elements.get('diagramExportStatusText').textContent,/architecture/i);
});
test('changed requirement never exports a mismatched architecture',async()=>{
    const f=fixture();f.state.lastAnalyzedText='previous';f.resolve({ok:true,blob:async()=>new Blob(['unexpected'])});await f.run();assert.equal(f.requests.length,0);
    assert.match(f.elements.get('diagramExportStatusText').textContent,/changed/i);
});
test('failed export keeps the architecture and restores previously disabled controls',async()=>{
    const f=fixture();f.elements.get('exportArchiMate').disabled=true;
    const view=f.state.currentArchView;const pending=f.run();f.reject(new Error('network down'));await pending;
    assert.equal(f.state.currentArchView,view);assert.equal(f.elements.get('exportVisio').disabled,false);
    assert.equal(f.elements.get('exportArchiMate').disabled,true);
    assert.match(f.elements.get('diagramExportStatusText').textContent,/network down/);
});
test('duplicate clicks do not create duplicate export requests',async()=>{
    const f=fixture();const pending=f.run();f.run();await Promise.resolve();assert.equal(f.requests.length,1);
    f.resolve({ok:true,blob:async()=>new Blob(['vsdx'])});await pending;
});
