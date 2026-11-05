import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';
const resources=process.env.TAXONOMY_TEST_RESOURCE_ROOT||path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{}),args:['--no-sandbox','--disable-dev-shm-usage']});});
after(async()=>{await browser?.close();});
async function fixture(standaloneUrl=null){
    const page=await browser.newPage();
    if(standaloneUrl){
        await page.route('http://editor.test/**',route=>route.fulfill({body:'',contentType:'text/html'}));
        await page.goto(standaloneUrl);
    }
    const html=await readFile(path.join(resources,'templates/architecture-editor.html'),'utf8');
    const body=await page.evaluate(template=>{
        const parsed=new DOMParser().parseFromString(template,'text/html');
        parsed.querySelectorAll('script,link').forEach(node=>node.remove());
        return parsed.body.innerHTML;
    },html);
    await page.setContent(`${standaloneUrl?'':'<div id="architectureWorkspace"></div>'}<section id="architectureWorkingSource">${body}</section>`);
    await page.evaluate(()=>{
        window.loaded=[];window.published=[];window.navigation=[];window.focusCalls=[];window.diagramActivity=[];window.rendered=[];
        window.TaxonomyI18n={ready:()=>Promise.resolve(),t:key=>key};
        window.ArchitectureEditorRenderer=()=>({render(...args){rendered.push(args);},select(){},presentFocus(){},fit(){},
            focus(...args){focusCalls.push(args.map(value=>typeof value));},zoom(){},setActive(value){diagramActivity.push(value);}});
        window.TaxonomyPlanningEditor={create:()=>({render(){},setEnabled(){}})};
        window.ArchitectureFocusPanel=()=>({observe(){},review(){},setLocked(){},setContext(){},select(){}});
        window.AnalysisSnapshotAdoption=()=>({setLocked(){},update(){},getSource:()=>null});
        window.TaxonomyAppChrome={updateContext:value=>published.push(value)};
        window.ArchitectureEditorApi={load:async(scope,revision,_signal,layout,layoutRevision,viewId)=>{
            loaded.push({scope,revision,layout,layoutRevision,viewId});
            return {document:{context:{repositoryId:scope.repositoryId||'repo',workspaceScopeKey:scope.workspaceScopeKey||'private-a',branch:scope.branch||'draft/a',actor:'alice',revision:revision||'7',commit:null},source:'WORKSPACE_REVISION',projectionState:'READY',dsl:'',history:[],versions:[]},
                mayEdit:true,model:{elements:[],relations:[],evidence:[],packages:[],views:[]},scene:{nodes:[],edges:[]},searchIndex:[],history:[],versions:[],
                schema:{elementTypes:['capability'],relationTypes:[],relationStatuses:['proposed'],layoutStrategy:layout||'stable',layoutRevision:layoutRevision??0}};
        }};
        history.replaceState=(_state,_title,url)=>navigation.push({kind:'replace',url:String(url)});history.pushState=(_state,_title,url)=>navigation.push({kind:'push',url:String(url)});
    });
    await page.addScriptTag({path:path.join(resources,'static/js/architecture-editor.js')});
    return page;
}
test('the working editor supplies its authoritative taxonomy type mapping to the shared renderer',async()=>{
    const page=await fixture();try{
        const actual=await page.evaluate(async()=>{
            const load=ArchitectureEditorApi.load;
            ArchitectureEditorApi.load=async(...args)=>{const result=await load(...args);result.schema.typeRoots={Capability:'CP',System:'SY'};return result;};
            window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));
            await editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a'});
            return rendered.at(-1)[2];
        });
        assert.deepEqual(actual,{Capability:'CP',System:'SY'});
    }finally{await page.close();}
});
for(const workspace of ['private-a',''])test(`standalone editor keeps source pins and rejects URL object keys (${workspace||'shared'})`,async()=>{
    const pins={repositoryId:'repo',workspaceId:workspace,workspaceScopeKey:workspace||'__shared__',branch:'draft/a',
        projectId:'42',requirementId:'7',requirementVersionId:'9',snapshotId:'snapshot-A',revision:'17',
        viewId:'view-A',layout:'stable',layoutRevision:'3',sourceRepositoryId:'source-repo',
        sourceWorkspaceId:workspace?'':'source-private',sourceWorkspaceScopeKey:workspace?'__shared__':'source-private',sourceBranch:'frozen/source',
        sourceProjectId:'52',sourceRequirementId:'8',sourceRequirementVersionId:'11',sourceSnapshotId:'source-A',adoption:'preview'};
    const query=new URLSearchParams(pins);
    for(const key of ['__proto__','constructor','prototype','hasOwnProperty','mayEdit','unexpected'])query.set(key,'untrusted');
    query.set('kind','snapshot');query.set('lang','de');
    const page=await fixture('http://editor.test/taxonomy/architecture/editor?'+query);
    try{
        await page.waitForFunction(()=>document.body.__architectureEditor?.getData());
        const actual=await page.evaluate(()=>document.body.__architectureEditor.getSource());
        for(const [key,value]of Object.entries(pins))assert.equal(actual[key],value,key);
        assert.equal(actual.kind,'working');
        for(const key of ['__proto__','constructor','prototype','hasOwnProperty','mayEdit','unexpected'])
            assert.equal(Object.hasOwn(actual,key),false,'Untrusted URL property escaped into source: '+key);
        assert.deepEqual(await page.evaluate(()=>loaded[0]),{scope:{repositoryId:'repo',workspaceScopeKey:workspace||'__shared__',branch:'draft/a'},revision:'17',layout:'stable',layoutRevision:'3',viewId:'view-A'});
        assert.equal(new URL(page.url()).searchParams.get('lang'),'de');
        await page.evaluate(()=>window.dispatchEvent(new PopStateEvent('popstate')));
        assert.equal(await page.evaluate(()=>Object.hasOwn(document.body.__architectureEditor.getSource(),'constructor')),false,'History replay must keep the same safe source boundary');
    }finally{await page.close();}
});
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

