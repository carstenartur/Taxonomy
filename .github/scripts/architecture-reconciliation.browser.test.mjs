/** Authored frozen previews exercise the real lifecycle, transport and shared SVG renderer. */
import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import {chromium} from 'playwright';

const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser, d3Source;
before(async () => {
    d3Source = process.env.TAXONOMY_TEST_D3_SCRIPT ? await readFile(process.env.TAXONOMY_TEST_D3_SCRIPT, 'utf8')
        : execFileSync('unzip', ['-p', path.join(process.env.MAVEN_REPO_LOCAL || path.join(os.homedir(), '.m2/repository'),
            'org/webjars/npm/d3/7.9.0/d3-7.9.0.jar'), 'META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js'], {encoding:'utf8'});
    browser = await chromium.launch({headless:true, ...(process.env.CHROMIUM_EXECUTABLE ? {executablePath:process.env.CHROMIUM_EXECUTABLE} : {}),
        args:['--no-sandbox', '--disable-dev-shm-usage']});
});
after(async () => { await browser?.close(); });

async function fixture(unresolved = false) {
    const page = await browser.newPage({viewport:{width:1400,height:1000},reducedMotion:'reduce'});
    page.setDefaultTimeout(4000);
    page.fixtureErrors = [];
    page.on('pageerror', error => {
        const diagnostic = error.stack || error.message;
        page.fixtureErrors.push(diagnostic);
        console.error('[architecture-reconciliation fixture] ' + diagnostic);
    });
    await page.route('https://reconciliation.test/**', route => route.fulfill({contentType:'text/html',body:'<html><body><section id="architectureLifecycle"></section></body></html>'}));
    await page.goto('https://reconciliation.test/?repositoryId=repo&workspaceId=copy&branch=draft');
    assert.deepEqual(await page.evaluate(() => ({secure: window.isSecureContext,
        randomUUID: typeof window.crypto?.randomUUID})), {secure: true, randomUUID: 'function'},
    'The real lifecycle requires native secure-context command identity generation');
    await page.addScriptTag({content:d3Source});
    await page.evaluate(unresolved => {
        const context = {repositoryId:'repo', workspaceScopeKey:'copy', branch:'draft', commit:'a'.repeat(40), revision:4, actor:'alice', writeMode:'PRIVATE_WORKSPACE'};
        window.scope = structuredClone(context);
        window.overview = {context,source:'WORKSPACE_REVISION',projectionState:'VERSION_CHANGED',mayEdit:false,requirements:[],projects:[],dependencies:[]};
        const original = {id:'one',label:'Original name',type:'CoreService',layer:0,x:52,y:52,width:238,height:82};
        const scene = nodes => ({width:760,height:420,direction:'LR',nodes,edges:[]});
        const comparison = {typeRoots:{CoreService:'CR'},scenes:{base:scene([original]),local:scene([{...original,label:'Local name'}]),
            incoming:scene([]),...(unresolved?{}:{proposed:scene([{...original,label:'Local name'}])})},
            properties:[{blockId:'element:one',property:'title',category:'SEMANTIC',base:['Original name'],local:['Local name'],incoming:[],proposed:unresolved?null:['Local name']},
                {blockId:'element:one',property:'x-exchange-artifact',category:'EXCHANGE_EVIDENCE',base:['frozen'],local:['<img src=x onerror=window.injected=true>'],incoming:[],proposed:unresolved?null:['frozen']}]};
        window.previewValue = {context,headCommit:'b'.repeat(40),fingerprint:'frozen-preview-one',baseDsl:'base source',localDsl:'local source',
            incomingDsl:'incoming source',mergedDsl:unresolved?null:'merged source',changes:[],comparison,canAccept:!unresolved,
            conflicts:unresolved?[{id:'element:one',reason:'Choose the retained source',base:'original',local:'local',incoming:null}]:[]};
        window.writes = [];
        window.TaxonomyI18n = {ready:()=>Promise.resolve(),t:key=>key,resolveUrl:value=>value};
        window.navigationRefreshes = 0;
        window.TaxonomyAppChrome = {updateContext(){},getContext:()=>({}),
            refreshNavigation(){window.navigationRefreshes++;}};
        window.TaxonomyArchitectureWorkflow = {getScope:()=>scope,setScope:value=>{scope=structuredClone(value);}};
        window.TaxonomyApiClient = {getJson:async()=>structuredClone(overview),request:async(url,options,policy)=>{
            const request = {url,options:structuredClone(options),policy:structuredClone(policy),body:JSON.parse(options.body)};
            writes.push(request);
            if (url.includes('/preview')) return {json:async()=>window.deferPreview ? new Promise(resolve=>{window.resolvePreview=()=>resolve(structuredClone(previewValue));}) : structuredClone(previewValue)};
            return {json:async()=>({context:structuredClone(context),state:'CHECKPOINTED'})};
        }};
    }, unresolved);
    const fragment = await readFile(path.join(resources,'templates/fragments/application-chrome.html'),'utf8');
    const scripts = [...fragment.matchAll(/src="(\/js\/(?:shared\/architecture-(?:diagram|reconciliation|lifecycle)|architecture-editor-renderer|api\/architecture-lifecycle-api)\.js)"/g)].map(match=>match[1]);
    for (const script of [...new Set(scripts)]) await page.addScriptTag({path:path.join(resources,'static',script)});
    for (const css of ['architecture-lifecycle.css','shared/architecture-diagram.css']) await page.addStyleTag({path:path.join(resources,'static/css',css)});
    await page.evaluate(()=>TaxonomyArchitectureLifecycle.ready());
    await page.locator('#architectureLifecycle > details > summary').click();
    await page.locator('[data-lifecycle-reconcile]').click();
    await page.locator('#lifecycleReconciliationReason').fill('Review this exact preview');
    return page;
}

