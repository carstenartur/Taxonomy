import { navigateArchitectureSubtab } from './ui-role-fixtures.mjs';

const MANUAL_RELATION = {
  sourceCode: 'CP',
  targetCode: 'IP',
  relationType: 'CONSUMES'
};

export async function runRelationWorkflows({ page, evidence }) {
  const { passed, axeState, saveState } = evidence;
  await navigateArchitectureSubtab(page, 'relations');
  const panel = page.locator('#relationsBrowser');
  if (!(await panel.getAttribute('open'))) await panel.locator('summary').click();
  await page.locator('#relationsTableContainer').waitFor({ state: 'visible' });

  async function waitForRelation(present) {
    await page.waitForFunction(({ relation, present }) => {
      const matches = Array.from(document.querySelectorAll('#relationsTableContainer tbody tr'))
        .filter(row => {
          const cells = row.querySelectorAll('td');
          return cells.length >= 3
            && cells[0].textContent.trim() === relation.sourceCode
            && cells[1].textContent.trim() === relation.targetCode
            && cells[2].textContent.trim() === relation.relationType;
        });
      return present ? matches.length === 1 : matches.length === 0;
    }, { relation: MANUAL_RELATION, present }, { timeout: 15_000 });
  }

  async function exactRelationRow() {
    const rows = page.locator('#relationsTableContainer tbody tr');
    const matching = rows.filter({
      has: page.locator('td:nth-child(1)', { hasText: /^CP$/ })
    }).filter({
      has: page.locator('td:nth-child(2)', { hasText: /^IP$/ })
    }).filter({
      has: page.locator('td:nth-child(3)', { hasText: /^CONSUMES$/ })
    });
    if (await matching.count() !== 1) {
      throw new Error(`Expected exactly one CP -> IP CONSUMES row, found ${await matching.count()}`);
    }
    return matching.first();
  }

  async function assertBackendRelationCount(expected) {
    const count = await page.evaluate(async relation => {
      const response = await fetch('/api/relations', { headers: { Accept: 'application/json' } });
      if (!response.ok) throw new Error(`Relation list returned ${response.status}`);
      const relations = await response.json();
      return relations.filter(item => item.sourceCode === relation.sourceCode
        && item.targetCode === relation.targetCode
        && item.relationType === relation.relationType).length;
    }, MANUAL_RELATION);
    if (count !== expected) {
      throw new Error(`Expected ${expected} persisted CP -> IP CONSUMES relation(s), found ${count}`);
    }
  }

  async function openCreate() {
    await page.locator('#createRelationBtn').click();
    await page.locator('#createRelationModal').waitFor({ state: 'visible', timeout: 10_000 });
    await page.locator('#newRelSource').fill(MANUAL_RELATION.sourceCode);
    await page.locator('#newRelTarget').fill(MANUAL_RELATION.targetCode);
    await page.locator('#newRelType').selectOption(MANUAL_RELATION.relationType);
    await page.locator('#newRelDescription').fill('Primary workflow relation');
  }

  await openCreate();
  await page.locator('#createRelationSubmit').click();
  await page.locator('#createRelationModal').waitFor({ state: 'hidden', timeout: 15_000 });
  await waitForRelation(true);
  await assertBackendRelationCount(1);
  passed('relation create');

  await openCreate();
  await page.locator('#createRelationSubmit').click();
  await page.locator('#createRelationError').waitFor({ state: 'visible', timeout: 15_000 });
  await assertBackendRelationCount(1);
  await axeState('relation-duplicate-error', '#createRelationModal');
  await saveState('relation-duplicate-error', '#createRelationModal');
  const modal = page.locator('#createRelationModal');
  await modal.locator('[data-bs-dismiss="modal"]').first().click();
  await modal.waitFor({ state: 'hidden' });
  passed('relation duplicate conflict feedback');

  const row = await exactRelationRow();
  page.once('dialog', dialog => dialog.accept());
  await row.locator('.relation-delete-btn').click();
  await waitForRelation(false);
  await assertBackendRelationCount(0);
  passed('relation delete confirmation');
}
