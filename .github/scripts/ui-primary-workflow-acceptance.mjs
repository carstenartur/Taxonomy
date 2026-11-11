import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { ROLE_ACCOUNTS, openRoleSession } from './ui-role-fixtures.mjs';
import { createEvidence } from './ui-primary-evidence.mjs';
import { captureFailureEvidence } from './ui-evidence-policy.mjs';
import { runVersionComparisonWorkflow } from './ui-primary-version-workflow.mjs';
import { runBasicWorkflows } from './ui-primary-basic-workflows.mjs';
import { runAnalysisSessionWorkflow } from './ui-primary-session-workflow.mjs';
import { runProposalWorkflows } from './ui-primary-proposal-workflows.mjs';
import { runRelationWorkflows } from './ui-primary-relation-workflows.mjs';
import { runImportWorkflows } from './ui-primary-import-workflows.mjs';
import { runPasswordWorkflows, runWorkspaceSyncWorkflows,
  verifyUserMutationDenied } from './ui-primary-account-workflows.mjs';
import { runPreferencesWorkflow } from './ui-primary-preferences-workflow.mjs';
import { runLocalePrintWorkflow } from './ui-locale-print-workflow.mjs';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const role = process.env.TAXONOMY_ROLE || 'USER';
const browserName = process.env.TAXONOMY_BROWSER || 'chromium';
const adminUsername = process.env.TAXONOMY_UI_ADMIN_USERNAME || 'admin';
const adminPassword = process.env.TAXONOMY_UI_ADMIN_PASSWORD || ROLE_ACCOUNTS.ADMIN.password;
const outputDir = path.resolve(process.env.TAXONOMY_UI_OUTPUT_DIR
  || path.join('target', 'ui-primary', role.toLowerCase()));

function installSessionVisualAssertions(page, evidence) {
  const capture = evidence.saveRequiredViewportState;
  evidence.saveRequiredViewportState = async (state, selector = '#mainContent') => {
    await capture(state, selector);
    if (state !== 'session-copilot-complete'
        && state !== 'session-copilot-updated-mobile') return;

    const rendered = await page.evaluate(currentState => {
      const content = document.getElementById('copilotContent');
      const text = content?.textContent || '';
      const percentages = [...text.matchAll(/(-?\d+(?:[.,]\d+)?)\s*%/g)]
        .map(match => Number(match[1].replace(',', '.')));
      const row = content?.querySelector(':scope > .row');
      const columns = row ? [...row.querySelectorAll(':scope > .col-4')] : [];
      const rowRect = row?.getBoundingClientRect();
      const cardRects = columns.map(column => column.getBoundingClientRect());
      const mobileGeometry = currentState !== 'session-copilot-updated-mobile' ? null : {
        count: cardRects.length,
        rowWidth: rowRect?.width || 0,
        widths: cardRects.map(rect => rect.width),
        stacked: cardRects.every((rect, index) => index === 0
          || cardRects[index - 1].bottom <= rect.top + 1),
        insideViewport: cardRects.every(rect => rect.left >= -1
          && rect.right <= window.innerWidth + 1)
      };
      return { percentages, mobileGeometry };
    }, state);

    evidence.assert(rendered.percentages.length >= 2,
      `${state} did not render the expected Copilot percentages`);
    evidence.assert(rendered.percentages.every(value => Number.isFinite(value)
        && value >= 0 && value <= 100),
    `${state} rendered an invalid percentage: ${JSON.stringify(rendered.percentages)}`);

    if (rendered.mobileGeometry) {
      const geometry = rendered.mobileGeometry;
      evidence.assert(geometry.count === 3,
        `Mobile Copilot summary has ${geometry.count} metric cards`);
      evidence.assert(geometry.rowWidth > 0
          && geometry.widths.every(width => width >= geometry.rowWidth * 0.9),
      `Mobile Copilot metrics remain compressed: ${JSON.stringify(geometry)}`);
      evidence.assert(geometry.stacked && geometry.insideViewport,
        `Mobile Copilot metrics collide or leave the viewport: ${JSON.stringify(geometry)}`);
    }
    evidence.passed(`${state} rendered bounded percentages and readable metric geometry`);
  };
}

