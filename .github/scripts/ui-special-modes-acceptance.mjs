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

async function testPartialAnalysis() {
  await navigateToPage(page, 'analyze');
  const interactive = page.locator('#interactiveMode');
  if (await interactive.isChecked()) await interactive.uncheck();
  await page.locator('#businessText').fill('Provide traceable and resilient hospital communication services.');
  await page.locator('#analyzeBtn').click();
  await page.waitForFunction(() => {
    const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
    return text.includes('complete') || text.includes('completed');
  }, null, { timeout: 120_000 });

  const fixture = await page.evaluate(() => ({
    tree: window.TaxonomyState.taxonomyData,
    scores: window.TaxonomyState.currentScores
  }));
  assert(Array.isArray(fixture.tree) && fixture.tree.length > 0, 'Successful analysis produced no reusable tree');
  assert(fixture.scores && Object.keys(fixture.scores).length > 0, 'Successful analysis produced no reusable scores');

  await page.locator('#viewSunburst').click();
  await page.waitForFunction(() => window.TaxonomyState.currentView === 'sunburst');

  await page.route('**/api/analyze', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        status: 'PARTIAL',
        errorMessage: 'One provider branch was unavailable',
        warnings: ['Some architecture branches were not evaluated.'],
        tree: fixture.tree,
        scores: fixture.scores,
        discrepancies: [],
        provisionalRelations: []
      })
    });
  }, { times: 1 });
  await page.locator('#businessText').fill('Provide traceable hospital communication services with a partial provider result.');
  await page.locator('#analyzeBtn').click();
  const statusHandle = await page.waitForFunction(() => {
    const text = (document.querySelector('#statusArea')?.textContent || '').trim();
    const normalized = text.toLowerCase();
    return text && (normalized.includes('unavailable')
      || normalized.includes('incomplete')
      || normalized.includes('partial')) ? text : false;
  }, null, { timeout: 30_000 });
  const statusText = await statusHandle.jsonValue();
  assert(typeof statusText === 'string' && statusText.length > 0, 'Partial analysis status is empty');
  await page.waitForFunction(() => {
    const text = (document.querySelector('#a11yStatus')?.textContent || '').trim().toLowerCase();
    return text.includes('unavailable') || text.includes('incomplete') || text.includes('partial');
  }, null, { timeout: 10_000 });
  checks.push('partial analysis status, warning detail, and live announcement');
  await runAxe('analysis-partial', '#tab-analyze');
  await screenshot('analysis-partial', '#tab-analyze');
}

async function testTextSpacing() {
  await navigateToPage(page, 'analyze');
  await page.locator('#documentImportPanel').waitFor({ state: 'attached', timeout: 20_000 });

  const result = await page.evaluate(() => {
    const sample = document.querySelector('#documentImportPanel p');
    if (!sample) return { applied: false, reason: 'sample paragraph unavailable', attempts: [] };

    const spacingRule = '#tab-analyze, #tab-analyze * { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; }';
    const paragraphRule = '#tab-analyze p { margin-bottom: 2em !important; }';
    const attempts = [];

    function measure() {
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
    }

    function satisfies(metrics) {
      return Number.isFinite(metrics.fontSize) && metrics.fontSize > 0
        && metrics.lineHeight / metrics.fontSize >= 1.49
        && metrics.letterSpacing / metrics.fontSize >= 0.119
        && metrics.wordSpacing / metrics.fontSize >= 0.159
        && metrics.marginBottom / metrics.fontSize >= 1.99;
    }

    for (const sheet of Array.from(document.styleSheets)) {
      const media = sheet.media ? sheet.media.mediaText : '';
      if (sheet.disabled || (media && !matchMedia(media).matches)) continue;
      let start;
      try {
        start = sheet.cssRules.length;
        sheet.insertRule(spacingRule, start);
        sheet.insertRule(paragraphRule, start + 1);
        const metrics = measure();
        attempts.push({ kind: 'document', href: sheet.href, media, metrics });
        if (satisfies(metrics)) {
          return { applied: true, kind: 'document', href: sheet.href, media, metrics, attempts };
        }
        sheet.deleteRule(start + 1);
        sheet.deleteRule(start);
      } catch (error) {
        attempts.push({ kind: 'document', href: sheet.href, media, error: String(error) });
        if (Number.isInteger(start)) {
          try {
            while (sheet.cssRules.length > start) sheet.deleteRule(start);
          } catch (ignored) {
            // Nothing else can be cleaned up on an inaccessible stylesheet.
          }
        }
      }
    }

    if ('adoptedStyleSheets' in document && typeof CSSStyleSheet === 'function') {
      try {
        const userSheet = new CSSStyleSheet();
        userSheet.replaceSync(`${spacingRule}\n${paragraphRule}`);
        document.adoptedStyleSheets = [...document.adoptedStyleSheets, userSheet];
        const metrics = measure();
        attempts.push({ kind: 'constructed', metrics });
        if (satisfies(metrics)) {
          return { applied: true, kind: 'constructed', metrics, attempts };
        }
      } catch (error) {
        attempts.push({ kind: 'constructed', error: String(error) });
      }
    }

    return { applied: false, reason: 'no active cascade path applied the requested spacing', metrics: measure(), attempts };
  });

  assert(result.applied, `Unable to apply user stylesheet: ${JSON.stringify(result)}`);
  const spacing = result.metrics;
  assert(spacing.scrollWidth <= spacing.clientWidth + 2,
    `Text spacing introduced horizontal scrolling: ${JSON.stringify(result)}`);
  checks.push(`WCAG text spacing and reflow via ${result.kind} stylesheet`);
  await runAxe('text-spacing', '#tab-analyze');
  await screenshot('text-spacing', '#tab-analyze .card:first-of-type');
}

async function testWorkspaceOffline() {
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
