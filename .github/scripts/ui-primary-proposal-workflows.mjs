import { csrfJson, navigateArchitectureSubtab } from './ui-role-fixtures.mjs';

const PROJECTION_ENDPOINT = '/api/architecture/relations/projection';

function responseSummary(response) {
  return `${response.status} ${response.text || JSON.stringify(response.json) || ''}`.trim();
}

async function waitForTaxonomyReady(page, assert) {
  let response = null;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    response = await csrfJson(page, '/api/taxonomy', { method: 'GET' });
    if (response.status === 200) return;
    await page.waitForTimeout(250);
  }
  assert(false, `Taxonomy did not become ready: ${responseSummary(response)}`);
}

async function csrfJsonWithHeaders(page, endpoint, {
  method = 'POST',
  headers: additionalHeaders = {},
  body = undefined
} = {}) {
  return page.evaluate(async ({ endpoint, method, additionalHeaders, body }) => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content
      || 'X-CSRF-TOKEN';
    const headers = { 'Content-Type': 'application/json', ...additionalHeaders };
    if (token) headers[header] = token;
    const response = await fetch(endpoint, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* retain text evidence */ }
    return { status: response.status, text, json };
  }, { endpoint, method, additionalHeaders, body });
}

async function waitForReadyProjection(page, assert) {
  let readiness = null;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    readiness = await csrfJson(page, `${PROJECTION_ENDPOINT}/readiness`, {
      method: 'GET'
    });
    if (readiness.status === 200 && readiness.json?.readinessState === 'READY') {
      return readiness;
    }
    await page.waitForTimeout(250);
  }
  assert(false, `Relation projection did not become ready: ${responseSummary(readiness)}`);
  return readiness;
}

async function ensureWorkspaceProjection(page, assert) {
  await waitForTaxonomyReady(page, assert);

  const provision = await csrfJson(page, '/api/workspace/provision');
  assert(
    provision.status === 200 && provision.json?.status === 'READY',
    `Workspace provisioning failed: ${responseSummary(provision)}`);

  const readiness = await csrfJson(page, `${PROJECTION_ENDPOINT}/readiness`, {
    method: 'GET'
  });
  assert(readiness.status === 200,
    `Unable to inspect relation projection: ${responseSummary(readiness)}`);

  if (readiness.json?.readinessState !== 'READY') {
    const currentHead = readiness.json?.currentHeadCommit;
    assert(currentHead,
      `Provisioned workspace has no authoritative branch head: ${responseSummary(readiness)}`);
    const rebuild = await csrfJsonWithHeaders(
      page,
      `${PROJECTION_ENDPOINT}/rebuild`,
      { headers: { 'If-Match': `"${currentHead}"` } });
    assert(rebuild.status === 200,
      `Relation projection rebuild failed: ${responseSummary(rebuild)}`);
  }

  await waitForReadyProjection(page, assert);

  // Reload so a provisioning modal opened during login observes READY and cannot
  // obscure the controls exercised by the remainder of the workflow.
  await page.reload({ waitUntil: 'networkidle' });
  await page.locator('#mainContent').waitFor({ state: 'visible', timeout: 60_000 });
  await page.evaluate(() => window.TaxonomyI18n?.ready?.());
  await page.waitForFunction(
    () => Boolean(window.TaxonomyRoleSurface?.ready),
    null,
    { timeout: 20_000 });
  await page.evaluate(() => window.TaxonomyRoleSurface.ready);
}

async function createPendingProposals(page, assert) {
  await ensureWorkspaceProjection(page, assert);

  const candidates = [
    ['CP', 'BR', 'SUPPORTS'], ['BP', 'UA', 'DEPENDS_ON'],
    ['CI', 'CO', 'COMMUNICATES_WITH'], ['CR', 'IP', 'PRODUCES'],
    ['UA', 'CO', 'USES'], ['BP', 'CI', 'REALIZES']
  ];
  const ids = [];
  const attempts = [];
  for (const [sourceCode, targetCode, relationType] of candidates) {
    const response = await csrfJson(page, '/api/proposals/from-hypothesis', {
      body: { sourceCode, targetCode, relationType, confidence: 0.73,
        rationale: `Primary workflow ${sourceCode}-${targetCode}-${relationType}` }
    });
    attempts.push(`${sourceCode}-${targetCode}-${relationType}: ${response.status}`);
    if (response.status === 200 && response.json?.id) ids.push(response.json.id);
    if (ids.length >= 4) break;
  }
  assert(ids.length >= 4,
    `Unable to create four independent proposals; created ${ids.length}; ${attempts.join(', ')}`);
  return ids;
}

async function loadPending(page) {
  await navigateArchitectureSubtab(page, 'relations');
  await page.locator('#filterPending').click();
  await page.locator('.proposal-table').waitFor({ state: 'visible', timeout: 20_000 });
}

export async function runProposalWorkflows({ page, evidence }) {
  const { assert, passed, axeState, saveState, waitForText } = evidence;
  const ids = await createPendingProposals(page, assert);
  await loadPending(page);
  await axeState('proposals-pending', '#proposalsPanel');
  await saveState('proposals-pending', '#proposalsPanel');

  await page.getByRole('button', { name: `Accept proposal ${ids[0]}` }).click();
  await page.locator('#undoToast').waitFor({ state: 'visible', timeout: 15_000 });
  await page.locator('#undoBtn').click();
  await waitForText('#statusArea', text => /revert|undo/i.test(text));
  passed('proposal accept and revert');

  await loadPending(page);
  await page.getByRole('button', { name: `Reject proposal ${ids[1]}` }).click();
  await waitForText('#statusArea', text => /reject/i.test(text));
  passed('proposal reject');

  await loadPending(page);
  assert(await page.locator('.proposal-select').count() >= 2,
    'Bulk proposal fixture has fewer than two pending proposals');
  await page.locator('#proposalSelectAll').check();
  await page.locator('#bulkAcceptBtn').click();
  await waitForText('#statusArea', text => /accept/i.test(text));
  await axeState('proposals-bulk-result', '#proposalsPanel');
  await saveState('proposals-bulk-result', '#proposalsPanel');
  passed('proposal bulk action');
}