async function closeFixture(page) {
    await page.close();
    assert.deepEqual(page.fixtureErrors, [], 'Unexpected reconciliation page errors remain blocking');
}

test('one preview renders frozen property evidence and four real diagrams with stable identities', async () => {
    const page = await fixture();
    try {
        await page.locator('#lifecycleReconciliationPreview').click();
        await page.locator('#lifecycleReconciliationAccept').waitFor({state:'visible'});
        assert.equal(await page.locator('[data-reconciliation-scene]').count(),4);
        assert.equal(await page.locator('[data-reconciliation-preview]').getAttribute('data-preview-fingerprint'),'frozen-preview-one');
        assert.equal(await page.locator('[data-reconciliation-scene="base"] [data-node-id="one"]').getAttribute('transform'),
            await page.locator('[data-reconciliation-scene="local"] [data-node-id="one"]').getAttribute('transform'));
        assert.match(await page.locator('[data-reconciliation-scene="base"]').textContent(),/Original name/);
        assert.match(await page.locator('[data-reconciliation-scene="local"]').textContent(),/Local name/);
        assert.equal(await page.locator('[data-reconciliation-scene="incoming"] [data-node-id]').count(),0);
        assert.match(await page.locator('[data-reconciliation-category="EXCHANGE_EVIDENCE"]').textContent(),/x-exchange-artifact/);
        assert.equal(await page.locator('[data-reconciliation-preview] img').count(),0,'Property evidence is text, never markup');
        await page.locator('[data-reconciliation-scene="local"] [data-node-id="one"]').click();
        assert.equal(await page.locator('[data-reconciliation-properties] [data-block-id="element:one"][aria-selected="true"]').count(),2);
        await page.locator('#lifecycleReconciliationAccept').click();
        await page.waitForFunction(() => window.navigationRefreshes === 1);
        const request = await page.evaluate(()=>writes.find(write=>write.url.includes('/accept')));
        assert.equal(request.body.previewFingerprint,'frozen-preview-one');
        assert.equal(request.body.headCommit,'b'.repeat(40));
        assert.equal(request.body.context.revision,4);
        assert.equal(request.options.headers['If-Match'],'"workspace-revision-4"');
        assert.equal(request.policy.pinnedWorkspaceId,'copy');
        assert.equal(request.policy.retries,0);
    } finally { await closeFixture(page); }
});

