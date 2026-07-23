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

  await page.addStyleTag({ content: `
    * { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; }
    p { margin-bottom: 2em !important; }
  ` });
  await navigateToPage(page, 'analyze');

  const spacing = await page.evaluate(() => {
    const sample = document.querySelector('#tab-analyze p') || document.querySelector('#tab-analyze label');
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
  await runAxe('text-spacing', '#mainContent');
  await screenshot('text-spacing', '#mainContent');

  await page.route('**/api/workspace/sync-state', route => route.abort('internetdisconnected'));
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
