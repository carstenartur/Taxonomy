import { chromium } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_UI_USERNAME || 'admin';
const password = process.env.TAXONOMY_UI_PASSWORD || 'ui-acceptance-password';
const outputDir = process.env.TAXONOMY_UI_OUTPUT_DIR || '/tmp/taxonomy-portfolio-ui';
const viewport = { width: 1440, height: 1000 };
const suffix = `${Date.now().toString(36)}-${process.pid.toString(36)}`.toUpperCase();
const keys = {
  project: `P-UI-${suffix}`,
  requirement: 'REQ-001',
  solution: `SOL-UI-${suffix}`,
  product: `PRD-UI-${suffix}`
};

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function waitUntilIdle(page) {
  const busy = page.locator('#portfolioBusy');
  await busy.waitFor({ state: 'hidden', timeout: 120_000 }).catch(() => {});
}

async function submitModal(page, modalId, fill, responsePattern) {
  const modal = page.locator(`#${modalId}`);
  await modal.waitFor({ state: 'visible', timeout: 20_000 });
  await fill(modal);
  const response = page.waitForResponse(value =>
    value.request().method() === 'POST' && responsePattern.test(new URL(value.url()).pathname),
  { timeout: 120_000 });
  await modal.locator('button[type="submit"]').click();
  const result = await response;
  assert(result.ok(), `${modalId} request failed with HTTP ${result.status()}`);
  await modal.waitFor({ state: 'hidden', timeout: 30_000 });
  await waitUntilIdle(page);
}

await mkdir(outputDir, { recursive: true });
const reportPath = path.join(outputDir, 'report.json');
const screenshotPath = path.join(outputDir, 'screenshot.png');
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
const page = await context.newPage();
const checks = [];
const consoleErrors = [];
const externalRequests = [];
let auditError = null;
let axeViolations = [];

page.on('console', message => {
  if (message.type() === 'error') consoleErrors.push(message.text());
});
page.on('pageerror', error => consoleErrors.push(error.message));
page.on('request', request => {
  const url = request.url();
  if (url.startsWith('data:') || url.startsWith('blob:')) return;
  if (new URL(url).origin !== new URL(baseUrl).origin) externalRequests.push(url);
});

