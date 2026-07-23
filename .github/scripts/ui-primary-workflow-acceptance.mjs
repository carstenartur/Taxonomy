import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { ROLE_ACCOUNTS, openRoleSession } from './ui-role-fixtures.mjs';
import { createEvidence } from './ui-primary-evidence.mjs';
import { runBasicWorkflows } from './ui-primary-basic-workflows.mjs';
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

let auditError = null;
let browser;
let context;
let page;
let account;
let evidence;
await mkdir(outputDir, { recursive: true });

try {
  ({ browser, context, page, account } = await openRoleSession({
    baseUrl, role, browserName, adminUsername, adminPassword
  }));
  evidence = createEvidence(page, outputDir);
  const workflow = { page, role, account, baseUrl, evidence };

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
  const details = evidence ? evidence.report(auditError)
    : { checks: [], axeFindings: [], screenshots: [], auditError };
  const report = { role, browserName, ...details };
  await writeFile(path.join(outputDir, 'report.json'),
    `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  if (auditError) console.error(`Primary workflow acceptance failed for ${role}:\n${auditError}`);
  if (context) await context.close().catch(() => undefined);
  if (browser) await browser.close().catch(() => undefined);
}

if (auditError) throw new Error(auditError);
console.log(`Primary workflow acceptance passed for ${role}`);
