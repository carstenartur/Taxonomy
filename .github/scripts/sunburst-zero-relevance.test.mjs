import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-views.js',import.meta.url),'utf8');
const context={window:{},TaxonomyI18n:{t:key=>key}};
vm.runInNewContext(source,context);
const filter=(...args)=>context.window.TaxonomyViews.filterZeroRelevance(...args);
// Real top-level catalogue identifiers; no invented children or hierarchy links.
const catalogue=Object.freeze(['BP','BR','CP','CI','CO','CR','IP','UA'].map(code=>Object.freeze({code,children:Object.freeze([])})));
test('zero filter is a projection and does not mutate the real source identities',()=>{
    const scores=Object.freeze({BP:0,BR:75});
    assert.deepEqual(Array.from(filter(catalogue,scores),node=>node.code),['BR','CP','CI','CO','CR','IP','UA']);
    assert.equal(catalogue.length,8); assert.deepEqual(scores,{BP:0,BR:75});
});
for(const value of [undefined,null,NaN,'0'])test(`missing or nonnumeric ${String(value)} is not a measured zero`,()=>{
    assert.equal(filter(catalogue,{BP:value}).length,8);
});
test('all actual zeros have an empty result, not an invented fallback node',()=>{
    assert.equal(filter(catalogue,Object.fromEntries(catalogue.map(n=>[n.code,0]))).length,0);
});
test('retain a zero ancestor while an actual descendant remains visible',()=>{
    // BP -> BP-1000 is the catalogue's real root/family edge; this is an explicitly bounded projection.
    const nodes=[{code:'BP',children:[{code:'BP-1000',children:[]}]}];
    assert.equal(filter(nodes,{BP:0,'BP-1000':75})[0].children[0].code,'BP-1000');
    assert.equal(filter(nodes,{BP:0})[0].children.length,1);
    assert.equal(filter(nodes,{BP:0,'BP-1000':0}).length,0);
});
test('a missing product-family evaluation must not become a zero rejection',()=>{
    // Recorded catalogue product IP-1286 has the direct family IP-2072.
    const product = [{code: 'IP-1286', parentCode: 'IP-2072', analysisRole: 'PRODUCT', children: []}];
    const detail = {'IP-1286': {kind: 'PRODUCT_SUITABILITY', parentCode: 'IP-2072', parentScore: null}};
    assert.equal(filter(product, {'IP-1286': 0}, detail).length, 1);
    assert.equal(filter(product, {'IP-1286': 0}, {'IP-1286': {...detail['IP-1286'], parentScore: 0}}).length, 0);
});
test('strict positive projection excludes unknown, pending and nonpositive evidence',()=>{
    const scores=Object.freeze({BP:0,BR:75,CP:null,CI:NaN,CO:'80',CR:-1,IP:Infinity});
    const project=context.window.TaxonomyViews.filterRelevance;
    assert.equal(typeof project,'function','All diagrams need the shared relevance projection');
    assert.deepEqual(Array.from(project(catalogue,scores,{},'positive'),node=>node.code),['BR']);
    assert.equal(project(catalogue,scores,{},'all'),catalogue);
    assert.deepEqual(Array.from(project(catalogue,scores,{},'hide-zero'),node=>node.code),
        Array.from(filter(catalogue,scores),node=>node.code));
    assert.equal(project(catalogue,null,{},'positive').length,0);
    const product=[{code:'IP-1286',children:[]}];
    const pending={'IP-1286':{kind:'PRODUCT_SUITABILITY',parentScore:null}};
    assert.equal(project(product,{'IP-1286':0},pending,'positive').length,0);
});
test('strict positive projection keeps context ancestors without introducing unassessed siblings',()=>{
    const nodes=Object.freeze([Object.freeze({code:'BP',children:Object.freeze([
        Object.freeze({code:'BP-1000',children:Object.freeze([])})
    ])}),catalogue[1]]);
    const scores=Object.freeze({BP:0,'BP-1000':75});
    const projected=context.window.TaxonomyViews.filterRelevance(nodes,scores,{},'positive');
    assert.deepEqual(Array.from(projected,node=>node.code),['BP']);
    assert.deepEqual(Array.from(projected[0].children,node=>node.code),['BP-1000']);
    assert.equal(nodes.length,2);
    assert.notEqual(projected[0],nodes[0]);
    assert.deepEqual(scores,{BP:0,'BP-1000':75});
});

