import { chromium } from '@playwright/test';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_UI_USERNAME || 'admin';
const password = process.env.TAXONOMY_UI_PASSWORD || 'ui-acceptance-password';
const baseOrigin = new URL(baseUrl).origin;

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const externalRequests = [];
const consoleErrors = [];

page.on('request', request => {
  const url = request.url();
  if (url.startsWith('data:') || url.startsWith('blob:')) return;
  const origin = new URL(url).origin;
  if (origin !== baseOrigin) externalRequests.push(url);
});
page.on('console', message => {
  if (message.type() === 'error') consoleErrors.push(message.text());
});
page.on('pageerror', error => consoleErrors.push(error.message));

try {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), { timeout: 30_000 }),
    page.locator('button[type="submit"], input[type="submit"]').first().click()
  ]);

  await page.locator('#taxonomyTree [role="treeitem"]').first()
    .waitFor({ state: 'visible', timeout: 90_000 });

  const onboardingDismiss = page.locator('#onboardingDismiss');
  if (await onboardingDismiss.isVisible().catch(() => false)) {
    await onboardingDismiss.click();
  }

  // Navigation is a real keyboard-operable tablist.
  const mainTabs = page.locator('#mainNavTabs');
  assert(await mainTabs.getAttribute('role') === 'tablist', 'Main navigation lacks tablist semantics');
  const analyzeTab = page.locator('#mainNavTabs [data-page="analyze"]');
  await analyzeTab.focus();
  await page.keyboard.press('ArrowRight');
  assert(await page.locator('#mainNavTabs [data-page="architecture"]').getAttribute('aria-selected') === 'true',
    'Arrow-key navigation did not activate Architecture');
  await analyzeTab.click();

  const interactive = page.locator('#interactiveMode');
  if (await interactive.isChecked()) await interactive.uncheck();
  await page.locator('#businessText').fill(
    'Provide secure hospital communications with traceable architecture decisions and resilient data exchange.');
  await page.locator('#analyzeBtn').click();

  await page.locator('#statusArea').waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForFunction(() => {
    const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
    return text.includes('complete') || text.includes('completed');
  }, null, { timeout: 120_000 });

  assert(await page.locator('.tax-pct').count() > 0,
    'Real UI analysis completed without rendering scored taxonomy nodes');
  assert(await page.locator('[role="treeitem"][aria-label*="Relevance"]').count() > 0,
    'Dynamic scores were not synchronized to accessible tree-item names');

  // Stale-result protection must react to a real user edit.
  await page.locator('#businessText').fill(
    'Provide secure hospital communications with an additional emergency notification capability.');
  await page.locator('#businessText.stale-results').waitFor({ state: 'visible', timeout: 10_000 });

  // ROLE_ADMIN controls the admin surface without a second shared password.
  await page.waitForFunction(() => {
    const tab = document.querySelector('#adminNavTab');
    return tab && getComputedStyle(tab).display !== 'none';
  }, null, { timeout: 20_000 });
  await page.locator('#mainNavTabs [data-page="admin"]').click();
  await page.locator('#tab-admin').waitFor({ state: 'visible' });

  // Technical code inputs must offer recognition-based suggestions.
  await page.evaluate(() => {
    window.navigateToPage('analyze');
    document.getElementById('newRelSource')?.setAttribute('data-check', 'true');
  });
  const sourceInput = page.locator('#newRelSource');
  if (await sourceInput.count()) {
    assert(await sourceInput.getAttribute('list') === 'taxonomyNodeCodeOptions',
      'Relation source field lacks taxonomy-code suggestions');
  }

  assert(externalRequests.length === 0,
    `Application made external browser requests: ${externalRequests.join(', ')}`);
  assert(consoleErrors.length === 0,
    `Browser console errors: ${consoleErrors.join(' | ')}`);

  console.log('UI acceptance passed: login, navigation, analysis, accessibility state, stale warning, admin role, and local assets.');
} finally {
  await browser.close();
}
