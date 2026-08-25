const DEFAULT_TIMEOUT_MS = 90_000;

async function waitForAnalysisSessionReady(page, timeout) {
  await page.waitForFunction(() => {
    const session = window.TaxonomyAnalysisSession;
    const state = session?.state?.();
    return Boolean(session
      && window.TaxonomyAnalysisSessionApi
      && window.TaxonomyBrowse
      && Array.isArray(window.TaxonomyState?.taxonomyData)
      && window.TaxonomyState.taxonomyData.length > 0
      && state?.restoring === false);
  }, null, { timeout });
}

async function authoritativeWorkspaceId(page) {
  return page.evaluate(() => {
    const session = window.TaxonomyAnalysisSession;
    const state = session?.state?.();
    const conflictMessage = document.querySelector(
      '#statusArea[data-analysis-session-message="conflict"]');
    if (state?.conflict === true || conflictMessage) {
      throw new Error(
        'Role-state isolation cannot mutate while a draft conflict is active');
    }
    if (!state?.workspaceId) {
      throw new Error(
        'Role-state isolation cannot verify cleanup without an authoritative workspace ID');
    }
    return state.workspaceId;
  });
}

/**
 * Give every role/state browser profile a deterministic ad-hoc analysis baseline.
 *
 * The role matrix deliberately reuses one application per role. Resumable drafts
 * are therefore real shared server state and a later browser profile can restore
 * the previous profile's text, scores and selected visualization. That behaviour
 * is correct for the product, but it must not leak between otherwise independent
 * acceptance scenarios.
 *
 * The server's optimistic draft revision is authoritative. A fresh browser
 * runtime first loads that revision, checks that restoration ended conflict-free,
 * deletes the draft through the public session API and then performs an uncached
 * read through the public draft endpoint to prove that no remote state remains.
 */
export async function isolateRoleStateScenario(page, timeout = DEFAULT_TIMEOUT_MS) {
  await waitForAnalysisSessionReady(page, timeout);

  await page.evaluate(async () => {
    await window.TaxonomyAnalysisSession.reload();
  });
  await waitForAnalysisSessionReady(page, timeout);
  const workspaceId = await authoritativeWorkspaceId(page);

  await page.evaluate(async (expectedWorkspaceId) => {
    const session = window.TaxonomyAnalysisSession;
    // Re-check inside the same browser task that performs the mutation. A second
    // tab may change the active workspace or create a conflict after the first
    // readiness check; invalidate() would otherwise clear that new conflict.
    const stateBeforeMutation = session.state?.();
    const conflictBeforeMutation = document.querySelector(
      '#statusArea[data-analysis-session-message="conflict"]');
    if (stateBeforeMutation?.conflict === true || conflictBeforeMutation) {
      throw new Error(
        'Role-state isolation cannot mutate while a draft conflict is active');
    }
    if (stateBeforeMutation?.workspaceId !== expectedWorkspaceId) {
      throw new Error(
        'Role-state isolation workspace changed before authoritative cleanup');
    }

    session.invalidate({
      keepText: false,
      silent: true,
      reason: 'role-state-acceptance-isolation'
    });
    await session.saveNow();

    const state = session.state?.();
    const conflictMessage = document.querySelector(
      '#statusArea[data-analysis-session-message="conflict"]');
    if (state?.conflict === true || conflictMessage) {
      throw new Error(
        'Role-state isolation could not delete the authoritative draft revision');
    }
    if (state?.workspaceId !== expectedWorkspaceId) {
      throw new Error(
        'Role-state isolation workspace changed during authoritative cleanup');
    }

    const response = await window.TaxonomyAnalysisSessionApi.request(
      '/api/analysis-drafts/' + encodeURIComponent(expectedWorkspaceId),
      {
        method: 'GET',
        headers: { Accept: 'application/json' },
        cache: 'no-store'
      }
    );
    if (response.status !== 204) {
      throw new Error(
        `Role-state isolation verification returned HTTP ${response.status}, expected 204`);
    }

    const input = document.getElementById('businessText');
    if (input) {
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    if (window.TaxonomyState.currentView !== 'list') {
      window.TaxonomyBrowse.switchView('list');
    }
  }, workspaceId);

  await page.waitForFunction(() => {
    const tree = document.getElementById('taxonomyTree');
    const input = document.getElementById('businessText');
    const state = window.TaxonomyAnalysisSession?.state?.();
    const conflictMessage = document.querySelector(
      '#statusArea[data-analysis-session-message="conflict"]');
    return Boolean(tree
      && tree.dataset.viewRendered === 'list'
      && tree.querySelector('[role="treeitem"]')
      && input?.value === ''
      && !input.classList.contains('stale-results')
      && document.querySelector('#taskStageDescribe[data-state="current"]')
      && state?.restoring === false
      && state?.conflict !== true
      && !conflictMessage);
  }, null, { timeout });
}
