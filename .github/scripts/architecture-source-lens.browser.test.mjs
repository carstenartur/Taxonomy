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

async function workingPanel(page) {
    const template = await readFile(path.join(resources, 'templates/fragments/architecture-source-lens.html'), 'utf8');
    await page.setContent(template.replace(/th:id="\$\{idPrefix(?: \+ '([^']+)')?\}"/g, (_match, suffix) => `id="editorLens${suffix || ''}"`));
    await page.addScriptTag({ path: path.join(resources, 'static/js/architecture-focus.js') });
    await page.evaluate(() => {
        const root = document.getElementById('editorLens');
        root.hidden = false;
        window.investigations = [];
        window.workingLens = ArchitectureFocusPanel({ root, t: key => key, present() {},
            request: body => new Promise((resolve, reject) => investigations.push({ body, resolve, reject })),
            accept() {}, undo() {}, canUndo: () => false, locate() {} });
        workingLens.setContext({ repositoryId: 'repo', workspaceScopeKey: 'copy', branch: 'draft',
            revision: 1, commit: null, actor: 'tester', writeMode: 'PRIVATE_WORKSPACE' }, true);
        workingLens.select('arch-a');
    });
    await page.locator('#editorLensUseAi').uncheck();
    await page.locator('#editorLensEnabled').check();
    await page.locator('#editorLensInspect').click();
    await page.evaluate(() => investigations[0].reject(new Error('Fixture failure')));
    const retry = page.locator('#editorLensStates li.failed button');
    await retry.waitFor();
    return retry;
}

test('paused and fully reserved working lens controls cannot start investigations', async () => {
    const page = await browser.newPage();
    try {
        const retry = await workingPanel(page);
        await page.locator('#editorLensPause').click();
        const pausedRetryDisabled = await retry.isDisabled();
        await page.locator('#editorLensPause').click();
        await page.locator('#editorLensBudget').fill('3');
        await page.locator('#editorLensBudget').press('Tab');
        await page.evaluate(() => workingLens.select('arch-b'));
        await page.locator('#editorLensInspect').click();
        await page.evaluate(() => workingLens.select('arch-c'));
        await page.locator('#editorLensInspect').click();
        await page.locator('#editorLensStates li.queued').waitFor();
        await page.evaluate(() => workingLens.select('arch-a'));
        assert.deepEqual({ pausedRetryDisabled, reservedRetryDisabled: await retry.isDisabled(),
            reservedInspectDisabled: await page.locator('#editorLensInspect').isDisabled() }, {
            pausedRetryDisabled: true, reservedRetryDisabled: true, reservedInspectDisabled: true
        }, 'Working lens controls must respect both pause and reserved queue slots');
        assert.equal(await page.evaluate(() => investigations.length), 2);
    } finally { await page.close(); }
});

test('working lens status updates preserve keyboard access to Retry', async () => {
    const page = await browser.newPage();
    try {
        const retry = await workingPanel(page);
        await page.evaluate(() => workingLens.select('arch-b'));
        await page.locator('#editorLensInspect').click();
        await retry.focus();
        await page.evaluate(() => {
            const request = investigations[1];
            request.resolve({ context: request.body.context, sourceId: 'arch-b', sourceHash: 'a'.repeat(64),
                method: 'LOCAL_TEXT_UNASSESSED', outcome: 'NO_CANDIDATES', modelCalls: 0, candidateCount: 0, suggestions: [] });
        });
        await page.locator('#editorLensStates li.noCandidates').waitFor();
        assert.equal(await retry.evaluate(button => document.activeElement === button), true, 'An unrelated status update must retain keyboard focus');
        await page.keyboard.press('Enter');
        assert.equal(await page.evaluate(() => investigations[2]?.body.sourceId), 'arch-a');
        assert.equal(await page.evaluate(() => document.activeElement.id), 'editorLensPause', 'Retry moves focus to an available control when it disappears');
    } finally { await page.close(); }
});

