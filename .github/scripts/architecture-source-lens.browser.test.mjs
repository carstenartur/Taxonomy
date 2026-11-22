import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';
import { architectureLensMarkup } from './architecture-lens-fixture.mjs';

const resources=process.env.TAXONOMY_TEST_RESOURCE_ROOT||path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async()=>{
    browser=await chromium.launch({headless:true,args:['--no-sandbox','--disable-dev-shm-usage'],
        ...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{})});
});
after(async()=>{await browser?.close();});

test('a candidate in a copy named __shared__ is reviewed with that exact source',async()=>{
    const page=await browser.newPage();const errors=[];page.on('pageerror',error=>errors.push(error.message));
    try {
        const { panel, controls } = await architectureLensMarkup(resources, 'sourceLens');
        await page.setContent(controls + panel);
        await page.evaluate(()=>{
            const source={kind:'snapshot',repositoryId:'repo',workspaceScopeKey:'__shared__',workspaceId:'__shared__',branch:'draft',projectId:1,
                requirementId:2,requirementVersionId:3,snapshotId:'saved-source'};
            const data={elements:[{id:'arch-a'},{id:'arch-b'},{id:'arch-c'}]};
            window.TaxonomyI18n={t:(key,...args)=>[key,...args].join(' '),getLocale:()=> 'en'};
            window.TaxonomyArchitectureWorkspace={getSource:()=>source,getData:()=>data,
                getDiagram:()=>null,select(id){window.located=id;},open(kind,values){window.handoff={kind,values};}};
            window.TaxonomyApiClient={request:async(url,options,policy)=>{
                const request=JSON.parse(options.body);
                window.focusPins={query:new URL(url,'https://example.test').searchParams.get('workspaceId'),
                    header:options.headers['X-Taxonomy-Workspace-Id'],transport:policy.pinnedWorkspaceId,body:request.source.workspaceId};
                return{json:async()=>({source:request.source,sourceId:'arch-a',sourceHash:'a'.repeat(64),method:'LOCAL_TEXT_UNASSESSED',
                    outcome:'LOCAL_CANDIDATES',modelCalls:0,candidateCount:2,suggestions:[
                        {id:'first',sourceId:'arch-a',targetId:'arch-b',relationType:'SUPPORTS',rationale:'First hypothesis',evidence:[],properties:{}},
                        {id:'second',sourceId:'arch-c',targetId:'arch-a',relationType:'SUPPORTS',rationale:'Second reviewed hypothesis',
                            evidence:[{elementId:'arch-c',field:'title',quote:'Candidate source'},{elementId:'arch-a',field:'title',quote:'Investigation subject'}],properties:{}}
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
        assert.equal(await page.locator('#sourceLensEnabled').isChecked(),false);
        await page.locator('#sourceLensInspect').click();
        await page.waitForFunction(()=>document.querySelectorAll('#sourceLensSuggestions button').length===2);
        assert.match(await page.locator('#sourceLensSuggestions button').nth(1).innerText(), /Candidate source .* Investigation subject/);
        await page.locator('#sourceLensSuggestions button').nth(1).click();
        assert.equal(await page.evaluate(()=>window.handoff),undefined,'Review alone cannot open the editor');
        assert.deepEqual(await page.evaluate(()=>window.focusPins),
            {query:'__shared__',header:'__shared__',transport:'__shared__',body:'__shared__'});
        assert.match(await page.locator('#sourceLensReview').textContent(),/Second reviewed hypothesis/);
        const actions=await page.locator('#sourceLensReview [data-focus-action]').evaluateAll(buttons=>
            buttons.map(button=>button.dataset.focusAction));
        assert.deepEqual(actions,['dismiss','locate']);
        await page.locator('#sourceLensReview [data-focus-action="locate"]').click();
        assert.equal(await page.evaluate(()=>window.located),'arch-c');
        assert.equal(await page.evaluate(()=>window.handoff),undefined);
        assert.deepEqual(errors,[]);
    } finally {await page.close();}
});

async function workingPanel(page) {
    const { panel, controls } = await architectureLensMarkup(resources, 'editorLens');
    await page.setContent(controls + panel);
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
    await page.locator('#editorLensSettings > summary').click();
    await page.locator('#editorLensUseAi').uncheck();
    await page.locator('#editorLensEnabled').check();
    await page.locator('#editorLensInspect').click();
    await page.evaluate(() => investigations[0].reject(new Error('Fixture failure')));
    const retry = page.locator('#editorLensStates li.failed button');
    await retry.waitFor();
    return retry;
}

test('a working investigation previews only the relationships explicitly selected', async () => {
    const page = await browser.newPage({viewport:{width:390,height:850}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    try {
        const fragment = await readFile(path.join(resources, 'templates/fragments/architecture-source-lens.html'), 'utf8');
        const panel = fragment.replace(/th:id="\$\{idPrefix(?: \+ '([^']+)')?\}"/g, (_, suffix) => `id="editorLens${suffix || ''}"`);
        await page.setContent(`<section class="relationship-review">${panel}</section>`);
        await page.addStyleTag({path:path.join(resources, 'static/css/architecture-editor.css')});
        await page.addScriptTag({path:path.join(resources, 'static/js/architecture-focus.js')});
        await page.evaluate(() => {
            const root = document.getElementById('editorLens');
            const context = {repositoryId:'repo',workspaceScopeKey:'copy',branch:'draft',revision:1,commit:null,actor:'tester',writeMode:'PRIVATE_WORKSPACE'};
            const names = new Map([['source','Training application'],['a','User service'],['b','Content repository'],['c','Support team']]);
            const focus = ArchitectureFocusPanel({root, t:(key,...args)=>key === 'mermaid.relation.supports' ? 'unterstützt' : [key,...args].join(' '),
                request: async () => ({context,sourceId:'source',method:'LOCAL_TEXT_UNASSESSED',outcome:'UNASSESSED',modelCalls:0,suggestions:['a','b','c'].map(id => ({
                    id, sourceId:'source',targetId:id,relationType:'SUPPORTS',rationale:`Evidence ${id}`,properties:{'x-focus-evidence':id}
                }))}), elementNames:() => names, present() {}, accept() {}, undo() {}, canUndo:() => false, locate() {},
                acceptSelection:items=>{window.reviewedRelationships=items.map(item=>[item.targetId,item.properties['x-focus-evidence']]);}
            });
            focus.setContext(context,true);
            focus.select('source');
        });
        await page.locator('#editorLensInspect').click();
        const candidates = page.locator('#editorLensSuggestions');
        await candidates.locator('button').last().waitFor();
        assert.match(await candidates.locator('button').first().innerText(), /Training application — unterstützt → User service/);
        assert.equal(await candidates.evaluate(el=>getComputedStyle(el).overflowY), 'visible');
        const checks = candidates.getByRole('checkbox');
        await checks.nth(0).check();
        await checks.nth(2).check();
        assert.ok(await checks.nth(0).isChecked());
        assert.ok(!await checks.nth(1).isChecked());
        assert.equal(await page.evaluate(()=>window.reviewedRelationships), undefined);
        await page.locator('#editorLensBatch').click();
        assert.deepEqual(await page.evaluate(()=>window.reviewedRelationships), [['a','a'],['c','c']]);
        assert.deepEqual(errors, []);
    } finally { await page.close(); }
});

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

test('the snapshot adoption link previews B after leaving an adopted working view A', async () => {
    const page = await browser.newPage();
    try {
        await page.route('http://lens.test/**', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><body></body>' }));
        await page.goto('http://lens.test/taxonomy/');
        const lensMarkup = await architectureLensMarkup(resources, 'sourceLens');
        const editorTemplate = await readFile(path.join(resources, 'templates/architecture-editor.html'), 'utf8');
        const workflowTemplate = await readFile(path.join(resources, 'templates/fragments/architecture-workflow.html'), 'utf8');
        await page.evaluate(({ lensMarkup, editorTemplate, workflowTemplate }) => {
            const editor = new DOMParser().parseFromString(editorTemplate, 'text/html');
            const workflow = new DOMParser().parseFromString(workflowTemplate, 'text/html');
            document.body.insertAdjacentHTML('beforeend', editor.getElementById('snapshotAdoption').outerHTML);
            document.body.insertAdjacentHTML('beforeend', workflow.getElementById('architectureWorkflow').outerHTML);
            document.body.insertAdjacentHTML('beforeend', lensMarkup.controls + lensMarkup.panel);
            window.TaxonomyI18n = { t: key => key, getLocale: () => 'en', resolveUrl: path => '/taxonomy' + path, getBasePath: () => '/taxonomy' };
        }, { lensMarkup, editorTemplate, workflowTemplate });
        for (const file of ['shared/architecture-workflow.js', 'shared/architecture-workspace.js', 'analysis-snapshot-adoption.js',
            'architecture-focus.js', 'shared/architecture-source-lens.js']) {
            await page.addScriptTag({ path: path.join(resources, 'static/js', file) });
        }
        await page.evaluate(async () => {
            const working = { kind: 'working', repositoryId: 'repo', workspaceId: 'copy', workspaceScopeKey: 'copy',
                branch: 'draft', revision: 4, viewId: 'analysis-A' };
            const snapshot = { kind: 'snapshot', repositoryId: 'repo', workspaceId: 'copy', workspaceScopeKey: 'copy',
                branch: 'draft', projectId: 7, requirementId: 8, requirementVersionId: 9, snapshotId: 'saved-B' };
            const data = { ...snapshot, branchName: snapshot.branch, elements: [{ id: 'a' }, { id: 'b' }],
                exportProvenance: { repositoryId: snapshot.repositoryId, requirementVersionId: snapshot.requirementVersionId } };
            const workingView = { mayEdit: true, document: { context: working }, model: { views: [{ id: 'analysis-A',
                extensions: { 'x-analysis-snapshot-id': 'saved-A', 'x-analysis-repository-id': 'repo',
                    'x-analysis-workspace-scope': 'copy', 'x-analysis-branch': 'draft', 'x-analysis-project-id': '7',
                    'x-analysis-requirement-id': '8', 'x-analysis-requirement-version-id': '6' } }] } };
            const { create, parseSource, urlFor } = TaxonomyArchitectureWorkspaceController;
            let selectedView;
            const adoption = AnalysisSnapshotAdoption({ t: key => key, selectedView: () => selectedView,
                metadata: () => ({}), report: error => { throw error; }, api: { previewAdoption: async request => {
                    window.previewedSource = request.source;
                    return { summary: { addedElements: [], reusedElements: [], addedRelations: [], reusedRelations: [],
                        conflicts: [], differences: [] }, fingerprint: 'pinned-preview' };
                } } });
            const workspace = create({ initial: working, defaults: { working, snapshot }, adapters: {
                working: { activate: async source => {
                    selectedView = source.viewId; adoption.update(workingView);
                    if (source.adoption === 'preview') await adoption.showPreview();
                } },
                snapshot: { getData: () => data }
            }, beforeChange(source) { history.replaceState(null, '', urlFor(new URL(location.href), source, '/taxonomy/')); } });
            window.ArchitectureSourceFocusApi = { suggest: async body => ({ source: body.source, sourceId: body.sourceId,
                sourceHash: 'a'.repeat(64), method: 'LOCAL_TEXT_UNASSESSED', outcome: 'LOCAL_CANDIDATES', modelCalls: 0,
                candidateCount: 1, suggestions: [{ id: 'retained-link', sourceId: 'a', targetId: 'b', relationType: 'SUPPORTS',
                    rationale: 'A source B hypothesis', evidence: [], properties: {} }] }) };
            await workspace.open('working');
            await workspace.open('snapshot');
            TaxonomyArchitectureWorkflow.showSnapshot(data, 'a');
            document.getElementById('workflow-adopt').addEventListener('click', event => {
                event.preventDefault();
                const target = parseSource(new URL(event.currentTarget.href));
                void workspace.open(target.kind, { source: target });
            });
            const lens = TaxonomyArchitectureSourceLens.create({ root: document.getElementById('sourceLens'), workspace, idPrefix: 'sourceLens' });
            await lens.activate(workspace.getSource()); lens.select('a');
        });
        await page.locator('#sourceLensInspect').click();
        await page.locator('#sourceLensReview').getByText('A source B hypothesis').waitFor();
        assert.equal(await page.locator('#sourceLensReview [data-focus-action="adopt"]').count(),0);
        await page.locator('#workflow-adopt').click();
        await page.locator('#adoptionSummary').waitFor();
        assert.equal(await page.locator('#adoptionAccept').isVisible(),true);
        const captured = await page.evaluate(() => window.previewedSource);
        assert.equal(captured.snapshotId, 'saved-B', 'The displayed snapshot owns adoption, even when the working editor previously retained view A');
        assert.equal(captured.requirementVersionId, 9);
        assert.equal(new URL(page.url()).searchParams.get('viewId'), null);
    } finally { await page.close(); }
});