try {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), { timeout: 30_000 }),
    page.locator('button[type="submit"], input[type="submit"]').first().click()
  ]);
  await page.locator('#taxonomyTree [role="treeitem"]').first()
    .waitFor({ state: 'visible', timeout: 120_000 });
  checks.push('authenticated application readiness');

  await page.goto(`${baseUrl}/projects`, { waitUntil: 'networkidle' });
  await page.locator('#portfolioMain').waitFor({ state: 'visible', timeout: 30_000 });
  assert(await page.locator('#projectList').getAttribute('role') === 'listbox',
    'Project list lacks listbox semantics');
  checks.push('portfolio page and project list semantics');

  const skipLink = page.locator('.skip-link');
  await skipLink.focus();
  await page.keyboard.press('Enter');
  assert(await page.evaluate(() => document.activeElement?.id === 'portfolioMain'),
    'Portfolio skip link did not move focus to the main region');
  checks.push('skip link and keyboard focus');

  await page.locator('[data-bs-target="#projectModal"]').click();
  await submitModal(page, 'projectModal', async modal => {
    await modal.locator('#projectKey').fill(keys.project);
    await modal.locator('#projectTitle').fill('Portfolio browser acceptance');
    await modal.locator('#projectDescription').fill('Created by the authoritative UI verification suite.');
  }, /^\/api\/projects$/);
  await page.locator('#selectedProjectKey').filter({ hasText: keys.project })
    .waitFor({ state: 'visible', timeout: 30_000 });
  checks.push('project creation through UI');

  await page.locator('[data-bs-target="#requirementModal"]').click();
  await submitModal(page, 'requirementModal', async modal => {
    await modal.locator('#requirementKey').fill(keys.requirement);
    await modal.locator('#requirementTitle').fill('Secure portfolio analysis');
    await modal.locator('#requirementType').selectOption('SECURITY');
    await modal.locator('#requirementText').fill(
      'Provide traceable secure communication, resilient data exchange and auditable architecture decisions.');
  }, /^\/api\/projects\/\d+\/requirements$/);
  await page.locator('#requirementsTable tbody').getByText(keys.requirement)
    .waitFor({ state: 'visible', timeout: 30_000 });
  checks.push('separate requirement creation through UI');

  await page.locator('#solutions-tab').click();
  await page.locator('[data-bs-target="#solutionModal"]').click();
  await submitModal(page, 'solutionModal', async modal => {
    await modal.locator('#solutionKey').fill(keys.solution);
    await modal.locator('#solutionTitle').fill('Secure communication service');
    await modal.locator('#solutionType').selectOption('SERVICE');
    await modal.locator('#solutionOperatingModel').selectOption('PRIVATE_CLOUD');
    await modal.locator('#solutionDescription').fill('Reusable solution created by UI acceptance.');
  }, /^\/api\/solutions$/);
  await page.locator('#solutionsList').getByText('Secure communication service')
    .waitFor({ state: 'visible', timeout: 30_000 });
  checks.push('solution creation and project assignment through UI');

  await page.locator('#products-tab').click();
  await page.locator('[data-bs-target="#productModal"]').click();
  await submitModal(page, 'productModal', async modal => {
    await modal.locator('#productKey').fill(keys.product);
    await modal.locator('#productManufacturer').fill('Acceptance Vendor');
    await modal.locator('#productName').fill('Acceptance Product');
    await modal.locator('#productVersion').fill('1.0');
    await modal.locator('#productOperatingModel').selectOption('PRIVATE_CLOUD');
    await modal.locator('#productSource').fill('UI acceptance catalogue reference, version 1.0');
  }, /^\/api\/products$/);
  await page.locator('#productsList').getByText('Acceptance Product')
    .waitFor({ state: 'visible', timeout: 30_000 });
  checks.push('sourced product creation through UI');

  await page.locator('#requirements-tab').click();
  const analysisResponse = page.waitForResponse(value =>
    value.request().method() === 'POST'
      && /^\/api\/projects\/\d+\/analyses$/.test(new URL(value.url()).pathname),
  { timeout: 180_000 });
  await page.locator('#analyzeAllBtn').click();
  const analysis = await analysisResponse;
  assert(analysis.ok(), `Portfolio analysis failed with HTTP ${analysis.status()}`);
  await waitUntilIdle(page);
  const analysisBody = await analysis.json();
  assert(analysisBody.totalItems === 1,
    `Expected one independently analysed requirement, got ${analysisBody.totalItems}`);
  assert(analysisBody.successfulItems + analysisBody.partialItems === 1,
    'Requirement analysis did not create a successful or partial snapshot');
  checks.push('independent mock analysis and persisted snapshot');

  await page.locator('#taxonomy-tab').click();
  await page.locator('#taxonomyPane').waitFor({ state: 'visible' });
  await page.locator('#snapshots-tab').click();
  await page.locator('#snapshotsPane').waitFor({ state: 'visible' });
  checks.push('portfolio tab navigation and result surfaces');

  const axeResult = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  axeViolations = axeResult.violations;
  const severe = axeViolations.filter(item =>
    item.impact === 'critical' || item.impact === 'serious');
  assert(severe.length === 0,
    `Portfolio accessibility has severe violations: ${severe.map(item => item.id).join(', ')}`);
  checks.push('portfolio axe audit without serious violations');

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  assert(overflow.scrollWidth <= overflow.clientWidth + 2,
    `Portfolio overflows desktop viewport: ${overflow.scrollWidth} > ${overflow.clientWidth}`);
  assert(externalRequests.length === 0,
    `Portfolio made external browser requests: ${externalRequests.join(', ')}`);
  assert(consoleErrors.length === 0,
    `Portfolio browser console errors: ${consoleErrors.join(' | ')}`);
  checks.push('reflow, local assets and clean console');
} catch (error) {
  auditError = error?.stack || String(error);
  process.exitCode = 1;
} finally {
  await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
  await writeFile(reportPath, `${JSON.stringify({
    keys, viewport, checks, consoleErrors, externalRequests, axeViolations, auditError
  }, null, 2)}\n`, 'utf8');
  await context.close();
  await browser.close();
}

if (auditError) throw new Error(auditError);
console.log(`Portfolio UI acceptance passed: ${checks.join(', ')}`);
