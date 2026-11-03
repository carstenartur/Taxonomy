import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const root=new URL('../../taxonomy-app/src/main/resources/static/js/',import.meta.url);
for(const selected of ['working-B','__shared__'])test(`architecture requests retain ${selected} while analysis stays in private A`,async()=>{
    const calls=[],context=vm.createContext({URL,URLSearchParams});context.window=context;
    context.location={href:'https://example.test/taxonomy/',origin:'https://example.test'};
    context.TaxonomyI18n={getBasePath:()=>'/taxonomy',resolveUrl:p=>p.startsWith('/taxonomy/')?p:'/taxonomy'+p,t:key=>key};
    context.__TaxonomyAnalysisSessionContext={runtime:{workspaceId:'analysis-A'}};
    context.TaxonomyApiClient={
        request:(url,options,policy)=>{calls.push({url,options,policy});return Promise.resolve({json:()=>Promise.resolve({})});},
        getJson:(url,options)=>{calls.push({url,options});return Promise.resolve({});},
        sendJson:(url,body)=>{calls.push({url,body});return Promise.resolve({});}
    };
    for(const file of ['core/taxonomy-analysis-session-api-routing.js','api/architecture-editor-api.js','api/architecture-lifecycle-api.js'])vm.runInContext(await readFile(new URL(file,root),'utf8'),context);
    const scope={repositoryId:'repo',workspaceScopeKey:selected,branch:'draft/b',revision:7};
    await context.ArchitectureEditorApi.load(scope,7);
    await context.ArchitectureEditorApi.preview({context:scope,kind:'CREATE_ELEMENT'});
    await context.ArchitectureEditorApi.createWorkspace(scope);
    await context.TaxonomyArchitectureLifecycleApi.overview(scope);
    await context.TaxonomyArchitectureLifecycleApi.previewReview({context:scope});
    for(const call of calls){
        const url=new URL(call.url,context.location.href);
        assert.equal(url.searchParams.get('workspaceId'),selected==='__shared__'?'':selected,call.url);
        assert.equal(url.searchParams.get('workspaceScopeKey'),selected,call.url);
    }
    assert.equal(context.__TaxonomyAnalysisSessionContext.runtime.workspaceId,'analysis-A');
});
for(const basePath of ['', '/taxonomy'])test(`snapshot fetch preserves explicit private/shared pins beneath ${basePath || '/'}`,async()=>{
    const calls=[],context=vm.createContext({URL,URLSearchParams,Headers,Request});context.window=context;
    context.location={href:'https://example.test'+basePath+'/',origin:'https://example.test'};
    context.TaxonomyState={};context.TaxonomyI18n={getBasePath:()=>basePath};
    context.fetch=async(url,options)=>{calls.push({url,headers:new Headers(options.headers)});return {ok:true};};
    vm.runInContext(await readFile(new URL('core/taxonomy-analysis-session-core.js',root),'utf8'),context);
    context.__TaxonomyAnalysisSessionContext.runtime.workspaceId='analysis-A';
    context.__TaxonomyAnalysisSessionContext.installWorkspaceFetchRouting();
    await context.fetch(basePath+'/api/projects/42/architecture-workbench/s-2?workspaceId=working-B');
    await context.fetch(basePath+'/api/projects/42/architecture-workbench/s-2?workspaceId=');
    await context.fetch(basePath+'/api/architecture/focus/suggestions',{headers:{'X-Taxonomy-Workspace-Id':'working-C'}});
    assert.deepEqual(calls.map(c=>c.headers.get('X-Taxonomy-Workspace-Id')),['working-B','','working-C']);
});