function diagnosticNavigationUrl(value) {
  if (!value) return null;
  try {
    const url = new URL(value);
    // Exclude credentials, query values and fragments from diagnostic output.
    return ['http:', 'https:'].includes(url.protocol)
      ? (url.origin + url.pathname).slice(0, 512) : url.protocol;
  } catch {
    return '[unavailable]';
  }
}

function observeMainFrameNavigation(page, diagnostics) {
  const mainFrame = page.mainFrame();
  const record = (event, url) => {
    if (diagnostics.events.length === 20) {
      diagnostics.events.shift();
      diagnostics.omittedEarlierEvents++;
    }
    diagnostics.events.push({ event, at: new Date().toISOString(), url: diagnosticNavigationUrl(url) });
  };
  const navigated = frame => {
    if (frame === mainFrame) record('framenavigated', frame.url());
  };
  const requested = request => {
    if (!request.isNavigationRequest()) return;
    try {
      if (request.frame() === mainFrame) record('navigation-request', request.url());
    } catch { /* A navigation without an attributable frame is outside this diagnostic scope. */ }
  };
  page.on('framenavigated', navigated);
  page.on('request', requested);
  return () => {
    page.off('framenavigated', navigated);
    page.off('request', requested);
  };
}

let auditError = null;
let browser;
let context;
let page;
let account;
let evidence;
let failureEvidence = null;
const navigationDiagnostics = {
  purpose: 'Diagnostic navigation evidence only; not a fix or retry.',
  events: [], omittedEarlierEvents: 0, failureUrl: null, failureAt: null
};
let stopNavigationDiagnostics = () => {};
await mkdir(outputDir, { recursive: true });

try {
  ({ browser, context, page, account } = await openRoleSession({
    baseUrl, role, browserName, adminUsername, adminPassword
  }));
  stopNavigationDiagnostics = observeMainFrameNavigation(page, navigationDiagnostics);
  evidence = createEvidence(page, outputDir);
  installSessionVisualAssertions(page, evidence);
  const workflow = { page, role, account, baseUrl, evidence };

  // Capture the complete USER session before the deliberately invalid empty-input
  // baseline below can leave validation feedback in the shared browser surface.
  if (role === 'USER') await runAnalysisSessionWorkflow(workflow);

  await runBasicWorkflows(workflow);
  await runVersionComparisonWorkflow(workflow);
  if (role === 'ARCHITECT' || role === 'ADMIN') {
    await runProposalWorkflows(workflow);
    await runRelationWorkflows(workflow);
    await runImportWorkflows(workflow);
  } else {
    await verifyUserMutationDenied(workflow);
  }
  if (role === 'ADMIN') {
    await runWorkspaceSyncWorkflows(workflow);
    await runPreferencesWorkflow(workflow);
    await runLocalePrintWorkflow({ ...workflow, outputDir });
  }
  if (role === 'USER') await runPasswordWorkflows(workflow);
} catch (error) {
  auditError = error?.stack || String(error);
  navigationDiagnostics.failureUrl = diagnosticNavigationUrl(page?.url());
  navigationDiagnostics.failureAt = new Date().toISOString();
  process.exitCode = 1;
} finally {
  stopNavigationDiagnostics();
  if (auditError && page) {
    try {
      failureEvidence = await captureFailureEvidence({
        page, outputDir, prefix: 'failure', selector: '#mainContent'
      });
    } catch (error) {
      failureEvidence = { error: error?.stack || String(error), files: [] };
    }
  }
  const details = evidence ? evidence.report(auditError)
    : { checks: [], axeFindings: [], states: [], screenshots: [], auditError };
  const report = { role, browserName, ...details, failureEvidence, navigationDiagnostics };
  await writeFile(path.join(outputDir, 'report.json'),
    `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  if (auditError) console.error(`Primary workflow acceptance failed for ${role}:\n${auditError}`);
  if (context) await context.close().catch(() => undefined);
  if (browser) await browser.close().catch(() => undefined);
}

if (auditError) throw new Error(auditError);
console.log(`Primary workflow acceptance passed for ${role}`);
