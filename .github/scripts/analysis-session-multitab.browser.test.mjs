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
  const continuation = { request: { businessText: 'Hospital communications', provider: 'LOCAL' },
    recovery: { id: 'continuation-1', version: 1, state: 'RUNNING', completedCalls: 0, openQuestions: [] },
    clusterOperationId: 'operation-1' };
  await context.route('http://taxonomy.test/**', async route => {
    const request = route.request(), url = new URL(request.url());
    const json = value => route.fulfill({ status: 200, json: value });
    if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="de"><body>
      <textarea id="businessText" aria-label="Anforderung"></textarea>
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
  async function openPage() {
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.clock.install();
    await page.goto('http://taxonomy.test/');
    await page.evaluate(() => {
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
    await second.waitForFunction(() => window.TaxonomyState.recoveryContext?.distributed === true);
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
  } finally { await context.close(); }
});
