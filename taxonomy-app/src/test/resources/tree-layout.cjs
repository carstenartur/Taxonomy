'use strict';
// JUnit owns this deterministic driver; use the application's real WebJar/D3 implementation.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {window: {}, document: {readyState:'loading',addEventListener(){}}, TaxonomyI18n: {t: (key,...args) => key+(args.length?': '+args.join(' / '):'')}};
vm.runInNewContext(fs.readFileSync(process.argv[3], 'utf8'), context);
const utils = process.argv[5] || require('node:path').resolve(require('node:path').dirname(process.argv[2]), '../shared/taxonomy-utils.js');
vm.runInNewContext(fs.readFileSync(utils, 'utf8'), context);
vm.runInNewContext(fs.readFileSync(process.argv[2], 'utf8').replace('window.TaxonomyViews = {',
    'window.treeContract = {collapse: typeof collapseInitialTree === "function" ? collapseInitialTree : null,'
    + 'expand: typeof expandDiagramTree === "function" ? expandDiagramTree : null,'
    + 'print: typeof printTreeDiagram === "function" ? printTreeDiagram : null,'
    + 'layout: typeof layoutTaxonomyTree === "function" ? layoutTaxonomyTree : null,'
    + 'endpoints: typeof treeLinkEndpoints === "function" ? treeLinkEndpoints : null}; window.TaxonomyViews = {'), context);
