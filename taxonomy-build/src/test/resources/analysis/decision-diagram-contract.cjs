'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2], 'utf8');
const document = makeDocument();
const state = {currentReasons: {'BP-1000': 'Saved explanation\n' + 'Detailed reason. '.repeat(100)}, currentScores: {'BP-1000': 75}};
const window = {TaxonomyState: state};
const context = {window, document, d3: {hierarchy}, TaxonomyI18n: {t: (key, ...args) => key + (args.length ? ': ' + args.join(' / ') : '')}};
vm.runInNewContext(source.replace('window.TaxonomyViews = {',
    'window.diagramTest = {layout: typeof layoutDecisionPanels === "function" ? layoutDecisionPanels : null, pages: typeof buildDecisionPrintPages === "function" ? buildDecisionPrintPages : null, print: typeof printDecisionDiagram === "function" ? printDecisionDiagram : null, evidence: typeof buildDiagramEvidencePages === "function" ? buildDiagramEvidencePages : null, reason: typeof showDecisionReason === "function" ? showDecisionReason : null}; window.TaxonomyViews = {'), context);
const api = window.diagramTest;
assert.equal(typeof api.layout, 'function', 'Decision groups need occupied-bounds layout');
assert.equal(typeof api.pages, 'function', 'Printing needs paginated complete diagram content');
assert.equal(typeof api.print, 'function', 'Decision diagrams need the complete print entry point');
assert.equal(typeof api.reason, 'function', 'Evaluated nodes need explicit full saved rationale');
function makeDocument() {
    const doc = {activeElement: null, getElementById(id) { return find(doc.body, e => e.id === id); },
        createElement: tag => element(tag), createElementNS: (_, tag) => element(tag)};
    function element(tag) {
        return {tagName: tag, attributes: {}, children: [], listeners: {}, style: {}, textContent: '', isConnected: true,
            setAttribute(k,v) {this.attributes[k] = String(v);}, getAttribute(k) {return this.attributes[k] ?? null;},
            appendChild(child) {if(child.parentNode)child.parentNode.children=child.parentNode.children.filter(c=>c!==child);this.children.push(child); child.parentNode=this; return child;}, append(...children) {children.forEach(c => this.appendChild(c));},
            replaceChildren(...children) {this.children=[];this.append(...children);},
            addEventListener(k,v) {this.listeners[k]=v;}, focus() {doc.activeElement=this;},
            showModal() {this.open=true;}, close() {this.open=false; if(this.listeners.close)this.listeners.close();},
            remove() {this.isConnected=false; if(this.parentNode)this.parentNode.children=this.parentNode.children.filter(c=>c!==this);}};
    }
    doc.body=element('body'); doc.head=element('head'); doc.documentElement={lang:'en'}; return doc;
}
function find(node, predicate) {if(predicate(node))return node; for(const child of node.children || []){const result=find(child,predicate);if(result)return result;}}
function all(node,predicate){return [node,...(node.children || []).flatMap(c=>all(c,predicate))].filter(predicate);}
function text(node){return node.textContent + (node.children || []).map(text).join(' ');}
function hierarchy(data,parent=null,depth=0) {
    const node={data,parent,depth}; if(data.children?.length)node.children=data.children.map(c=>hierarchy(c,node,depth+1));
    node.descendants=()=>[node,...(node.children||[]).flatMap(c=>c.descendants())]; return node;
}
// Explicit authored presentation fixtures; these are not live catalogue or model findings.
const data={code:'__root__',children:[{code:'BP-1000',name:'Long multiline label '.repeat(18),children:[{code:'BP-2000',name:'child',children:Array.from({length:20},(_,i)=>({code:'fixture-child-'+i,name:'Detailed child name '.repeat(8)}))}]},{code:'BR-1000',name:'Another area '.repeat(30)}]};
const root=hierarchy(data), scores={'BP-1000':75,'BR-1000':50, BP:99, 'unknown-score':100};
const areas={'BP-1000':{code:'BP',name:'Business processes '.repeat(30)},'BR-1000':{code:'BR',name:'Business roles'}};
const measure=s=>String(s).length*7;
const frozen=JSON.stringify(data), savedScores=JSON.stringify(scores);
let layout=api.layout(root,scores,areas,measure);
function check(layout){
    assert.ok(layout.panels.length===2);
    for(let i=1;i<layout.panels.length;i++)assert.ok(layout.panels[i].top-layout.panels[i-1].bottom>=32,'Actual panel occupied bounds need a clear gap');
    for(const panel of layout.panels)for(const node of panel.nodes){assert.ok(node.box.top>=panel.top);assert.ok(node.box.bottom<=panel.bottom);assert.ok(node.box.left>=0);assert.ok(node.box.right<=layout.width);}
    for(let i=0;i<layout.nodes.length;i++)for(let j=i+1;j<layout.nodes.length;j++){
        const a=layout.nodes[i].box,b=layout.nodes[j].box;
        assert.ok(a.right<=b.left || b.right<=a.left || a.bottom<=b.top || b.bottom<=a.top,'Node occupied boxes must not overlap');
    }
}
check(layout);assert.ok(layout.nodes[0].lines.length>1,'Long labels must wrap and retain full names');
const branch=root.children[0];branch._children=branch.children;branch.children=null;
check(api.layout(root,scores,areas,measure));branch.children=branch._children;branch._children=null;
const printDoc=makeDocument();
const pages=api.pages(layout,printDoc);
assert.ok(pages.length>2,'Large panels must continue across readable landscape pages');
const printed=all(printDoc.body,e=>e.tagName==='g' && e.getAttribute('data-print-node'));
assert.equal(printed.length,layout.nodes.length,'Print DOM must include every intended node exactly once');
for(const page of pages){
    const svg=find(page,e=>e.tagName==='svg');assert.ok(svg);const vb=svg.getAttribute('viewBox').split(' ').map(Number);
    assert.ok(vb[2]<=1040 && vb[3]<=620,'Print pages must retain readable physical scale');
    for(const group of all(page,e=>e.tagName==='g' && e.getAttribute('data-print-node'))){const b=JSON.parse(group.getAttribute('data-occupied-bounds'));assert.ok(b.left>=0 && b.top>=0 && b.right<=vb[2] && b.bottom<=vb[3],'No node label may be clipped by a print page');}
}
assert.ok(text(printDoc.body).includes('75%'));
assert.ok(text(printDoc.body).includes('views.diagram.printContinue'));
assert.ok(text(printDoc.body).includes('views.diagram.printOverview'));
assert.ok(text(printDoc.body).includes('views.diagram.printParent'));
const rootPage=pages.findIndex(page=>!!find(page,e=>e.getAttribute?.('data-print-node')==='BP-1000'))+1;
assert.ok(text(printDoc.body).includes('views.diagram.printParent: BP-1000 / '+rootPage),'Continuation points to the actual parent page');
assert.ok(rootPage===1,'A short root overview shares the first root-bearing content page');
for(const page of pages)assert.ok(page.getAttribute('data-taxonomy-panel'),'Every printed sheet identifies its taxonomy');
for(const label of all(printDoc.body,e=>e.tagName==='text'))assert.ok(Number(label.getAttribute('font-size'))>=14,'Print labels and continuation context must remain at least 10.5pt at 1:1');
assert.equal(JSON.stringify(data),frozen);assert.equal(JSON.stringify(scores),savedScores);
// Print-only metrics reflow four ordinary levels at the same measured 14px type.
const fourRoot=hierarchy({code:'__root__',children:[{code:'fixture-level-1',name:'Business processes',children:[{code:'fixture-level-2',name:'Information services',children:[{code:'fixture-level-3',name:'Analytical support',children:[{code:'fixture-level-4',name:'Acquire data'}]}]}]}]});
const fourAreas={'fixture-level-1':{code:'BP',name:'Processes'}},fourScores={'fixture-level-1':75,'fixture-level-2':65,'fixture-level-3':55,'fixture-level-4':45};
const four=api.layout(fourRoot,fourScores,fourAreas,measure,undefined,{textWidth:174,cardWidth:210,columnPitch:240,columnsPerPage:4});
assert.equal(four.nodes[0].box.right-four.nodes[0].box.left,210,'Print card widths must be remeasured independently of screen cards');
const fourDoc=makeDocument(),fourPages=api.pages(four,fourDoc);
assert.equal(fourPages.length,1,'Four ordinary measured levels fit one landscape page without type shrinking');
assert.equal(all(fourDoc.body,e=>e.getAttribute?.('data-print-node')).length,4);
assert.equal(layout.nodes[0].box.right-layout.nodes[0].box.left,262,'Screen metrics remain unchanged');
// Mixed recorded area-only evidence remains a caption panel and never becomes a node.
const mixedRoot=hierarchy({code:'__root__',children:[{code:'fixture-positive',name:'Assessed process'}]});
const mixedAreas=[{code:'BP',name:'Processes'},{code:'BR',name:'Roles'}];
Object.assign(state.currentReasons,{BP:'Recorded process area reason. BP-AREA-REASON-END',BR:'Recorded caption-only area reason. BR-AREA-ONLY-REASON-END',
    'fixture-positive':'Recorded ordinary node reason. CONTENT-NODE-REASON-END',CR:'EXCLUDED-AREA-REASON-MUST-NOT-LEAK'});
