import AxeBuilder from '@axe-core/playwright';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';

export function createRoleStateEvidence({ page, outputDir, checks, findings }) {
  async function saveState(state) {
    const prefix = path.join(outputDir, state);
    const dimensions = await page.evaluate(() => ({
      width: Math.max(document.documentElement.scrollWidth, document.body?.scrollWidth || 0),
      height: Math.max(document.documentElement.scrollHeight, document.body?.scrollHeight || 0)
    }));
    const viewport = page.viewportSize() || {
      width: Math.min(dimensions.width, 1440),
      height: Math.min(dimensions.height, 1000)
    };
    const screenshotFiles = [];
    const segments = [];
    if (dimensions.width <= 30000 && dimensions.height <= 30000) {
      const file = `${prefix}.png`;
      await page.screenshot({ path: file, fullPage: true, animations: 'disabled' });
      screenshotFiles.push(path.basename(file));
      segments.push({ file: path.basename(file), scrollY: 0, fullPage: true });
    } else {
      const maxScrollY = Math.max(0, dimensions.height - viewport.height);
      const targets = [];
      for (let y = 0; y < maxScrollY; y += viewport.height) targets.push(y);
      if (targets.length === 0 || targets.at(-1) !== maxScrollY) targets.push(maxScrollY);
      for (let index = 0; index < targets.length; index += 1) {
        const requestedY = targets[index];
        const actualY = await page.evaluate(y => {
          window.scrollTo(0, y);
          return window.scrollY;
        }, requestedY);
        await page.waitForTimeout(50);
        const file = `${prefix}.part-${String(index + 1).padStart(2, '0')}.png`;
        await page.screenshot({ path: file, fullPage: false, animations: 'disabled' });
        screenshotFiles.push(path.basename(file));
        segments.push({ file: path.basename(file), requestedY, scrollY: actualY,
          width: viewport.width, height: viewport.height });
      }
      await page.evaluate(() => window.scrollTo(0, 0));
    }
    await writeFile(`${prefix}.screenshots.json`, `${JSON.stringify({
      dimensions, viewport, screenshotFiles, segments
    }, null, 2)}\n`, 'utf8');
    await writeFile(`${prefix}.html`, await page.content(), 'utf8');
    const body = page.locator('body');
    const aria = typeof body.ariaSnapshot === 'function'
      ? await body.ariaSnapshot().catch(error => `ARIA snapshot unavailable: ${error}`)
      : 'ARIA snapshot API unavailable';
    await writeFile(`${prefix}.aria.txt`, `${aria}\n`, 'utf8');
  }

  async function runAxe(state) {
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    const blocking = result.violations.filter(violation =>
      ['critical', 'serious', 'moderate'].includes(violation.impact));
    findings.push({ state, violations: result.violations, blocking });
    if (blocking.length) {
      const summary = blocking.map(item => `${item.impact}:${item.id}`).join(', ');
      throw new Error(`Blocking axe findings in ${state}: ${summary}`);
    }
    checks.push(`axe ${state}`);
  }

  return { saveState, runAxe };
}
