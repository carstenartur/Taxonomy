import { request } from '@playwright/test';
import { basicAuthorization, ROLE_ACCOUNTS, navigateToPage } from './ui-role-fixtures.mjs';
import { usingComparisonBranch } from './ui-primary-version-workflow.mjs';

/** Persist real catalogue identities and an accepted relation; no browser graph response is intercepted. */
export async function seedSavedGraph(api, branch, onResources = () => {}) {
  async function json(response) {
    if (!response.ok()) throw new Error(`Graph fixture HTTP ${response.status()}: ${await response.text()}`);
    return response.json();
  }
  const roots = await json(await api.get('/api/taxonomy'));
  const first = root => roots.find(node => node.code === root)?.children?.[0];
  const service = first('CR'), process = first('BP');
  if (!service?.code || !service.nameEn || !process?.code || !process.nameEn)
    throw new Error('Real service and process catalogue nodes are required');
  const dsl = 'meta {\n  language: "taxdsl";\n  version: "2.0";\n  namespace: "default";\n}\n'
    + `element ${service.code} type CoreService {\n  title: ${JSON.stringify(service.nameEn)};\n}\n`
    + `element ${process.code} type Process {\n  title: ${JSON.stringify(process.nameEn)};\n}\n`
    + `relation ${service.code} SUPPORTS ${process.code} {\n  status: accepted;\n}\n`;
  // The dedicated UI JVM uses an in-memory database. Its PUBLIC repository is
  // intentionally bounded by that process; only the private working copy has a deletion API.
  const repository = await json(await api.post('/api/repositories', {data:{
    displayName:`Saved graph ${branch}`,slug:`graph-${branch}`,
    description:'Published catalogue graph for native role verification',visibility:'PUBLIC',defaultBranch:branch
  }}));
  if (!repository.repositoryId) throw new Error('Graph fixture repository identity is missing');
  let resources = {repositoryId:repository.repositoryId,branch};
  onResources(resources);
  const scope = new URLSearchParams({repositoryId:repository.repositoryId,workspaceId:'',workspaceScopeKey:'__shared__',branch});
  const centralHeaders = {'X-Taxonomy-Workspace-Id':''};
  const workspace = await json(await api.post('/api/architecture/editor/workspaces?' + scope,
    {headers:centralHeaders,data:{repositoryId:repository.repositoryId,branch,displayName:`Graph working copy ${branch}`}}));
  resources = {...resources,workspaceId:workspace.workspaceId};
  // Capture the owned workspace before commit/publication, including a lost commit response.
  onResources(resources);
  if (!workspace.workspaceId || !workspace.currentBranch || workspace.provisioningStatus !== 'READY'
      || workspace.sourceRepositoryId !== repository.repositoryId || workspace.sourceBranch !== branch)
    throw new Error('The exact graph fixture working copy was not provisioned');
  const privateScope = new URLSearchParams({repositoryId:repository.repositoryId,
    workspaceId:workspace.workspaceId,branch:workspace.currentBranch});
  const privateHeaders = {'X-Taxonomy-Workspace-Id':workspace.workspaceId};
  const saved = await json(await api.post('/api/dsl/commit?' + privateScope + '&message=Saved+graph+entry+fixture',
    {data:dsl,headers:{...privateHeaders,'Content-Type':'text/plain;charset=UTF-8'}}));
  if (!saved.valid || !/^[a-f0-9]{40}$/.test(saved.commitId || '')) throw new Error('Graph fixture commit was not persisted');
  const published = await json(await api.post('/api/workspace/publish?' + privateScope
    + '&userBranch=' + encodeURIComponent(workspace.currentBranch), {headers:privateHeaders}));
  if (!published.success || !/^[a-f0-9]{40}$/.test(published.mergeCommit || ''))
    throw new Error('Graph fixture publication was not persisted');
  const commit = published.mergeCommit;
  const readiness = await json(await api.get('/api/architecture/relations/projection/readiness?' + scope,
    {headers:centralHeaders}));
  if (readiness.repositoryId !== repository.repositoryId || readiness.workspaceId !== null
      || readiness.branch !== branch || readiness.currentHeadCommit !== commit)
    throw new Error('Graph fixture publication is not the selected central head');
  const projection = await json(await api.post('/api/architecture/relations/projection/rebuild?' + scope,
    {headers:{...centralHeaders,'If-Match':`"${commit}"`}}));
  if (projection.repositoryId !== repository.repositoryId || projection.workspaceId !== null || projection.branch !== branch
      || projection.readinessState !== 'READY' || projection.currentHeadCommit !== commit
      || projection.projectedCommit !== commit || projection.relationCount !== 1)
    throw new Error('The saved graph projection does not prove its accepted relation at the selected head');
  return {...resources,workspaceScopeKey:'__shared__',commit,
    code:process.code,serviceCode:service.code,scope:String(scope)};
}

/** Deletes only the owned private working copy; central fixture data ends with the disposable JVM. */
export async function removeSavedGraph(api, resources) {
  if (!resources?.repositoryId) throw new Error('The exact graph fixture repository is required for cleanup');
  if (resources.workspaceId === undefined) return;
  if (typeof resources.workspaceId !== 'string' || !resources.workspaceId)
    throw new Error('The exact private graph fixture workspace is required for cleanup');
  const query = new URLSearchParams({repositoryId:resources.repositoryId,workspaceId:resources.workspaceId});
  const response = await api.delete('/api/workspace/' + encodeURIComponent(resources.workspaceId) + '?' + query,
    {headers:{'X-Taxonomy-Workspace-Id':resources.workspaceId}});
  if (!response.ok()) throw new Error(`Graph fixture cleanup failed: HTTP ${response.status()}`);
  const deleted = await response.json();
  if (!deleted.success || deleted.deleted !== resources.workspaceId)
    throw new Error('Graph fixture workspace cleanup was not confirmed');
}

export async function verifyGraphEntry(workflow) {
  const {page} = workflow;
  const returnUrl = page.url();
  // Provision through the same administrator account used by the role fixture. The
  // browser keeps its original USER/ARCHITECT/ADMIN identity for all reads and actions.
  const api = await request.newContext({baseURL:new URL(page.url()).origin,extraHTTPHeaders:{Authorization:basicAuthorization(
    process.env.TAXONOMY_UI_ADMIN_USERNAME || 'admin', process.env.TAXONOMY_UI_ADMIN_PASSWORD || ROLE_ACCOUNTS.ADMIN.password)}});
  let fixtureResources;
  try {
    await usingComparisonBranch(async branch => {
      const fixture = await seedSavedGraph(api,branch, resources => { fixtureResources = resources; });
      try {
        const selected = new URL(page.url());
        selected.search = 'architectureSource=working&' + fixture.scope + '&commit=' + fixture.commit + '&lang=en';
        selected.hash = 'architecture';
        await page.goto(selected.href);
        await page.locator('#architectureWorkingSource').waitFor({state:'visible'});
        await page.waitForFunction(commit => document.getElementById('applicationContextSummaryStand').textContent.includes(commit.slice(0,12)), fixture.commit);
        await verifySelectedGraphEntry(workflow,fixture);
      } finally {
        // Leave the temporary branch even when browser assertions fail.
        await page.goto(returnUrl);
        await page.waitForFunction(() => Boolean(window.TaxonomyRoleSurface?.getContext()));
      }
    }, async () => {
      if (fixtureResources) await removeSavedGraph(api,fixtureResources);
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
