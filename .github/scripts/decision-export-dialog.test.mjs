import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/decision-export-dialog.js', import.meta.url), 'utf8');
function boot() {
    const downloads = [];
    const context = vm.createContext({window:{}, document:{createElement:()=>({click(){ downloads.push(this.download); }, remove(){}}),body:{appendChild(){}}},
        URLSearchParams, URL:{createObjectURL:()=> 'blob:report',revokeObjectURL(){}}, setTimeout:fn=>fn(), Blob, console});
    vm.runInContext(source,context);return {api:context.window.TaxonomyDecisionExport,downloads};
}
test('selection uses actual available roots and cannot include excluded analysis roots', () => {
    const {api}=boot();const roots=[{code:'CP',inAnalysisScope:true},{code:'BP',inAnalysisScope:false}];
    assert.equal(api.selection({roots,selectedRoots:['CP'],profile:'COMPACT'}).profile,'COMPACT');
    assert.throws(()=>api.selection({roots,selectedRoots:[],profile:'COMPACT'}));
    assert.throws(()=>api.selection({roots,selectedRoots:['BP'],profile:'COMPACT'}));
    const query = api.query({profile:'COMPACT',taxonomyRoots:['CP','IP'],contents:'NONE',treeLayout:'AUTO'});
    assert.deepEqual(new URLSearchParams(query).getAll('taxonomyRoots'),['CP','IP']);
});
test('downloads require report content type and provenance; error pages never become reports',async()=>{
    const {api,downloads}=boot();
    const response=(type,ok=true)=>({ok,status:ok?200:422,redirected:false,
        headers:{get:key=>({'content-type':type,'x-taxonomy-analysis-sha256':'a'.repeat(64),'x-taxonomy-snapshot-id':'saved-1'}[key.toLowerCase()]||null)},
        json:async()=>({detail:'Choose Auto or A3'}),blob:async()=>new Blob(['report'],{type})});
    await assert.rejects(api.download(response('text/html'),'docx','saved-1'));
    await assert.rejects(api.download(response('application/json',false),'json','saved-1'),/Auto/);
    await assert.rejects(api.download(response('application/json'),'json','different-snapshot'));
    assert.equal(downloads.length,0);
    await api.download(response('application/json'),'json','saved-1');assert.equal(downloads.length,1);
});
