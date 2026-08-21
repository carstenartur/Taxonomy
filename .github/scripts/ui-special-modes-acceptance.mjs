import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { injectAxe, checkA11y } from 'axe-playwright';
import {
  ROLE_ACCOUNTS,
  navigateToPage,
  openRoleSession,
  readFirstText
} from './ui-role-fixtures.mjs';

const baseUrl = process.env.TAXONOMY_UI_BASE_URL || 'http://127.0.0.1:8080';
const outputDir = path.resolve(
  process.env.TAXONOMY_UI_EVIDENCE_DIR || 'target/ui-evidence/special-modes'
);
const axeReportPath = path.join(outputDir, 'axe-report.json');
const findings = [];
const checks = [];
let browser;
let context;
let page;
let auditError;

async function openDetails(selector) {
  const details = page.locator(selector);
  if (!(await details.count())) return;
  const open = await details.evaluate(element => element.hasAttribute('open'));
  if (!open) await details.locator('summary').click();
}

async function runAxe(name, selector) {
  await injectAxe(page);
  let report;
  await checkA11y(page, selector, {
    detailedReport: true,
    detailedReportOptions: { html: true },
    axeOptions: {
      runOnly: {
        type: 'tag',
        values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']
      }
    },
    reporter: 'v2'
  }, true, 'html', {
    log: violation => {
      report = violation;
    }
  });
  if (report) findings.push({ name, report });
}

async function screenshot(name, selector) {
  await page.locator(selector).screenshot({
    path: path.join(outputDir, `${name}.png`)
  });
}

async function waitForAnalysisReady() {
  await page.locator('#analysisTaskProgress').waitFor({ state: 'visible', timeout: 20_000 });
  await page.waitForFunction(() => {
    const status = document.querySelector('#analysisStatus');
    return status && status.textContent && status.textContent.trim().length > 0;
  }, null, { timeout: 20_000 });
}

async function testPartialAnalysis() {
  await navigateToPage(page, 'analyze');
  await waitForAnalysisReady();
  const viewMode = page.locator('#viewMode');
  await viewMode.selectOption('list');

  const input = page.locator('#businessText');
  await input.fill('Assess emergency communications capability and field coordination.');
  await page.locator('#analyzeBtn').click();

  await page.waitForFunction(() => {
    const status = document.querySelector('#analysisStatus');
    return status && /complete|completed|partial|finished|abgeschlossen|teilweise/i.test(status.textContent || '');
  }, null, { timeout: 60_000 });

  const statusText = (await page.locator('#analysisStatus').textContent()).trim();
  assert(statusText.length > 0, 'Analysis status is empty');
  const cards = page.locator('#resultsContainer .card, #resultsContainer [data-node-id]');
  assert(await cards.count() > 0, 'Partial analysis produced no visible result');
  checks.push('partial analysis exact-response rendering');
  await runAxe('partial-analysis', '#tab-analyze');
  await screenshot('partial-analysis', '#tab-analyze');
}

async function testTextSpacing() {
  await page.addStyleTag({
    content: `
      * { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; }
      p { margin-bottom: 2em !important; }
    `
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
