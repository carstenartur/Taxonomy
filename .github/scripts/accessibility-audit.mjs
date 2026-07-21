import { chromium } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFile, writeFile } from 'node:fs/promises';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_A11Y_USERNAME || 'admin';
const password = process.env.TAXONOMY_A11Y_PASSWORD || 'a11y-test-password';
const profile = process.env.TAXONOMY_A11Y_PROFILE || 'desktop';
const viewport = {
  width: Number.parseInt(process.env.TAXONOMY_VIEWPORT_WIDTH || '1440', 10),
  height: Number.parseInt(process.env.TAXONOMY_VIEWPORT_HEIGHT || '1000', 10)
};
const reportPath = process.env.TAXONOMY_A11Y_REPORT || `/tmp/taxonomy-a11y-${profile}.json`;
const textReportPath = process.env.TAXONOMY_A11Y_TEXT_REPORT || `/tmp/taxonomy-a11y-${profile}.txt`;
const baselinePath = process.env.TAXONOMY_A11Y_BASELINE || '.github/accessibility-baseline.json';
const baseline = JSON.parse(await readFile(baselinePath, 'utf8'));
if (baseline.schemaVersion !== 1 || !Array.isArray(baseline.allowedModerate)) {
  throw new Error(`Invalid accessibility baseline: ${baselinePath}`);
}
const allowedModerate = new Set(baseline.allowedModerate);

function signature(section, violation, node) {
  return `${profile}|${section}|${violation.id}|${node.target.join(' ')}`;
}

function formatReport(findings, unexpectedModerate, severe, auditError) {
  const lines = [`Accessibility audit profile: ${profile} (${viewport.width}x${viewport.height})`, ''];
  if (auditError) lines.push('Execution error:', auditError, '');
  for (const finding of findings) {
    lines.push(`Section #${finding.section}:`);
    if (!finding.violations.length) lines.push('- no axe violations');
    for (const violation of finding.violations) {
      lines.push(`- [${violation.impact || 'unknown'}] ${violation.id}: ${violation.help}`);
      for (const node of violation.nodes) lines.push(`  ${node.target.join(' ')}`);
    }
    lines.push('');
  }
  if (unexpectedModerate.length) {
    lines.push('Unexpected moderate signatures (review before baselining):');
    lines.push(...unexpectedModerate.map(item => `- ${item}`), '');
  }
  lines.push(`Severe findings: ${severe.length}`);
  lines.push(`Unexpected moderate findings: ${unexpectedModerate.length}`);
  lines.push(auditError || severe.length || unexpectedModerate.length ? 'Result: FAIL' : 'Result: PASS');
  return lines.join('\n') + '\n';
}

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
const page = await context.newPage();
const findings = [];
let auditError = null;

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
    if (section === 'dsl-editor') {
      await page.locator('#dslEditorContainer .cm-content').waitFor({ state: 'visible', timeout: 30_000 });
    }
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    findings.push({ section, violations: result.violations });
  }
} catch (error) {
  auditError = error?.stack || String(error);
}

const severe = [];
const moderateSignatures = [];
for (const finding of findings) {
  for (const violation of finding.violations) {
    for (const node of violation.nodes) {
      const item = { section: finding.section, id: violation.id, target: node.target };
      if (violation.impact === 'critical' || violation.impact === 'serious') severe.push(item);
      if (violation.impact === 'moderate') moderateSignatures.push(signature(finding.section, violation, node));
    }
  }
}
const unexpectedModerate = [...new Set(moderateSignatures.filter(item => !allowedModerate.has(item)))].sort();
const staleBaseline = baseline.allowedModerate.filter(item => item.startsWith(`${profile}|`) && !moderateSignatures.includes(item));
const textReport = formatReport(findings, unexpectedModerate, severe, auditError);
await Promise.all([
  writeFile(reportPath, JSON.stringify({
    profile, viewport, auditError, findings, severe, moderateSignatures,
    unexpectedModerate, staleBaseline
  }, null, 2) + '\n', 'utf8'),
  writeFile(textReportPath, textReport, 'utf8')
]);
console.log(textReport.trim());
await context.close();
await browser.close();

if (auditError || severe.length || unexpectedModerate.length) process.exitCode = 1;