test('reviewing snapshot B through the lens previews B after leaving an adopted working view A', async () => {
    const page = await browser.newPage();
    try {
        await page.route('http://lens.test/**', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><body></body>' }));
        await page.goto('http://lens.test/taxonomy/');
        const lensTemplate = await readFile(path.join(resources, 'templates/fragments/architecture-source-lens.html'), 'utf8');
        const editorTemplate = await readFile(path.join(resources, 'templates/architecture-editor.html'), 'utf8');
        await page.evaluate(({ lensTemplate, editorTemplate }) => {
            const editor = new DOMParser().parseFromString(editorTemplate, 'text/html');
            document.body.insertAdjacentHTML('beforeend', editor.getElementById('snapshotAdoption').outerHTML);
            document.body.insertAdjacentHTML('beforeend', lensTemplate.replace(/th:id="\$\{idPrefix(?: \+ '([^']+)')?\}"/g,
                (_match, suffix) => `id="sourceLens${suffix || ''}"`));
            window.TaxonomyI18n = { t: key => key, getLocale: () => 'en', resolveUrl: path => '/taxonomy' + path, getBasePath: () => '/taxonomy' };
        }, { lensTemplate, editorTemplate });
        for (const file of ['shared/architecture-workflow.js', 'shared/architecture-workspace.js', 'analysis-snapshot-adoption.js',
            'architecture-focus.js', 'shared/architecture-source-lens.js']) {
            await page.addScriptTag({ path: path.join(resources, 'static/js', file) });
        }
        await page.evaluate(async () => {
            const working = { kind: 'working', repositoryId: 'repo', workspaceId: 'copy', workspaceScopeKey: 'copy',
                branch: 'draft', revision: 4, viewId: 'analysis-A' };
            const snapshot = { kind: 'snapshot', repositoryId: 'repo', workspaceId: 'copy', workspaceScopeKey: 'copy',
                branch: 'draft', projectId: 7, requirementId: 8, requirementVersionId: 9, snapshotId: 'saved-B' };
            const data = { elements: [{ id: 'a' }, { id: 'b' }] };
            const workingView = { mayEdit: true, document: { context: working }, model: { views: [{ id: 'analysis-A',
                extensions: { 'x-analysis-snapshot-id': 'saved-A', 'x-analysis-repository-id': 'repo',
                    'x-analysis-workspace-scope': 'copy', 'x-analysis-branch': 'draft', 'x-analysis-project-id': '7',
                    'x-analysis-requirement-id': '8', 'x-analysis-requirement-version-id': '6' } }] } };
            const { create, urlFor } = TaxonomyArchitectureWorkspaceController;
            let selectedView;
            const adoption = AnalysisSnapshotAdoption({ t: key => key, selectedView: () => selectedView,
                metadata: () => ({}), report: error => { throw error; }, api: { previewAdoption: async request => {
                    window.previewedSource = request.source;
                    return { summary: { addedElements: [], reusedElements: [], addedRelations: [], reusedRelations: [],
                        conflicts: [], differences: [] }, fingerprint: 'pinned-preview' };
                } } });
            const workspace = create({ initial: working, defaults: { working, snapshot }, adapters: {
                working: { activate: source => { selectedView = source.viewId; adoption.update(workingView); } },
                snapshot: { getData: () => data }
            }, beforeChange(source) { history.replaceState(null, '', urlFor(new URL(location.href), source, '/taxonomy/')); } });
            window.ArchitectureSourceFocusApi = { suggest: async body => ({ source: body.source, sourceId: body.sourceId,
                sourceHash: 'a'.repeat(64), method: 'LOCAL_TEXT_UNASSESSED', outcome: 'LOCAL_CANDIDATES', modelCalls: 0,
                candidateCount: 1, suggestions: [{ id: 'retained-link', sourceId: 'a', targetId: 'b', relationType: 'SUPPORTS',
                    rationale: 'A source B hypothesis', evidence: [], properties: {} }] }) };
            await workspace.open('working');
            await workspace.open('snapshot');
            const lens = TaxonomyArchitectureSourceLens.create({ root: document.getElementById('sourceLens'), workspace, idPrefix: 'sourceLens' });
            await lens.activate(workspace.getSource()); lens.select('a');
        });
        await page.locator('#sourceLensEnabled').check();
        await page.locator('#sourceLensInspect').click();
        await page.locator('#sourceLensReview [data-focus-action="adopt"]').click();
        await page.locator('#adoptionPreview').click();
        const captured = await page.evaluate(() => window.previewedSource);
        assert.equal(captured.snapshotId, 'saved-B', 'The explicitly reviewed snapshot owns adoption, even when the working editor retained view A');
        assert.equal(captured.requirementVersionId, 9);
        assert.equal(new URL(page.url()).searchParams.get('viewId'), null);
    } finally { await page.close(); }
});
