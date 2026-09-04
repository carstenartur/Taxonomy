import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { ROLE_ACCOUNTS, openRoleSession } from './ui-role-fixtures.mjs';
import { createEvidence } from './ui-primary-evidence.mjs';
import { captureFailureEvidence } from './ui-evidence-policy.mjs';
import { runBasicWorkflows } from './ui-primary-basic-workflows.mjs';
import { runAnalysisSessionWorkflow } from './ui-primary-session-workflow.mjs';
import { runProposalWorkflows } from './ui-primary-proposal-workflows.mjs';
import { runRelationWorkflows } from './ui-primary-relation-workflows.mjs';
import { runImportWorkflows } from './ui-primary-import-workflows.mjs';
import { runPasswordWorkflows, runWorkspaceSyncWorkflows,
  verifyUserMutationDenied } from './ui-primary-account-workflows.mjs';

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

let auditError = null;
let browser;
let context;
let page;
let account;
let evidence;
let failureEvidence = null;
await mkdir(outputDir, { recursive: true });

try {
  ({ browser, context, page, account } = await openRoleSession({
    baseUrl, role, browserName, adminUsername, adminPassword
  }));
  evidence = createEvidence(page, outputDir);
  installSessionVisualAssertions(page, evidence);
  const workflow = { page, role, account, baseUrl, evidence };

  // Capture the complete USER session before the deliberately invalid empty-input
  // baseline below can leave validation feedback in the shared browser surface.
  if (role === 'USER') await runAnalysisSessionWorkflow(workflow);

  await runBasicWorkflows(workflow);
  if (role === 'ARCHITECT' || role === 'ADMIN') {
    await runProposalWorkflows(workflow);
    await runRelationWorkflows(workflow);
    await runImportWorkflows(workflow);
  } else {
    await verifyUserMutationDenied(workflow);
  }
  if (role === 'ADMIN') await runWorkspaceSyncWorkflows(workflow);
  if (role === 'USER') await runPasswordWorkflows(workflow);
} catch (error) {
  auditError = error?.stack || String(error);
  process.exitCode = 1;
} finally {
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
  const report = { role, browserName, ...details, failureEvidence };
  await writeFile(path.join(outputDir, 'report.json'),
    `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  if (auditError) console.error(`Primary workflow acceptance failed for ${role}:\n${auditError}`);
  if (context) await context.close().catch(() => undefined);
  if (browser) await browser.close().catch(() => undefined);
}

if (auditError) throw new Error(auditError);
console.log(`Primary workflow acceptance passed for ${role}`);
