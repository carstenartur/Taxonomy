const DEFAULT_TIMEOUT_MS = 90_000;

/**
 * Give every role/state browser profile a deterministic ad-hoc analysis baseline.
 *
 * The role matrix deliberately reuses one application per role. Resumable drafts
 * are therefore real shared server state and a later browser profile can restore
 * the previous profile's text, scores and selected visualization. That behaviour
 * is correct for the product, but it must not leak between otherwise independent
 * acceptance scenarios.
 *
 * Synchronize the server draft first so clearing it uses the authoritative
 * optimistic-lock version. Otherwise a later profile can start with version null,
 * issue a PUT against an existing draft and manufacture a 409 conflict itself.
 */
export async function isolateRoleStateScenario(page, timeout = DEFAULT_TIMEOUT_MS) {
  await page.waitForFunction(() => {
    const session = window.TaxonomyAnalysisSession;
    const state = session?.state?.();
    return Boolean(session
      && window.TaxonomyBrowse
      && Array.isArray(window.TaxonomyState?.taxonomyData)
      && window.TaxonomyState.taxonomyData.length > 0
      && state?.restoring === false);
  }, null, { timeout });

  await page.evaluate(async () => {
    await window.TaxonomyAnalysisSession.reload();
  });
  await page.waitForFunction(() =>
    window.TaxonomyAnalysisSession?.state?.().restoring === false,
  null, { timeout });

  await page.evaluate(async () => {
    const session = window.TaxonomyAnalysisSession;
    session.invalidate({
      keepText: false,
      silent: true,
      reason: 'role-state-acceptance-isolation'
    });
    await session.saveNow();

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
      && state?.restoring === false);
  }, null, { timeout });
}
