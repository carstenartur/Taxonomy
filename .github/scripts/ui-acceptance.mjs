import { chromium, firefox } from '@playwright/test';
import { deepStrictEqual } from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { observeHttpFailures } from './ui-http-failure-evidence.mjs';
import { signInWithKeyboard, verifyAnonymousLocalLogin } from './ui-local-login.mjs';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_UI_USERNAME || 'admin';
const password = process.env.TAXONOMY_UI_PASSWORD || 'ui-acceptance-password';
const browserName = process.env.TAXONOMY_BROWSER || 'chromium';
const profile = process.env.TAXONOMY_UI_PROFILE || 'desktop-chromium';
const mode = process.env.TAXONOMY_UI_MODE || 'full';
const javaScriptEnabled = mode !== 'login-no-js';
const viewport = {
  width: Number.parseInt(process.env.TAXONOMY_VIEWPORT_WIDTH || '1440', 10),
  height: Number.parseInt(process.env.TAXONOMY_VIEWPORT_HEIGHT || '1000', 10)
};
const reportDirectory = !process.env.TAXONOMY_UI_REPORT
  || !process.env.TAXONOMY_UI_SCREENSHOT
  ? await mkdtemp(join(tmpdir(), 'taxonomy-ui-'))
  : null;
const reportPath = process.env.TAXONOMY_UI_REPORT
  || join(reportDirectory, `${profile}.json`);
const screenshotPath = process.env.TAXONOMY_UI_SCREENSHOT
  || join(reportDirectory, `${profile}.png`);
const baseOrigin = new URL(baseUrl).origin;
const browserType = { chromium, firefox }[browserName];
if (!browserType) throw new Error(`Unsupported browser: ${browserName}`);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const browser = await browserType.launch({ headless: true });
const context = await browser.newContext({ viewport, reducedMotion: 'reduce', javaScriptEnabled });
const page = await context.newPage();
const externalRequests = [];
const consoleErrors = [];
const httpFailures = observeHttpFailures(page);
const checks = [];
let architectureLens = null;

function passed(name) { checks.push(name); }

async function navigateTo(pageName) {
  if (pageName === 'help') {
    await page.locator('#main-tab-help').click();
    await page.locator('#tab-help').waitFor({ state: 'visible' });
    return;
  }

  const settingsDestination = {
    admin: 'adminNavTab',
    preferences: 'preferencesNavTab'
  }[pageName];
  if (settingsDestination) {
    const settings = page.locator('#applicationSettings');
    if (await settings.getAttribute('open') === null) {
      await page.locator('#applicationSettings > summary').click();
    }
    const control = page.locator(`#applicationSettings #${settingsDestination}`);
    await control.waitFor({ state: 'visible', timeout: 20_000 });
    await control.click();
    await page.locator(`#tab-${pageName}`).waitFor({ state: 'visible' });
    return;
  }

  const responsiveSelect = page.locator('#mobileMainNavigationSelect');
  if (await responsiveSelect.isVisible().catch(() => false)) {
    await responsiveSelect.selectOption(pageName);
  } else {
    await page.locator(`#mainNavTabs [data-page="${pageName}"]`).click();
  }
  await page.locator(`#tab-${pageName}`).waitFor({ state: 'visible' });
}

page.on('request', request => {
  const url = request.url();
  if (url.startsWith('data:') || url.startsWith('blob:')) return;
  if (new URL(url).origin !== baseOrigin) externalRequests.push(url);
});
page.on('console', message => {
  if (message.type() === 'error') consoleErrors.push(message.text());
});
page.on('pageerror', error => consoleErrors.push(error.message));

