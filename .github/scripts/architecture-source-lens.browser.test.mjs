import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';

const resources=process.env.TAXONOMY_TEST_RESOURCE_ROOT||path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async()=>{
    browser=await chromium.launch({headless:true,args:['--no-sandbox','--disable-dev-shm-usage'],
        ...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{})});
});
after(async()=>{await browser?.close();});

test('a proposal in a copy named __shared__ keeps the exact source through review and adoption',async()=>{
    const page=await browser.newPage();const errors=[];page.on('pageerror',error=>errors.push(error.message));
    try {
        const template=await readFile(path.join(resources,'templates/fragments/architecture-source-lens.html'),'utf8');
        // Render this fragment's parameterized IDs; the actual production controls and handlers remain intact.
        const markup=template.replace(/th:id="\$\{idPrefix(?: \+ '([^']+)')?\}"/g,(_match,suffix)=>`id="sourceLens${suffix||''}"`);
        await page.setContent(markup);
        await page.evaluate(()=>{
            const source={kind:'snapshot',repositoryId:'repo',workspaceScopeKey:'__shared__',workspaceId:'__shared__',branch:'draft',projectId:1,
                requirementId:2,requirementVersionId:3,snapshotId:'saved-source'};
            const data={elements:[{id:'arch-a'},{id:'arch-b'},{id:'arch-c'}]};
            window.TaxonomyI18n={t:(key,...args)=>[key,...args].join(' '),getLocale:()=> 'en'};
            window.TaxonomyArchitectureWorkspace={getSource:()=>source,getData:()=>data,
                getDiagram:()=>null,select(){},open(kind,values){window.handoff={kind,values};}};
            window.TaxonomyApiClient={request:async(url,options,policy)=>{
                const request=JSON.parse(options.body);
                window.focusPins={query:new URL(url,'https://example.test').searchParams.get('workspaceId'),
                    header:options.headers['X-Taxonomy-Workspace-Id'],transport:policy.pinnedWorkspaceId,body:request.source.workspaceId};
                return{json:async()=>({source:request.source,sourceId:'arch-a',sourceHash:'a'.repeat(64),method:'LOCAL_TEXT_UNASSESSED',
                    outcome:'LOCAL_CANDIDATES',modelCalls:0,candidateCount:2,suggestions:[
                        {id:'first',sourceId:'arch-a',targetId:'arch-b',relationType:'SUPPORTS',rationale:'First hypothesis',evidence:[],properties:{}},
                        {id:'second',sourceId:'arch-a',targetId:'arch-c',relationType:'SUPPORTS',rationale:'Second reviewed hypothesis',evidence:[],properties:{}}
                    ]})};
            }};
        });
        for(const file of ['architecture-focus.js','api/architecture-source-focus-api.js','shared/architecture-source-lens.js'])
            await page.addScriptTag({path:path.join(resources,'static/js',file)});
        await page.evaluate(async()=>{
            window.sourceLens=TaxonomyArchitectureSourceLens.create({root:document.getElementById('sourceLens'),
                workspace:TaxonomyArchitectureWorkspace,idPrefix:'sourceLens'});
            await sourceLens.activate(TaxonomyArchitectureWorkspace.getSource());sourceLens.select('arch-a');
        });
        await page.locator('#sourceLensEnabled').check();await page.locator('#sourceLensInspect').click();
        await page.waitForFunction(()=>document.querySelectorAll('#sourceLensSuggestions button').length===2);
        await page.locator('#sourceLensSuggestions button').nth(1).click();
        assert.equal(await page.evaluate(()=>window.handoff),undefined,'Review alone cannot open the editor');
        assert.deepEqual(await page.evaluate(()=>window.focusPins),
            {query:'__shared__',header:'__shared__',transport:'__shared__',body:'__shared__'});
        assert.match(await page.locator('#sourceLensReview').textContent(),/Second reviewed hypothesis/);
        assert.equal(await page.locator('#sourceLensReview [data-focus-action="accept"]').count(),0);
        await page.locator('#sourceLensReview [data-focus-action="adopt"]').click();
        const handoff=await page.evaluate(()=>window.handoff);
        assert.equal(handoff.kind,'working');
        assert.equal(handoff.values.sourceWorkspaceId,'__shared__');
        assert.equal(handoff.values.sourceSnapshotId,'saved-source');
        assert.deepEqual(errors,[]);
    } finally {await page.close();}
});
