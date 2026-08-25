const DEFAULT_TIMEOUT_MS = 90_000;

async function waitForAnalysisSessionReady(page, timeout) {
  await page.waitForFunction(() => {
    const session = window.TaxonomyAnalysisSession;
    const state = session?.state?.();
    return Boolean(session
      && window.TaxonomyBrowse
      && Array.isArray(window.TaxonomyState?.taxonomyData)
      && window.TaxonomyState.taxonomyData.length > 0
      && state?.restoring === false
      && state?.conflict === false);
  }, null, { timeout });
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
 * <p>The server's optimistic draft revision is authoritative. A fresh browser
 * runtime starts without that revision, so it must first load the current remote
 * draft before attempting to delete it. Otherwise the isolation itself creates a
 * deterministic HTTP 409 by submitting {@code expectedVersion=null} for an
 * existing draft.</p>
 */
export async function isolateRoleStateScenario(page, timeout = DEFAULT_TIMEOUT_MS) {
  await waitForAnalysisSessionReady(page, timeout);

  // Synchronize the browser runtime with the exact server-side draft revision.
  // reload() is deliberately awaited before invalidation; treating a conflict as
  // an allowed cleanup outcome would hide a real optimistic-concurrency defect.
  await page.evaluate(async () => {
    const session = window.TaxonomyAnalysisSession;
    await session.reload();
  });
  await waitForAnalysisSessionReady(page, timeout);

  await page.evaluate(async () => {
    const session = window.TaxonomyAnalysisSession;
    session.invalidate({
      keepText: false,
      silent: true,
      reason: 'role-state-acceptance-isolation'
    });
    await session.saveNow();

    const state = session.state?.();
    if (state?.conflict) {
      throw new Error(
        'Role-state isolation could not delete the authoritative draft revision');
    }

    const input = document.getElementById('businessText');
    if (input) {
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    if (window.TaxonomyState.currentView !== 'list') {
      window.TaxonomyBrowse.switchView('list');
    }
  });

  await page.waitForFunction(() => {
    const tree = document.getElementById('taxonomyTree');
    const input = document.getElementById('businessText');
    const state = window.TaxonomyAnalysisSession?.state?.();
    return Boolean(tree
      && tree.dataset.viewRendered === 'list'
      && tree.querySelector('[role="treeitem"]')
      && input?.value === ''
      && !input.classList.contains('stale-results')
      && document.querySelector('#taskStageDescribe[data-state="current"]')
      && state?.restoring === false
      && state?.conflict === false);
  }, null, { timeout });
}
