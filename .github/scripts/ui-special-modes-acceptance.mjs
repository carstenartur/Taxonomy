import AxeBuilder from '@axe-core/playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { navigateToPage, openRoleSession, ROLE_ACCOUNTS } from './ui-role-fixtures.mjs';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const outputDir = path.resolve(process.env.TAXONOMY_UI_OUTPUT_DIR || 'target/ui-special-modes');
const checks = [];
const findings = [];
let auditError = null;
let browser;
let context;
let page;

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function isAnalyzeResponseForText(response, expectedText) {
  try {
    const url = new URL(response.url());
    if (response.request().method() !== 'POST' || url.pathname !== '/api/analyze') {
      return false;
    }
    return response.request().postDataJSON()?.businessText === expectedText;
  } catch {
    return false;
  }
}

async function waitForTaxonomyReady(timeout = 180_000) {
  try {
    await page.waitForFunction(async () => {
      try {
        const response = await fetch('/api/status/startup', {
          credentials: 'same-origin',
          headers: { Accept: 'application/json' }
        });
        if (!response.ok) return false;
        const status = await response.json();
        window.__taxonomyStartupStatus = status;
        return status?.initialized === true;
      } catch (error) {
        window.__taxonomyStartupStatus = { error: String(error) };
        return false;
      }
    }, null, { timeout, polling: 1_000 });
  } catch (error) {
    const status = await page.evaluate(() => window.__taxonomyStartupStatus || null);
    throw new Error(
      `Taxonomy server did not become ready: ${JSON.stringify(status)}`,
      { cause: error }
    );
  }

  await page.locator('#analyzeBtn').waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForFunction(() => {
    const button = document.querySelector('#analyzeBtn');
    return Boolean(button) && button.disabled === false;
  }, null, { timeout: 30_000 });
}

async function selectSynchronousAnalysisView() {
  const sunburst = page.locator('#viewSunburst');
  await sunburst.scrollIntoViewIfNeeded();
  await sunburst.click();
  await page.waitForFunction(() => window.TaxonomyState?.currentView === 'sunburst', null,
    { timeout: 10_000 });
}

async function submitAnalysisAndWaitForPublishedState(
  businessText,
  expectedStatus,
  timeout = 120_000
) {
  const responsePromise = page.waitForResponse(
    response => isAnalyzeResponseForText(response, businessText),
    { timeout }
  );
  await page.locator('#analyzeBtn').click();
  const response = await responsePromise;
  assert(response.ok(), `Analysis request failed with HTTP ${response.status()}`);

  const payload = await response.json();
  const actualStatus = String(payload.status || '').toUpperCase();
  assert(actualStatus === expectedStatus,
    `Expected ${expectedStatus} analysis response but received ${actualStatus || '(missing)'}`);
  assert(Array.isArray(payload.tree) && payload.tree.length > 0,
    `${expectedStatus} analysis response produced no reusable tree`);
  assert(payload.scores && Object.values(payload.scores).some(value => Number(value) > 0),
    `${expectedStatus} analysis response produced no reusable positive scores`);

  await page.waitForFunction(({ treeCount, scores, businessText }) => {
    const state = window.TaxonomyState;
    const button = document.querySelector('#analyzeBtn');
    const spinner = document.querySelector('#analyzeSpinner');
    return Boolean(button)
      && button.disabled === false
      && (!spinner || spinner.classList.contains('d-none'))
      && state?.lastAnalyzedText === businessText
      && Array.isArray(state.taxonomyData)
      && state.taxonomyData.length === treeCount
      && Object.entries(scores).every(([code, score]) =>
        Number(state.currentScores?.[code]) === Number(score));
  }, {
    treeCount: payload.tree.length,
    scores: payload.scores,
    businessText
  }, { timeout });

  return payload;
}

async function openDetails(selector) {
  const details = page.locator(selector);
  await details.waitFor({ state: 'attached', timeout: 20_000 });
  if ((await details.getAttribute('open')) === null) {
    const summary = details.locator(':scope > summary');
    await summary.scrollIntoViewIfNeeded();
    await summary.click();
    await page.waitForFunction(candidate =>
      document.querySelector(candidate)?.hasAttribute('open'), selector,
    { timeout: 10_000 });
  }
}