test('quick undo previews the latest personal operation and protects an unaccepted draft',async()=>{
    const page=await fixture();try{
        await page.evaluate(async()=>{
            const original=ArchitectureEditorApi.load;
            crypto.randomUUID=()=> '123e4567-e89b-42d3-a456-426614174000';
            window.quickPreviews=[];window.quickCommands=[];
            ArchitectureEditorApi.load=async(...args)=>{
                const view=await original(...args);
                view.document.history=[{operationId:'operation-7',kind:'CREATE_ELEMENT',actor:'alice',revision:7,occurredAt:'now',rationale:'Create capability'}];
                view.document.historyTargets={undo:view.document.history[0],redo:null};
                return view;
            };
            ArchitectureEditorApi.preview=async command=>{
                quickPreviews.push(command);
                return {context:command.context,change:{changes:[{id:'one',before:'before',after:'after'}]}};
            };
            ArchitectureEditorApi.execute=async command=>{quickCommands.push(command);};
            window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));
            await editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a'});
            window.confirm=()=>false;
        });
        assert.equal(await page.locator('#editorQuickUndo').isEnabled(),true);
        assert.equal(await page.locator('#editorQuickRedo').isDisabled(),true);
        await page.locator('#editorTitle').fill('Unaccepted draft');
        await page.locator('#editorQuickUndo').click();
        assert.equal(await page.evaluate(()=>quickPreviews.length),0);
        assert.equal(await page.locator('#editorTitle').inputValue(),'Unaccepted draft');
        await page.evaluate(()=>window.confirm=()=>true);
        await page.locator('#editorQuickUndo').click();
        await page.locator('#editorPreviewDialog[open]').waitFor();
        const previews=await page.evaluate(()=>quickPreviews);
        assert.equal(previews.length,1);
        assert.equal(previews[0].kind,'UNDO');
        assert.equal(previews[0].targetOperationId,'operation-7');
        assert.ok(previews[0].metadata.rationale);
        assert.equal(await page.evaluate(()=>quickCommands.length),0,'Preview must not change the architecture');
        await page.locator('#editorCancel').click();
        assert.equal(await page.locator('#editorTitle').inputValue(),'Unaccepted draft');
    }finally{await page.close();}
});

