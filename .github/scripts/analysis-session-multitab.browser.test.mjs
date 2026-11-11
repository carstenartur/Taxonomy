/** Chromium component acceptance: real session controllers and two pages sharing
 * one optimistic draft fixture. This does not replace backend/auth acceptance. */
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async () => {
  browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'],
    ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}) });
});
after(async () => { await browser?.close(); });

test('two pages can open and observe the same running draft without writes or conflicts', async () => {
  const context = await browser.newContext();
  let draft = null;
  const writes = [], failures = [], errors = [];
  const template = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
  const intakeChoices = template.match(/<section id="analysisIntakeChoices"[\s\S]*?<\/section>/)?.[0];
  assert.ok(intakeChoices, 'the real analysis entry controls are mounted');
  const continuation = { request: { businessText: 'Hospital communications', provider: 'LOCAL' },
    recovery: { id: 'continuation-1', version: 1, state: 'RUNNING', completedCalls: 0, openQuestions: [] },
    clusterOperationId: 'operation-1' };
  await context.route('http://taxonomy.test/**', async route => {
    const request = route.request(), url = new URL(request.url());
    const json = value => route.fulfill({ status: 200, json: value });
    if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="de"><head>
      <script data-taxonomy-analysis-session></script>
      </head><body>
      <textarea id="businessText" aria-label="Anforderung"></textarea>
      ${intakeChoices}
      <select id="providerSelect"><option value="LOCAL">LOCAL</option></select>
      <input type="checkbox" id="interactiveMode"><input type="checkbox" id="includeArchitectureView">
      <button id="analyzeBtn">Analyse</button><button id="copilotBtn">Copilot</button>
      <div id="statusArea"></div></body></html>` });
    if (url.pathname === '/api/workspace/current') return json({ workspaceId: 'workspace-1' });
    if (url.pathname.startsWith('/api/analysis-continuations/')) return json(continuation);
    assert.match(url.pathname, /^\/api\/analysis-drafts\/workspace-1(?:\/status)?$/);
    if (request.method() === 'PUT') {
      const body = request.postDataJSON();
      writes.push({ page: request.frame().page(), body });
      if (body.expectedVersion !== (draft?.version ?? null)) {
        failures.push(409);
        return route.fulfill({ status: 409, json: { detail: 'Draft version conflict' } });
      }
      draft = { workspaceId: 'workspace-1', branch: 'draft', version: (draft?.version ?? -1) + 1, payload: body.payload };
    } else assert.equal(request.method(), 'GET');
    return draft ? json(draft) : route.fulfill({ status: 204 });
  });
  async function openPage(query = '') {
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.clock.install();
    await page.goto('http://taxonomy.test/' + query);
    await page.evaluate(() => {
      // This component fixture loads session modules explicitly below. Keep
      // the API client's unrelated companion surfaces from bootstrapping a
      // second script-loading lifecycle into the draft-only server fixture.
      window.TaxonomyRoleSurface = {};
      window.TaxonomyUiSemantics = {};
      window.TaxonomyBrowse = {
        renderView() { document.dispatchEvent(new CustomEvent('taxonomy:view-rendered')); },
        updateExportGroupVisibility() {}
      };
      window.TaxonomyScoring = { renderArchitectureView() {}, renderSuggestedRelations() {} };
      window.TaxonomyRecoveryViewport = { mount: () => ({ update() {}, close() {}, isOpen: () => false, isDismissed: () => true }) };
    });
    for (const file of ['api/taxonomy-api-client.js', 'api/analysis-session-api.js',
      'core/taxonomy-state.js', 'core/taxonomy-analysis-session-core.js',
      'core/taxonomy-analysis-session-api-routing.js', 'core/taxonomy-analysis-session-transport.js',
      'core/taxonomy-analysis-session-ui.js', 'core/taxonomy-analysis-session-draft.js',
      'core/taxonomy-analysis-recovery.js']) {
      await page.addScriptTag({ content: await readFile(path.join(resources, 'static/js', file), 'utf8') });
    }
    await page.evaluate(() => { window.TaxonomyState.taxonomyData = [{ code: 'BP', children: [] }]; });
    await page.addScriptTag({ content: await readFile(path.join(resources, 'static/js/core/taxonomy-analysis-session-projects.js'), 'utf8') });
    assert.equal(await page.evaluate(() => window.TaxonomyAnalysisSession.whenInitialized()), true);
    return page;
  }
  try {
    const first = await openPage();
    await first.locator('#businessText').fill('Hospital communications');
    assert.equal(await first.evaluate(continuation => {
      const S = window.TaxonomyState;
      S.currentView = 'tabs'; S.analysisOperationId = 'operation-1'; S.lastAnalysisStatus = 'IN_PROGRESS';
      S.analysisRecovery = continuation.recovery;
      S.recoveryContext = { id: 'continuation-1', workspaceId: 'workspace-1', request: continuation.request, followups: {} };
      return window.TaxonomyAnalysisSession.saveNow();
    }, continuation), true);
    const second = await openPage();
    await second.waitForFunction(() => window.TaxonomyState.recoveryContext?.distributed === true, null, { timeout: 5000 });
    await second.clock.runFor(2600); // Exercise the real change observer and autosave timer.
    assert.equal(await second.evaluate(() => window.TaxonomyAnalysisSession.saveNow()), true);
    assert.equal(writes.length, 1, 'opening and observing must not increment the shared draft version');
    assert.equal(await second.locator('#businessText').inputValue(), 'Hospital communications');
    assert.equal(await second.evaluate(() => window.TaxonomyState.currentView), 'tabs');

    assert.equal(await first.evaluate(() => {
      window.TaxonomyState.currentRawScores = { BP: 80 };
      window.TaxonomyState.currentEffectiveScores = { BP: 80 };
      window.TaxonomyState.currentScores = window.TaxonomyState.currentEffectiveScores;
      window.TaxonomyState.lastAnalysisStatus = 'SUCCESS';
      return window.TaxonomyAnalysisSession.saveNow();
    }), true);
    await second.evaluate(() => window.__TaxonomyAnalysisSessionContext.reconcileDraft());
    await second.clock.runFor(2600);
    assert.equal(await second.evaluate(() => window.TaxonomyState.currentScores.BP), 80);
    assert.equal(writes.length, 2);
    assert.deepEqual(failures, []);
    assert.deepEqual(errors, []);

    // A genuine competing edit must still preserve the newer server revision.
    await first.locator('#businessText').fill('First edit');
    assert.equal(await first.evaluate(() => window.TaxonomyAnalysisSession.saveNow()), true);
    await second.locator('#businessText').fill('Independent second edit');
    assert.equal(await second.evaluate(() => window.TaxonomyAnalysisSession.saveNow()), false);
    assert.equal(await second.evaluate(() => window.TaxonomyAnalysisSession.state().conflict), true);
    assert.equal(await second.locator('#businessText').inputValue(), 'Independent second edit');
    assert.equal(draft.payload.businessText, 'First edit');
    assert.equal(await first.locator('#analysisIntakeChoices').isVisible(), true);
    assert.equal(await first.locator('#analysisIntakeVersion').isVisible(), false);
    const selected = await openPage('?projectId=1&requirementId=2&requirementVersionId=7');
    assert.equal(await selected.locator('#businessText').inputValue(), 'First edit');
    assert.equal(await selected.locator('#analysisIntakeChoices').isVisible(), false);
    const before = writes.length;
    await selected.locator('#businessText').fill('A proposed new text version');
    assert.equal(await selected.locator('#analysisIntakeVersion').isVisible(), true);
    assert.equal(await selected.locator('#analysisIntakeProject').isVisible(), true);
    assert.equal(writes.length, before, 'displaying intake choices never creates a job or requirement');
    assert.deepEqual(errors, []);

  } finally { await context.close(); }
});

test('native event streams use the requested workspace and nested application path', async () => {
  const context=await browser.newContext(),requests=[];
  await context.route('http://taxonomy.test/**',async route=>{
    const url=new URL(route.request().url());
    if(url.pathname==='/taxonomy/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html><body></body></html>'});
    if(url.pathname==='/taxonomy/api/events'){
      requests.push(url);
      return route.fulfill({status:200,headers:{'content-type':'text/event-stream','cache-control':'no-cache'},body:'data: ready\n\n'});
    }
    return route.fulfill({status:404,body:''});
  });
  try {
    const page=await context.newPage();await page.goto('http://taxonomy.test/taxonomy/');
    await page.evaluate(()=>{
      window.TaxonomyState={};
      window.TaxonomyI18n={getBasePath:()=>'/taxonomy',resolveUrl:value=>typeof value==='string'&&value.startsWith('/')&&!value.startsWith('/taxonomy/')?'/taxonomy'+value:value};
    });
    for(const file of ['taxonomy-analysis-session-core.js','taxonomy-analysis-session-api-routing.js'])
      await page.addScriptTag({path:path.join(resources,'static/js/core',file)});
    const messages=await page.evaluate(async()=>{
      const session=window.__TaxonomyAnalysisSessionContext;session.runtime.workspaceId='analysis-A';
      session.installWorkspaceEventSourceRouting();
      return Promise.all(['/api/events?case=implicit','/api/events?case=private&workspaceId=working-B',
        '/api/events?case=shared&workspaceId='].map(url=>new Promise((resolve,reject)=>{
        const source=new EventSource(url);const timeout=setTimeout(()=>{source.close();reject(new Error('Event stream timed out'));},5000);
        source.onmessage=event=>{clearTimeout(timeout);source.close();resolve(event.data);};
        source.onerror=()=>{clearTimeout(timeout);source.close();reject(new Error('Event stream failed'));};
      })));
    });
    assert.deepEqual(messages,['ready','ready','ready']);
    const pins=Object.fromEntries(requests.map(url=>[url.searchParams.get('case'),url.searchParams.get('workspaceId')]));
    assert.deepEqual(pins,{implicit:'analysis-A',private:'working-B',shared:''});
  } finally {await context.close();}
});
