import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const script = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/analysis-snapshot-adoption.js',import.meta.url),'utf8').catch(()=>'');
function boot() {
    const nodes = new Map();
    const node = id => {
        if (!nodes.has(id)) nodes.set(id,{hidden:false,disabled:false,textContent:'',value:'',children:[],append(...items){this.children.push(...items);},
            replaceChildren(){this.children=[];},addEventListener(type,fn){this[type]=fn;},focus(){this.focused=true;}});
        return nodes.get(id);
    };
    const source={repositoryId:'source-repo',workspaceScopeKey:'__shared__',branch:'old-branch',projectId:1,requirementId:2,requirementVersionId:3,snapshotId:'snapshot'};
    const document={getElementById:node,createElement:()=>({textContent:'',append(){}})};
    const context=vm.createContext({document,URL,URLSearchParams,location:{search:'?adoption=preview'},console});context.window=context;
    context.TaxonomyArchitectureWorkflow={adoptionSource:()=>source,editorSourceLinks:()=>({snapshot:'/source'})};
    vm.runInContext(script,context);
    assert.equal(typeof context.AnalysisSnapshotAdoption,'function');
    return {context,node,source};
}
const target={repositoryId:'source-repo',workspaceScopeKey:'target-copy',branch:'draft',revision:4,actor:'alice',writeMode:'PRIVATE_WORKSPACE'};
function view(context=target,mayEdit=true){return {document:{context,source:'WORKSPACE_REVISION'},mayEdit,model:{views:[]}};}

test('opening adoption panel never previews or accepts automatically',()=>{
    const {context,node}=boot();let calls=0;
    const panel=context.AnalysisSnapshotAdoption({t:key=>key,api:{previewAdoption(){calls++;},adoptSnapshot(){calls++;}},metadata:()=>({commandId:'one'})});
    panel.update(view());assert.equal(calls,0);assert.equal(node('adoptionPreview').disabled,false);
    panel.update(view(target,false));assert.equal(node('adoptionPreview').disabled,true);
});

test('explicit preview freezes source target metadata and ambiguous retry keeps the same acceptance identity',async()=>{
    const {context,node,source}=boot();const accepted=[];const errors=[];let requests=[];let attempts=0;
    const panel=context.AnalysisSnapshotAdoption({t:key=>key,metadata:()=>({commandId:'one',rationale:'Adopt reviewed snapshot'}),report:error=>errors.push(error),
        api:{previewAdoption:async request=>{requests.push(request);return {fingerprint:'frozen',viewId:'analysis-view',summary:{addedElements:['CP-1000'],reusedElements:[],addedRelations:[],reusedRelations:[],differences:[],conflicts:[]},preview:{context:target}};},
            adoptSnapshot:async request=>{requests.push(request);if(attempts++===0)throw new Error('transport uncertain');return {viewId:'analysis-view',accepted:{context:{...target,revision:5}}};}},
        onAccepted:result=>accepted.push(result)});
    panel.update(view());await node('adoptionPreview').click();
    assert.deepEqual(JSON.parse(JSON.stringify(requests[0].source)),source);assert.equal(requests[0].context.workspaceScopeKey,'target-copy');
    assert.equal(node('adoptionAccept').disabled,false);await node('adoptionAccept').click();assert.equal(errors.length,1);
    await node('adoptionAccept').click();assert.strictEqual(requests[1],requests[2]);assert.equal(requests[2].previewFingerprint,'frozen');
    assert.equal(accepted[0].viewId,'analysis-view');
});

test('selecting a recorded adoption view renders its real provenance without throwing',()=>{
    const {context,node}=boot();
    const panel=context.AnalysisSnapshotAdoption({t:key=>key,api:{},metadata:()=>({commandId:'one'}),selectedView:()=> 'analysis-B'});
    const selected=view();selected.model.views=[{id:'analysis-B',title:'Recorded B',extensions:{'x-analysis-snapshot-id':'snapshot-B','x-analysis-graph-fingerprint':'a'.repeat(64)}}];
    assert.doesNotThrow(()=>panel.update(selected));
    assert.match(node('adoptionProvenance').textContent,/snapshot-B/);
});

test('deliberate canonical view selection uses its source B while retaining independent target pins',async()=>{
    const {context,node,source}=boot();let selectedId=null;let panel;const requests=[];
    const sourceB={repositoryId:'source-repo',workspaceScopeKey:'source-copy-B',branch:'source-branch-B',projectId:11,requirementId:12,requirementVersionId:13,snapshotId:'snapshot-B'};
    const extensions=Object.fromEntries(Object.entries(sourceB).map(([key,value])=>[{
        repositoryId:'x-analysis-repository-id',workspaceScopeKey:'x-analysis-workspace-scope',branch:'x-analysis-branch',projectId:'x-analysis-project-id',
        requirementId:'x-analysis-requirement-id',requirementVersionId:'x-analysis-requirement-version-id',snapshotId:'x-analysis-snapshot-id'}[key],String(value)]));
    const current=view();current.model.views=[{id:'analysis-B',title:'Recorded B',extensions}];
    panel=context.AnalysisSnapshotAdoption({t:key=>key,metadata:()=>({commandId:'one'}),report:error=>{throw error;},selectedView:()=>selectedId,
        onSelectView:id=>{selectedId=id;panel.update(current);},api:{previewAdoption:async request=>{requests.push(request);return {fingerprint:'B',summary:{addedElements:[],reusedElements:[],addedRelations:[],reusedRelations:[],differences:[],conflicts:[]}};}}});
    panel.update(current);assert.match(node('adoptionSource').textContent,new RegExp(source.snapshotId));
    node('adoptionView').value='analysis-B';node('adoptionView').change();
    assert.match(node('adoptionSource').textContent,/snapshot-B/);
    const link=new URL(node('adoptionSourceLink').href,'https://example.test');
    assert.equal(link.searchParams.get('snapshotId'),'snapshot-B');assert.equal(link.searchParams.get('workspaceId'),'source-copy-B');
    await node('adoptionPreview').click();
    assert.deepEqual(JSON.parse(JSON.stringify(requests[0].source)),sourceB);
    assert.deepEqual(JSON.parse(JSON.stringify(requests[0].context)),target);
});

test('historical checkpoint links clear a later canonical view selection and preserve exact source pins',async()=>{
    const editor=await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/architecture-editor.js',import.meta.url),'utf8');
    const body=editor.slice(editor.indexOf('    function renderVersions()'),editor.indexOf('    function metadata('));
    const list={children:[],replaceChildren(){this.children=[];},append(item){this.children.push(item);}};
    const context=vm.createContext({URL,location:{href:'https://example.test/taxonomy/architecture/editor?repositoryId=repo&workspaceId=copy&branch=draft&revision=4&viewId=analysis-B&sourceSnapshotId=A'},
        view:{document:{versions:[{commitId:'a'.repeat(40),author:'Alice',message:'Before adoption'}]}},
        el:()=>list,short:value=>value.substring(0,12),document:{createElement:()=>({children:[],append(item){this.children.push(item);}})}});
    vm.runInContext(body+'\nrenderVersions();',context);
    const link=new URL(list.children[0].children[0].href);
    assert.equal(link.searchParams.has('viewId'),false);
    assert.equal(link.searchParams.get('commit'),'a'.repeat(40));
    assert.equal(link.searchParams.get('sourceSnapshotId'),'A');
    assert.equal(link.searchParams.get('workspaceId'),'copy');
});