const mixed=api.layout(mixedRoot,{BP:90,BR:85,'fixture-positive':60},{'fixture-positive':mixedAreas[0]},measure,mixedAreas);
assert.equal(mixed.panels.length,2);assert.equal(mixed.nodes.length,1);assert.equal(mixed.panels[1].nodes.length,0);
const mixedDoc=makeDocument();api.pages(mixed,mixedDoc);
assert.ok(text(mixedDoc.body).includes('BR – Roles · browse.container.relevance: 85'));
assert.equal(all(mixedDoc.body,e=>e.getAttribute?.('data-print-node')).length,1);
for(const mode of ['pages','single'])for(const areaOnly of [false,true]){
    const selected=areaOnly?api.layout(hierarchy({code:'__root__',children:[]}),{BR:85,CR:99},{},measure,[mixedAreas[1]]):mixed;
    const printedDoc=makeDocument();let printCalls=0;
    window.open=()=>({document:printedDoc,addEventListener(){},focus(){},requestAnimationFrame(callback){callback();},print(){printCalls++;},close(){}});
    api.print(selected,{querySelector(){return null;}},mode);
    assert.equal(printCalls,1);
    assert.ok(text(printedDoc.body).includes('BR-AREA-ONLY-REASON-END'),'Caption-only area explanations survive Decision print in '+mode+' mode');
    assert.ok(!text(printedDoc.body).includes('EXCLUDED-AREA-REASON-MUST-NOT-LEAK'),'Unselected area explanations stay excluded');
    assert.equal(all(printedDoc.body,e=>e.getAttribute?.('data-print-node')).length,areaOnly?0:1,'Areas remain captions, not fabricated screen/print nodes');
    assert.ok(all(printedDoc.body,e=>e.getAttribute?.('data-evidence-area')==='BR').length>0,'Area evidence stays distinct from ordinary node evidence');
    if(areaOnly){assert.ok(!text(printedDoc.body).includes('BP-AREA-REASON-END'));}
    else{
        assert.ok(text(printedDoc.body).includes('BP-AREA-REASON-END'));
        assert.ok(text(printedDoc.body).includes('CONTENT-NODE-REASON-END'));
        for(const code of ['BP','BR','fixture-positive'])assert.equal(all(printedDoc.body,e=>e.getAttribute?.('data-evidence-code')===code&&e.getAttribute?.('id')).length,1,'Each selected code has one explanation reference');
    }
}
// Expanding a giant parent into fragments must not leave its ordinary child behind
// a later fragment's top. Every fragment, including parent continuations, fits its page.
const nestedRoot=hierarchy({code:'__root__',children:[{code:'fixture-long',name:'Exceptionally long complete node name '.repeat(80),children:[{code:'fixture-child',name:'Ordinary child'}]},{code:'fixture-sibling',name:'Ordinary sibling'}]});
const nestedAreas={'fixture-long':{code:'fixture-area',name:'Processes'},'fixture-sibling':{code:'fixture-area',name:'Processes'}};
const nested=api.layout(nestedRoot,{'fixture-long':75,'fixture-child':55,'fixture-sibling':35},nestedAreas,measure);
const nestedDoc=makeDocument();const nestedPages=api.pages(nested,nestedDoc);
for(const page of nestedPages){const svg=find(page,e=>e.tagName==='svg');const vb=svg.getAttribute('viewBox').split(' ').map(Number);
    for(const fragment of all(svg,e=>e.getAttribute?.('data-print-fragment'))){const box=JSON.parse(fragment.getAttribute('data-occupied-bounds'));assert.ok(box.top>=0&&box.left>=0&&box.bottom<=vb[3]&&box.right<=vb[2],'Every giant-parent/child/sibling fragment stays within the actual page bounds');}}
