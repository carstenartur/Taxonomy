import { chromium, firefox, webkit, request } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const adminUsername = process.env.TAXONOMY_UI_ADMIN_USERNAME || 'admin';
const adminPassword = process.env.TAXONOMY_UI_ADMIN_PASSWORD || 'ui-state-admin-password';
const profileId = process.env.TAXONOMY_UI_PROFILE || 'desktop-user-chromium';
const browserName = process.env.TAXONOMY_BROWSER || 'chromium';
const role = process.env.TAXONOMY_ROLE || 'USER';
const physicalWidth = Number.parseInt(process.env.TAXONOMY_VIEWPORT_WIDTH || '1440', 10);
const physicalHeight = Number.parseInt(process.env.TAXONOMY_VIEWPORT_HEIGHT || '1000', 10);
const zoom = Number.parseFloat(process.env.TAXONOMY_ZOOM || '1');
const forcedColors = process.env.TAXONOMY_FORCED_COLORS === 'true';
const outputDir = path.resolve(
  process.env.TAXONOMY_UI_OUTPUT_DIR || path.join('target', 'ui-state', profileId));
const matrixPath = process.env.TAXONOMY_UI_MATRIX || '.github/ui-acceptance-matrix.json';

const browserType = { chromium, firefox, webkit }[browserName];
if (!browserType) throw new Error(`Unsupported browser: ${browserName}`);
if (!['USER', 'ARCHITECT', 'ADMIN'].includes(role)) throw new Error(`Unsupported role: ${role}`);
if (!Number.isFinite(zoom) || zoom < 1) throw new Error(`Invalid zoom factor: ${zoom}`);

const matrix = JSON.parse(await readFile(matrixPath, 'utf8'));
if (matrix.schemaVersion !== 1) throw new Error(`Unsupported UI matrix schema: ${matrix.schemaVersion}`);
const declaredProfile = matrix.profiles.find(profile => profile.id === profileId);
if (!declaredProfile) throw new Error(`Profile ${profileId} is missing from ${matrixPath}`);
if (declaredProfile.browser !== browserName || declaredProfile.role !== role
    || declaredProfile.zoom !== zoom || Boolean(declaredProfile.forcedColors) !== forcedColors) {
  throw new Error(`Workflow/profile drift for ${profileId}`);
}

const effectiveViewport = {
  width: Math.max(320, Math.floor(physicalWidth / zoom)),
  height: Math.max(240, Math.floor(physicalHeight / zoom))
};
const credentials = {
  USER: { username: 'qa-ui-user', password: 'qa-ui-user-password-2026', roles: ['USER'] },
  ARCHITECT: {
    username: 'qa-ui-architect', password: 'qa-ui-architect-password-2026',
    roles: ['USER', 'ARCHITECT']
  },
  ADMIN: { username: adminUsername, password: adminPassword, roles: ['USER', 'ARCHITECT', 'ADMIN'] }
};
const account = credentials[role];
const checks = [];
const findings = [];
const consoleErrors = [];
const externalRequests = [];
const httpFailures = [];
let auditError = null;
let browser;
let context;
let page;

function passed(name) {
  checks.push(name);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function ensureRoleAccounts() {
  const basicAuthorization = `Basic ${Buffer.from(`${adminUsername}:${adminPassword}`, 'utf8').toString('base64')}`;
  const api = await request.newContext({
    baseURL: baseUrl,
    extraHTTPHeaders: { Authorization: basicAuthorization }
  });
  try {
    for (const value of [credentials.USER, credentials.ARCHITECT]) {
      const response = await api.post('/api/admin/users', {
        data: {
          username: value.username,
          password: value.password,
          displayName: `QA ${value.roles.at(-1)}`,
          email: `${value.username}@example.invalid`,
          roles: value.roles
        }
      });
      if (![200, 201, 409].includes(response.status())) {
        throw new Error(`Unable to provision ${value.username}: ${response.status()} ${await response.text()}`);
      }
    }
  } finally {
    await api.dispose();
  }
}

async function saveState(currentPage, state) {
  const prefix = path.join(outputDir, state);
  await currentPage.screenshot({ path: `${prefix}.png`, fullPage: true });
  await writeFile(`${prefix}.html`, await currentPage.content(), 'utf8');
  const body = currentPage.locator('body');
  const aria = typeof body.ariaSnapshot === 'function'
    ? await body.ariaSnapshot().catch(error => `ARIA snapshot unavailable: ${error}`)
    : 'ARIA snapshot API unavailable';
  await writeFile(`${prefix}.aria.txt`, `${aria}\n`, 'utf8');
}

async function runAxe(currentPage, state) {
  const result = await new AxeBuilder({ page: currentPage })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter(violation =>
    ['critical', 'serious', 'moderate'].includes(violation.impact));
  findings.push({ state, violations: result.violations, blocking });
  if (blocking.length) {
    const summary = blocking.map(item => `${item.impact}:${item.id}`).join(', ');
    throw new Error(`Blocking axe findings in ${state}: ${summary}`);
  }
  passed(`axe ${state}`);
}

async function csrfPost(currentPage, endpoint, body) {
  return currentPage.evaluate(async ({ endpoint, body }) => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers[header] = token;
    const response = await fetch(endpoint, {
      method: 'POST', headers, body: JSON.stringify(body)
    });
    const text = await response.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* stable status remains authoritative */ }
    return { status: response.status, json, text };
  }, { endpoint, body });
}

