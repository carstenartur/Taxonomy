/** Shared SVG renderer acceptance using the packaged D3 dependency, with no API/provider calls. */
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { chromium } from 'playwright';
const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser, d3Source;
before(async () => {
    d3Source = process.env.TAXONOMY_TEST_D3_SCRIPT ? await readFile(process.env.TAXONOMY_TEST_D3_SCRIPT, 'utf8')
        : execFileSync('unzip', ['-p', path.join(process.env.MAVEN_REPO_LOCAL || path.join(os.homedir(), '.m2/repository'), 'org/webjars/npm/d3/7.9.0/d3-7.9.0.jar'), 'META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js'], { encoding: 'utf8' });
    browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}), args: ['--no-sandbox', '--disable-dev-shm-usage'] });
});
after(async () => { await browser?.close(); });
async function setSnapshotTemplate(page) {
    const template = await readFile(path.join(resources, 'templates/architecture-workbench.html'), 'utf8');
    const markup = await page.evaluate(html => {
        const parsed = new DOMParser().parseFromString(html, 'text/html');
        parsed.querySelectorAll('script,link').forEach(node => node.remove());
        return '<!DOCTYPE html>' + parsed.documentElement.outerHTML;
    }, template);
    await page.setContent(markup);
}
async function fixture(touch = false) {
    const page = await browser.newPage({ viewport: { width: touch ? 390 : 1200, height: 800 }, hasTouch: touch, reducedMotion: 'reduce' });
    page.errors = []; page.on('pageerror', error => page.errors.push(error.message));
    await page.setContent('<html><body><div id="map" style="width:100%;height:700px"></div><svg id="editorGraph" viewBox="0 0 1000 420" tabindex="0"></svg></body></html>');
    await page.addScriptTag({ content: d3Source });
    for (const css of ['taxonomy-impact-map.css', 'architecture-editor.css', 'shared/architecture-diagram.css']) await page.addStyleTag({ path: path.join(resources, 'static/css', css) });
    await page.addScriptTag({ path: path.join(resources, 'static/js/shared/architecture-diagram.js') });
    return page;
}
async function mountAnalysis(page) {
    await page.evaluate(() => { window.diagramSelections=[];document.addEventListener('taxonomy:architecture-selection',event=>diagramSelections.push(event.detail));window.TaxonomyGraph = { selectNode(id) { window.selectedAnalysis = id; } }; });
    await page.addScriptTag({ path: path.join(resources, 'static/js/shared/taxonomy-impact-map.js') });
    await page.evaluate(() => {
        window.mapState = TaxonomyImpactMap.render(document.getElementById('map'), [
            { nodeCode: 'A', title: 'Capability evidence', taxonomySheet: 'CP', relevance: 0.9, hopDistance: 0 },
            { nodeCode: 'B', title: 'Context service', taxonomySheet: 'CR', relevance: 0.5, hopDistance: 1 },
            { nodeCode: 'C', title: 'Other evidence', taxonomySheet: 'BP', relevance: 0.7, hopDistance: 0 }
        ], [{ sourceCode: 'A', targetCode: 'B', relationType: 'SUPPORTS', includedBecause: 'Fixture evidence' }], { anchorCodes: new Set(['A', 'C']) });
    });
    await page.waitForFunction(() => document.querySelector('.impact-map-workbench').dataset.fitState === 'complete');
}
async function appendFocusPanel(page, prefix) {
    const template = await readFile(path.join(resources, 'templates/fragments/architecture-source-lens.html'), 'utf8');
    await page.evaluate(({ template, prefix }) => {
        // Resolve only this fragment's parameterized IDs; retain its actual controls.
        const markup = template.replace(/th:id="\$\{idPrefix(?: \+ '([^']+)')?\}"/g,
            (_match, suffix) => `id="${prefix}${suffix || ''}"`);
        const parsed = new DOMParser().parseFromString(markup, 'text/html');
        document.body.append(parsed.querySelector('section'));
    }, { template, prefix });
    await page.addScriptTag({ path: path.join(resources, 'static/js/architecture-focus.js') });
}
async function attachSourceLens(page, source, enabled) {
    await appendFocusPanel(page, 'nativeSourceLens');
    for (const file of ['api/architecture-source-focus-api.js', 'shared/architecture-source-lens.js']) {
        await page.addScriptTag({ path: path.join(resources, 'static/js', file) });
    }
    await page.evaluate(async kind => {
        window.TaxonomyI18n ||= { t: key => key, getLocale: () => 'en' };
        window.nativeLensRequests = []; window.nativeLensSelections = []; window.nativeLensSaves = 0;
        window.nativeLensOpenCalls = [];
        // Keep the real source-pinning/request adapter; replace only its HTTP transport.
        window.TaxonomyApiClient = { request: async (url, options, policy) => {
            const body = JSON.parse(options.body);
            nativeLensRequests.push({ url, method: options.method, body,
                headers: Object.fromEntries(new Headers(options.headers)), retries: policy.retries,
                pinnedWorkspaceId: policy.pinnedWorkspaceId });
            return { json: async () => ({ source: body.source, sourceId: body.sourceId,
                sourceHash: 'a'.repeat(64), method: 'LOCAL_TEXT_UNASSESSED', outcome: 'NO_CANDIDATES',
                modelCalls: 0, candidateCount: 0, suggestions: [] }) };
        } };
        const analysisSource = { kind: 'analysis', repositoryId: 'repository-A',
            workspaceScopeKey: 'private-A', workspaceId: 'private-A', branch: 'review/A' };
        const analysisEvidence = { includedElements: [
            { nodeCode: 'A', title: 'Capability evidence' }, { nodeCode: 'B', title: 'Context service' },
            { nodeCode: 'C', title: 'Other evidence' }
        ], includedRelationships: [{ sourceCode: 'A', targetCode: 'B', relationType: 'SUPPORTS' }] };
        window.TaxonomyAnalysisSession = {
            state: () => ({ ready: true, conflict: false, restoring: false, workspaceId: 'private-A', version: 4 }),
            saveNow: async () => { nativeLensSaves++; return true; }
        };
        const workspace = {
            isActive: () => true,
            getSource: () => kind === 'analysis' ? analysisSource : snapshotAdapter.getSource(),
            getData: () => kind === 'analysis' ? analysisEvidence : snapshotAdapter.getData(),
            getDiagram: () => kind === 'analysis' ? mapState.diagram : snapshotAdapter.getDiagram(),
            select: id => workspace.getDiagram().select(id),
            rememberSelection: id => { if (kind === 'analysis') analysisSource.element = id; },
            open: (...args) => { nativeLensOpenCalls.push(args); throw new Error('Double-click cannot enter an editor'); }
        };
        window.nativeSourceLens = TaxonomyArchitectureSourceLens.create({
            root: document.getElementById('nativeSourceLens'), workspace, idPrefix: 'nativeSourceLens'
        });
        // This is the production workspace selection bridge, including the source fence.
        document.addEventListener('taxonomy:architecture-selection', event => {
            if (!workspace.isActive() || event.detail?.kind !== workspace.getSource().kind) return;
            workspace.rememberSelection(event.detail.elementId);
            nativeLensSelections.push(event.detail.elementId);
            nativeSourceLens.select(event.detail.elementId);
        });
        if (await nativeSourceLens.activate(workspace.getSource()) !== true) {
            throw new Error(document.getElementById('nativeSourceLensError').textContent);
        }
    }, source);
    assert.equal(await page.locator('#nativeSourceLensUseAi').isChecked(), false);
    if (enabled) await page.locator('#nativeSourceLensEnabled').check();
    assert.equal(await page.locator('#nativeSourceLensEnabled').isChecked(), enabled);
}
async function recordNativeDoubleClick(page) {
    await page.evaluate(() => {
        window.nativeDoubleClicks = [];
        window.nativeDoubleClickTarget = document.querySelector('[data-node-id="A"]');
        document.addEventListener('dblclick', event => {
            const node = event.target.closest('[data-node-id]');
            if (node) nativeDoubleClicks.push({ id: node.dataset.nodeId, retained: node === nativeDoubleClickTarget });
        }, true);
    });
}
for (const source of ['analysis', 'snapshot']) for (const enabled of [false, true]) {
    test(`${source}: native double-click focuses through the actual source lens (${enabled ? 'enabled' : 'disabled'})`, async () => {
        const page = source === 'analysis' ? await fixture() : await snapshotFixture();
        const errors = []; page.on('pageerror', error => errors.push(error.message));
        try {
            if (source === 'analysis') await mountAnalysis(page);
            else {
                await page.evaluate(() => snapshotAdapter.activate({ ...sourceA, requirementId: '7', requirementVersionId: '11' }));
                await page.waitForFunction(() => document.getElementById('architectureWorkbench').dataset.fitState === 'complete');
            }
            await attachSourceLens(page, source, enabled);
            await recordNativeDoubleClick(page);
            await page.locator('[data-node-id="A"]').dblclick();
            const actual = await page.evaluate(kind => {
                const diagram = kind === 'analysis' ? mapState.diagram : snapshotAdapter.getDiagram();
                return { mode: diagram.getState().mode, selected: diagram.getState().selectedNodeId,
                    nodes: diagram.getModel().nodes.map(node => node.id).sort() };
            }, source);
            assert.deepEqual(actual, { mode: 'focus', selected: 'A', nodes: ['A', 'B'] });
            assert.deepEqual(await page.evaluate(() => nativeDoubleClicks), [{ id: 'A', retained: true }],
                'Panel selection/presentation must preserve the original SVG target until the native gesture completes');
            assert.ok(await page.evaluate(() => nativeLensSelections.includes('A')),
                'Real selection events must traverse SourceLens.select and ArchitectureFocusPanel.render');
            assert.equal(await page.locator('#nativeSourceLensEnabled').isChecked(), enabled);
            assert.equal(await page.locator('#nativeSourceLensInspect').isEnabled(), enabled);
            const requests = await page.evaluate(() => {
                nativeSourceLens.suspend();
                return { calls: nativeLensRequests, editor: nativeLensOpenCalls, saves: nativeLensSaves };
            });
            if (!enabled) assert.deepEqual(requests.calls, []);
            if (source === 'analysis') assert.ok(requests.saves >= 1, 'The lens pins an acknowledged analysis draft');
            for (const request of requests.calls) {
                assert.equal(new URL(request.url, 'http://fixture.test').pathname, '/api/architecture/focus/suggestions');
                assert.equal(request.method, 'POST'); assert.equal(request.body.useAi, false);
                assert.equal(request.body.source.kind, source === 'analysis' ? 'ANALYSIS_DRAFT' : 'ANALYSIS_SNAPSHOT');
                assert.equal(request.body.source.repositoryId, 'repository-A');
                assert.equal(request.body.source.workspaceScopeKey, 'private-A');
                assert.equal(request.body.source.branch, 'review/A');
                if (source === 'analysis') assert.equal(request.body.source.version, 4);
                else {
                    assert.equal(request.body.source.projectId, 42); assert.equal(request.body.source.requirementId, 7);
                    assert.equal(request.body.source.requirementVersionId, 11); assert.equal(request.body.source.snapshotId, 'snapshot-A');
                }
                assert.equal(request.headers['if-match'], undefined);
                assert.equal(request.headers['x-taxonomy-workspace-id'], 'private-A');
                assert.equal(request.retries, 0); assert.equal(request.pinnedWorkspaceId, 'private-A');
            }
            assert.deepEqual(requests.editor, []);
            assert.equal(await page.locator('#nativeSourceLens [data-focus-action="accept"], #nativeSourceLens [data-focus-action="undo"]').count(), 0);
            assert.deepEqual(errors, []);
        } finally { await page.close(); }
    });
}
for (const enabled of [false, true]) test(`working: native double-click survives actual focus-panel selection (${enabled ? 'enabled' : 'disabled'})`, async () => {
    const page = await fixture();
    try {
        await appendFocusPanel(page, 'editorLens');
        await page.addScriptTag({ path: path.join(resources, 'static/js/architecture-editor-renderer.js') });
        await page.evaluate(() => {
            document.getElementById('map').hidden = true;
            const svg = document.getElementById('editorGraph'); svg.style.width = '1000px'; svg.style.height = '420px';
            document.getElementById('editorLens').hidden = false;
            const context = { repositoryId: 'repository-A', workspaceScopeKey: 'private-A', branch: 'draft/A', actor: 'alice',
                writeMode: 'WORKSPACE_REVISION', revision: 7, commit: null };
            window.workingLensRequests = []; window.workingSelections = []; window.workingSelected = null;
            window.renderer = ArchitectureEditorRenderer(svg, id => {
                if (id === workingSelected) return true;
                workingSelected = id; workingSelections.push(id);
                renderer.select(id); workingFocusPanel.select(id);
                return true;
            }, () => { throw new Error('Double-click cannot issue a relation command'); }, () => {},
            ids => workingFocusPanel.observe(ids), id => workingFocusPanel.review(id));
            window.workingFocusPanel = ArchitectureFocusPanel({ root: document.getElementById('editorLens'), idPrefix: 'editorLens',
                t: key => key, language: () => 'en', present: renderer.presentFocus,
                request: async body => {
                    workingLensRequests.push(body);
                    return { context: body.context, sourceId: body.sourceId, outcome: 'NO_CANDIDATES',
                        method: 'LOCAL_TEXT_UNASSESSED', modelCalls: 0, suggestions: [] };
                },
                accept: () => { throw new Error('Double-click cannot accept a hypothesis'); },
                undo: () => { throw new Error('Double-click cannot undo a command'); }, canUndo: () => false,
                locate: id => renderer.select(id)
            });
            renderer.render({ width: 1000, height: 420, nodes: [
                { id: 'A', label: 'Working capability', x: 105, y: 107, width: 230, height: 86 },
                { id: 'B', label: 'Working service', x: 405, y: 107, width: 230, height: 86 },
                { id: 'C', label: 'Working evidence', x: 705, y: 107, width: 230, height: 86 }
            ], edges: [{ id: 'AB', sourceId: 'A', targetId: 'B', relationType: 'SUPPORTS', sourceX: 335, sourceY: 150, targetX: 405, targetY: 150 }] }, null);
            workingFocusPanel.setContext(context, true); workingFocusPanel.select(null);
        });
        // Keep investigation local; enabling the working lens otherwise opts into AI by default.
        await page.locator('#editorLensUseAi').uncheck();
        if (enabled) await page.locator('#editorLensEnabled').check();
        await recordNativeDoubleClick(page);
        await page.locator('[data-node-id="A"]').dblclick();
        assert.deepEqual(await page.evaluate(() => ({ selected: renderer.getState().selectedNodeId, mode: renderer.getState().mode,
            events: nativeDoubleClicks, selections: workingSelections })),
            { selected: 'A', mode: 'focus', events: [{ id: 'A', retained: true }], selections: ['A'] });
        assert.equal(await page.locator('#editorLensEnabled').isChecked(), enabled);
        assert.equal(await page.locator('#editorLensInspect').isEnabled(), enabled);
        assert.equal(await page.evaluate(() => workingLensRequests.every(request => request.useAi === false)), true);
        await page.evaluate(() => { workingFocusPanel.setContext(null, false); renderer.destroy(); });
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});
for (const touch of [false, true]) test(`analysis shared drawing preserves selection, focus/unfocus and viewport (${touch ? 'touch' : 'keyboard'})`, async () => {
    const page = await fixture(touch);
    try {
        await mountAnalysis(page);
        const node = page.locator('[data-node-id="A"]');
        if (touch) await node.tap(); else { await node.focus(); await page.keyboard.press('Enter'); }
        assert.equal(await page.evaluate(() => window.selectedAnalysis), 'A');
        assert.deepEqual(await page.evaluate(() => diagramSelections.at(-1)),{kind:'analysis',elementId:'A'});
        assert.equal(await node.getAttribute('aria-pressed'), 'true');
        await page.evaluate(() => mapState.diagram.focus('A'));
        assert.equal(await page.locator('.impact-map-node').count(), 2);
        await page.evaluate(() => mapState.diagram.unfocus());
        assert.equal(await page.locator('.impact-map-node').count(), 3);
        assert.equal(await page.locator('[data-node-id="A"]').getAttribute('aria-pressed'), 'true');
        await page.evaluate(() => mapState.diagram.restoreViewport({ x: 23, y: 41, k: 0.8 }));
        assert.deepEqual(await page.evaluate(() => mapState.diagram.captureViewport()), { x: 23, y: 41, k: 0.8 });
        await page.evaluate(() => document.getElementById('map').__taxonomyImpactMapCleanup());
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});
test('working diagram fits after hidden mount, preserves server geometry and restores focus viewport', async () => {
    const page = await fixture();
    try {
        await page.addScriptTag({ path: path.join(resources, 'static/js/architecture-editor-renderer.js') });
        await page.evaluate(() => {
            const svg = document.getElementById('editorGraph'); svg.style.display = 'none';
            const scene = { nodes: [{ id: 'A', label: 'Working element', x: 105, y: 207, width: 230, height: 86 }], edges: [], width: 2000, height: 700 };
            window.renderer = ArchitectureEditorRenderer(svg, id => renderer.select(id)); renderer.render(scene, 'A'); renderer.fit();
        });
        assert.deepEqual(await page.evaluate(() => renderer.captureViewport()), { x: 0, y: 0, k: 1 });
        await page.evaluate(() => document.getElementById('editorGraph').style.display = '');
        await page.waitForFunction(() => renderer.captureViewport().k < 1);
        assert.equal(await page.locator('.editor-node').getAttribute('transform'), 'translate(105,207)');
        await page.evaluate(() => renderer.restoreViewport({ x: 21, y: 43, k: 0.8 }));
        await page.evaluate(() => { renderer.focus(); renderer.unfocus(); });
        assert.deepEqual(await page.evaluate(() => renderer.captureViewport()), { x: 21, y: 43, k: 0.8 });
        await page.evaluate(() => renderer.render({ width: 1000, height: 420, nodes: [
            { id: 'A', x: 105, y: 207, width: 230, height: 86 }, { id: 'B', x: 600, y: 207, width: 230, height: 86 }
        ], edges: [{ id: 'AB', sourceId: 'A', targetId: 'B', relationType: 'SUPPORTS', sourceX: 335, sourceY: 250, targetX: 600, targetY: 250 }] }, 'A'));
        await page.locator('.editor-edge').focus();
        await page.evaluate(() => renderer.presentFocus({ enabled: false, states: {}, suggestions: [] }));
        assert.equal(await page.evaluate(() => document.activeElement.classList.contains('editor-edge')), true, 'lens redraw must retain keyboard relation focus');
        await page.evaluate(() => renderer.destroy());
        assert.equal(await page.locator('#editorGraph g').count(), 0);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

for (const embedded of [false,true]) test(`snapshot adapter keeps exact geometry and source selection (${embedded ? 'embedded' : 'standalone'})`, async () => {
    const page = await browser.newPage({ viewport: { width: 1200, height: 800 }, reducedMotion: 'reduce' });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    try {
        await setSnapshotTemplate(page);
        if (embedded) await page.evaluate(()=>{const marker=document.createElement('div');marker.id='architectureWorkspace';document.body.append(marker);});
        await page.addStyleTag({ path: path.join(resources, 'static/css/architecture-workbench.css') });
        await page.addStyleTag({ content: '.d-none { display:none; } #architectureCanvasShell { width:1000px;height:540px; }' });
        await page.addScriptTag({ content: d3Source });
        await page.addScriptTag({ path: path.join(resources, 'static/js/shared/architecture-diagram.js') });
        await page.evaluate(() => {
            document.body.dataset.projectId = 'fixture-project'; document.body.dataset.snapshotId = 'fixture-snapshot';
            window.TaxonomyI18n = { t: key => key, ready: () => Promise.resolve(), getLocale: () => 'en', formatEnum: value => value || '' };
            window.snapshotLoads=0;
            window.ArchitectureWorkbenchApi = { load: () => {window.snapshotLoads++;return Promise.resolve({ elements: {}, relations: {}, warnings: [], snapshotId: 'fixture-snapshot', scene: {
                width: 1200, height: 500, nodes: [
                    { id: 'A', label: 'Capability evidence', type: 'Capabilities', layer: 0, anchor: true, relevance: 0.00125, x: 105, y: 207, width: 230, height: 86 },
                    { id: 'B', label: 'Context service', type: 'Services', layer: 1, anchor: false, relevance: 0.5, x: 600, y: 207, width: 230, height: 86 },
                    { id: 'C', label: 'Other evidence', type: 'Business Processes', layer: 2, anchor: true, relevance: 0.8, x: 900, y: 207, width: 230, height: 86 }
                ], edges: [{ id: 'AB', sourceId: 'A', targetId: 'B', relationType: 'SUPPORTS', sourceX: 335, sourceY: 250, targetX: 600, targetY: 250 }]
            } });} };
        });
        await page.addScriptTag({ path: path.join(resources, 'static/js/architecture-workbench.js') });
        if (embedded) {
            assert.equal(await page.evaluate(()=>snapshotLoads),0,'Hidden snapshot adapter must not load or publish context');
            await page.evaluate(async()=>{window.snapshotAdapter=TaxonomyArchitectureSnapshot.mount(document.body);await snapshotAdapter.activate({kind:'snapshot',projectId:'fixture-project',snapshotId:'fixture-snapshot'});});
        }
        await page.waitForFunction(() => document.querySelectorAll('.architecture-node').length === 3);
        assert.equal(await page.locator('[data-node-id="A"]').getAttribute('transform'), 'translate(105,207)');
        assert.equal(await page.locator('[data-node-id="A"] .architecture-node-score').textContent(), '★ 0.125%');
        assert.match(await page.locator('.architecture-edge-path').getAttribute('d'), /^M335,250 .* 600,250$/);
        await page.evaluate(() => TaxonomySnapshotDiagram.restoreViewport({ x: 23, y: 41, k: 0.8 }));
        await page.locator('#architectureSearch').fill('Context service'); await page.locator('#architectureSearch').press('Enter');
        await page.evaluate(() => TaxonomySnapshotDiagram.focus('A'));
        assert.match(await page.locator('#architectureDetails').textContent(), /Capability evidence/);
        assert.equal(await page.locator('.architecture-node').count(), 2);
        await page.evaluate(() => TaxonomySnapshotDiagram.unfocus());
        assert.equal(await page.locator('.architecture-node').count(), 3);
        assert.equal(await page.locator('[data-node-id="A"]').getAttribute('transform'), 'translate(105,207)');
        await page.evaluate(() => { TaxonomySnapshotDiagram.focus('A'); TaxonomySnapshotDiagram.restoreViewport({ x: 23, y: 41, k: 0.8 }); });
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        assert.deepEqual(await page.evaluate(() => TaxonomySnapshotDiagram.captureViewport()), { x: 23, y: 41, k: 0.8 }, 'explicit restored viewport wins over a pending automatic fit');
        await page.evaluate(() => { TaxonomySnapshotDiagram.focus('A'); TaxonomySnapshotDiagram.select(null); });
        assert.equal(await page.locator('#architectureDetails').textContent(), '');
        await page.evaluate(() => TaxonomySnapshotDiagram.destroy()); assert.deepEqual(errors, []);
    } finally { await page.close(); }
});

async function snapshotFixture(locale = 'en', standaloneUrl = null) {
    const page = await browser.newPage({ viewport: { width: 1200, height: 900 }, reducedMotion: 'reduce' });
    await page.route('http://snapshot.test/**', route => route.fulfill({body:'',contentType:'text/html'}));
    await page.goto(standaloneUrl || 'http://snapshot.test/');
    await setSnapshotTemplate(page);
    await page.evaluate(({locale,standaloneUrl}) => {
        document.documentElement.lang = locale;
        if(!standaloneUrl){const marker = document.createElement('div'); marker.id = 'architectureWorkspace'; document.body.append(marker);}
        document.body.classList.add('architecture-snapshot-surface');
        document.querySelector('header').classList.add('architecture-source-actions');
        window.TaxonomyI18n = { t: key => ({ 'archview.policy.title.defaultImpact': locale === 'de' ? 'Architektur-Wirkungsansicht' : 'Architecture Impact View' }[key] || key), ready: () => Promise.resolve(), getLocale: () => locale, formatEnum: value => value || '' };
        window.snapshotRequests = [];
        window.snapshotData = source => ({ projectId: Number(source.projectId), requirementId:source.requirementId,snapshotId: source.snapshotId, workspaceId: source.workspaceId || source.workspaceScopeKey || '', branchName: source.branch, exportProvenance: { repositoryId: source.repositoryId,requirementVersionId:source.requirementVersionId }, policyTitleKey: 'archview.policy.title.defaultImpact', elements: {}, relations: {}, warnings: [], scene: {
            width: 1100, height: 420, nodes: [
                { id:'A',label:'Capability evidence',type:'Capabilities',layer:0,anchor:true,relevance:0.9,x:100,y:100,width:230,height:86 },
                { id:'B',label:'Context service',type:'Services',layer:1,anchor:false,relevance:0.5,x:500,y:100,width:230,height:86 },
                { id:'C',label:'Other capability',type:'Capabilities',layer:0,anchor:true,relevance:0.7,x:100,y:220,width:230,height:86 }
            ], edges: [{ id:'AB',sourceId:'A',targetId:'B',relationType:'SUPPORTS',sourceX:330,sourceY:143,targetX:500,targetY:143 }]
        } });
        window.ArchitectureWorkbenchApi = { load: (projectId,snapshotId,source,signal) => { snapshotRequests.push({ ...source, signal }); return Promise.resolve(snapshotData(source)); } };
    }, {locale,standaloneUrl});
    for (const css of ['architecture-workbench.css','architecture-workspace.css']) await page.addStyleTag({path:path.join(resources,'static/css',css)});
    await page.addStyleTag({ content: '.d-none {display:none} #architectureCanvasShell {width:1000px;height:540px}' });
    await page.addScriptTag({content:d3Source});
    await page.addScriptTag({path:path.join(resources,'static/js/shared/architecture-diagram.js')});
    await page.addScriptTag({path:path.join(resources,'static/js/architecture-workbench.js')});
    await page.evaluate(() => { window.snapshotAdapter = TaxonomyArchitectureSnapshot.mount(document.body); window.sourceA = {kind:'snapshot',projectId:'42',snapshotId:'snapshot-A',repositoryId:'repository-A',workspaceId:'private-A',branch:'review/A'}; });
    return page;
}
for(const workspace of ['private-A',''])test(`standalone snapshot keeps exact source pins and rejects URL object keys (${workspace||'shared'})`,async()=>{
    const pins={repositoryId:'repository-A',workspaceId:workspace,workspaceScopeKey:workspace||'__shared__',branch:'review/A',
        projectId:'42',requirementId:'7',requirementVersionId:'9',snapshotId:'snapshot-A',element:'A',
        commit:'a'.repeat(40),revision:'17',viewId:'view-A',layout:'stable',layoutRevision:'3',
        sourceRepositoryId:'source-repo',sourceWorkspaceId:workspace?'':'source-private',sourceWorkspaceScopeKey:workspace?'__shared__':'source-private',sourceBranch:'frozen/source',
        sourceProjectId:'52',sourceRequirementId:'8',sourceRequirementVersionId:'11',sourceSnapshotId:'source-A',adoption:'preview'};
    const query=new URLSearchParams(pins);
    for(const key of ['__proto__','constructor','prototype','hasOwnProperty','mayEdit','unexpected'])query.set(key,'untrusted');
    query.set('kind','working');query.set('lang','de');
    const page=await snapshotFixture('de','http://snapshot.test/taxonomy/architecture/workbench?'+query);
    try{
        await page.waitForFunction(()=>snapshotAdapter.getData());
        const actual=await page.evaluate(()=>snapshotAdapter.getSource());
        for(const [key,value]of Object.entries(pins))assert.equal(actual[key],value,key);
        assert.equal(actual.kind,'snapshot');
        for(const key of ['__proto__','constructor','prototype','hasOwnProperty','mayEdit','unexpected'])
            assert.equal(Object.hasOwn(actual,key),false,'Untrusted URL property escaped into source: '+key);
        assert.equal(await page.evaluate(()=>snapshotRequests[0].workspaceScopeKey),workspace||'__shared__');
        assert.equal(await page.evaluate(()=>snapshotRequests[0].workspaceId),workspace);
        assert.equal(await page.evaluate(()=>snapshotRequests[0].snapshotId),'snapshot-A');
        assert.equal(await page.locator('[data-node-id="A"]').getAttribute('aria-pressed'),'true');
        assert.equal(new URL(page.url()).searchParams.get('lang'),'de');
    }finally{await page.close();}
});
test('snapshot return normalizes loaded IDs and workspace aliases without refetch or losing retained view', async () => {
    const page = await snapshotFixture();
    try {
        await page.evaluate(() => snapshotAdapter.activate(sourceA));
        await page.locator('#architectureSearch').fill('Capability');
        await page.locator('#showArchitectureContext').uncheck();
        await page.evaluate(() => { snapshotAdapter.select('A'); snapshotAdapter.getDiagram().restoreViewport({x:28,y:-94,k:0.7}); snapshotAdapter.getDiagram().focus('A'); snapshotAdapter.getDiagram().restoreViewport({x:23,y:41,k:0.8}); window.loadedA=snapshotAdapter.getSource(); snapshotAdapter.suspend(); });
        await page.evaluate(() => snapshotAdapter.activate(loadedA));
        assert.equal(await page.evaluate(() => snapshotRequests.length), 1, 'same immutable source must reuse the loaded diagram');
        await page.evaluate(async () => { snapshotAdapter.suspend(); await snapshotAdapter.activate({...sourceA,snapshotId:'snapshot-B'}); snapshotAdapter.suspend(); await snapshotAdapter.activate(loadedA); });
        assert.equal(await page.evaluate(() => snapshotRequests.length), 2, 'returning from a different snapshot must use canonical retained source');
        assert.equal(await page.locator('#architectureSearch').inputValue(),'Capability');
        assert.equal(await page.locator('#showArchitectureContext').isChecked(),false);
        assert.equal(await page.locator('[data-node-id="A"]').getAttribute('aria-pressed'),'true');
        assert.equal(await page.locator('#architectureFocus').getAttribute('aria-pressed'),'true');
        assert.deepEqual(await page.evaluate(() => snapshotAdapter.getDiagram().captureViewport()),{x:23,y:41,k:0.8});
        await page.locator('#architectureOverview').click();
        assert.deepEqual(await page.evaluate(() => snapshotAdapter.getDiagram().captureViewport()),{x:28,y:-94,k:0.7},
            'returning from another snapshot must retain the overview camera saved before focus');
    } finally { await page.close(); }
});
for (const requested of ['B', 'C']) test(`explicit snapshot element ${requested} is revealed after restoring another focused source`, async () => {
    const page = await snapshotFixture();
    try {
        await page.evaluate(() => snapshotAdapter.activate(sourceA));
        await page.locator('#showArchitectureContext').uncheck();
        await page.evaluate(() => { snapshotAdapter.getDiagram().focus('A'); snapshotAdapter.suspend(); });
        await page.evaluate(() => snapshotAdapter.activate({ ...sourceA, snapshotId: 'snapshot-B' }));
        await page.evaluate(id => {
            snapshotAdapter.suspend();
            // The workspace passes its current source by reference and remembers
            // synchronous selection events on that same object while activate renders.
            window.requestedSnapshotSource = { ...sourceA, element: id };
            window.restoredSelections = [];
            document.addEventListener('taxonomy:architecture-selection', event => {
                if (event.detail?.kind !== requestedSnapshotSource.kind) return;
                requestedSnapshotSource.element = event.detail.elementId;
                restoredSelections.push(event.detail.elementId);
            });
            return snapshotAdapter.activate(requestedSnapshotSource);
        }, requested);
        const actual = await page.evaluate(() => ({ state: snapshotAdapter.getDiagram().getState(),
            source: snapshotAdapter.getSource(), loads: snapshotRequests.length }));
        assert.equal(actual.loads, 2, 'a retained snapshot is reused');
        assert.equal(actual.state.selectedNodeId, requested, 'the explicit route selection overrides retained selection');
        assert.equal(actual.state.mode, 'overview', 'the requested element is revealed outside the previous neighborhood');
        assert.equal(actual.state.showContext, true, 'hidden context is revealed when needed');
        assert.equal(actual.source.element, requested);
        assert.deepEqual(await page.evaluate(() => restoredSelections), ['A', requested],
            'the requested element survives the workspace remembering the intermediate restored selection');
        assert.equal(await page.locator(`[data-node-id="${requested}"]`).getAttribute('aria-pressed'), 'true');
        assert.equal(await page.locator('.architecture-node').count(), 3);
    } finally { await page.close(); }
});
test('snapshot zoom continues from its full-model fit after focus and source remount', async () => {
    const page = await snapshotFixture();
    try {
        await page.evaluate(() => {
            const original = snapshotData;
            window.snapshotData = source => {
                const data = original(source); data.scene.width = 50230;
                data.scene.nodes.find(node => node.id === 'C').x = 50000;
                return data;
            };
            return snapshotAdapter.activate(sourceA);
        });
        await page.waitForFunction(() => document.getElementById('architectureWorkbench').dataset.fitState === 'complete');
        const fitted = await page.evaluate(() => snapshotAdapter.getDiagram().captureViewport().k);
        assert.ok(fitted < 0.2);
        await page.evaluate(() => snapshotAdapter.getDiagram().focus('A'));
        await page.waitForFunction(() => document.getElementById('architectureWorkbench').dataset.fitState === 'complete');
        await page.evaluate(() => snapshotAdapter.getDiagram().unfocus());
        for (const stage of ['after focus', 'after remount']) {
            if (stage === 'after remount') await page.evaluate(async () => {
                snapshotAdapter.suspend(); await snapshotAdapter.activate({ ...sourceA, snapshotId: 'snapshot-B' });
                snapshotAdapter.suspend(); await snapshotAdapter.activate(sourceA);
            });
            await page.evaluate(() => snapshotAdapter.getDiagram().zoom(1.4));
            assert.ok(Math.abs(await page.evaluate(() => snapshotAdapter.getDiagram().captureViewport().k) - fitted * 1.4) < 1e-9, stage);
            await page.evaluate(() => snapshotAdapter.getDiagram().zoom(1 / 1.4));
        }
    } finally { await page.close(); }
});
test('suspended snapshot disables exports and ignores a late response after the next source loads', async () => {
    const page = await snapshotFixture();
    try {
        await page.addScriptTag({path:path.join(resources,'static/js/architecture-workbench-print.js')});
        await page.evaluate(() => snapshotAdapter.activate(sourceA));
        await page.evaluate(() => snapshotAdapter.suspend());
        assert.equal(await page.locator('#downloadDecisionWord').isDisabled(),true);
        assert.equal(await page.locator('#printArchitectureView').isDisabled(),true,'hidden retained snapshot cannot print old evidence');
        await page.evaluate(() => { window.pendingSnapshots=new Map(); ArchitectureWorkbenchApi.load=(projectId,snapshotId,source,signal)=>new Promise(resolve=>pendingSnapshots.set(snapshotId,{resolve,source,signal})); window.firstLoad=snapshotAdapter.activate({...sourceA,snapshotId:'slow-A'}); });
        await page.waitForFunction(() => pendingSnapshots.has('slow-A'));
        await page.evaluate(() => { window.nextLoad=snapshotAdapter.activate({...sourceA,snapshotId:'fast-B'}); });
        await page.waitForFunction(() => pendingSnapshots.has('fast-B'));
        await page.evaluate(async () => { const next=pendingSnapshots.get('fast-B');next.resolve(snapshotData(next.source));await nextLoad;const first=pendingSnapshots.get('slow-A');first.resolve(snapshotData(first.source));await firstLoad; });
        assert.equal(await page.evaluate(() => snapshotAdapter.getSource().snapshotId),'fast-B');
        assert.equal(await page.evaluate(() => pendingSnapshots.get('slow-A').signal.aborted),true);
        assert.equal(await page.locator('#downloadDecisionWord').isDisabled(),false);
    } finally { await page.close(); }
});
for (const locale of ['de','en']) test(`embedded snapshot resolves translated policy title (${locale})`, async () => {
    const page=await snapshotFixture(locale);
    try { await page.evaluate(() => snapshotAdapter.activate(sourceA)); assert.equal(await page.locator('#architectureTitle').textContent(),locale==='de'?'Architektur-Wirkungsansicht':'Architecture Impact View'); }
    finally { await page.close(); }
});
test('embedded dark export menu retains readable resting text contrast', async () => {
    const page=await snapshotFixture();
    try {
        const bootstrap=process.env.BOOTSTRAP_CSS ? await readFile(process.env.BOOTSTRAP_CSS,'utf8')
            : execFileSync('unzip',['-p',path.join(process.env.MAVEN_REPO_LOCAL || path.join(os.homedir(),'.m2/repository'),'org/webjars/npm/bootstrap/5.3.8/bootstrap-5.3.8.jar'),'META-INF/resources/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css'],{encoding:'utf8'});
        await page.addStyleTag({content:bootstrap});
        // Include the actual application overrides between Bootstrap and the source styles.
        for(const css of ['taxonomy.css','taxonomy-ergonomics.css','architecture-workbench.css','architecture-workspace.css'])await page.addStyleTag({path:path.join(resources,'static/css',css)});
        await page.setViewportSize({width:390,height:844});
        const experimental=['downloadArchitectureArchiMate','downloadArchitectureVisio'];
        for(const id of experimental)assert.equal(await page.locator('#'+id).isDisabled(),true,id+' starts disabled');
        await page.evaluate(()=>snapshotAdapter.activate(sourceA));
        const exports=['downloadArchitectureSvg','downloadArchitecturePdf','downloadArchitectureWord','downloadDecisionWord',...experimental];
        for(const id of exports)assert.equal(await page.locator('#'+id).isEnabled(),true,id+' is enabled by the loaded snapshot');
        await page.evaluate(() => document.getElementById('architectureExportOptions').open=true);
        const {default:axeCore}=await import('axe-core');
        await page.addScriptTag({content:axeCore.source});
        const axe=await page.evaluate(()=>window.axe.run(document.getElementById('architectureExportOptions'),{
            runOnly:{type:'rule',values:['color-contrast']}
        }));
        assert.deepEqual(axe.violations.map(violation=>({id:violation.id,nodes:violation.nodes.map(node=>({
            target:node.target,contrast:node.any.find(check=>check.id==='color-contrast')?.data
        }))})),[],'Enabled exports retain AA text contrast in the real dark popup');
        const contrast=async id=>page.locator('#'+id).evaluate(element=>{
            const fg=getComputedStyle(element).color;
            let parent=element,bg=getComputedStyle(parent).backgroundColor;
            while(parent.parentElement && (bg==='transparent'||bg==='rgba(0, 0, 0, 0)')){
                parent=parent.parentElement;bg=getComputedStyle(parent).backgroundColor;
            }
            function luminance(value){const rgb=value.match(/[\d.]+/g).slice(0,3).map(Number).map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;}
            const a=luminance(fg),b=luminance(bg);return {fg,bg,ratio:(Math.max(a,b)+.05)/(Math.min(a,b)+.05)};
        });
        const assertContrast=async(id,state)=>{
            const value=await contrast(id);assert.ok(value.ratio>=4.5,`${id} ${state}: ${JSON.stringify(value)}`);
        };
        for(const id of exports)await assertContrast(id,'resting');
        for(const id of experimental){
            const button=page.locator('#'+id);
            await button.hover();assert.equal(await button.evaluate(e=>e.matches(':hover')),true);
            await assertContrast(id,'hover');
            await page.mouse.down();
            try{assert.equal(await button.evaluate(e=>e.matches(':active')),true);await assertContrast(id,'active');}
            finally{await page.mouse.move(0,0);await page.mouse.up();}
            const disclosure=page.locator('#architectureExportOptions');
            await disclosure.locator('summary').focus();
            if(!await disclosure.evaluate(e=>e.open))await page.keyboard.press('Enter');
            for(let presses=0;presses<=exports.length && await page.evaluate(()=>document.activeElement.id)!==id;presses++)
                await page.keyboard.press('Tab');
            assert.equal(await page.evaluate(()=>document.activeElement.id),id,'Tab reaches '+id);
            assert.equal(await button.evaluate(e=>e.matches(':focus-visible')),true);
            await assertContrast(id,'keyboard focus');
            await button.evaluate(e=>e.blur());
        }
        await page.evaluate(()=>snapshotAdapter.suspend());
        for(const id of exports)assert.equal(await page.locator('#'+id).isDisabled(),true,id+' is disabled when its source is suspended');
    } finally {await page.close();}
});

async function snapshotInResponsivePanels(page) {
    await page.evaluate(() => {
        const row = document.createElement('div'); row.id = 'taskPanels';
        const left = document.createElement('div'); left.id = 'leftPanel';
        const right = document.createElement('div'); right.id = 'rightPanel';
        // The real application nests the snapshot surface in the primary panel.
        for (const node of [...document.body.children]) {
            if (!['SCRIPT', 'STYLE'].includes(node.tagName)) right.appendChild(node);
        }
        row.append(left, right); document.body.appendChild(row);
    });
    await page.addScriptTag({ path: path.join(resources, 'static/js/shared/taxonomy-utils.js') });
}

test('responsive panel reordering preserves the open snapshot export keyboard interaction', async () => {
    const page = await snapshotFixture();
    try {
        await page.setViewportSize({ width: 1024, height: 844 });
        await snapshotInResponsivePanels(page);
        await page.evaluate(async () => {
            await snapshotAdapter.activate(sourceA);
            snapshotAdapter.select('B');
            window.retainedSnapshotData = snapshotAdapter.getData();
            window.retainedSnapshotSource = JSON.stringify(snapshotAdapter.getSource());
            window.exportKeys = [];
            document.addEventListener('keydown', event => {
                if (event.key === 'Escape') exportKeys.push({ trusted: event.isTrusted,
                    withinExport: Boolean(event.target.closest('#architectureExportOptions')) });
            }, true);
        });
        const disclosure = page.locator('#architectureExportOptions');
        await disclosure.locator('summary').focus();
        await page.keyboard.press('Enter');
        assert.equal(await disclosure.evaluate(node => node.open), true);
        await page.setViewportSize({ width: 390, height: 844 });
        await page.waitForFunction(() => document.getElementById('taskPanels').dataset.taskOrder === 'primary-first');
        await page.keyboard.press('Escape');
        assert.equal(await disclosure.evaluate(node => node.open), false,
            'Moving the primary panel at the mobile breakpoint must preserve the native Escape target');
        assert.equal(await disclosure.locator('summary').evaluate(node => document.activeElement === node), true);
        assert.deepEqual(await page.evaluate(() => exportKeys), [{ trusted: true, withinExport: true }]);
        assert.equal(await page.evaluate(() => snapshotAdapter.getData() === retainedSnapshotData
            && JSON.stringify(snapshotAdapter.getSource()) === retainedSnapshotSource), true);
        assert.equal(await page.locator('[data-node-id="B"]').getAttribute('aria-pressed'), 'true');
        assert.equal(await page.evaluate(() => snapshotRequests.length), 1);

        await page.keyboard.press('Enter');
        await page.evaluate(() => {
            const dialog = document.createElement('dialog'); dialog.id = 'activeExportDialog';
            dialog.innerHTML = '<button id="dialogAction">Keep this dialog interaction</button>';
            document.body.appendChild(dialog); dialog.showModal();
        });
        await page.setViewportSize({ width: 1024, height: 844 });
        await page.waitForFunction(() => document.getElementById('taskPanels').dataset.taskOrder === 'reference-first');
        assert.equal(await page.locator('#dialogAction').evaluate(node => document.activeElement === node), true);
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('#activeExportDialog').evaluate(node => node.open), false);
        assert.equal(await disclosure.evaluate(node => node.open), true,
            'Escape belongs to the active modal, not the export disclosure behind it');
    } finally { await page.close(); }
});

test('responsive panel reordering preserves native input selection in the reference panel', async () => {
    const page = await snapshotFixture();
    try {
        await page.setViewportSize({ width: 390, height: 844 });
        await snapshotInResponsivePanels(page);
        await page.evaluate(() => {
            const input = document.createElement('textarea'); input.id = 'referenceFilter';
            document.getElementById('leftPanel').appendChild(input);
        });
        const input = page.locator('#referenceFilter');
        await input.fill('stable search query');
        await input.press('Home');
        for (let i = 0; i < 6; i++) await page.keyboard.press('Shift+ArrowRight');
        await page.setViewportSize({ width: 1024, height: 844 });
        await page.waitForFunction(() => document.getElementById('taskPanels').dataset.taskOrder === 'reference-first');
        assert.deepEqual(await input.evaluate(node => ({ focused: node === document.activeElement,
            start: node.selectionStart, end: node.selectionEnd })), { focused: true, start: 0, end: 6 });
        await page.keyboard.type('new');
        assert.equal(await input.inputValue(), 'new search query');
    } finally { await page.close(); }
});

test('responsive panel reordering keeps the native fullscreen snapshot connected', async () => {
    const page = await snapshotFixture();
    try {
        // These are the content sizes recorded by the original Chrome 150 CI failure.
        await page.setViewportSize({ width: 1600, height: 957 });
        await snapshotInResponsivePanels(page);
        assert.equal(await page.locator('#rightPanel #architectureWorkbench').count(), 1);
        await page.evaluate(async () => {
            await snapshotAdapter.activate(sourceA);
            snapshotAdapter.select('B');
            window.retainedFullscreenData = snapshotAdapter.getData();
            window.retainedFullscreenSource = JSON.stringify(snapshotAdapter.getSource());
            window.fullscreenChanges = [];
            document.addEventListener('fullscreenchange', event => fullscreenChanges.push({
                trusted: event.isTrusted, active: document.fullscreenElement?.id || null
            }));
        });
        await page.locator('#fullscreenArchitecture').click();
        await page.waitForFunction(() => document.fullscreenElement?.id === 'architectureWorkbench');
        // A fullscreen window cannot be resized through Browser.setWindowBounds.
        // Change its real content viewport without replacing the native fullscreen API.
        const cdp = await page.context().newCDPSession(page);
        await cdp.send('Emulation.setDeviceMetricsOverride', {
            width: 800, height: 544, deviceScaleFactor: 1, mobile: false
        });
        await page.waitForFunction(() => document.getElementById('taskPanels').dataset.taskOrder === 'primary-first');
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        assert.deepEqual(await page.evaluate(() => ({ width: innerWidth, height: innerHeight })),
            { width: 800, height: 544 });
        assert.equal(await page.evaluate(() => document.fullscreenElement?.id), 'architectureWorkbench',
            'Responsive reading order must not detach an active fullscreen descendant');
        assert.deepEqual(await page.evaluate(() => fullscreenChanges), [{ trusted: true, active: 'architectureWorkbench' }]);
        assert.equal(await page.evaluate(() => snapshotAdapter.getData() === retainedFullscreenData
            && JSON.stringify(snapshotAdapter.getSource()) === retainedFullscreenSource), true);
        assert.equal(await page.locator('[data-node-id="B"]').getAttribute('aria-pressed'), 'true');
        await page.locator('#fullscreenArchitecture').click();
        await page.waitForFunction(() => document.fullscreenElement === null);
        assert.deepEqual(await page.evaluate(() => fullscreenChanges), [
            { trusted: true, active: 'architectureWorkbench' }, { trusted: true, active: null }
        ]);
        assert.equal(await page.evaluate(() => snapshotRequests.length), 1);
    } finally { await page.close(); }
});

for (const workspace of ['private-A','__shared__']) test(`Decision Word captures immutable source scope from opening through confirmation (${workspace})`, async () => {
    const page=await snapshotFixture();
    try {
        await page.evaluate(workspace => {
            window.exportScope={kind:'snapshot',projectId:'42',snapshotId:'snapshot-A',repositoryId:'repository-A',workspaceScopeKey:workspace,workspaceId:workspace==='__shared__'?'':workspace,branch:'review/A'};
            window.TaxonomyArchitectureWorkspace={getSource:()=>exportScope};
            window.TaxonomyArchitectureWorkflow={getScope:()=>exportScope};
            window.exportRequests=[];
            window.fetch=async(url,init={})=>{
                exportRequests.push({url:String(url),headers:Object.fromEntries(new Headers(init.headers)),signalAborted:init.signal?.aborted});
                if(String(url).includes('/options'))return new Response(JSON.stringify({roots:[{code:'CP',title:'Capabilities',inAnalysisScope:true}],analysisLanguage:'en'}),{status:200,headers:{'Content-Type':'application/json'}});
                return new Response(JSON.stringify({detail:'Deterministic download boundary'}),{status:422,headers:{'Content-Type':'application/json'}});
            };
            window.TaxonomyCapabilities={load:async()=>({reports:[]}),formats:()=>[{id:'docx',displayName:'Word',fileExtension:'docx',contentType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document'}]};
            document.getElementById('downloadDecisionWord').disabled=false;
        },workspace);
        await page.addScriptTag({path:path.join(resources,'static/js/api/portfolio-api.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/shared/decision-export-dialog.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/architecture-workbench-export.js')});
        await page.locator('#downloadDecisionWord').evaluate(button=>button.click());
        await page.waitForFunction(()=>document.querySelector('.decision-export-dialog button[type=submit]')?.disabled===false);
        await page.evaluate(()=>{ exportScope.repositoryId='repository-B';exportScope.workspaceScopeKey='private-B';exportScope.workspaceId='private-B';exportScope.branch='review/B';exportScope.snapshotId='snapshot-B'; });
        await page.locator('.decision-export-dialog button[type=submit]').click();
        await page.waitForFunction(()=>exportRequests.length===2);
        const requests=await page.evaluate(()=>exportRequests);
        for(const request of requests){
            const url=new URL(request.url,'https://fixture.test');
            assert.equal(url.searchParams.get('repositoryId'),'repository-A');
            assert.equal(url.searchParams.get('workspaceScopeKey'),workspace);
            assert.equal(url.searchParams.get('workspaceId'),workspace==='__shared__'?'':workspace);
            assert.equal(url.searchParams.get('branch'),'review/A');
            assert.ok(url.pathname.includes('/snapshots/snapshot-A/'));
            assert.equal(request.headers['x-taxonomy-workspace-id'],workspace==='__shared__'?'':workspace);
        }
    } finally {await page.close();}
});


test('snapshot search, keyboard and programmatic selections publish the active source identity', async () => {
    const page=await snapshotFixture();
    try {
        await page.evaluate(async()=>{window.diagramSelections=[];document.addEventListener('taxonomy:architecture-selection',event=>diagramSelections.push(event.detail));await snapshotAdapter.activate(sourceA);});
        await page.locator('#architectureSearch').fill('Context service');await page.locator('#architectureSearch').press('Enter');
        assert.deepEqual(await page.evaluate(()=>diagramSelections.at(-1)),{kind:'snapshot',elementId:'B'});
        await page.locator('[data-node-id="A"]').focus();await page.keyboard.press('Enter');
        assert.deepEqual(await page.evaluate(()=>diagramSelections.at(-1)),{kind:'snapshot',elementId:'A'});
        await page.evaluate(()=>snapshotAdapter.select(null));
        assert.deepEqual(await page.evaluate(()=>diagramSelections.at(-1)),{kind:'snapshot',elementId:null});
        const count=await page.evaluate(()=>diagramSelections.length);
        await page.evaluate(()=>{snapshotAdapter.suspend();snapshotAdapter.select('B');});
        assert.equal(await page.evaluate(()=>diagramSelections.length),count,'suspended source must not change the visible lens selection');
    } finally {await page.close();}
});
