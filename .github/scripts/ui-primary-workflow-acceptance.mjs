import AxeBuilder from '@axe-core/playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ROLE_ACCOUNTS,
  csrfJson,
  navigateArchitectureSubtab,
  navigateToPage,
  openRoleSession
} from './ui-role-fixtures.mjs';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const role = process.env.TAXONOMY_ROLE || 'USER';
const browserName = process.env.TAXONOMY_BROWSER || 'chromium';
const adminUsername = process.env.TAXONOMY_UI_ADMIN_USERNAME || 'admin';
const adminPassword = process.env.TAXONOMY_UI_ADMIN_PASSWORD || ROLE_ACCOUNTS.ADMIN.password;
const outputDir = path.resolve(
  process.env.TAXONOMY_UI_OUTPUT_DIR || path.join('target', 'ui-primary', role.toLowerCase()));

const checks = [];
const axeFindings = [];
const screenshots = [];
let auditError = null;
let browser;
let context;
let page;
let account;

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function passed(check) {
  checks.push(check);
}

async function saveState(state, locator = '#mainContent') {
  const target = page.locator(locator);
  await target.waitFor({ state: 'visible', timeout: 20_000 });
  const file = path.join(outputDir, `${state}.png`);
  await target.screenshot({ path: file, animations: 'disabled' });
  screenshots.push(path.basename(file));
  await writeFile(path.join(outputDir, `${state}.html`), await target.evaluate(node => node.outerHTML), 'utf8');
  const aria = typeof target.ariaSnapshot === 'function'
    ? await target.ariaSnapshot().catch(error => `ARIA snapshot unavailable: ${error}`)
    : 'ARIA snapshot API unavailable';
  await writeFile(path.join(outputDir, `${state}.aria.txt`), `${aria}\n`, 'utf8');
}

async function axeState(state, include = '#mainContent') {
  const result = await new AxeBuilder({ page })
    .include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter(item =>
    ['critical', 'serious', 'moderate'].includes(item.impact));
  axeFindings.push({ state, violations: result.violations, blocking });
  if (blocking.length) {
    throw new Error(`Blocking axe findings in ${state}: ${blocking.map(item => `${item.impact}:${item.id}`).join(', ')}`);
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

async function testEmptyAnalysisAndLiveRegion() {
  await navigateToPage(page, 'analyze');
  await page.locator('#businessText').fill('');
  await page.locator('#analyzeBtn').click();
  await waitForText('#statusArea', text => text.trim().length > 0);
  await waitForText('#a11yStatus', text => text.trim().length > 0);
  await axeState('analysis-empty-error');
  await saveState('analysis-empty-error');
  passed('empty analysis actionable status and live announcement');
}

async function testRoleSurfaces() {
  const contextData = await page.evaluate(() => window.TaxonomyRoleSurface.getContext());
  assert(contextData.roles.includes(role), `Role context does not contain ${role}: ${JSON.stringify(contextData)}`);

  async function controlCapability(selector) {
    const locator = page.locator(selector);
    assert(await locator.count() === 1, `Expected exactly one control for ${selector}`);
    return locator.evaluate(element => ({
      hidden: element.hidden,
      disabled: 'disabled' in element ? element.disabled : false,
      ariaHidden: element.getAttribute('aria-hidden')
    }));
  }

  const architectureAllowed = role === 'ARCHITECT' || role === 'ADMIN';
  const architectureSelectors = [
    '#docImportUploadBtn', '#createRelationBtn', '#archiMateImportBtn',
    '#importExecuteBtn', '#proposeRelationsSubmit'
  ];
  for (const selector of architectureSelectors) {
    const capability = await controlCapability(selector);
    const available = !capability.hidden && !capability.disabled && capability.ariaHidden !== 'true';
    assert(available === architectureAllowed,
      `${selector} capability=${JSON.stringify(capability)}, expected allowed=${architectureAllowed} for ${role}`);
  }

  const workspaceCapability = await controlCapability('#wsCreateBtn');
  const workspaceAvailable = !workspaceCapability.hidden
    && !workspaceCapability.disabled
    && workspaceCapability.ariaHidden !== 'true';
  assert(workspaceAvailable === (role === 'ADMIN'),
    `Workspace creation capability=${JSON.stringify(workspaceCapability)}, expected allowed=${role === 'ADMIN'}`);
  passed('role-appropriate mutation controls');
}

async function testCsvExportAndBackendFailure() {
  const downloadPromise = page.waitForEvent('download');
  await page.evaluate(() => {
    window.TaxonomyExport.exportCsv(
      { BP: 91, CR: 72 },
      [
        { code: 'BP', name: 'Business Processes', children: [] },
        { code: 'CR', name: 'Core Services', children: [] }
      ]
    );
  });
  const download = await downloadPromise;
  assert((await download.suggestedFilename()) === 'taxonomy-scores.csv',
    `Unexpected export filename: ${await download.suggestedFilename()}`);
  passed('CSV export download');

  await page.route('**/api/diagram/visio', async route => {
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'EXPORT_PROVIDER_UNAVAILABLE', message: 'Diagram service unavailable' })
    });
  }, { times: 1 });
  await page.evaluate(() => window.TaxonomyExport.exportVisio('QA export failure requirement'));
  await waitForText('#operationToastBody', text => text.toLowerCase().includes('unavailable') || text.includes('503'));
  await page.waitForFunction(() => {
    const toast = document.getElementById('operationToast');
    if (!toast || toast.dataset.toastVisible !== 'true') return false;
    const style = getComputedStyle(toast);
    return style.display !== 'none'
      && style.visibility !== 'hidden'
      && Number.parseFloat(style.opacity || '1') > 0;
  }, null, { timeout: 10_000 });
  await page.locator('#operationToast').waitFor({ state: 'visible', timeout: 10_000 });
  await axeState('export-backend-error', '#operationToast');
  await saveState('export-backend-error', '#operationToast');
  passed('export failure feedback');
}

