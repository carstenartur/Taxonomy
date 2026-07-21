import { chromium } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_A11Y_USERNAME || 'admin';
const password = process.env.TAXONOMY_A11Y_PASSWORD || 'a11y-test-password';

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const findings = [];

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
    await page.waitForTimeout(250);

    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      // CodeMirror maintains its own accessibility tree and is tested separately.
      .exclude('#dslEditorContainer .cm-editor')
      .analyze();

    const severe = result.violations.filter(v => v.impact === 'critical' || v.impact === 'serious');
    if (severe.length) {
      findings.push({ section, violations: severe });
    }
  }

  if (findings.length) {
    for (const finding of findings) {
      console.error(`\nAccessibility violations in #${finding.section}:`);
      for (const violation of finding.violations) {
        console.error(`- [${violation.impact}] ${violation.id}: ${violation.help}`);
        for (const node of violation.nodes) {
          console.error(`  target: ${node.target.join(' ')}`);
          console.error(`  ${node.failureSummary || ''}`);
        }
      }
    }
    process.exitCode = 1;
  } else {
    console.log('No critical or serious axe violations found in the audited UI sections.');
  }
} finally {
  await browser.close();
}
