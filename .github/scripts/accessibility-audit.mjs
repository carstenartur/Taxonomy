import { chromium } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { writeFile } from 'node:fs/promises';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_A11Y_USERNAME || 'admin';
const password = process.env.TAXONOMY_A11Y_PASSWORD || 'a11y-test-password';
const reportPath = process.env.TAXONOMY_A11Y_REPORT || '/tmp/taxonomy-a11y-report.json';
const textReportPath = process.env.TAXONOMY_A11Y_TEXT_REPORT || '/tmp/taxonomy-a11y-report.txt';

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const findings = [];

function formatFindings(items) {
  if (!items.length) return 'No critical or serious axe violations found in the audited UI sections.\n';
  const lines = [];
  for (const finding of items) {
    lines.push(`Accessibility violations in #${finding.section}:`);
    for (const violation of finding.violations) {
      lines.push(`- [${violation.impact}] ${violation.id}: ${violation.help}`);
      for (const node of violation.nodes) {
        lines.push(`  target: ${node.target.join(' ')}`);
        if (node.failureSummary) lines.push(`  ${node.failureSummary}`);
        if (node.html) lines.push(`  html: ${node.html}`);
      }
    }
    lines.push('');
  }
  return lines.join('\n') + '\n';
}

try {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), { timeout: 30_000 }),
    page.locator('button[type="submit"], input[type="submit"]').first().click()
  ]);
  await page.locator('#mainContent').waitFor({ state: 'visible', timeout: 60_000 });

  const sections = ['analyze', 'architecture', 'graph', 'versions', 'dsl-editor', 'help', 'admin', 'preferences'];
  for (const section of sections) {
    await page.evaluate(name => {
      if (typeof window.navigateToPage === 'function') window.navigateToPage(name);
      else window.location.hash = name;
    }, section);
    await page.waitForFunction(name => {
      const pane = document.getElementById(`tab-${name}`);
      return pane && !pane.classList.contains('d-none');
    }, section);

    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      // CodeMirror maintains its own accessibility tree and is tested separately.
      .exclude('#dslEditorContainer .cm-editor')
      .analyze();

    const severe = result.violations.filter(v => v.impact === 'critical' || v.impact === 'serious');
    if (severe.length) findings.push({ section, violations: severe });
  }
} finally {
  const textReport = formatFindings(findings);
  await Promise.all([
    writeFile(reportPath, JSON.stringify({ baseUrl, findings }, null, 2) + '\n', 'utf8'),
    writeFile(textReportPath, textReport, 'utf8')
  ]);
  if (findings.length) {
    console.error(textReport);
    process.exitCode = 1;
  } else {
    console.log(textReport.trim());
  }
  await browser.close();
}
