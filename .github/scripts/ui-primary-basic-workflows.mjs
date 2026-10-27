import { navigateToPage } from './ui-role-fixtures.mjs';

export async function verifyGraphEntry({ page, evidence }) {
  await navigateToPage(page, 'analyze');
  const actions = page.locator('#taxonomyTree .tax-node-actions-toggle:visible').first();
  if (!await actions.isVisible()) {
    await page.locator('#taxonomyTree .tax-container-toggle:visible').first().click();
  }
  await actions.click();
  const button = page.locator('#taxonomyActionsDialog .graph-explore-btn');
  const code = await button.getAttribute('data-code');
  const response = page.waitForResponse(r => new URL(r.url()).pathname
    .endsWith(`/api/graph/node/${encodeURIComponent(code)}/upstream`));
  await button.click();
  const result = await response;
  evidence.assert(result.ok(), `Graph entry failed: HTTP ${result.status()}`);
  const graph = await result.json();
  evidence.assert(graph.originNodeCode === code, 'Graph entry queried a different node');
  await page.locator('#tab-graph').waitFor({ state: 'visible' });
  await page.locator('#graphResultsContent .spinner-border').waitFor({ state: 'hidden' });
  evidence.assert(await page.locator('#graphNodeInput').inputValue() === code, 'Selected code was lost');
  evidence.assert(await page.locator('#graphNodeInput').evaluate(el => el === document.activeElement),
    'Graph entry must move keyboard focus out of the closed action dialog');
  if (graph.neighbors.length) {
    await page.locator('#graphViewGraph svg').waitFor({ state: 'visible' });
  } else {
    await page.locator('#graphResultsContent [role="status"]').waitFor({ state: 'visible' });
  }
  await evidence.saveState('graph-node-action', '#graphExplorerPanel');
  evidence.passed('taxonomy node action opens a visible graph with a real upstream response and keyboard focus');
  await navigateToPage(page, 'analyze');
}

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
  await verifyGraphEntry({ page, evidence });

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

  // Export an existing view, not just text. The current-view route must actually
  // receive the request; a preflight "missing architecture" error is not this test.
  const fixture = {
    viewTitle: 'QA existing architecture',
    includedElements: [{ nodeCode: 'BP', title: 'Business Processes',
      taxonomySheet: 'BP', relevance: .91, anchor: true, taxonomyDepth: 0 }],
    includedRelationships: []
  };
  const exportRequests = [];
  const exportRoute = '**/api/diagram/current/visio';
  const failExport = route => {
    exportRequests.push(route.request().postDataJSON());
    return route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'EXPORT_SERVICE_UNAVAILABLE' })
    });
  };
  await page.route(exportRoute, failExport);
  try {
    const outcome = await page.evaluate(async view => {
      const state = window.TaxonomyState;
      const previousView = state.currentArchView;
      const previousText = state.lastAnalyzedText;
      const text = 'QA export failure requirement';
      state.currentArchView = view;
      state.lastAnalyzedText = text;
      const before = JSON.stringify(state.currentArchView);
      try {
        const exported = await window.TaxonomyExport.exportVisio(text);
        return { exported, preserved: before === JSON.stringify(state.currentArchView) };
      } finally {
        state.currentArchView = previousView;
        state.lastAnalyzedText = previousText;
      }
    }, fixture);
    assert(outcome.exported === false, 'Failed backend export must not report success');
    assert(outcome.preserved, 'Failed export changed the existing architecture');
    assert(exportRequests.length === 1, `Expected one current-view export, got ${exportRequests.length}`);
    const expectedRequest = {
      ...fixture,
      originalRequirementText: 'QA export failure requirement'
    };
    assert(JSON.stringify(exportRequests[0]) === JSON.stringify(expectedRequest),
      'Export did not send the frozen existing architecture with its captured requirement text');
    await waitForText('#operationToastBody', text => text.includes('EXPORT_SERVICE_UNAVAILABLE'));
  } finally {
    await page.unroute(exportRoute, failExport);
  }
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