async function runAxe(state, include) {
  const result = await new AxeBuilder({ page })
    .include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter(item =>
    ['critical', 'serious', 'moderate'].includes(item.impact));
  findings.push({ state, violations: result.violations, blocking });
  if (blocking.length) {
    throw new Error(`Blocking axe findings in ${state}: ${blocking.map(item => `${item.impact}:${item.id}`).join(', ')}`);
  }
  checks.push(`axe ${state}`);
}

async function screenshot(state, selector) {
  const target = page.locator(selector);
  await target.waitFor({ state: 'visible', timeout: 20_000 });
  await target.screenshot({ path: path.join(outputDir, `${state}.png`), animations: 'disabled' });
  await writeFile(path.join(outputDir, `${state}.html`), await target.evaluate(node => node.outerHTML), 'utf8');
}

async function testPartialAnalysis() {
  const partialMarker = 'PARTIAL_FIXTURE_803';
  const stableAiStatus = async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        level: 'FULL',
        available: true,
        limited: false,
        provider: 'TEST',
        availableProviders: ['TEST']
      })
    });
  };
  await page.route('**/api/ai-status', stableAiStatus);

  try {
    await navigateToPage(page, 'analyze');
    await waitForTaxonomyReady();
    const interactive = page.locator('#interactiveMode');
    if (await interactive.isChecked()) await interactive.uncheck();

    // List and tabs intentionally use SSE. This scenario validates the synchronous
    // POST response and then replaces exactly one POST with a PARTIAL response, so
    // select a non-streaming view explicitly rather than relying on UI timing.
    await selectSynchronousAnalysisView();

    const successfulText = 'Provide traceable and resilient hospital communication services.';
    await page.locator('#businessText').fill(successfulText);
    const fixture = await submitAnalysisAndWaitForPublishedState(successfulText, 'SUCCESS');

    await page.route('**/api/analyze', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'PARTIAL',
          errorMessage: partialMarker,
          warnings: [`${partialMarker}: Some architecture branches were not evaluated.`],
          tree: fixture.tree,
          scores: fixture.scores,
          discrepancies: [],
          provisionalRelations: []
        })
      });
    }, { times: 1 });

    const partialText = 'Provide traceable hospital communication services with a partial provider result.';
    await page.locator('#businessText').fill(partialText);

    // The editor deliberately debounces its stale-result warning. Let that
    // input-owned status settle before starting the new analysis, otherwise its
    // pending callback can clear a correctly published PARTIAL result afterward.
    await page.waitForFunction(() =>
      document.querySelector('#businessText')?.classList.contains('stale-results'),
    null, { timeout: 5_000 });

    const [statusHandle] = await Promise.all([
      page.waitForFunction(marker => {
        const warningText = (
          document.querySelector('#statusArea .alert-warning')?.textContent || ''
        ).trim();
        const liveText = (document.querySelector('#a11yStatus')?.textContent || '').trim();
        return warningText.includes(marker) && liveText.includes(marker)
          ? { warningText, liveText }
          : false;
      }, partialMarker, { timeout: 30_000 }),
      submitAnalysisAndWaitForPublishedState(partialText, 'PARTIAL', 30_000)
    ]);
    const partialUi = await statusHandle.jsonValue();
    assert(partialUi?.warningText?.includes(partialMarker),
      'Partial analysis warning was not published in the visible status area');
    assert(partialUi?.liveText?.includes(partialMarker),
      'Partial analysis warning was not announced in the accessibility live region');
    checks.push('partial analysis status, warning detail, and live announcement');
    await runAxe('analysis-partial', '#tab-analyze');
    await screenshot('analysis-partial', '#tab-analyze');
  } finally {
    await page.unroute('**/api/ai-status', stableAiStatus).catch(() => undefined);
  }
}