for(const removed of [false,true])test(`quick undo conflict and canceled re-preview retain a package draft (${removed?'deleted':'retained'} package)`,async()=>{
    const page=await fixture();try{
        await page.evaluate(async removed=>{
            const original=ArchitectureEditorApi.load;
            crypto.randomUUID=()=> '123e4567-e89b-42d3-a456-426614174000';
            window.packageReads=0;window.packagePreviews=[];window.packageCommands=[];
            ArchitectureEditorApi.load=async(...args)=>{
                const view=await original(...args);
                view.document.context.revision=++packageReads===1?7:8;
                view.document.history=[{operationId:'operation-7',kind:'CREATE_ELEMENT',actor:'alice',revision:7,occurredAt:'now',rationale:'Create capability'}];
                view.document.historyTargets={undo:view.document.history[0],redo:null};
                view.model.packages=packageReads===1 || !removed?[
                    {id:'package-a',title:'Original package',description:'Original description',parentId:'parent-a',position:1},
                    {id:'parent-a',title:'Original parent',description:'',parentId:null,position:0}
                ]:[];
                return view;
            };
            ArchitectureEditorApi.preview=async command=>{
                packagePreviews.push(command);
                if(packagePreviews.length===1)throw Object.assign(new Error('Revision changed'),{status:412});
                return {context:command.context,change:{changes:[{id:'one',before:'before',after:'after'}]}};
            };
            ArchitectureEditorApi.execute=async command=>{packageCommands.push(command);};
            window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));
            await editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a'});
            window.confirm=()=>true;
        },removed);
        await page.locator('#editorPackage').selectOption('package-a');
        await page.locator('#editorPackageTitle').fill('Unaccepted package name');
        await page.locator('#editorPackageDescription').fill('Unaccepted package description');
        await page.locator('#editorPackageParent').selectOption('parent-a');
        await page.locator('#editorPackagePosition').fill('4');
        await page.locator('#editorQuickUndo').click();
        await page.locator('#editorReapply:visible').waitFor();
        await page.locator('#editorReapply').click();
        await page.waitForFunction(()=>packagePreviews.length===2 && !document.getElementById('editorAccept').disabled);
        await page.locator('#editorCancel').click();
        assert.deepEqual(await page.evaluate(()=>Object.fromEntries(['editorPackage','editorPackageTitle','editorPackageDescription','editorPackageParent','editorPackagePosition']
            .map(id=>[id,document.getElementById(id).value]))),{
            editorPackage:'package-a',editorPackageTitle:'Unaccepted package name',
            editorPackageDescription:'Unaccepted package description',editorPackageParent:'parent-a',editorPackagePosition:'4'});
        if(removed){
            assert.equal(await page.locator('#editorPackage option:checked').textContent(),'editor.packageUnavailable');
            assert.equal(await page.locator('#editorPackageParent option:checked').textContent(),'editor.packageUnavailable');
        }
        assert.equal(await page.evaluate(()=>editor.hasDraft()),true,'The retained package fields must still be treated as an unaccepted draft');
        assert.equal(await page.evaluate(()=>packageCommands.length),0,'Cancel must not accept the preview');
    }finally{await page.close();}
});

test('editor Focus toolbar keeps the selected element and dirty form and sends no DOM event as an ID',async()=>{
    const page=await fixture();try{
        await page.evaluate(async()=>{
            const original=ArchitectureEditorApi.load;
            ArchitectureEditorApi.exportUrl=()=>'/exact-source-export';
            ArchitectureEditorApi.load=async(...args)=>{
                const view=await original(...args);
                view.model.elements=[{id:'one',title:'First',type:'capability',extensions:{}}];
                view.scene.nodes=[{id:'one',label:'First',x:10,y:10,width:230,height:86}];
                view.searchIndex=[{id:'one',text:'one first'}];
                return view;
            };
            window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));
            await editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a'});
            editor.select('one');window.focusCalls=[];window.discardPrompts=0;
            window.confirm=()=>{discardPrompts++;return false;};
        });
        await page.locator('#editorTitle').fill('Keep this draft');
        await page.locator('#editorFocus').click();
        assert.deepEqual(await page.evaluate(()=>focusCalls),[[]]);
        assert.equal(await page.evaluate(()=>editor.getSource().element),'one');
        assert.equal(await page.locator('#editorTitle').inputValue(),'Keep this draft');
        assert.equal(await page.evaluate(()=>discardPrompts),0);
        await page.evaluate(async()=>{editor.suspend();await editor.activate(editor.getSource());});
        assert.deepEqual(await page.evaluate(()=>diagramActivity),[true,false,true]);
    }finally{await page.close();}
});

