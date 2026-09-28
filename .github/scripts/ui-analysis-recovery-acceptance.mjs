import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';

// Execute against an isolated Spring application with the loopback provider fixture.
// Never send test passwords, requirement writes or failure injection to a deployed application.
const base = new URL(process.env.TAXONOMY_QA_BASE_URL || 'http://127.0.0.1:18080');
const provider = new URL(process.env.TAXONOMY_QA_PROVIDER_URL || 'http://127.0.0.1:18081');
for (const url of [base, provider]) {
  assert(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
    && !url.username && !url.password && !url.search && !url.hash,
  'Recovery acceptance only supports explicit loopback test servers');
}
const output = process.env.TAXONOMY_QA_OUTPUT || fileURLToPath(new URL('../../target/analysis-recovery-browser/', import.meta.url));
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true,
  ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}) });
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await context.newPage();
const evidence = { tests: [], errors: [] };
page.on('pageerror', error => evidence.errors.push(error.message));
async function control(values) {
  const response = await fetch(new URL('/control', provider), { method: 'POST', body: JSON.stringify(values) });
  assert(response.ok);
}
async function paused() {
  await page.waitForFunction(() => window.TaxonomyState?.analysisRecovery?.state === 'PAUSED',
    null, { timeout: 60_000 });
  await page.locator('#analysisRecoveryDialog[open]').waitFor();
}
async function viewportContains(selector) {
  const result = await page.locator(selector).evaluate(element => {
    const v = window.visualViewport;
    const left = v?.offsetLeft || 0, top = v?.offsetTop || 0;
    const width = v?.width || innerWidth, height = v?.height || innerHeight;
    const r = element.getBoundingClientRect();
    return { left, top, width, height, rect: { x: r.x, y: r.y, right: r.right, bottom: r.bottom },
      visible: r.width > 0 && r.height > 0 && r.left >= left - 1 && r.top >= top - 1
        && r.right <= left + width + 1 && r.bottom <= top + height + 1 };
  });
  assert(result.visible, `${selector} outside visual viewport: ${JSON.stringify(result)}`);
  return result;
}
try {
  await page.goto(new URL('/login', base).href);
  await page.locator('[name="username"]').fill(process.env.TAXONOMY_QA_USER || 'admin');
  await page.locator('[name="password"]').fill(process.env.TAXONOMY_QA_PASSWORD || 'qa-continuation-admin-2026');
  await Promise.all([page.waitForURL(url => !url.pathname.includes('login')), page.locator('button[type="submit"]').click()]);
  await page.waitForFunction(() => window.TaxonomyAnalysisSession?.state?.().ready === true
    && !window.__taxonomyAnalysisSessionLoading, null, { timeout: 60_000 });
  await control({ calls: [], mode: 'once', failed: false, delay: 0 });
  await page.locator('#businessText').fill('Provide resilient hospital communications with traceable architecture decisions.');
  if (await page.locator('#providerSelect option[value="CUSTOM_OPENAI"]').count())
    await page.locator('#providerSelect').selectOption('CUSTOM_OPENAI');
  await page.locator('#copilotBtn').click();
  // Checking geometry never scrolls the tested status into view.
  await page.locator('#analysisRecoveryBar').waitFor({ state: 'visible', timeout: 4000 });
  evidence.tests.push({ name: 'running status is initially in viewport', geometry: await viewportContains('#analysisRecoveryBar') });
  await paused();
  assert.equal(await page.evaluate(() => window.TaxonomyState.currentRawScores.BP), 100);
  evidence.tests.push({ name: 'failure pauses with valid parent evidence', geometry: await viewportContains('#analysisRecoveryDialog') });
  if (process.env.RECOVERY_RED_ONLY) throw new Error('Red probe unexpectedly reached implemented recovery dialog');
  const runId = await page.evaluate(() => window.TaxonomyState.analysisRecovery.id);
  const before = await (await fetch(provider)).json();
  assert.equal(before.calls.length, 3);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await paused();
  assert.equal(await page.evaluate(() => window.TaxonomyState.analysisRecovery.id), runId);
  assert.equal((await (await fetch(provider)).json()).calls.length, 3, 'reload repeated provider work');
  evidence.tests.push({ name: 'reload retains paused run without provider calls' });
  for (const size of [{ width: 320, height: 480 }, { width: 768, height: 220 }]) {
    await page.setViewportSize(size);
    await page.waitForTimeout(100); // Allow the viewport resize frame to paint; not an analysis timeout.
    await viewportContains('#analysisRecoveryDialog');
    for (const id of ['#analysisRecoveryRetry', '#analysisRecoverySkip', '#analysisRecoveryCancelDialog'])
      await viewportContains(id);
    evidence.tests.push({ name: 'dialog actions visible', viewport: size });
  }
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.locator('#analysisRecoveryRetry').click();
  await page.waitForFunction(() => window.TaxonomyState?.analysisRecovery?.state === 'COMPLETED',
    null, { timeout: 60_000 });
  const after = await (await fetch(provider)).json();
  assert.equal(after.calls.length, 11);
  assert.equal(after.calls.filter(call => call.keys.length === 1 && call.keys[0] === 'BP').length, 1);
  assert.equal(after.calls.filter(call => call.keys.length === 1 && call.keys[0] === 'BP-1000').length, 1);
  evidence.tests.push({ name: 'only the failed question is repeated', providerCalls: after.calls });
  assert.deepEqual(evidence.errors, []);
  evidence.result = 'PASS';
} catch (error) {
  evidence.failure = error.stack;
  console.error(error);
  process.exitCode = 1;
} finally {
  await page.screenshot({ path: `${output}/last-state.png` }).catch(error => { evidence.screenshotFailure = error.message; });
  await writeFile(`${output}/result.json`, JSON.stringify(evidence, null, 2));
  await browser.close();
}