async function auditApplication() {
  await verifyAnonymousLocalLogin(page, {
    baseUrl, username, password, javaScriptEnabled, passed
  });
  if (!javaScriptEnabled) return;

  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  await signInWithKeyboard(page, username, password);
  passed('keyboard login');

  await page.locator('#taxonomyTree [role="treeitem"]').first()
    .waitFor({ state: 'visible', timeout: 90_000 });
  const onboardingDismiss = page.locator('#onboardingDismiss');
  if (await onboardingDismiss.isVisible().catch(() => false)) {
    await onboardingDismiss.focus();
    await page.keyboard.press('Enter');
  }

  assert(await page.locator('#mainNavTabs').getAttribute('role') === 'tablist',
    'Main navigation lacks tablist semantics');
  assert(JSON.stringify(await page.locator('.taxonomy-app-primary [data-app-route]')
    .evaluateAll(links => links.map(link => link.dataset.appRoute)))
      === JSON.stringify(['projects', 'analysis', 'architecture', 'graph', 'versions', 'dsl-editor']),
    'The application must expose the six retained primary destinations exactly once');
  assert(await page.locator('[data-app-route="architecture"]').count() === 1,
    'Architecture has more than one primary application destination');
  const responsiveSelect = page.locator('#mobileMainNavigationSelect');
  if (await responsiveSelect.isVisible().catch(() => false)) {
    await responsiveSelect.focus();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => {
      const select = document.querySelector('#mobileMainNavigationSelect');
      const pane = document.querySelector('#tab-architecture');
      return select?.value === 'architecture'
        && pane && !pane.classList.contains('d-none');
    });
    assert(await responsiveSelect.inputValue() === 'architecture',
      'Keyboard navigation did not select Architecture in the responsive control');
    await responsiveSelect.selectOption('analyze');
    await page.locator('#tab-analyze').waitFor({ state: 'visible' });
    passed('responsive keyboard section navigation');
  } else {
    const analyzeTab = page.locator('#mainNavTabs [data-page="analyze"]');
    await analyzeTab.focus();
    await page.keyboard.press('ArrowRight');
    await page.waitForFunction(() => document.querySelector(
      '#mainNavTabs [data-page="architecture"]')?.getAttribute('aria-selected') === 'true');
    assert(await page.locator('#mainNavTabs [data-page="architecture"]').getAttribute('aria-selected') === 'true',
      'Arrow-key navigation did not activate Architecture');
    await analyzeTab.click();
    passed('keyboard tab navigation');
  }

  const firstTreeItem = page.locator('#taxonomyTree [role="treeitem"]').first();
  await firstTreeItem.focus();
  await page.keyboard.press('ArrowDown');
  assert(await page.evaluate(() => document.activeElement?.getAttribute('role') === 'treeitem'),
    'Taxonomy tree did not retain keyboard focus on a tree item');
  const focusStyle = await page.evaluate(() => {
    const style = getComputedStyle(document.activeElement);
    return { outline: style.outlineStyle, shadow: style.boxShadow };
  });
  assert(focusStyle.outline !== 'none' || focusStyle.shadow !== 'none',
    'Focused taxonomy item has no visible focus indicator');
  passed('tree keyboard and visible focus');

  assert(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches),
    'Reduced-motion browser preference was not applied');
  passed('reduced-motion preference');

  if (mode === 'full') {
    const interactive = page.locator('#interactiveMode');
    if (await interactive.isChecked()) await interactive.uncheck();
    const architectureView = page.locator('#includeArchitectureView');
    if (!(await architectureView.isChecked())) await architectureView.check();
    await page.locator('#businessText').fill(
      'Provide secure hospital communications with traceable architecture decisions and resilient data exchange.');
    await page.locator('#analyzeBtn').focus();
    await page.keyboard.press('Enter');
    await page.locator('#statusArea').waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForFunction(() => {
      const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
      return text.includes('complete') || text.includes('completed');
    }, null, { timeout: 120_000 });
    assert(await page.evaluate(() => Object.values(window.TaxonomyState?.currentScores || {})
      .some(score => Number(score) > 0)),
      'Real UI analysis completed without producing scored taxonomy state');
    await page.locator('#viewList').click();
    await page.locator('.tax-pct').first().waitFor({ state: 'attached', timeout: 10_000 });
    assert(await page.locator('.tax-pct').count() > 0,
      'Scored taxonomy nodes were not restored in the result list');
    assert(await page.locator('[role="treeitem"][aria-label*="Relevance"]').count() > 0,
      'Dynamic scores were not synchronized to accessible tree-item names');

    const retainedText = await page.locator('#businessText').inputValue();
    const documentIdentity = await page.evaluate(() => {
      window.__unifiedArchitectureAcceptanceDocument = crypto.randomUUID();
      return window.__unifiedArchitectureAcceptanceDocument;
    });
    await page.locator('#analysisLensAction').focus();
    await page.keyboard.press('Enter');
    await page.locator('#tab-architecture').waitFor({ state: 'visible' });
    await page.locator('#architectureSourceLensTitle').waitFor({ state: 'visible' });
    await page.waitForFunction(() => window.TaxonomyArchitectureWorkspace?.getSource().kind === 'analysis'
      && document.activeElement?.id === 'architectureSourceLensTitle');
    assert(await page.evaluate(() => window.__unifiedArchitectureAcceptanceDocument) === documentIdentity,
      'Opening the analysis lens replaced the application document');
    assert(await page.locator('#businessText').inputValue() === retainedText,
      'Opening the analysis lens changed the retained requirement input');
    assert(await page.locator('#architectureWorkingSource').isHidden(),
      'The analysis lens revealed the working editor instead of the analysis source');
    assert(new URL(page.url()).searchParams.get('architectureSource') === 'analysis',
      'The analysis lens did not publish its source identity in the URL');
    passed('analysis lens opens in place with retained input and keyboard focus');

    await navigateTo('architecture');
    const impactMap = page.locator('.impact-map-workbench');
    await impactMap.waitFor({ state: 'visible', timeout: 30_000 });
    assert(await impactMap.locator('.impact-map-kpi').count() >= 4,
      'Impact map did not render its orientation KPIs');
    const impactNodes = impactMap.locator('.impact-map-node');
    assert(await impactNodes.count() > 0,
      'Impact map did not render readable element cards');
    assert(await impactMap.locator('.impact-map-node.is-muted').count() === 0,
      'Impact map starts with unrelated elements muted before the user selects anything');
    assert((await impactMap.locator('.impact-map-details').getAttribute('class')).includes('is-empty'),
      'Impact map starts with an implicit selection instead of the overview hint');
    const firstImpactNode = impactNodes.first();
    const impactCode = await firstImpactNode.getAttribute('data-node-id');
    assert(Boolean(impactCode), 'Impact-map element lacks its stable node code');
    await firstImpactNode.click();
    assert(!(await impactMap.locator('.impact-map-details').getAttribute('class')).includes('is-empty'),
      'Selecting an impact-map element did not expose its details');
    const evidenceBeforeInspection = await page.evaluate(() => JSON.stringify(window.TaxonomyState.currentArchView));
    await page.locator('#architectureSourceLensUseAi').uncheck();
    await page.locator('#architectureSourceLensEnabled').check();
    const investigationResponse = page.waitForResponse(response =>
      new URL(response.url()).pathname.endsWith('/api/architecture/focus/suggestions')
        && response.request().method() === 'POST');
    await page.locator('#architectureSourceLensInspect').click();
    const investigationHttp = await investigationResponse;
    const request = investigationHttp.request().postDataJSON();
    architectureLens = { source: request.source, sourceId: request.sourceId,
      useAi: request.useAi, status: investigationHttp.status() };
    assert(investigationHttp.status() === 200,
      `Local retained-source investigation failed: ${investigationHttp.status()} ${await investigationHttp.text()}`);
    const investigation = await investigationHttp.json();
    assert(request.useAi === false && investigation.modelCalls === 0,
      'The deterministic source investigation used a provider call');
    assert(request.source.kind === 'ANALYSIS_DRAFT' && request.sourceId === impactCode,
      'The lens investigated a different source or selection');
    deepStrictEqual(investigation.source, request.source,
      'The source investigation changed its exact retained-source coordinates');
    assert(/^[0-9a-f]{64}$/i.test(investigation.sourceHash || ''),
      'The source investigation did not retain its source hash');
    assert(await page.evaluate(() => JSON.stringify(window.TaxonomyState.currentArchView)) === evidenceBeforeInspection,
      'Inspecting the retained analysis mutated its displayed evidence');
    architectureLens = { source: investigation.source, sourceId: investigation.sourceId,
      sourceHash: investigation.sourceHash, method: investigation.method,
      outcome: investigation.outcome, modelCalls: investigation.modelCalls };
    await page.locator('#architectureSourceLensEnabled').uncheck();
    await page.locator('#architectureSourceLensTitle').scrollIntoViewIfNeeded();
    await page.screenshot({ path: screenshotPath.replace(/\.png$/, '-architecture-lens.png'), fullPage: false });
    passed('local source lens investigates the selected retained draft without provider calls or evidence changes');
    const impactSearch = impactMap.locator('.impact-map-search');
    await impactSearch.fill(impactCode);
    await impactSearch.press('Enter');
    assert(await impactMap.locator('.impact-map-node.is-search-match').count() > 0,
      'Impact-map search did not highlight the matching element');
    await impactMap.locator('.impact-map-layer-column').first().evaluate(element => {
      element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    assert((await impactMap.locator('.impact-map-details').getAttribute('class')).includes('is-empty'),
      'Clicking empty impact-map space did not clear the selection');
    await impactSearch.fill('');
    passed('impact map layout, search, details, and background clearing');

    await navigateTo('analyze');
    await page.locator('#businessText').fill(
      'Provide secure hospital communications with an additional emergency notification capability.');
    await page.locator('#businessText.stale-results').waitFor({ state: 'visible', timeout: 10_000 });
    passed('analysis, accessible scores, and stale state');
  }

  await navigateTo('versions');
  passed('versions navigation');

  if (mode === 'full') {
    await navigateTo('dsl-editor');
    const editor = page.locator('#dslEditorContainer .cm-content');
    await editor.waitFor({ state: 'visible', timeout: 30_000 });
    assert(await editor.getAttribute('aria-label') === 'TaxDSL editor',
      'TaxDSL CodeMirror surface lacks an accessible name');
    const shortcuts = await editor.getAttribute('aria-keyshortcuts') || '';
    assert(shortcuts.includes('Alt+Shift+F') && shortcuts.includes('Escape'),
      'TaxDSL editor does not expose command-discovery keyboard shortcuts');
    await editor.focus();
    await page.keyboard.press('Escape');
    assert(await page.evaluate(() => !document.activeElement?.closest('.cm-editor')),
      'Escape did not move focus out of the CodeMirror editor');
    passed('DSL editor accessible name, commands, and focus escape');

    await page.waitForFunction(() => {
      const tab = document.querySelector('#adminNavTab');
      return tab && getComputedStyle(tab).display !== 'none';
    }, null, { timeout: 20_000 });
    await navigateTo('admin');
    passed('admin role surface');
  } else {
    await navigateTo('help');
    passed('responsive read-only navigation');
  }

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  assert(overflow.scrollWidth <= overflow.clientWidth + 2,
    `Page overflows viewport: ${overflow.scrollWidth} > ${overflow.clientWidth}`);
  passed('viewport reflow');
}

let auditError = null;
try {
  await auditApplication();
  assert(externalRequests.length === 0,
    `Application made external browser requests: ${externalRequests.join(', ')}`);
  assert(consoleErrors.length === 0,
    `Browser console errors: ${consoleErrors.join(' | ')}`);
  passed('local assets and clean console');
} catch (error) {
  auditError = error?.stack || String(error);
  process.exitCode = 1;
} finally {
  await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
  await writeFile(reportPath, JSON.stringify({
    profile, browser: browserName, mode, viewport, javaScriptEnabled,
    contextPath: new URL(baseUrl).pathname.replace(/\/+$/, ''), checks,
    architectureLens, externalRequests, consoleErrors, httpFailures, auditError
  }, null, 2) + '\n', 'utf8');
  await context.close();
  await browser.close();
}

if (auditError) throw new Error(auditError);
console.log(`UI acceptance passed for ${profile}: ${checks.join(', ')}`);