test('checkpoint stays visibly busy until the confirmed stand finishes loading',async()=>{
    const page=await fixture();try{
        await page.evaluate(async()=>{
            const original=ArchitectureEditorApi.load;
            let reads=0;
            // The about:blank fixture has no secure-context UUID API; the command ID is incidental here.
            crypto.randomUUID=()=> '123e4567-e89b-42d3-a456-426614174000';
            window.beforeCheckpoint='a'.repeat(40);window.afterCheckpoint='b'.repeat(40);
            ArchitectureEditorApi.load=async(...args)=>{
                const next=await original(...args);
                next.document.context.commit=++reads===1?beforeCheckpoint:afterCheckpoint;
                next.document.versions=[{commitId:next.document.context.commit,author:'alice',message:'Checkpoint'}];
                if(reads>1)await new Promise(resolve=>{window.releaseCheckpointLoad=resolve;});
                return next;
            };
            ArchitectureEditorApi.checkpoint=()=>new Promise(resolve=>{window.acceptCheckpoint=()=>resolve({commitId:afterCheckpoint});});
            window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));
            await editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a'});
            window.historicalSource={...editor.getSource(),commit:beforeCheckpoint,revision:null};
        });
        await page.locator('#editorRationale').fill('Retain this architecture checkpoint');
        await page.locator('#editorCheckpoint').click();
        const pending=await page.evaluate(()=>({busy:document.getElementById('architectureEditor').getAttribute('aria-busy'),
            disabled:document.getElementById('editorCheckpoint').disabled,canLeave:editor.canLeave(historicalSource,{retain:false})}));
        assert.equal(pending.disabled,true,'The checkpoint request must be in progress: '+JSON.stringify(pending));
        assert.equal(pending.canLeave,false,'A running checkpoint must keep its navigation guard');
        assert.equal(pending.busy,'true','The surface must not claim readiness while the checkpoint blocks navigation');
        await page.evaluate(()=>acceptCheckpoint());
        await page.waitForFunction(()=>typeof releaseCheckpointLoad==='function');
        assert.equal(await page.locator('#architectureEditor').getAttribute('aria-busy'),'true','The accepted response is not yet the loaded stand');
        assert.equal(await page.evaluate(()=>editor.getData().document.context.commit),'a'.repeat(40));
        await page.evaluate(()=>releaseCheckpointLoad());
        await page.waitForFunction(()=>document.getElementById('architectureEditor').getAttribute('aria-busy')==='false');
        assert.equal(await page.evaluate(()=>editor.getData().document.context.commit),'b'.repeat(40));
        assert.equal(await page.locator('#editorCheckpoint').isEnabled(),true);
        assert.equal(await page.evaluate(()=>editor.canLeave(historicalSource,{retain:false})),true,'The confirmed loaded stand permits historical navigation');
    }finally{await page.close();}
});

test('an unpinned current working source reads the latest head while an exact retained revision preserves its draft',async()=>{
    const page=await fixture();try{
        await page.evaluate(async()=>{
            const original=ArchitectureEditorApi.load;
            window.headRevision='7';
            ArchitectureEditorApi.load=async(...args)=>{
                const view=await original(...args);
                if(args[1]===undefined || args[1]===null)view.document.context.revision=headRevision;
                return view;
            };
            window.editor=TaxonomyArchitectureEditor.mount(document.getElementById('architectureWorkingSource'));
            await editor.activate({kind:'working',repositoryId:'repo',workspaceScopeKey:'private-a',branch:'draft/a',revision:'7'});
            window.retainedSource=editor.getSource();window.headRevision='8';
        });
        await page.locator('#editorTitle').fill('Retain this unaccepted draft');
        await page.evaluate(async()=>{editor.suspend();await editor.activate(retainedSource);});
        assert.equal(await page.evaluate(()=>loaded.length),1,'An exact source return must retain its mounted form');
        assert.equal(await page.locator('#editorTitle').inputValue(),'Retain this unaccepted draft');
        await page.evaluate(()=>{
            window.currentSource={...retainedSource};delete currentSource.revision;delete currentSource.commit;
            window.confirm=()=>false;
        });
        assert.equal(await page.evaluate(()=>editor.canLeave(currentSource,{retain:false})),false,'Reading the current head must guard an unrelated form draft');
        assert.equal(await page.locator('#editorTitle').inputValue(),'Retain this unaccepted draft');
        await page.evaluate(async()=>{window.confirm=()=>true;if(editor.canLeave(currentSource,{retain:false}))await editor.activate(currentSource);});
        assert.equal(await page.evaluate(()=>loaded.length),2,'An unpinned current stand must perform a new authorized read');
        assert.equal(await page.evaluate(()=>editor.getSource().revision),'8');
        assert.equal(await page.evaluate(()=>loaded[1].revision==null),true);
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
