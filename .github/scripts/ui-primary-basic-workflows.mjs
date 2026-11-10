import { request } from '@playwright/test';
import { basicAuthorization, ROLE_ACCOUNTS, navigateToPage } from './ui-role-fixtures.mjs';
import { usingComparisonBranch } from './ui-primary-version-workflow.mjs';

/** Persist real catalogue identities and an accepted relation; no browser graph response is intercepted. */
export async function seedSavedGraph(api, branch) {
  async function json(response) {
    if (!response.ok()) throw new Error(`Graph fixture HTTP ${response.status()}: ${await response.text()}`);
    return response.json();
  }
  const roots = await json(await api.get('/api/taxonomy'));
  const first = root => roots.find(node => node.code === root)?.children?.[0];
  const service = first('CR'), process = first('BP');
  if (!service?.code || !service.nameEn || !process?.code || !process.nameEn)
    throw new Error('Real service and process catalogue nodes are required');
  const dsl = 'meta { language: "taxdsl"; version: "2.0"; namespace: "default"; }\n'
    + `element ${service.code} type CoreService { title: ${JSON.stringify(service.nameEn)}; }\n`
    + `element ${process.code} type Process { title: ${JSON.stringify(process.nameEn)}; }\n`
    + `relation ${service.code} SUPPORTS ${process.code} { status: accepted; }\n`;
  const saved = await json(await api.post('/api/dsl/commit?' + new URLSearchParams({branch,message:'Saved graph entry fixture'}),
    {data:dsl,headers:{'Content-Type':'text/plain;charset=UTF-8'}}));
  if (!saved.valid || !/^[a-f0-9]{40}$/.test(saved.commitId || '')) throw new Error('Graph fixture commit was not persisted');
  const scope = new URLSearchParams({workspaceId:'',workspaceScopeKey:'__shared__',branch});
  const initial = await json(await api.get('/api/architecture/relations/projection/readiness?' + scope));
  if (!initial.repositoryId) throw new Error('Graph fixture repository identity is missing');
  scope.set('repositoryId',initial.repositoryId);
  const projection = await json(await api.post('/api/architecture/relations/projection/rebuild?' + scope,
    {headers:{'If-Match':`"${saved.commitId}"`}}));
  if (projection.readinessState !== 'READY' || projection.currentHeadCommit !== saved.commitId
      || projection.projectedCommit !== saved.commitId || projection.relationCount !== 1)
    throw new Error('The saved graph projection does not prove its accepted relation at the selected head');
  return {repositoryId:initial.repositoryId,workspaceScopeKey:'__shared__',branch,commit:saved.commitId,
    code:process.code,serviceCode:service.code,scope:String(scope)};
}

export async function verifyGraphEntry(workflow) {
  const {page} = workflow;
  const returnUrl = page.url();
  // Provision through the same administrator account used by the role fixture. The
  // browser keeps its original USER/ARCHITECT/ADMIN identity for all reads and actions.
  const api = await request.newContext({baseURL:new URL(page.url()).origin,extraHTTPHeaders:{Authorization:basicAuthorization(
    process.env.TAXONOMY_UI_ADMIN_USERNAME || 'admin', process.env.TAXONOMY_UI_ADMIN_PASSWORD || ROLE_ACCOUNTS.ADMIN.password)}});
  try {
    await usingComparisonBranch(async branch => {
      const fixture = await seedSavedGraph(api,branch);
      const selected = new URL(page.url());
      selected.search = 'architectureSource=working&' + fixture.scope + '&commit=' + fixture.commit + '&lang=en';
      selected.hash = 'architecture';
      await page.goto(selected.href);
      await page.locator('#architectureWorkingSource').waitFor({state:'visible'});
      await page.waitForFunction(commit => document.getElementById('applicationContextSummaryStand').textContent.includes(commit.slice(0,12)), fixture.commit);
      await verifySelectedGraphEntry(workflow,fixture);
      // Leave the temporary branch before cleanup so later role workflows retain their own scope.
      await page.goto(returnUrl);
      await page.waitForFunction(() => Boolean(window.TaxonomyRoleSurface?.getContext()));
    }, async branch => {
      const response = await api.delete('/api/dsl/branch?' + new URLSearchParams({name:branch}));
      if (!response.ok() && response.status() !== 404) throw new Error(`Graph fixture cleanup failed: HTTP ${response.status()}`);
    });
  } finally { await api.dispose(); }
}

async function verifySelectedGraphEntry({ page, evidence }, fixture) {
  await navigateToPage(page, 'analyze');
  await page.locator('#gapSelectModel').click();
  await page.waitForFunction(commit => document.getElementById('gapModelSource').textContent.includes(commit.slice(0,12)), fixture.commit);
  await page.locator('#viewList').click();
  const actions = page.locator(`#taxonomyTree .tax-node[data-code="${fixture.code}"] .tax-node-actions-toggle`).first();
  if (!await actions.isVisible()) {
    await page.locator('#taxonomyTree [data-taxonomy-container="BP"] > .tax-container-heading > .tax-container-toggle').click();
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
  evidence.assert(graph.evidence.repositoryId === fixture.repositoryId
    && graph.evidence.workspaceScopeKey === '__shared__' && graph.evidence.branch === fixture.branch
    && graph.evidence.commitId === fixture.commit, 'Graph response must prove the explicitly selected saved model');
  evidence.assert(graph.neighbors.some(node => node.nodeCode === fixture.serviceCode),
    'The graph must traverse the accepted saved relation');
  evidence.assert(graph.traversedRelationships.some(edge => edge.evidence?.status === 'accepted'),
    'The real canonical relation must retain its accepted provenance');
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
