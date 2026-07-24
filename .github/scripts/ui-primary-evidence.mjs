import AxeBuilder from '@axe-core/playwright';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';

export function createEvidence(page, outputDir) {
  const checks = [];
  const axeFindings = [];
  const screenshots = [];

  function assert(condition, message) {
    if (!condition) throw new Error(message);
  }

  function passed(check) {
    checks.push(check);
  }

  async function saveState(state, selector = '#mainContent') {
    const target = page.locator(selector);
    await target.waitFor({ state: 'visible', timeout: 20_000 });
    const file = path.join(outputDir, `${state}.png`);
    await target.screenshot({ path: file, animations: 'disabled' });
    screenshots.push(path.basename(file));
    await writeFile(path.join(outputDir, `${state}.html`),
      await target.evaluate(node => node.outerHTML), 'utf8');
    const aria = typeof target.ariaSnapshot === 'function'
      ? await target.ariaSnapshot().catch(error => `ARIA snapshot unavailable: ${error}`)
      : 'ARIA snapshot API unavailable';
    await writeFile(path.join(outputDir, `${state}.aria.txt`), `${aria}\n`, 'utf8');
  }

  async function axeState(state, selector = '#mainContent') {
    const result = await new AxeBuilder({ page })
      .include(selector)
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    const blocking = result.violations.filter(item =>
      ['critical', 'serious', 'moderate'].includes(item.impact));
    axeFindings.push({ state, violations: result.violations, blocking });
    if (blocking.length) {
      throw new Error(`Blocking axe findings in ${state}: ${blocking
        .map(item => `${item.impact}:${item.id}`).join(', ')}`);
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

  return {
    assert,
    passed,
    saveState,
    axeState,
    waitForText,
    report: auditError => ({ checks, axeFindings, screenshots, auditError })
  };
}
