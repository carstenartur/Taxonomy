import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';
const resources=process.env.TAXONOMY_TEST_RESOURCE_ROOT||path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{}),args:['--no-sandbox','--disable-dev-shm-usage']});});
after(async()=>{await browser?.close();});
async function fixture(){
    const page=await browser.newPage();
    const html=await readFile(path.join(resources,'templates/architecture-editor.html'),'utf8');
    const body=html.match(/<body\b[^>]*>([\s\S]*?)<\/body>/)[1].replace(/<script\b[\s\S]*?<\/script>/g,'');
    await page.setContent(`<div id="architectureWorkspace"></div><section id="architectureWorkingSource">${body}</section>`);
    await page.evaluate(()=>{
        window.loaded=[];window.published=[];window.navigation=[];
        window.TaxonomyI18n={ready:()=>Promise.resolve(),t:key=>key};
        window.ArchitectureEditorRenderer=()=>({render(){},select(){},presentFocus(){},fit(){},focus(){},zoom(){}});
        window.TaxonomyPlanningEditor={create:()=>({render(){},setEnabled(){}})};
        window.ArchitectureFocusPanel=()=>({observe(){},review(){},setLocked(){},setContext(){},select(){}});
        window.AnalysisSnapshotAdoption=()=>({setLocked(){},update(){},getSource:()=>null});
        window.TaxonomyAppChrome={updateContext:value=>published.push(value)};
        window.ArchitectureEditorApi={load:async(scope,revision)=>{
            loaded.push({scope,revision});
            return {document:{context:{repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a',actor:'alice',revision:revision||'7',commit:null},source:'WORKSPACE_REVISION',projectionState:'READY',dsl:'',history:[],versions:[]},
                mayEdit:true,model:{elements:[],relations:[],evidence:[],packages:[],views:[]},scene:{nodes:[],edges:[]},searchIndex:[],history:[],versions:[],
                schema:{elementTypes:['capability'],relationTypes:[],relationStatuses:['proposed'],layoutStrategy:'stable',layoutRevision:0}};
        }};
        history.replaceState=(_state,_title,url)=>navigation.push({kind:'replace',url:String(url)});history.pushState=(_state,_title,url)=>navigation.push({kind:'push',url:String(url)});
    });
    await page.addScriptTag({path:path.join(resources,'static/js/architecture-editor.js')});
    return page;
}
test('embedded editor loads only when activated and retains an unaccepted form across suspension',async()=>{
    const page=await fixture();try{
        assert.equal(await page.evaluate(()=>loaded.length),0,'Hidden editor must not load or replace the analysis context');
        assert.equal(await page.evaluate(async()=>{window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));return editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a'});}),true,await page.locator('#editorError').textContent());
        assert.equal(await page.evaluate(()=>loaded.length),1);
        await page.locator('#editorTitle').fill('Unaccepted design draft');
        await page.evaluate(()=>editor.suspend());
        await page.evaluate(async()=>{await editor.activate(editor.getSource());});
        assert.equal(await page.locator('#editorTitle').inputValue(),'Unaccepted design draft');
        assert.equal(await page.evaluate(()=>loaded.length),1,'Same-source return must retain the mounted form');
    }finally{await page.close();}
});
test('a dirty editor refuses changing the exact source when discard is declined',async()=>{
    const page=await fixture();try{
        assert.equal(await page.evaluate(async()=>{window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));return editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a'});}),true,await page.locator('#editorError').textContent());
        await page.locator('#editorTitle').fill('Keep my draft');
        await page.evaluate(()=>window.confirm=()=>false);
        assert.equal(await page.evaluate(()=>editor.canLeave({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-b',branch:'draft/b'},{retain:false})),false);
        assert.equal(await page.locator('#editorTitle').inputValue(),'Keep my draft');
    }finally{await page.close();}
});

test('history replay replaces the selected element without adding history and can clear selection',async()=>{
    const page=await fixture();try{
        await page.evaluate(async()=>{
            const original=ArchitectureEditorApi.load;
            ArchitectureEditorApi.exportUrl=()=>'/exact-source-export';
            ArchitectureEditorApi.load=async(...args)=>{const view=await original(...args);
                view.model.elements=[{id:'one',title:'First',type:'capability',extensions:{}},{id:'two',title:'Second',type:'capability',extensions:{}}];
                view.model.relations=[{sourceId:'one',targetId:'two',relationType:'SUPPORTS',status:'proposed',extensions:{}}];
                view.schema.relationTypes=['SUPPORTS'];
                view.searchIndex=view.model.elements.map(e=>({id:e.id,text:(e.id+' '+e.title).toLowerCase()}));return view;};
            window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));
            await editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a'});
            editor.select('one');editor.select('two');window.navigation=[];
            await editor.activate({...editor.getSource(),element:'one'});
        });
        assert.equal(await page.locator('#editorTitle').inputValue(),'First');
        assert.deepEqual(await page.evaluate(()=>navigation.map(item=>item.kind)),['replace']);
        assert.equal(await page.evaluate(()=>new URL(navigation.at(-1).url).searchParams.get('element')),'one');
        await page.evaluate(async()=>{const source=editor.getSource();delete source.element;window.navigation=[];await editor.activate(source);});
        assert.equal(await page.evaluate(()=>editor.getSource().element),null);
        assert.equal(await page.locator('#editorTitle').inputValue(),'');
        assert.deepEqual(await page.evaluate(()=>navigation.map(item=>item.kind)),['replace']);
        assert.equal(await page.evaluate(()=>new URL(navigation.at(-1).url).searchParams.has('element')),false);
        await page.locator('#editorTitle').fill('Unaccepted new element');await page.evaluate(()=>window.confirm=()=>false);
        assert.equal(await page.evaluate(()=>editor.canLeave({...editor.getSource(),element:'one'},{retain:false})),false);
        assert.equal(await page.locator('#editorTitle').inputValue(),'Unaccepted new element');
        await page.evaluate(()=>editor.select('one'));
        assert.equal(await page.locator('#editorTitle').inputValue(),'Unaccepted new element','Direct diagram/tree selection must preserve a declined draft');
        assert.equal(await page.evaluate(()=>editor.getSource().element),null);
        await page.evaluate(()=>{window.confirm=()=>true;editor.select('one');});
        assert.equal(await page.locator('#editorTitle').inputValue(),'First');
        await page.locator('#editorTitle').fill('Keep this edit');
        await page.evaluate(()=>{window.confirmCalls=0;window.confirm=()=>{confirmCalls++;return false;};});
        await page.locator('#editorTree button').filter({hasText:'First'}).click();
        assert.equal(await page.locator('#editorTitle').inputValue(),'Keep this edit');
        assert.equal(await page.evaluate(()=>editor.hasDraft()),true);
        assert.equal(await page.evaluate(()=>confirmCalls),0,'Selecting the same element need not replace its form');
        await page.locator('#editorRelations button').first().click();
        assert.equal(await page.locator('#editorTitle').inputValue(),'Keep this edit');
        assert.equal(await page.locator('#editorRelationTarget').inputValue(),'');
        assert.equal(await page.evaluate(()=>confirmCalls),1,'Selecting a relation must guard replacement even for the same source element');
        await page.evaluate(()=>window.confirm=()=>true);await page.locator('#editorRelations button').first().click();
        assert.equal(await page.locator('#editorTitle').inputValue(),'First');
        assert.equal(await page.locator('#editorRelationTarget').inputValue(),'two');
        assert.equal(await page.evaluate(()=>editor.hasDraft()),false,'Selecting existing relation values is not an unaccepted edit');
    }finally{await page.close();}
});
