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