test('each side renders shared element types with its own recorded root mapping', async () => {
    const page = await fixture();
    try {
        const colors = await page.evaluate(() => {
            const comparison = window.previewValue.comparison;
            comparison.typeRoots = {};
            comparison.sceneTypeRoots = {
                base: {Collection: 'BP'}, local: {Collection: 'BP'},
                incoming: {Collection: 'CP'}, proposed: {Collection: 'CP'}
            };
            for (const scene of Object.values(comparison.scenes)) {
                scene.nodes = [{...comparison.scenes.base.nodes[0], type: 'Collection'}];
            }
            const palette = window.TaxonomyArchitectureDiagram.taxonomyPalette();
            return {before: palette.layer('BP'), after: palette.layer('CP')};
        });
        await page.locator('#lifecycleReconciliationPreview').click();
        await page.locator('#lifecycleReconciliationAccept').waitFor({state:'visible'});
        assert.notEqual(colors.before, colors.after);
        for (const [part, color] of Object.entries({base: colors.before, local: colors.before,
            incoming: colors.after, proposed: colors.after})) {
            const card = page.locator(`[data-reconciliation-scene="${part}"] [data-node-id="one"] .editor-node-card`);
            assert.equal(await card.getAttribute('fill'), color);
        }
    } finally { await closeFixture(page); }
});

test('editing the intent clears its visual evidence until another matching preview arrives', async () => {
    const page = await fixture();
    try {
        await page.locator('#lifecycleReconciliationPreview').click();
        await page.locator('#lifecycleReconciliationAccept').waitFor({state:'visible'});
        await page.locator('#lifecycleReconciliationReason').fill('Changed rationale');
        assert.equal(await page.locator('[data-reconciliation-preview]').count(),0);
        assert.equal(await page.locator('#lifecycleReconciliationAccept').isHidden(),true);
        await page.evaluate(()=>{previewValue.fingerprint='frozen-preview-two';});
        await page.locator('#lifecycleReconciliationPreview').click();
        await page.locator('#lifecycleReconciliationAccept').waitFor({state:'visible'});
        assert.equal(await page.locator('[data-reconciliation-preview]').getAttribute('data-preview-fingerprint'),'frozen-preview-two');
    } finally { await closeFixture(page); }
});

test('changing workspace removes a displayed comparison and rejects late preview evidence', async () => {
    for (const late of [false,true]) {
        const page = await fixture();
        try {
            if (late) await page.evaluate(()=>{window.deferPreview=true;});
            await page.locator('#lifecycleReconciliationPreview').click();
            if (late) await page.waitForFunction(()=>Boolean(window.resolvePreview));
            else await page.locator('#lifecycleReconciliationAccept').waitFor({state:'visible'});
            await page.evaluate(async()=>{
                scope = {...scope,workspaceScopeKey:'another-copy'};
                overview = {...overview,context:structuredClone(scope)};
                await TaxonomyArchitectureLifecycle.refresh(scope,null);
                window.resolvePreview?.();
            });
            assert.equal(await page.locator('[data-reconciliation-preview]').count(),0);
            assert.equal(await page.locator('#lifecycleReconciliationAccept').isHidden(),true);
            assert.equal(await page.locator('#lifecycleReconciliationPreview').isDisabled(),true);
            assert.equal(await page.evaluate(()=>writes.filter(write=>write.url.includes('/accept')).length),0);
        } finally { await closeFixture(page); }
    }
});

test('unresolved choices show their sources without inventing an empty proposed model', async () => {
    const page = await fixture(true);
    try {
        await page.locator('#lifecycleReconciliationPreview').click();
        await page.locator('[data-conflict-id]').waitFor({state:'visible'});
        assert.equal(await page.locator('[data-reconciliation-scene]').count(),3);
        assert.equal(await page.locator('[data-reconciliation-scene="proposed"]').count(),0);
        assert.match(await page.locator('[data-reconciliation-preview]').textContent(),/lifecycle.reconcile.proposedUnavailable/);
        assert.equal(await page.locator('#lifecycleReconciliationAccept').isHidden(),true);
    } finally { await closeFixture(page); }
});