assert.equal(all(nestedDoc.body,e=>e.getAttribute?.('data-print-node')).length,3);
const nestedIdentities=all(nestedDoc.body,e=>e.getAttribute?.('data-print-node')!==null&&e.getAttribute?.('data-print-node')!==undefined)
    .map(e=>e.getAttribute('data-print-node')).sort();
assert.deepEqual(nestedIdentities,['fixture-child','fixture-long','fixture-sibling'],'Only original nodes carry data-print-node; continuation fragments must not create empty CSS node identities');
// Extensive root lists get overview continuation sheets; short lists stay in the header.
const manyBranches=Array.from({length:40},(_,i)=>({code:'fixture-root-'+i,name:'Root '+i}));
const manyAreas={};manyBranches.forEach(branch=>manyAreas[branch.code]={code:'fixture-group',name:'One area'});
const many=api.layout(hierarchy({code:'__root__',children:manyBranches}),{},manyAreas,measure);
const manyDoc=makeDocument(),manyPages=api.pages(many,manyDoc);
assert.ok(text(manyPages[0]).includes('views.diagram.printOverview'));
assert.equal(all(manyDoc.body,e=>e.getAttribute?.('data-print-node')).length,40);
assert.ok(all(manyPages[0],e=>e.getAttribute?.('data-print-node')).length===0,'Extensive root overview has its own bounded sheet');
// Names longer than a landscape page must continue at readable type without losing text.
const giantRoot=hierarchy({code:'__root__',children:[{code:'fixture-long',name:'Exceptionally long complete node name '.repeat(180)}]});
const giant=api.layout(giantRoot,{'fixture-long':12},{'fixture-long':{code:'fixture-area',name:'Complete area name '.repeat(300)}},measure);
const giantDoc=makeDocument();api.pages(giant,giantDoc);
for(const svg of all(giantDoc.body,e=>e.tagName==='svg'))assert.ok(svg.getAttribute('viewBox').split(' ').map(Number)[3]<=620);
assert.equal(all(giantDoc.body,e=>e.tagName==='g' && e.getAttribute('data-print-node')).length,1);
const actualNodeText=all(giantDoc.body,e=>e.tagName==='g' && e.getAttribute('data-print-fragment')).map(text).join('').replace(/\s+/g,'');
assert.ok(actualNodeText.includes(('fixture-long – '+giantRoot.children[0].data.name+' · 12%').replace(/\s+/g,'')),'Continuation fragments preserve the complete name and numeric score');
assert.ok(all(giantDoc.body,e=>e.tagName==='section').length>3);
const trigger=document.createElement('button');document.body.appendChild(trigger);
api.reason({code:'BP-1000',name:'Processes',description:'Catalogue description'},scores,trigger);
let dialog=document.getElementById('decisionReasonDialog');assert.ok(dialog?.open);
assert.ok(text(dialog).includes(state.currentReasons['BP-1000']));assert.ok(text(dialog).includes('Catalogue description'));
assert.ok(text(dialog).includes('views.diagram.analysisReason'));assert.ok(text(dialog).includes('views.diagram.catalogueDescription'));
dialog.close();assert.equal(document.activeElement,trigger);
api.reason({code:'BR-1000',name:'Roles'},scores,trigger);dialog=document.getElementById('decisionReasonDialog');assert.ok(text(dialog).includes('views.diagram.reasonMissing'));
assert.equal(state.currentReasons['BR-1000'],undefined);
// A complete hierarchy fits the selected physical sheet without splitting its
// edges. Larger paper gives this tall sibling list more usable text scale.
const tallData={code:'__root__',children:[{code:'fixture-tall-root',name:'Root',children:Array.from({length:100},(_,i)=>({code:'fixture-tall-'+i,name:'Assessed leaf '+i}))}]};
const tall=api.layout(hierarchy(tallData),{'fixture-tall-root':87.5},{'fixture-tall-root':{code:'fixture-area',name:'Area'}},measure,undefined,{textWidth:174,cardWidth:210,columnPitch:240,columnsPerPage:4});
const printScales={};
// Printable portrait dimensions at 96dpi after the two 10mm margins.
for(const [paper,width,height,physicalSize] of [['A1',2169,3102,'594mm 841mm'],['A0',3102,4418,'841mm 1189mm']]){
    const tallDoc=makeDocument();let printCalls=0;
    window.open=()=>({document:tallDoc,addEventListener(){},focus(){},requestAnimationFrame(callback){callback();},print(){printCalls++;},close(){}});
    api.print(tall,{querySelector(){return null;}},{mode:'single',paper});
    assert.equal(printCalls,1,'The selected complete sheet reaches the print dialog');
    assert.equal(tallDoc.body.children.length,1,'The complete hierarchy occupies one printed sheet');
    const page=tallDoc.body.children[0],svg=find(page,e=>e.tagName==='svg');
    assert.equal(page.getAttribute('data-paper-format'),paper,'The chosen large paper format is retained');
    assert.equal(Number(svg.getAttribute('width')),width,'The selected portrait paper determines the printable width');
    assert.equal(Number(svg.getAttribute('height')),height,'The selected portrait paper determines the printable height');
    assert.ok(text(tallDoc.head).includes('size:'+physicalSize+';margin:10mm'),'The print stylesheet uses the selected physical paper and orientation');
    const scale=Number(page.getAttribute('data-print-scale'));
    assert.ok(Number.isFinite(scale) && scale>0 && scale<=1,'The complete drawing uses a positive finite print scale');
    printScales[paper]=scale;
    const content=svg.children[0],transform=content.getAttribute('transform').match(/^translate\(([^,]+),([^)]+)\) scale\(([^)]+)\)$/);
    assert.ok(transform,'The complete sheet exposes its content placement');
    assert.equal(Number(transform[3]),scale,'Reported print scale is applied to the complete drawing');
    const printedPanels=all(content,e=>e.tagName==='svg');
    assert.equal(printedPanels.length,1,'This taxonomy remains one intact hierarchy panel');
    for(const panel of printedPanels){
        const position=panel.parentNode.getAttribute('transform').match(/^translate\(([^,]+),([^)]+)\)$/);
        assert.ok(position,'Each intact hierarchy panel has a sheet position');
        const left=Number(transform[1])+scale*(Number(position[1])+Number(panel.getAttribute('x')));
        const top=Number(transform[2])+scale*(Number(position[2])+Number(panel.getAttribute('y')));
        const panelWidth=Number(panel.getAttribute('width')),panelHeight=Number(panel.getAttribute('height'));
        const right=left+scale*panelWidth,bottom=top+scale*panelHeight;
        assert.ok(left>=-1e-6 && top>=-1e-6 && right<=width+1e-6 && bottom<=height+1e-6,'All scaled hierarchy content fits the printable paper bounds');
        for(const node of all(panel,e=>e.getAttribute?.('data-print-node'))){
            const bounds=JSON.parse(node.getAttribute('data-occupied-bounds'));
            assert.ok(bounds.left>=0 && bounds.top>=0 && bounds.right<=panelWidth && bounds.bottom<=panelHeight,'No complete node label is clipped inside its hierarchy panel');
        }
    }
    assert.equal(all(tallDoc.body,e=>e.getAttribute?.('data-print-node')).length,101,'Reflow preserves every selected node exactly once');
    assert.equal(all(tallDoc.body,e=>e.tagName==='path').length,100,'Continuous connections preserve every original relationship');
    assert.equal(all(tallDoc.body,e=>e.getAttribute?.('data-parent-page') || e.getAttribute?.('data-print-section')).length,0,'A complete sheet keeps continuous hierarchy edges instead of A4 continuation fragments');
    assert.ok(text(tallDoc.body).includes('87.5%'),'Fractional percentages are not multiplied by 100');
}
assert.ok(printScales.A0>printScales.A1,'A0 gives the same complete large hierarchy a larger usable text scale than A1');
assert.equal(typeof api.evidence,'function','Print needs complete saved explanations, not tooltip-only text');
const reasonEnding='FINAL SAVED REASON — <not markup> & retained.';
state.currentReasons={'fixture-tall-root':'A saved explanation. '.repeat(400)+'\n'+reasonEnding,'excluded-node':'MUST NOT LEAK INTO SELECTED OUTPUT'};
const evidenceDoc=makeDocument(),evidencePages=api.evidence(tall.nodes,tall.scores,evidenceDoc,measure);
assert.ok(evidencePages.length>1,'Very long saved reasons continue at readable size');
const evidenceText=all(evidenceDoc.body,e=>e.getAttribute?.('data-evidence-node')).map(text).join(' ').replace(/\s+/g,'');
assert.ok(evidenceText.includes(state.currentReasons['fixture-tall-root'].replace(/\s+/g,'')),'Full saved explanation including its final line must survive wrapping');
assert.ok(evidenceText.includes('87.5%'));
assert.ok(!evidenceText.includes('MUSTNOTLEAK'),'Evidence must use the same selected subset as the diagram');
assert.equal(all(evidenceDoc.body,e=>e.getAttribute?.('id')==='diagram-evidence-1').length,1,'The diagram reference resolves to one complete evidence entry');
for(const svg of all(evidenceDoc.body,e=>e.tagName==='svg'))assert.ok(svg.getAttribute('viewBox').split(' ').map(Number)[3]<=620,'Evidence remains readable on ordinary paper');
console.log('DECISION_DIAGRAM_READING_OK');
