import { csrfJson, navigateToPage } from './ui-role-fixtures.mjs';

export async function verifyUserMutationDenied({ page, evidence }) {
  const { assert, passed } = evidence;
  const denied = await csrfJson(page, '/api/relations', {
    body: { sourceCode: 'CP', targetCode: 'IP', relationType: 'CONSUMES',
      description: 'Denied USER relation', provenance: 'MANUAL' }
  });
  assert(denied.status === 403, `USER relation mutation returned ${denied.status}`);
  passed('USER relation mutation forbidden');
}

export async function runWorkspaceSyncWorkflows({ page, evidence }) {
  const { passed, axeState, saveState, waitForText } = evidence;
  await navigateToPage(page, 'versions');
  await page.locator('#versionsSubTabs [data-versions-tab="sync"]').click();
  await page.locator('#versions-sync').waitFor({ state: 'visible', timeout: 10_000 });

  await page.route('**/api/workspace/sync-state', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ syncStatus: 'DIVERGED', unpublishedCommitCount: 2,
      lastSyncedCommitId: 'abcdef1234567890' })
  }), { times: 1 });
  await page.evaluate(() => window.TaxonomyWorkspaceSync.refresh());
  await page.locator('#syncStatePanel button').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('workspace-conflict', '#versions-sync');
  await saveState('workspace-conflict', '#versions-sync');
  passed('workspace diverged conflict state');

  await page.route('**/api/workspace/sync-from-shared?*', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ error: 'REMOTE_UNAVAILABLE', message: 'Team repository unavailable' })
  }), { times: 1 });
  await page.evaluate(() => window.TaxonomyWorkspaceSync.syncFromShared());
  await waitForText('#operationToastBody', text => /unavailable/i.test(text));
  await axeState('workspace-sync-error', '#operationToast');
  await saveState('workspace-sync-error', '#operationToast');
  passed('workspace remote sync error');

  await page.route('**/api/workspace/publish?*', route => route.fulfill({
    status: 200, contentType: 'application/json', body: '{}'
  }), { times: 1 });
  await page.evaluate(() => window.TaxonomyWorkspaceSync.publish());
  await page.locator('#operationToast').waitFor({ state: 'visible', timeout: 10_000 });
  await axeState('workspace-publish-success', '#operationToast');
  passed('workspace publish success');
}

export async function runPasswordWorkflows({ page, account, baseUrl, evidence }) {
  const { passed, axeState, saveState } = evidence;
  await page.goto(`${baseUrl}/change-password`, { waitUntil: 'networkidle' });
  await page.locator('#currentPassword').fill(account.password);
  await page.locator('#newPassword').fill('qa-primary-new-password-2026');
  await page.locator('#confirmPassword').fill('mismatch-password-2026');
  await page.locator('#changePasswordForm button[type="submit"]').click();
  await page.locator('[role="alert"]').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('password-confirmation-error', 'body');
  await saveState('password-confirmation-error', 'body');
  passed('password validation error');

  await page.locator('#currentPassword').fill(account.password);
  await page.locator('#newPassword').fill('qa-primary-new-password-2026');
  await page.locator('#confirmPassword').fill('qa-primary-new-password-2026');
  await Promise.all([
    page.waitForURL(url => url.pathname === '/'
      && url.searchParams.get('passwordChanged') === 'true', { timeout: 20_000 }),
    page.locator('#changePasswordForm button[type="submit"]').click()
  ]);
  passed('password change success');
}
