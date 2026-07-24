import { navigateToPage } from './ui-role-fixtures.mjs';

export async function runBasicWorkflows({ page, role, evidence }) {
  const { assert, passed, saveState, axeState, waitForText } = evidence;

  await navigateToPage(page, 'analyze');
  await page.locator('#businessText').fill('');
  await page.locator('#analyzeBtn').click();
  await waitForText('#statusArea', text => text.trim().length > 0);
  await waitForText('#a11yStatus', text => text.trim().length > 0);
  await axeState('analysis-empty-error');
  await saveState('analysis-empty-error');
  passed('empty analysis actionable status and live announcement');

  const roleContext = await page.evaluate(() => window.TaxonomyRoleSurface.getContext());
  assert(roleContext.roles.includes(role),
    `Role context does not contain ${role}: ${JSON.stringify(roleContext)}`);

  async function capability(selector) {
    const locator = page.locator(selector);
    assert(await locator.count() === 1, `Expected exactly one control for ${selector}`);
    return locator.evaluate(element => ({
      hidden: element.hidden,
      disabled: 'disabled' in element ? element.disabled : false,
      ariaHidden: element.getAttribute('aria-hidden')
    }));
  }

  const architectureAllowed = role === 'ARCHITECT' || role === 'ADMIN';
  for (const selector of ['#docImportUploadBtn', '#createRelationBtn', '#archiMateImportBtn',
    '#importExecuteBtn', '#proposeRelationsSubmit']) {
    const state = await capability(selector);
    const available = !state.hidden && !state.disabled && state.ariaHidden !== 'true';
    assert(available === architectureAllowed,
      `${selector} capability=${JSON.stringify(state)}, expected allowed=${architectureAllowed}`);
  }
  const workspace = await capability('#wsCreateBtn');
  const workspaceAvailable = !workspace.hidden && !workspace.disabled && workspace.ariaHidden !== 'true';
  assert(workspaceAvailable === (role === 'ADMIN'),
    `Workspace capability=${JSON.stringify(workspace)}, expected ADMIN-only`);
  passed('role-appropriate mutation controls');

  const downloadPromise = page.waitForEvent('download');
  await page.evaluate(() => window.TaxonomyExport.exportCsv(
    { BP: 91, CR: 72 },
    [{ code: 'BP', name: 'Business Processes', children: [] },
      { code: 'CR', name: 'Core Services', children: [] }]));
  const download = await downloadPromise;
  assert(download.suggestedFilename() === 'taxonomy-scores.csv',
    `Unexpected export filename: ${download.suggestedFilename()}`);
  passed('CSV export download');

  await page.route('**/api/diagram/visio', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'EXPORT_PROVIDER_UNAVAILABLE', message: 'Diagram service unavailable' })
  }), { times: 1 });
  await page.evaluate(() => window.TaxonomyExport.exportVisio('QA export failure requirement'));
  await waitForText('#operationToastBody', text =>
    text.toLowerCase().includes('unavailable') || text.includes('503'));
  await page.waitForFunction(() => {
    const toast = document.getElementById('operationToast');
    if (!toast || toast.dataset.toastVisible !== 'true') return false;
    const rect = toast.getBoundingClientRect();
    const style = getComputedStyle(toast);
    return rect.width > 0 && rect.height > 0 && style.display !== 'none'
      && style.visibility !== 'hidden' && Number.parseFloat(style.opacity || '1') > 0;
  }, null, { timeout: 10_000 });
  await axeState('export-backend-error', '#operationToast');
  await saveState('export-backend-error', '#operationToast');
  passed('export failure feedback');

  // Error feedback intentionally persists until the user acknowledges it. Close
  // it through the real accessible control before continuing with later flows.
  await page.locator('#operationToast [data-bs-dismiss="toast"]').click();
  await page.locator('#operationToast').waitFor({ state: 'hidden', timeout: 10_000 });
  passed('export failure dismissed');
}