const api = context.window.treeContract;
assert.equal(typeof api.collapse, 'function', 'Every initial hidden descendant needs a collapsed state');
assert.equal(typeof api.layout, 'function', 'SVG, Canvas and export need shared occupied-bounds layout');
const measure = (text, font) => String(text).length * (font && font.includes('10px') ? 6 : 7);
if (process.argv[4] === 'expansion' || process.argv[4] === 'expand-all') {
    const data = {code: '__root__', children: [{code: 'one', children: [{code: 'two', children: [
        {code: 'three', children: [{code: 'four', children: [{code: 'five'}]}]}]}]}]};
    const before = JSON.stringify(data), root = context.d3.hierarchy(data);
    api.collapse(root);
    if (process.argv[4] === 'expand-all') {
        assert.equal(typeof api.expand, 'function', 'Tree needs one action that restores every hidden descendant');
        context.document.getElementById = () => null;
        let updates = 0;
        api.expand(root, () => updates++);
        assert.deepEqual(Array.from(root.descendants(), n => n.data.code), ['__root__', 'one', 'two', 'three', 'four', 'five']);
        assert.ok(root.descendants().every(n => !n._children), 'Expand all must also clear nested hidden branches');
        assert.equal(updates, 1, 'One complete expansion produces one layout update');
        assert.equal(JSON.stringify(data), before, 'Expansion must never alter the source catalogue');
        process.exit(0);
    }
    assert.deepEqual(Array.from(root.descendants(), n => n.data.code), ['__root__', 'one', 'two', 'three']);
    const branch = root.descendants().find(n => n.data.code === 'three');
    branch.children = branch._children; branch._children = null;
    assert.deepEqual(Array.from(root.descendants(), n => n.data.code), ['__root__', 'one', 'two', 'three', 'four']);
    assert.ok(branch.children[0]._children, 'Next level remains collapsed until its own activation');
    assert.equal(JSON.stringify(data), before, 'Layout state must never alter source catalogue data');
} else if (process.argv[4] === 'geometry') {
    const data = {code: '__root__', children: [{code: 'root', name: 'Long original root label '.repeat(20), children: [
        {code: 'leaf', name: 'A long leaf beside another expanded branch '.repeat(20)},
        {code: 'branch', name: 'A very long intermediate branch label '.repeat(20), children: [
            {code: 'nested', name: 'A deep branch '.repeat(20), children: [{code: 'last', name: 'Last node '.repeat(20)}]},
            {code: 'sibling', name: 'Sibling label '.repeat(20)}]}]}]};
    const before = JSON.stringify(data), root = context.d3.hierarchy(data);
    const scores = {root: 80, leaf: 40, branch: 40, nested: 30, last: 30, sibling: 10};
    const result = api.layout(root, scores, measure);
    const nodes = result.nodes.filter(n => n.data.code !== '__root__');
    assert.equal(nodes.length, 6);
    for (const node of nodes) {
        const b = node._treeBounds;
        assert.ok(b.right - b.left > 220, 'Full fixture label must exceed the old fixed column distance');
        assert.ok(b.left >= result.left && b.right <= result.left + result.width);
        assert.ok(b.top >= result.top && b.bottom <= result.top + result.height);
    }
    for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
        const a = nodes[i]._treeBounds, b = nodes[j]._treeBounds;
        assert.ok(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top,
            'Occupied node boxes overlap: ' + nodes[i].data.code + ' / ' + nodes[j].data.code);
    }
    for (const link of result.links) {
        const ends = api.endpoints(link);
        assert.ok(ends.source.y >= link.source._treeBounds.right, 'Link starts outside parent text/badge');
        assert.ok(ends.target.y <= link.target._treeBounds.left, 'Link stops before child text/badge');
        assert.ok(ends.source.y < ends.target.y, 'Visible connector corridor must remain between columns');
    }
    // A short parent beside a long leaf: bending immediately after the parent
    // would cut diagonally through that leaf's text on the way to lower children.
    const asymmetric = api.layout(context.d3.hierarchy({code: '__root__', children: [
        {code: 'A', children: Array.from({length: 5}, (_, i) => ({code: 'A-' + i}))},
        {code: 'B', name: 'A long adjacent leaf label '.repeat(12)}
    ]}), {}, measure);
    for (const link of asymmetric.links) {
        const ends = api.endpoints(link);
        assert.ok(ends.bend, 'Outgoing curves need a shared corridor after all labels in their column');
        for (const node of asymmetric.nodes.filter(n => n.depth === link.source.depth)) {
            assert.ok(ends.bend.y > node._treeBounds.right, 'A curve must not enter a longer sibling label');
        }
    }
    assert.equal(JSON.stringify(data), before);
} else if (process.argv[4] === 'contrast' || process.argv[4] === 'evidence') {
    // Only the native DOM boundary is substituted; execute the actual export and D3 layout.
    const element = tag => ({tag, tagName: tag, attributes: {}, children: [], style: {}, textContent: '',
        setAttribute(name, value) { this.attributes[name] = String(value); },
        getAttribute(name) { return this.attributes[name] ?? null; },
        replaceChildren(...children) { this.children=[]; children.forEach(child=>this.appendChild(child)); },
        getContext: tag==='canvas'?()=>({measureText:text=>({width:measure(text)})}):undefined,
        appendChild(child) { if (child.parentNode) child.parentNode.children = child.parentNode.children.filter(c => c !== child);
            this.children.push(child); child.parentNode = this; return child; }});
    context.document.createElement = element;
    context.document.createElementNS = (_, tag) => element(tag);
    const data = [{code: 'one', children: [{code: 'two', children: [
        {code: 'three', children: [{code: 'four'}]}]}]}];
    const descendants = node => [node, ...node.children.flatMap(descendants)];
    function printTree(selected,scores,mode){
        const printDoc={documentElement:{lang:'en'},head:element('head'),body:element('body'),createElement:element,createElementNS:(_,tag)=>element(tag)};
        context.document.documentElement={lang:'en'};let printCalls=0;
        context.window.open=()=>({document:printDoc,addEventListener(){},focus(){},requestAnimationFrame(callback){callback();},print(){printCalls++;},close(){}});
        api.print(selected,scores,{querySelector(){return null;}},mode);
        assert.equal(printCalls,1);
        return descendants(printDoc.body);
    }
    if (process.argv[4] === 'evidence') {
        const ending = 'FULL EXPLANATION END — <not markup> & retained';
        context.window.TaxonomyState = {currentReasons: {one: 'Root justification', four: 'Complete detailed evidence. '.repeat(250) + ending,
            excluded: 'EXCLUDED REASON MUST NOT APPEAR'}};
        const scores = {one: 87.5, four: 0.75};
        const before = JSON.stringify({data, scores, reasons: context.window.TaxonomyState.currentReasons});
        const collapsed = context.window.TaxonomyViews.buildExportSVG(data, scores, {});
        assert.ok(descendants(collapsed).some(node => node.textContent === 'Root justification'), 'SVG has visible complete saved explanations');
        assert.ok(!descendants(collapsed).some(node => node.textContent.includes(ending)), 'Collapsed export keeps its original node selection');
        const full = context.window.TaxonomyViews.buildExportSVG(data, scores, {expandAll: true});
        const text = descendants(full).filter(node => node.getAttribute('data-evidence-node') === 'four').map(node => node.textContent).join('').replace(/\s+/g, '');
        assert.ok(text.includes(context.window.TaxonomyState.currentReasons.four.replace(/\s+/g, '')), 'SVG retains every saved explanation character through its final line');
        assert.ok(text.includes('0.75%'), 'Fractional percentage evidence keeps its original units');
        assert.ok(!descendants(full).some(node => node.textContent.includes('EXCLUDED')));
        assert.ok(descendants(full).some(node => node.getAttribute('href') === '#diagram-evidence-1'), 'Compact labels link to visible full explanations');
        assert.equal(JSON.stringify({data, scores, reasons: context.window.TaxonomyState.currentReasons}), before);
        for(const mode of ['pages','single']){
            const printed=printTree(data,scores,mode);
            assert.equal(printed.filter(node=>node.getAttribute('data-evidence-node')==='one'&&node.getAttribute('id')).length,1,'Ordinary roots shared by captions and content have one node explanation');
            assert.ok(!printed.some(node=>node.getAttribute('data-evidence-area')==='one'),'Ordinary root evidence is not reclassified as an area');
        }
        const selectedAreas=[{code:'BP',name:'Processes',taxonomyContainer:true,children:data},
            {code:'BR',name:'Roles',taxonomyContainer:true,children:[]}];
        Object.assign(context.window.TaxonomyState.currentReasons,{BP:'Saved process area justification. BP-AREA-REASON-END',
            BR:'Saved caption-only area justification. BR-AREA-ONLY-REASON-END',CR:'EXCLUDED-AREA-REASON-MUST-NOT-LEAK'});
        const areaScores={...scores,BP:90,BR:85,CR:99};
        const areaBefore=JSON.stringify({selectedAreas,areaScores,reasons:context.window.TaxonomyState.currentReasons});
        for(const areaOnly of [false,true]){
            const selected=areaOnly?[selectedAreas[1]]:selectedAreas;
            const svg=context.window.TaxonomyViews.buildExportSVG(selected,areaScores,{expandAll:true});
            const output=descendants(svg).map(node=>node.textContent).join(' ');
            assert.ok(output.includes('BR-AREA-ONLY-REASON-END'),'Caption-only area reason survives generated client SVG');
            assert.ok(!output.includes('EXCLUDED-AREA-REASON-MUST-NOT-LEAK'));
            assert.ok(descendants(svg).some(node=>node.getAttribute('data-evidence-area')==='BR'));
            const areaHeading=descendants(svg).find(node=>node.getAttribute('data-taxonomy-container')==='BR');
            const areaReference=areaHeading.parentNode.getAttribute('href');
            assert.ok(areaReference?.startsWith('#diagram-evidence-'),'Area captions link to their complete explanation');
            assert.equal(descendants(svg).filter(node=>node.getAttribute('data-evidence-area')==='BR'&&node.getAttribute('id')===areaReference.slice(1)).length,1);
            assert.ok(!descendants(svg).some(node=>['BP','BR'].includes(node.getAttribute('data-export-node'))),'Area captions never become ordinary export nodes');
            if(areaOnly){assert.ok(!output.includes('BP-AREA-REASON-END'));}
            else{
                assert.ok(output.includes('BP-AREA-REASON-END'));
                assert.ok(output.includes('Root justification'));
            }
            for(const mode of ['pages','single']){
                const printed=printTree(selected,areaScores,mode),printText=printed.map(node=>node.textContent).join(' ');
                assert.ok(printText.includes('BR-AREA-ONLY-REASON-END'),'Caption-only area reason survives Tree print in '+mode+' mode');
                assert.ok(!printText.includes('EXCLUDED-AREA-REASON-MUST-NOT-LEAK'));
                assert.equal(printed.filter(node=>node.getAttribute('data-print-node')).length,areaOnly?0:4,'Area-only output does not fabricate content nodes');
                assert.ok(printed.some(node=>node.getAttribute('data-evidence-area')==='BR'));
                if(areaOnly){assert.ok(!printText.includes('BP-AREA-REASON-END'));}
                else for(const code of ['BP','BR','one'])assert.equal(printed.filter(node=>node.getAttribute('data-evidence-code')===code&&node.getAttribute('id')).length,1,'Evidence is deduplicated by selected code');
            }
        }
        assert.equal(JSON.stringify({selectedAreas,areaScores,reasons:context.window.TaxonomyState.currentReasons}),areaBefore);
        process.exit(0);
    }
    for (const score of [1, 50, 100]) {
        const svg = context.window.TaxonomyViews.buildExportSVG(data, {three: score}, {});
        const markers = descendants(svg).filter(node => node.tag === 'text' && node.textContent === '+');
        assert.equal(markers.length, 1, 'Export must identify the branch whose descendants are hidden');
        const marker = markers[0].attributes;
        assert.equal(marker.fill, '#222', 'Dark marker remains visible at 1% green on white');
        assert.equal(marker.stroke, '#fff', 'White halo remains visible on dark root colours');
        assert.equal(marker['stroke-width'], '2');
        assert.equal(marker['paint-order'], 'stroke fill', 'Halo must not obscure the small glyph');
    }
    const expanded = context.window.TaxonomyViews.buildExportSVG(data, {}, {expandAll: true});
    assert.equal(descendants(expanded).filter(node => node.textContent === '+').length, 0,
        'Expanded branches must not advertise hidden descendants');
} else {
    const result = api.layout(context.d3.hierarchy({code: '__root__', children: []}), {}, measure);
    for (const property of ['left', 'top', 'width', 'height']) assert.ok(Number.isFinite(result[property]));
    assert.equal(result.links.length, 0);
}