test('Sunburst keeps an unassessed branch color when the preceding zero branch is hidden',()=>{
    // Both source root/family edges exist in the catalogue; scores are presentation fixtures.
    const nodes=Object.freeze(['BP','CO'].map(code=>Object.freeze({code,taxonomyRoot:code,
        taxonomyContainer:true,children:Object.freeze([Object.freeze({code:code+'-1000',
            taxonomyRoot:code,parentCode:code,children:Object.freeze([])})])})));
    const scores=Object.freeze({BP:0,'BP-1000':0});
    const descendants=node=>node.children.flatMap(child=>[child,...descendants(child)]);
    function element(tagName) {
        return {tagName,children:[],attributes:{},style:{},classList:{add(){}},
            set innerHTML(value){this.children=[];},
            setAttribute(key,value){this.attributes[key]=String(value);},
            getAttribute(key){return this.attributes[key]??null;},
            removeAttribute(key){delete this.attributes[key];},
            appendChild(child){child.parentNode=this;this.children.push(child);return child;},
            append(...children){children.forEach(child=>this.appendChild(child));},
            insertBefore(child,before){this.children.splice(this.children.indexOf(before),0,child);},
            addEventListener(){},contains(child){return this===child||descendants(this).includes(child);},
            querySelector(tag){return descendants(this).find(child=>child.tagName===tag)||null;}};
    }
    // Supply the DOM/D3 drawing boundary; projection, palette, SVG attributes and navigation run in production code.
    function selection(items,parents=[],values=[]) {
        const each=visit=>items.forEach((item,index)=>visit.call(item,item.__data__,index));
        return {
            append(tag){return selection(items.map(item=>{
                const child=element(tag);child.__data__=item.__data__;return item.appendChild(child);
            }));},
            attr(key,value){each(function(data){this.setAttribute(key,typeof value==='function'?value(data):value);});return this;},
            style(){return this;},on(){return this;},
            selectAll(){return selection([],items);},
            data(next){return selection(items,parents,next);},
            join(tag){return selection(values.map(data=>{
                const child=element(tag);child.__data__=data;return parents[0].appendChild(child);
            }));},
            filter(predicate){return selection(items.filter(item=>predicate(item.__data__)));},
            text(value){each(function(data){this.textContent=typeof value==='function'?value(data):value;});return this;},
            each(visit){each(visit);return this;},node(){return items[0];}
        };
    }
    const d3={select:node=>selection([node]),hierarchy(data){
        const all=[];
        function visit(data,parent,depth=0){
            const node={data,parent,x0:0,x1:1,y0:depth,y1:depth+1};all.push(node);
            if(data.children?.length)node.children=data.children.map(child=>visit(child,node,depth+1));
            return node;
        }
        const root=visit(data);root.sum=()=>root;root.each=visit=>all.forEach(visit);root.descendants=()=>all;return root;
    },partition(){const layout=()=>{};layout.size=()=>layout;return layout;},arc(){
        const arc=()=> 'M0,0';
        for(const name of ['startAngle','endAngle','padAngle','padRadius','innerRadius','outerRadius'])arc[name]=()=>arc;
        return arc;
    }};
    const container=element('div'),filterControl={value:'all'};
    const document={activeElement:null,readyState:'loading',createElement:element,addEventListener(){},dispatchEvent(){},
        getElementById:id=>id==='taxonomyTree'?container:id==='diagramRelevanceFilter'?filterControl:null};
    const runtime={window:{TaxonomyState:{currentView:'sunburst',taxonomyData:nodes,currentScores:scores,currentScoreDetails:{}}},
        document,d3,TaxonomyI18n:{t:key=>key},CustomEvent:class{},
        ResizeObserver:class{observe(){}disconnect(){}},console:{error(message,error){throw error;}}};
    for(const name of ['shared/taxonomy-utils.js','core/taxonomy-views.js','core/taxonomy-browse.js']) {
        vm.runInNewContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/'+name,import.meta.url),'utf8'),runtime);
        runtime.TaxonomyUtils=runtime.window.TaxonomyUtils;
    }
    function render(mode){
        filterControl.value=mode;
        runtime.window.TaxonomyBrowse.renderView(nodes,scores);
        assert.equal(container.getAttribute('data-view-rendered'),'sunburst');
        return new Map(descendants(container).filter(node=>node.tagName==='path')
            .map(node=>[node.getAttribute('data-diagram-node'),node.getAttribute('fill')]));
    }
    const all=render('all');
    assert.deepEqual([...all.keys()],['BP-1000','CO-1000']);
    assert.notEqual(all.get('BP-1000'),all.get('CO-1000'));
    const filtered=render('hide-zero');
    assert.deepEqual([...filtered.keys()],['CO-1000']);
    assert.equal(filtered.get('CO-1000'),all.get('CO-1000'),
        'Visibility changes must preserve the catalogue color of surviving unassessed nodes');
    assert.deepEqual(render('all'),all);
});
