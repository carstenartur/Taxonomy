import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { openRoleSession, ROLE_ACCOUNTS } from './ui-role-fixtures.mjs';
import { createRoleStateEvidence } from './ui-role-state-evidence.mjs';
import { runRoleStateFlow } from './ui-role-state-flow.mjs';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const adminUsername = process.env.TAXONOMY_UI_ADMIN_USERNAME || 'admin';
const adminPassword = process.env.TAXONOMY_UI_ADMIN_PASSWORD || 'ui-state-admin-password';
const profileId = process.env.TAXONOMY_UI_PROFILE || 'desktop-user-chromium';
const browserName = process.env.TAXONOMY_BROWSER || 'chromium';
const role = process.env.TAXONOMY_ROLE || 'USER';
const physicalWidth = Number.parseInt(process.env.TAXONOMY_VIEWPORT_WIDTH || '1440', 10);
const physicalHeight = Number.parseInt(process.env.TAXONOMY_VIEWPORT_HEIGHT || '1000', 10);
const zoom = Number.parseFloat(process.env.TAXONOMY_ZOOM || '1');
const forcedColors = process.env.TAXONOMY_FORCED_COLORS === 'true';
const outputDir = path.resolve(
  process.env.TAXONOMY_UI_OUTPUT_DIR || path.join('target', 'ui-state', profileId));
const matrixPath = process.env.TAXONOMY_UI_MATRIX || '.github/ui-acceptance-matrix.json';

const matrix = JSON.parse(await readFile(matrixPath, 'utf8'));
if (matrix.schemaVersion !== 1) throw new Error(`Unsupported UI matrix schema: ${matrix.schemaVersion}`);
const declaredProfile = matrix.profiles.find(profile => profile.id === profileId);
if (!declaredProfile) throw new Error(`Profile ${profileId} is missing from ${matrixPath}`);
if (declaredProfile.browser !== browserName || declaredProfile.role !== role
    || declaredProfile.zoom !== zoom || Boolean(declaredProfile.forcedColors) !== forcedColors) {
  throw new Error(`Workflow/profile drift for ${profileId}`);
}
const effectiveViewport = {
  width: Math.max(320, Math.floor(physicalWidth / zoom)),
  height: Math.max(240, Math.floor(physicalHeight / zoom))
};

const checks = [];
const findings = [];
const consoleErrors = [];
const externalRequests = [];
const httpFailures = [];
let auditError = null;
let browser;
let context;
let page;
await mkdir(outputDir, { recursive: true });

try {
  ({ browser, context, page } = await openRoleSession({
    baseUrl, role, browserName, viewport: effectiveViewport,
    adminUsername, adminPassword,
    forcedColors, reducedMotion: true
  }));
  checks.push('keyboard authentication');
  const baseOrigin = new URL(baseUrl).origin;
  page.on('request', requestEvent => {
    const url = requestEvent.url();
    if (url.startsWith('data:') || url.startsWith('blob:')) return;
    if (new URL(url).origin !== baseOrigin) externalRequests.push(url);
  });
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin === baseOrigin && response.status() >= 400) {
      httpFailures.push({ path: url.pathname, status: response.status(),
        method: response.request().method() });
    }
  });
  page.on('console', message => {
    if (message.type() === 'error' && !message.text().includes('Failed to load resource')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', error => consoleErrors.push(error.message));

  const evidence = createRoleStateEvidence({ page, outputDir, checks, findings });
  await runRoleStateFlow({
    page, role, zoom, forcedColors, checks, httpFailures,
    externalRequests, consoleErrors, evidence
  });
} catch (error) {
  auditError = error?.stack || String(error);
  process.exitCode = 1;
} finally {
  const report = {
    profileId, browserName, role,
    physicalViewport: { width: physicalWidth, height: physicalHeight },
    effectiveViewport, zoom, forcedColors, checks, findings,
    externalRequests, httpFailures, consoleErrors, auditError
  };
  await writeFile(path.join(outputDir, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  if (auditError) {
    console.error(`UI role/state acceptance failed for ${profileId}:\n${auditError}`);
    console.error(JSON.stringify(report, null, 2));
  }
  if (context) await context.close().catch(() => undefined);
  if (browser) await browser.close().catch(() => undefined);
}

if (auditError) throw new Error(auditError);
console.log(`UI role/state acceptance passed for ${profileId}: ${checks.join(', ')}`);