async function testTextSpacing() {
  // WCAG 1.4.12 models an external user stylesheet. Keep the product CSP strict
  // and append the test-only override to an already allowed, same-origin CSS
  // response before reloading the authenticated page.
  await page.route('**/css/taxonomy-ergonomics.css', async route => {
    const response = await route.fetch();
    const original = await response.text();
    const override = [
      '',
      '/* WCAG 1.4.12 test-only external user stylesheet */',
      '#tab-analyze, #tab-analyze * {',
      '  line-height: 1.5 !important;',
      '  letter-spacing: 0.12em !important;',
      '  word-spacing: 0.16em !important;',
      '}',
      '#tab-analyze p { margin-bottom: 2em !important; }'
    ].join('\n');
    await route.fulfill({ response, body: original + override });
  });
  await page.reload({ waitUntil: 'networkidle' });
  await page.locator('#mainContent').waitFor({ state: 'visible', timeout: 60_000 });
  await page.evaluate(() => window.TaxonomyI18n?.ready?.());
  await page.locator('#analysisTaskProgress').waitFor({ state: 'visible', timeout: 20_000 });
  const onboardingDismiss = page.locator('#onboardingDismiss');
  if (await onboardingDismiss.isVisible().catch(() => false)) {
    await onboardingDismiss.click();
    await page.locator('#onboardingOverlay').waitFor({ state: 'detached', timeout: 5_000 });
  }
  await page.waitForFunction(() => Boolean(window.TaxonomyRoleSurface?.ready), null, { timeout: 20_000 });
  await page.evaluate(() => window.TaxonomyRoleSurface.ready);
  await navigateToPage(page, 'analyze');
  await openDetails('#analysisSecondaryTools');
  await openDetails('#documentImportPanel');
  await page.locator('#documentImportPanel p').waitFor({ state: 'visible', timeout: 10_000 });

  const spacing = await page.evaluate(() => {
    const sample = document.querySelector('#documentImportPanel p');
    if (!sample) throw new Error('Text-spacing sample paragraph is unavailable');
    const style = getComputedStyle(sample);
    const fontSize = Number.parseFloat(style.fontSize);
    return {
      fontSize,
      lineHeight: Number.parseFloat(style.lineHeight),
      letterSpacing: Number.parseFloat(style.letterSpacing),
      wordSpacing: Number.parseFloat(style.wordSpacing),
      marginBottom: Number.parseFloat(style.marginBottom),
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth
    };
  });
  assert(spacing.lineHeight / spacing.fontSize >= 1.49,
    `Line spacing is below 1.5: ${JSON.stringify(spacing)}`);
  assert(spacing.letterSpacing / spacing.fontSize >= 0.119,
    `Letter spacing is below 0.12em: ${JSON.stringify(spacing)}`);
  assert(spacing.wordSpacing / spacing.fontSize >= 0.159,
    `Word spacing is below 0.16em: ${JSON.stringify(spacing)}`);
  assert(spacing.marginBottom / spacing.fontSize >= 1.99,
    `Paragraph spacing is below 2em: ${JSON.stringify(spacing)}`);
  assert(spacing.scrollWidth <= spacing.clientWidth + 2,
    `Text spacing introduced horizontal scrolling: ${JSON.stringify(spacing)}`);
  checks.push('WCAG text spacing and reflow');
  await runAxe('text-spacing', '#tab-analyze');
  await screenshot('text-spacing', '#documentImportPanel');
}

async function testWorkspaceOffline() {
  await page.route(
    url => url.pathname.endsWith('/api/workspace/sync-state'),
    route => route.abort('internetdisconnected')
  );
  await navigateToPage(page, 'versions');
  await page.locator('#versionsSubTabs [data-versions-tab="sync"]').click();
  await page.locator('#versions-sync').waitFor({ state: 'visible', timeout: 10_000 });
  await page.evaluate(() => window.TaxonomySyncOfflineGuard.refresh().catch(() => undefined));
  const offlineStatus = page.locator('#syncStatePanel [role="status"]');
  await offlineStatus.waitFor({ state: 'visible', timeout: 15_000 });
  const offlineText = (await offlineStatus.textContent()).trim();
  assert(offlineText.length > 0, 'Offline sync status has no accessible text');
  checks.push('workspace offline status and retry guidance');
  await runAxe('workspace-offline', '#versions-sync');
  await screenshot('workspace-offline', '#versions-sync');
}

await mkdir(outputDir, { recursive: true });

try {
  ({ browser, context, page } = await openRoleSession({
    baseUrl,
    role: 'ADMIN',
    browserName: 'chromium',
    viewport: { width: 1024, height: 768 },
    adminUsername: 'admin',
    adminPassword: ROLE_ACCOUNTS.ADMIN.password
  }));

  await testPartialAnalysis();
  await testTextSpacing();
  await testWorkspaceOffline();
} catch (error) {
  auditError = error?.stack || String(error);
  process.exitCode = 1;
} finally {
  const report = { checks, findings, auditError };
  await writeFile(path.join(outputDir, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  if (auditError) console.error(auditError);
  if (context) await context.close().catch(() => undefined);
  if (browser) await browser.close().catch(() => undefined);
}

if (auditError) throw new Error(auditError);
console.log(`Special modes acceptance passed: ${checks.join(', ')}`);
