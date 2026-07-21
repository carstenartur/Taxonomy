import { chromium, firefox } from '@playwright/test';
import { writeFile } from 'node:fs/promises';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_UI_USERNAME || 'admin';
const password = process.env.TAXONOMY_UI_PASSWORD || 'ui-acceptance-password';
const browserName = process.env.TAXONOMY_BROWSER || 'chromium';
const profile = process.env.TAXONOMY_UI_PROFILE || 'desktop-chromium';
const mode = process.env.TAXONOMY_UI_MODE || 'full';
const viewport = {
  width: Number.parseInt(process.env.TAXONOMY_VIEWPORT_WIDTH || '1440', 10),
  height: Number.parseInt(process.env.TAXONOMY_VIEWPORT_HEIGHT || '1000', 10)
};
const reportPath = process.env.TAXONOMY_UI_REPORT || `/tmp/taxonomy-ui-${profile}.json`;
const screenshotPath = process.env.TAXONOMY_UI_SCREENSHOT || `/tmp/taxonomy-ui-${profile}.png`;
const baseOrigin = new URL(baseUrl).origin;
const browserType = { chromium, firefox }[browserName];
if (!browserType) throw new Error(`Unsupported browser: ${browserName}`);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const browser = await browserType.launch({ headless: true });
const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
const page = await context.newPage();
const externalRequests = [];
const consoleErrors = [];
const checks = [];

function passed(name) { checks.push(name); }
page.on('request', request => {
  const url = request.url();
  if (url.startsWith('data:') || url.startsWith('blob:')) return;
  if (new URL(url).origin !== baseOrigin) externalRequests.push(url);
});
page.on('console', message => {
  if (message.type() === 'error') consoleErrors.push(message.text());
});
page.on('pageerror', error => consoleErrors.push(error.message));

let auditError = null;
try {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  const usernameInput = page.locator('input[name="username"]');
  const passwordInput = page.locator('input[name="password"]');
  await usernameInput.focus();
  await usernameInput.fill(username);
  await passwordInput.focus();
  await passwordInput.fill(password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), { timeout: 30_000 }),
    page.keyboard.press('Enter')
  ]);
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
  const analyzeTab = page.locator('#mainNavTabs [data-page="analyze"]');
  await analyzeTab.focus();
  await page.keyboard.press('ArrowRight');
  assert(await page.locator('#mainNavTabs [data-page="architecture"]').getAttribute('aria-selected') === 'true',
    'Arrow-key navigation did not activate Architecture');
  await analyzeTab.click();
  passed('keyboard tab navigation');

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
    await page.locator('#businessText').fill(
      'Provide secure hospital communications with traceable architecture decisions and resilient data exchange.');
    await page.locator('#analyzeBtn').focus();
    await page.keyboard.press('Enter');
    await page.locator('#statusArea').waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForFunction(() => {
      const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
      return text.includes('complete') || text.includes('completed');
    }, null, { timeout: 120_000 });
    assert(await page.locator('.tax-pct').count() > 0,
      'Real UI analysis completed without rendering scored taxonomy nodes');
    assert(await page.locator('[role="treeitem"][aria-label*="Relevance"]').count() > 0,
      'Dynamic scores were not synchronized to accessible tree-item names');
    await page.locator('#businessText').fill(
      'Provide secure hospital communications with an additional emergency notification capability.');
    await page.locator('#businessText.stale-results').waitFor({ state: 'visible', timeout: 10_000 });
    passed('analysis, accessible scores, and stale state');
  }

  await page.locator('#mainNavTabs [data-page="versions"]').click();
  await page.locator('#tab-versions').waitFor({ state: 'visible' });
  passed('versions navigation');

  if (mode === 'full') {
    await page.locator('#mainNavTabs [data-page="dsl-editor"]').click();
    await page.locator('#tab-dsl-editor').waitFor({ state: 'visible' });
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
    await page.locator('#mainNavTabs [data-page="admin"]').click();
    await page.locator('#tab-admin').waitFor({ state: 'visible' });
    passed('admin role surface');
  } else {
    await page.locator('#mainNavTabs [data-page="help"]').click();
    await page.locator('#tab-help').waitFor({ state: 'visible' });
    passed('responsive read-only navigation');
  }

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  assert(overflow.scrollWidth <= overflow.clientWidth + 2,
    `Page overflows viewport: ${overflow.scrollWidth} > ${overflow.clientWidth}`);
  passed('viewport reflow');

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
    profile, browser: browserName, mode, viewport, checks,
    externalRequests, consoleErrors, auditError
  }, null, 2) + '\n', 'utf8');
  await context.close();
  await browser.close();
}

if (auditError) throw new Error(auditError);
console.log(`UI acceptance passed for ${profile}: ${checks.join(', ')}`);