function expectedHttpFailure(failure) {
  if (failure.status === 503 && failure.path === '/api/analyze') return true;
  return role === 'USER'
    && failure.status === 403
    && failure.path === '/api/proposals/from-hypothesis';
}

await mkdir(outputDir, { recursive: true });

try {
  await ensureRoleAccounts();

  browser = await browserType.launch({ headless: true });
  context = await browser.newContext({
    viewport: effectiveViewport,
    reducedMotion: 'reduce',
    forcedColors: forcedColors ? 'active' : 'none'
  });
  page = await context.newPage();
  const baseOrigin = new URL(baseUrl).origin;
  page.on('request', requestEvent => {
    const url = requestEvent.url();
    if (url.startsWith('data:') || url.startsWith('blob:')) return;
    if (new URL(url).origin !== baseOrigin) externalRequests.push(url);
  });
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin === baseOrigin && response.status() >= 400) {
      httpFailures.push({ path: url.pathname, status: response.status(), method: response.request().method() });
    }
  });
  page.on('console', message => {
    if (message.type() === 'error' && !message.text().includes('Failed to load resource')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', error => consoleErrors.push(error.message));

  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[name="username"]').fill(account.username);
  await page.locator('input[name="password"]').fill(account.password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), { timeout: 30_000 }),
    page.keyboard.press('Enter')
  ]);
  await page.locator('#mainContent').waitFor({ state: 'visible', timeout: 60_000 });
  const onboardingDismiss = page.locator('#onboardingDismiss');
  if (await onboardingDismiss.isVisible().catch(() => false)) {
    await onboardingDismiss.focus();
    await page.keyboard.press('Enter');
  }
  passed('keyboard authentication');

  const adminTab = page.locator('#adminNavTab');
  if (role === 'ADMIN') {
    await page.waitForFunction(() => {
      const tab = document.querySelector('#adminNavTab');
      return tab && getComputedStyle(tab).display !== 'none';
    }, null, { timeout: 20_000 });
    const adminLink = adminTab.locator('a[data-page="admin"]');
    await adminLink.scrollIntoViewIfNeeded();
    assert(await adminLink.isVisible(), 'ADMIN navigation is unavailable after role authorization');
    passed('admin role navigation');
  } else {
    await page.locator('#adminLockBtn').waitFor({ state: 'visible', timeout: 15_000 });
    assert(!(await adminTab.isVisible().catch(() => false)),
      `${role} must not see admin navigation`);
    passed('role-specific navigation');
  }

  await page.locator('#mainNavTabs [data-page="analyze"]').click();
  const interactive = page.locator('#interactiveMode');
  if (await interactive.isChecked()) await interactive.uncheck();
  await page.locator('#businessText').fill(
    'Provide resilient hospital communications with traceable architecture decisions.');
  await page.locator('#analyzeBtn').focus();
  await page.keyboard.press('Enter');
  await page.locator('#statusArea').waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForFunction(() => {
    const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
    return text.includes('complete') || text.includes('completed');
  }, null, { timeout: 120_000 });
  assert(await page.locator('.tax-pct').count() > 0, 'Analysis produced no scored nodes');
  passed('analysis loading and success');
  await runAxe(page, 'analysis-success');
  await saveState(page, 'analysis-success');

  await page.locator('#businessText').fill(
    'Provide resilient hospital communications with an emergency notification capability.');
  await page.locator('#businessText.stale-results').waitFor({ state: 'visible', timeout: 10_000 });
  passed('stale-result indication');

  await page.route('**/api/analyze', async route => {
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'QA_PROVIDER_UNAVAILABLE', message: 'Deterministic QA failure' })
    });
  }, { times: 1 });
  await page.locator('#analyzeBtn').click();
  await page.waitForFunction(() => {
    const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
    return text.includes('error') || text.includes('failed') || text.includes('503')
      || text.includes('unavailable');
  }, null, { timeout: 30_000 });
  passed('analysis provider error state');
  await runAxe(page, 'analysis-error');
  await saveState(page, 'analysis-error');

  const proposal = await csrfPost(page, '/api/proposals/from-hypothesis', {
    sourceCode: 'BP', targetCode: 'BR', relationType: 'RELATED_TO',
    confidence: 0.72, rationale: 'Role acceptance test'
  });
  if (role === 'USER') {
    assert(proposal.status === 403, `USER proposal mutation returned ${proposal.status}, expected 403`);
    passed('user proposal mutation forbidden');
  } else {
    assert([200, 409].includes(proposal.status),
      `${role} proposal creation returned ${proposal.status}`);
    if (proposal.status === 200 && proposal.json?.id) {
      const accepted = await csrfPost(page, `/api/proposals/${proposal.json.id}/accept`, {});
      assert(accepted.status === 200, `Proposal acceptance returned ${accepted.status}`);
    }
    passed('architectural proposal review');
  }
  await runAxe(page, 'role-operation-feedback');
  await saveState(page, 'role-operation-feedback');

  const businessText = page.locator('#businessText');
  await businessText.focus();
  await page.evaluate(() => window.TaxonomyUtils.showMessage('Accessible QA dialog', 'QA notice'));
  const dialog = page.locator('#taxonomyAccessibleDialog');
  await dialog.waitFor({ state: 'visible' });
  assert(await page.evaluate(() => document.activeElement?.id === 'taxonomyAccessibleDialogConfirm'),
    'Dialog did not move focus to its confirmation control');
  await runAxe(page, 'dialog-open');
  await saveState(page, 'dialog-open');
  await page.keyboard.press('Enter');
  await dialog.waitFor({ state: 'hidden' });
  assert(await page.evaluate(() => document.activeElement?.id === 'businessText'),
    'Dialog did not restore focus to its invoker');
  passed('dialog focus entry and restoration');

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    forcedColors: matchMedia('(forced-colors: active)').matches,
    reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches
  }));
  assert(overflow.scrollWidth <= overflow.clientWidth + 2,
    `Viewport reflow failed at ${zoom}x: ${overflow.scrollWidth} > ${overflow.clientWidth}`);
  assert(overflow.reducedMotion, 'Reduced motion preference was not active');
  assert(forcedColors ? overflow.forcedColors : true, 'Forced-colors profile was not active');
  passed(`reflow at ${zoom}x`);
  if (forcedColors) passed('forced-colors media state');

  const unexpectedHttpFailures = httpFailures.filter(failure => !expectedHttpFailure(failure));
  assert(unexpectedHttpFailures.length === 0,
    `Unexpected HTTP failures: ${JSON.stringify(unexpectedHttpFailures)}`);
  passed('expected HTTP error states only');
  assert(externalRequests.length === 0,
    `External browser requests detected: ${externalRequests.join(', ')}`);
  assert(consoleErrors.length === 0,
    `Browser console errors: ${consoleErrors.join(' | ')}`);
  passed('local assets and clean console');
} catch (error) {
  auditError = error?.stack || String(error);
  process.exitCode = 1;
} finally {
  const report = {
    profileId, browserName, role,
    physicalViewport: { width: physicalWidth, height: physicalHeight },
    effectiveViewport, zoom, forcedColors, checks, findings,
    externalRequests, httpFailures, consoleErrors, auditError
  };
  await writeFile(path.join(outputDir, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  if (auditError) {
    console.error(`UI role/state acceptance failed for ${profileId}:\n${auditError}`);
    console.error(JSON.stringify(report, null, 2));
  }
  if (context) await context.close().catch(() => undefined);
  if (browser) await browser.close().catch(() => undefined);
}

if (auditError) throw new Error(auditError);
console.log(`UI role/state acceptance passed for ${profileId}: ${checks.join(', ')}`);