async function createPendingProposals() {
  const candidates = [
    ['CP', 'BR', 'SUPPORTS'],
    ['BP', 'UA', 'DEPENDS_ON'],
    ['CI', 'CO', 'COMMUNICATES_WITH'],
    ['CR', 'IP', 'PRODUCES'],
    ['UA', 'CO', 'USES'],
    ['BP', 'CI', 'REALIZES']
  ];
  const ids = [];
  for (const [sourceCode, targetCode, relationType] of candidates) {
    const response = await csrfJson(page, '/api/proposals/from-hypothesis', {
      body: {
        sourceCode,
        targetCode,
        relationType,
        confidence: 0.73,
        rationale: `Primary workflow ${sourceCode}-${targetCode}-${relationType}`
      }
    });
    if (response.status === 200 && response.json?.id) ids.push(response.json.id);
    if (ids.length >= 4) break;
  }
  assert(ids.length >= 4, `Unable to create four independent proposals; created ${ids.length}`);
  return ids;
}

async function loadPendingProposals() {
  await navigateArchitectureSubtab(page, 'relations');
  await page.locator('#filterPending').click();
  await page.locator('.proposal-table').waitFor({ state: 'visible', timeout: 20_000 });
}

async function testProposalLifecycle() {
  const ids = await createPendingProposals();
  await loadPendingProposals();
  await axeState('proposals-pending', '#proposalsPanel');
  await saveState('proposals-pending', '#proposalsPanel');

  await page.getByRole('button', { name: `Accept proposal ${ids[0]}` }).click();
  await page.locator('#undoToast').waitFor({ state: 'visible', timeout: 15_000 });
  await page.locator('#undoBtn').click();
  await waitForText('#statusArea', text => text.toLowerCase().includes('revert') || text.toLowerCase().includes('undo'));
  passed('proposal accept and revert');

  await loadPendingProposals();
  await page.getByRole('button', { name: `Reject proposal ${ids[1]}` }).click();
  await waitForText('#statusArea', text => text.toLowerCase().includes('reject'));
  passed('proposal reject');

  await loadPendingProposals();
  const selectable = page.locator('.proposal-select');
  assert(await selectable.count() >= 2, 'Bulk proposal fixture has fewer than two pending proposals');
  await page.locator('#proposalSelectAll').check();
  await page.locator('#bulkAcceptBtn').click();
  await waitForText('#statusArea', text => text.toLowerCase().includes('accept'));
  await axeState('proposals-bulk-result', '#proposalsPanel');
  await saveState('proposals-bulk-result', '#proposalsPanel');
  passed('proposal bulk action');
}

