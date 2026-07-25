import { csrfJson, navigateArchitectureSubtab } from './ui-role-fixtures.mjs';

async function createPendingProposals(page, assert) {
  const candidates = [
    ['CP', 'BR', 'SUPPORTS'], ['BP', 'UA', 'DEPENDS_ON'],
    ['CI', 'CO', 'COMMUNICATES_WITH'], ['CR', 'IP', 'PRODUCES'],
    ['UA', 'CO', 'USES'], ['BP', 'CI', 'REALIZES']
  ];
  const ids = [];
  for (const [sourceCode, targetCode, relationType] of candidates) {
    const response = await csrfJson(page, '/api/proposals/from-hypothesis', {
      body: { sourceCode, targetCode, relationType, confidence: 0.73,
        rationale: `Primary workflow ${sourceCode}-${targetCode}-${relationType}` }
    });
    if (response.status === 200 && response.json?.id) ids.push(response.json.id);
    if (ids.length >= 4) break;
  }
  assert(ids.length >= 4, `Unable to create four independent proposals; created ${ids.length}`);
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
