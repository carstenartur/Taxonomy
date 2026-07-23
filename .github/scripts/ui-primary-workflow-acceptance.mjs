import AxeBuilder from '@axe-core/playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ROLE_ACCOUNTS,
  csrfJson,
  navigateArchitectureSubtab,
  navigateToPage,
  openRoleSession
} from './ui-role-fixtures.mjs';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const role = process.env.TAXONOMY_ROLE || 'USER';
const browserName = process.env.TAXONOMY_BROWSER || 'chromium';
const adminUsername = process.env.TAXONOMY_UI_ADMIN_USERNAME || 'admin';
const adminPassword = process.env.TAXONOMY_UI_ADMIN_PASSWORD || ROLE_ACCOUNTS.ADMIN.password;
const outputDir = path.resolve(
  process.env.TAXONOMY_UI_OUTPUT_DIR || path.join('target', 'ui-primary', role.toLowerCase()));

const checks = [];
const axeFindings = [];
const screenshots = [];
let auditError = null;
let browser;
let context;
let page;
let account;

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function passed(check) {
  checks.push(check);
}

async function saveState(state, locator = '#mainContent') {
  const target = page.locator(locator);
  await target.waitFor({ state: 'visible', timeout: 20_000 });
  const file = path.join(outputDir, `${state}.png`);
  await target.screenshot({ path: file, animations: 'disabled' });
  screenshots.push(path.basename(file));
  await writeFile(path.join(outputDir, `${state}.html`), await target.evaluate(node => node.outerHTML), 'utf8');
  const aria = typeof target.ariaSnapshot === 'function'
    ? await target.ariaSnapshot().catch(error => `ARIA snapshot unavailable: ${error}`)
    : 'ARIA snapshot API unavailable';
  await writeFile(path.join(outputDir, `${state}.aria.txt`), `${aria}\n`, 'utf8');
}

async function axeState(state, include = '#mainContent') {
  const result = await new AxeBuilder({ page })
    .include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter(item =>
    ['critical', 'serious', 'moderate'].includes(item.impact));
  axeFindings.push({ state, violations: result.violations, blocking });
  if (blocking.length) {
    throw new Error(`Blocking axe findings in ${state}: ${blocking.map(item => `${item.impact}:${item.id}`).join(', ')}`);
  }
  passed(`axe ${state}`);
}

async function waitForText(selector, predicate, timeout = 20_000) {
  await page.waitForFunction(({ selector, predicateSource }) => {
    const node = document.querySelector(selector);
    if (!node) return false;
    const text = node.textContent || '';
    return Function('text', `return (${predicateSource})(text);`)(text);
  }, { selector, predicateSource: predicate.toString() }, { timeout });
}

async function testEmptyAnalysisAndLiveRegion() {
  await navigateToPage(page, 'analyze');
  await page.locator('#businessText').fill('');
  await page.locator('#analyzeBtn').click();
  await waitForText('#statusArea', text => text.trim().length > 0);
  await waitForText('#a11yStatus', text => text.trim().length > 0);
  await axeState('analysis-empty-error');
  await saveState('analysis-empty-error');
  passed('empty analysis actionable status and live announcement');
}

async function testRoleSurfaces() {
  const contextData = await page.evaluate(() => window.TaxonomyRoleSurface.getContext());
  assert(contextData.roles.includes(role), `Role context does not contain ${role}: ${JSON.stringify(contextData)}`);

  async function controlCapability(selector) {
    const locator = page.locator(selector);
    assert(await locator.count() === 1, `Expected exactly one control for ${selector}`);
    return locator.evaluate(element => ({
      hidden: element.hidden,
      disabled: 'disabled' in element ? element.disabled : false,
      ariaHidden: element.getAttribute('aria-hidden')
    }));
  }

  const architectureAllowed = role === 'ARCHITECT' || role === 'ADMIN';
  const architectureSelectors = [
    '#docImportUploadBtn', '#createRelationBtn', '#archiMateImportBtn',
    '#importExecuteBtn', '#proposeRelationsSubmit'
  ];
  for (const selector of architectureSelectors) {
    const capability = await controlCapability(selector);
    const available = !capability.hidden && !capability.disabled && capability.ariaHidden !== 'true';
    assert(available === architectureAllowed,
      `${selector} capability=${JSON.stringify(capability)}, expected allowed=${architectureAllowed} for ${role}`);
  }

  const workspaceCapability = await controlCapability('#wsCreateBtn');
  const workspaceAvailable = !workspaceCapability.hidden
    && !workspaceCapability.disabled
    && workspaceCapability.ariaHidden !== 'true';
  assert(workspaceAvailable === (role === 'ADMIN'),
    `Workspace creation capability=${JSON.stringify(workspaceCapability)}, expected allowed=${role === 'ADMIN'}`);
  passed('role-appropriate mutation controls');
}

async function testCsvExportAndBackendFailure() {
  const downloadPromise = page.waitForEvent('download');
  await page.evaluate(() => {
    window.TaxonomyExport.exportCsv(
      { BP: 91, CR: 72 },
      [
        { code: 'BP', name: 'Business Processes', children: [] },
        { code: 'CR', name: 'Core Services', children: [] }
      ]
    );
  });
  const download = await downloadPromise;
  assert((await download.suggestedFilename()) === 'taxonomy-scores.csv',
    `Unexpected export filename: ${await download.suggestedFilename()}`);
  passed('CSV export download');

  await page.route('**/api/diagram/visio', async route => {
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'EXPORT_PROVIDER_UNAVAILABLE', message: 'Diagram service unavailab×nöÑÈZ®Ëkºwµç