async function testRelationLifecycle() {
  await navigateArchitectureSubtab(page, 'relations');
  const browserPanel = page.locator('#relationsBrowser');
  if (!(await browserPanel.getAttribute('open'))) await browserPanel.locator('summary').click();
  await page.locator('#relationsTableContainer').waitFor({ state: 'visible' });

  async function openCreate() {
    await page.locator('#createRelationBtn').click();
    await page.locator('#createRelationModal').waitFor({ state: 'visible', timeout: 10_000 });
    await page.locator('#newRelSource').fill('CP');
    await page.locator('#newRelTarget').fill('IP');
    await page.locator('#newRelType').selectOption('CONSUMES');
    await page.locator('#newRelDescription').fill('Primary workflow relation');
  }

  await openCreate();
  await page.locator('#createRelationSubmit').click();
  await page.locator('#createRelationModal').waitFor({ state: 'hidden', timeout: 15_000 });
  await waitForText('#relationsTableContainer', text => text.includes('CP') && text.includes('IP'));
  passed('relation create');

  await openCreate();
  await page.locator('#createRelationSubmit').click();
  await page.locator('#createRelationError').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('relation-duplicate-error', '#createRelationModal');
  await saveState('relation-duplicate-error', '#createRelationModal');
  const modal = page.locator('#createRelationModal');
  await modal.locator('[data-bs-dismiss="modal"]').first().click();
  await modal.waitFor({ state: 'hidden' });
  passed('relation duplicate conflict feedback');

  const row = page.locator('#relationsTableContainer tr', { hasText: 'CP' }).filter({ hasText: 'IP' }).first();
  page.once('dialog', dialog => dialog.accept());
  await row.locator('.relation-delete-btn').click();
  await page.waitForFunction(() => {
    return !Array.from(document.querySelectorAll('#relationsTableContainer tr'))
      .some(row => row.textContent.includes('CP') && row.textContent.includes('IP'));
  }, null, { timeout: 15_000 });
  passed('relation delete confirmation');
}

async function testDocumentImportStates() {
  await navigateToPage(page, 'analyze');
  await page.route('**/api/documents/upload', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        fileName: 'requirements.pdf',
        mimeType: 'application/pdf',
        totalPages: 1,
        totalCandidates: 2,
        sourceArtifactId: 42,
        candidates: [
          { text: 'The service shall remain available.', sectionHeading: 'Availability', pageNumber: 1 },
          { text: 'The service shall record decisions.', sectionHeading: 'Traceability', pageNumber: 1 }
        ]
      })
    });
  }, { times: 1 });
  await page.locator('#docImportFile').setInputFiles({
    name: 'requirements.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('The service shall remain available.\nThe service shal decisions.')
  });
  await page.locator('#docImportUploadBtn').click();
  await page.locator('#docCandidateReviewPanel').waitFor({ state: 'visible', timeout: 15_000 });
  assert((await page.locator('#docCandidateCount').textContent()).trim() === '2', 'Candidate count is not two');
  await axeState('document-review-success', '#docCandidateReviewPanel');
  await saveState('document-review-success', '#docCandidateReviewPanel');
  passed('document upload and candidate review');

  await page.route('**/api/documents/upload', async route => {
    await route.fulfill({
      status: 422,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'DOCUMENT_RESOURCE_LIMIT', message: 'Expanded document exceeds the configured limit' })
    });
  }, { times: 1 });
  await page.locator('#docImportFile').setInputFiles({
    name: 'oversized.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('oversized')
  });
  await page.locator('#docImportUploadBtn').click();
  await waitForText('#docImportStatus', text => text.includes('DOCUMENT_RESOURCE_LIMIT'));
  await axeState('document-upload-error', '#docImportPanel');
  await saveState('document-upload-error', '#docImportPanel');
  passed('document upload resource error');
}

async function testFrameworkImportStates() {
  await navigateArchitectureSubtab(page, 'export');
  await page.waitForFunction(() => document.querySelectorAll('#importProfileSelect option').length > 1,
    null, { timeout: 20_000 });
  await page.locator('#importProfileSelect').selectOption('apqc');
  await page.locator('#importFrameworkFile').setInputFiles({
    name: 'apqc.csv',
    mimeType: 'text/csf',
    buffer: Buffer.from('PCF ID,Name,Level,Description\n1.0,Strategy,1,QA')
  });

  await page.route('**/api/import/preview/apqc', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        profileId: 'apqc',
        profileDisplayName: 'APPC PCF',
        elementsTotal: 1,
        elementsMapped: 1,
        relationsTotal: 0,
        relationsMapped: 0,
        unmappedTypes: [],
        warnings: []
      })
    });
  }, { times: 1 });
  await page.locator('#importPreviewBtn').click();
  await page.locator('#importResultArea table').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('framework-import-preview', '#importResultArea');
  await saveState('framework.import-preview', '#importResultArea');
  passed('framework import preview');

  await page.route('**/api/import/apqc?branch=', async route => {
    await route.fulfill({
      status: 422,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'INVALID_IMPORT', message: 'The selected framework file is inconsistent' })
    });
  }, { times: 1 });
  await page.locator('#importExecuteBtn').click();
  await page.locator('#importResultArea .alert-danger').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('framework-import-error', '#importResultArea');
  await saveState('framework-import-error', '#importResultArea');
  passed('framework import error feedback');
}

