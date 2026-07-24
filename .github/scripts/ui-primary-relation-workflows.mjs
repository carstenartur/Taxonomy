import { navigateArchitectureSubtab } from './ui-role-fixtures.mjs';

export async function runRelationWorkflows({ page, evidence }) {
  const { passed, axeState, saveState, waitForText } = evidence;
  await navigateArchitectureSubtab(page, 'relations');
  const panel = page.locator('#relationsBrowser');
  if (!(await panel.getAttribute('open'))) await panel.locator('summary').click();
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

  const row = page.locator('#relationsTableContainer tr', { hasText: 'CP' })
    .filter({ hasText: 'IP' }).first();
  page.once('dialog', dialog => dialog.accept());
  await row.locator('.relation-delete-btn').click();
  await page.waitForFunction(() => !Array.from(
    document.querySelectorAll('#relationsTableContainer tr'))
    .some(row => row.textContent.includes('CP') && row.textContent.includes('IP')),
  null, { timeout: 15_000 });
  passed('relation delete confirmation');
}
