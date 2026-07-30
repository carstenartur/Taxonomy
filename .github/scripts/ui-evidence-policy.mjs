import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const VALID_MODES = new Set(['compact', 'full']);
const DEFAULT_CURATED_STATES = 'analysis-success';
const MAX_FULL_PAGE_DIMENSION = 30_000;

function normalizeStates(value) {
  const candidates = Array.isArray(value) ? value : String(value || '').split(',');
  return [...new Set(candidates.map(item => String(item).trim()).filter(Boolean))];
}

export function createEvidencePolicy({
  mode = process.env.TAXONOMY_UI_EVIDENCE_MODE || 'compact',
  curatedStates = process.env.TAXONOMY_UI_CURATED_STATES || DEFAULT_CURATED_STATES
} = {}) {
  const normalizedMode = String(mode).trim().toLowerCase();
  if (!VALID_MODES.has(normalizedMode)) {
    throw new Error(`Unsupported TAXONOMY_UI_EVIDENCE_MODE=${mode}; expected compact or full`);
  }
  const normalizedStates = normalizeStates(curatedStates);
  const curated = new Set(normalizedStates);
  return {
    mode: normalizedMode,
    curatedStates: normalizedStates,
    shouldCapture: state => normalizedMode === 'full' || curated.has(state)
  };
}

export function screenshotStrategy({ mode, captured, width, height }) {
  if (!captured) return 'none';
  if (mode === 'compact') return 'viewport';
  return width <= MAX_FULL_PAGE_DIMENSION && height <= MAX_FULL_PAGE_DIMENSION
    ? 'full-page'
    : 'segmented';
}

export async function captureFailureEvidence({
  page,
  outputDir,
  prefix = 'failure',
  selector = 'body'
}) {
  await mkdir(outputDir, { recursive: true });
  const target = page.locator(selector);
  await target.waitFor({ state: 'visible', timeout: 5_000 });

  const screenshot = path.join(outputDir, `${prefix}.png`);
  const html = path.join(outputDir, `${prefix}.html`);
  const aria = path.join(outputDir, `${prefix}.aria.txt`);

  // A single viewport image is sufficient to locate the failing state while
  // avoiding the unbounded full-page screenshots that made successful CI
  // evidence hundreds of megabytes large.
  await page.screenshot({ path: screenshot, fullPage: false, animations: 'disabled' });
  await writeFile(html, await target.evaluate(node => node.outerHTML), 'utf8');
  const ariaSnapshot = typeof target.ariaSnapshot === 'function'
    ? await target.ariaSnapshot().catch(error => `ARIA snapshot unavailable: ${error}`)
    : 'ARIA snapshot API unavailable';
  await writeFile(aria, `${ariaSnapshot}\n`, 'utf8');

  return {
    selector,
    files: [path.basename(screenshot), path.basename(html), path.basename(aria)]
  };
}