async function openVersionsSync() {
  await navigateToPage(page, 'versions');
  await page.locator('#versionsSubTabs [data-versions-tab="sync"]').click();
  await page.locator('#versions-sync').waitFor({ state: 'visible', timeout: 10_000 });
}

async function testWorkspaceSyncStates() {
  await openVersionsSync();
  await page.route('**/api/workspace/sync-state', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        syncStatus: 'DIVERGED',
        unpublishedCommitCount: 2,
        lastSyncedCommitId: 'abcdef1234567890'
      })
    });
  }, { times: 1 });
  await page.evaluate(() => window.TaxonomyWorkspaceSync.refresh());
  await page.locator('#syncStatePanel button').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('workspace-conflict', '#versions-sync');
  await saveState('workspace-conflict', '#versions-sync');
  passed('workspace diverged conflict state');

  await page.route('**/api/workspace/sync-from-shared=', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'REMOTE_UNAVAILABLE', message: 'Team repository unavailable' })
    });
  }, { times: 1 });
  await page.evaluate(() => window.TaxonomyWorkspaceSync.syncFromShared());
  await waitForText('#operationToastBody', text => text.toLowerCase().includes('unavailable'));
  await axeState('workspace-sync-error', '#operationToast');
  await saveState('workspace-sync-error', '#operationToast');
  passed('workspace remote sync error');

  await page.route('**/api/workspace/publish?=', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  }, { times: 1 });
  await page.evaluate(() => window.TaxonomyWorkspaceSync.publish());
  await page.locator('#operationToast').waitFor({ state: 'visible', timeout: 10_000 });
  await axeState('workspace-publish-success', '#operationToast');
  passed('workspace publish success');
}

async function testPasswordChangeStates() {
  await page.goto(`${baseUrl}/change-password`, { waitUntil: 'networkidle' });
  await page.locator('#currentPassword').fill(account.password);
  await page.locator('#newPassword').fill('qa-primary-new-password-2026');
  await page.locator('#confirmPassword').fill('mismatch-password-2026');
  await page.locator('#changePasswordForm button[type="submit"]').click();
  await page.locator('[role="alert"]').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('password-confirmation-error', 'main');
  await saveState('password-confirmation-error', 'main');
  passed('password validation error');

  await page.locator('#currentPassword').fill(account.password);
  await page.locator('#newPassword').fill('qa-primary-new-password-2026');
  await page.locator('#confirmPassword').fill('qa-primary-new-password-2026');
  await Promise.all([
    page.waitForURL(url => url.pathname === '/' && url.searchParams.get('passwordChanged') === 'true', { timeout: 20_000 }),
    page.locator('#changePasswordForm button[type="submit"]').click()
  ]);
  passed('password change success');
}

await mkdir(outputDir, { recursive: true });

try {
  ({ browser, context, page, account } = await openRoleSession({
    baseUrl,
    role,
    browserName,
    adminUsername,
    adminPassword
  }));

  await testEmptyAnalysisAndLiveRegion();
  await testRoleSurfaces();
  await testCsvExportAndBackendFailure();

  if (role === 'ARCHITECT' || role === 'ADMIN') {
    await testProposalLifecycle();
    await testRelationLifecycle();
    await testDocumentImportStates();
    await testFrameworkImportStates();
  } else {
    const relationDenied = await csrfJson(page, '/api/relations', {
      body: {
        sourceCode: 'CP', targetCode: 'IP', relationType: 'CONSUMES',
        description: 'Denied USER relation', provenance: 'MANUAL'
      }
    });
    assert(relationDenied.status === 403, `USER relation mutation returned ${relationDenied.status}`);
    passed('USER relation mutation forbidden');
  }

  if (role === 'ADMIN') await testWorkspaceSyncStates();
  if (role === 'USER') await testPasswordChangeStates();
} catch (error) {
  auditError = error?.stack || String(error);
  process.exitCode = 1;
} finally {
  const report = { role, browserName, checks, axeFindings, screenshots, auditError };
  await writeFile(path.join(outputDir, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  if (auditError) {
    console.error(`Primary workflow acceptance failed for ${role}:\n${auditError}`);
    console.error(JSON.stringify(report, null, 2));
  }
  if (context) await context.close().catch(() => undefined);
  if (browser) await browser.close().catch(() => undefined);
}

if (auditError) throw new Error(auditError);
console.log(`Primary workflow acceptance passed for ${role}: ${checks.join(', ')}`);
