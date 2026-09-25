import { navigateToPage } from './ui-role-fixtures.mjs';

function workingStateExpression() {
  return () => {
    const state = window.TaxonomyState || {};
    const input = document.getElementById('businessText');
    const snapshot = {
      businessText: input?.value || '',
      currentScores: state.currentScores,
      currentRawScores: state.currentRawScores,
      currentEffectiveScores: state.currentEffectiveScores,
      currentScoreDetails: state.currentScoreDetails,
      currentProductSuitabilityScores: state.currentProductSuitabilityScores,
      scoreSemanticsVersion: state.scoreSemanticsVersion,
      currentScoreSemanticsWarnings: state.currentScoreSemanticsWarnings,
      currentReasons: state.currentReasons,
      currentDiscrepancies: state.currentDiscrepancies,
      currentProductCoverageGaps: state.currentProductCoverageGaps,
      currentArchView: state.currentArchView,
      storedBusinessText: state.storedBusinessText,
      lastAnalyzedText: state.lastAnalyzedText,
      lastAnalysisProvider: state.lastAnalysisProvider,
      lastAnalysisStatus: state.lastAnalysisStatus,
      lastAnalysisDurationMillis: state.lastAnalysisDurationMillis,
      evaluatedNodes: state.evaluatedNodes instanceof Set
        ? [...state.evaluatedNodes].sort() : [],
      currentView: state.currentView,
      provisionalRelations: window._currentProvisionalRelations || [],
      legacyScores: window._taxonomyCurrentScores
    };
    return JSON.stringify(snapshot);
  };
}

function restoreWorkingStateExpression() {
  return serialized => {
    const previous = JSON.parse(serialized);
    const state = window.TaxonomyState;
    const input = document.getElementById('businessText');
    if (input) input.value = previous.businessText || '';
    state.currentScores = previous.currentScores;
    state.currentRawScores = previous.currentRawScores || {};
    state.currentEffectiveScores = previous.currentEffectiveScores || previous.currentScores || {};
    state.currentScoreDetails = previous.currentScoreDetails || {};
    state.currentProductSuitabilityScores = previous.currentProductSuitabilityScores || {};
    state.scoreSemanticsVersion = previous.scoreSemanticsVersion || 0;
    state.currentScoreSemanticsWarnings = previous.currentScoreSemanticsWarnings || [];
    state.currentReasons = previous.currentReasons || {};
    state.currentDiscrepancies = previous.currentDiscrepancies || [];
    state.currentProductCoverageGaps = previous.currentProductCoverageGaps || [];
    state.currentArchView = previous.currentArchView || null;
    state.storedBusinessText = previous.storedBusinessText ?? null;
    state.lastAnalyzedText = previous.lastAnalyzedText ?? null;
    state.lastAnalysisProvider = previous.lastAnalysisProvider ?? null;
    state.lastAnalysisStatus = previous.lastAnalysisStatus ?? null;
    state.lastAnalysisDurationMillis = previous.lastAnalysisDurationMillis ?? null;
    state.evaluatedNodes = new Set(previous.evaluatedNodes || []);
    state.currentView = previous.currentView || 'list';
    window._currentProvisionalRelations = previous.provisionalRelations || [];
    window._taxonomyCurrentScores = previous.legacyScores ?? previous.currentScores ?? null;
  };
}

async function waitForPreferenceLoad(page) {
  await page.locator('#tab-preferences').waitFor({ state: 'visible', timeout: 20_000 });
  await page.waitForFunction(() => {
    const input = document.getElementById('pref-max-arch-nodes');
    const reset = document.getElementById('prefResetBtn');
    return Boolean(input && input.value && reset && !reset.disabled);
  }, null, { timeout: 20_000 });
}

async function saveArchitectureLimit(page, value) {
  const field = page.locator('#pref-max-arch-nodes');
  await field.fill(String(value));
  const save = page.locator('#prefSaveBtn');
  await save.waitFor({ state: 'visible' });
  await page.waitForFunction(() => !document.getElementById('prefSaveBtn')?.disabled);

  const response = page.waitForResponse(candidate =>
    candidate.url().includes('/api/preferences')
      && candidate.request().method() === 'PUT');
  await save.click();
  const result = await response;
  if (!result.ok()) {
    throw new Error(`Preferences PUT failed with HTTP ${result.status()}: ${await result.text()}`);
  }
  await page.waitForFunction(() => document.getElementById('prefSaveBtn')?.disabled === true);
  await page.waitForFunction(expected =>
    Number(document.getElementById('pref-max-arch-nodes')?.value) === expected,
  value);
}

async function saveArchitectureLimitExpectFailure(page, value) {
  const pattern = '**/api/preferences';
  const failPut = async route => {
    if (route.request().method() !== 'PUT') {
      await route.continue();
      return;
    }
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'QA_PREFERENCES_WRITE_FAILURE' })
    });
  };
  await page.route(pattern, failPut);
  try {
    const field = page.locator('#pref-max-arch-nodes');
    await field.fill(String(value));
    await page.waitForFunction(() => !document.getElementById('prefSaveBtn')?.disabled);
    const response = page.waitForResponse(candidate =>
      candidate.url().includes('/api/preferences')
        && candidate.request().method() === 'PUT');
    await page.locator('#prefSaveBtn').click();
    const result = await response;
    if (result.status() !== 503) {
      throw new Error(`Expected Preferences PUT HTTP 503, got ${result.status()}`);
    }
    await page.waitForFunction(() => {
      const message = document.getElementById('prefStatusMsg');
      return Boolean(message && !message.classList.contains('d-none')
        && /503|QA_PREFERENCES_WRITE_FAILURE/i.test(message.textContent || ''));
    });
  } finally {
    await page.unroute(pattern, failPut);
  }
}

async function saveDraftNow(page) {
  return page.evaluate(async () => {
    const session = window.TaxonomyAnalysisSession;
    if (!session || typeof session.saveNow !== 'function') return false;
    return session.saveNow();
  });
}

async function installRequestGate(page, pattern, method) {
  let releaseRequest;
  let markSeen;
  let released = false;
  const gate = new Promise(resolve => { releaseRequest = resolve; });
  const seen = new Promise(resolve => { markSeen = resolve; });
  const handler = async route => {
    if (route.request().method() !== method) {
      await route.continue();
      return;
    }
    markSeen();
    await gate;
    await route.continue();
  };
  await page.route(pattern, handler);
  return {
    seen,
    release() {
      if (released) return;
      released = true;
      releaseRequest();
    },
    async dispose() {
      if (!released) {
        released = true;
        releaseRequest();
      }
      await page.unroute(pattern, handler);
    }
  };
}

export async function runPreferencesWorkflow({ page, baseUrl, evidence }) {
  const { assert, passed, saveState, axeState } = evidence;

  // Exercise a direct deep link, not only tab navigation. This catches the
  // historical case where the pane became visible before its values were loaded.
  await page.goto(`${baseUrl}/#preferences`, { waitUntil: 'networkidle' });
  await page.locator('#mainContent').waitFor({ state: 'visible', timeout: 60_000 });
  await page.evaluate(() => window.TaxonomyI18n?.ready?.());
  await waitForPreferenceLoad(page);
  assert((await page.locator('#pref-max-arch-nodes').inputValue()).trim().length > 0,
    'Direct #preferences navigation did not load runtime preference values');
  passed('preferences direct-link load');

  // Use the real application state and real draft lifecycle. This is not a
  // detached fixture: the sentinel is persisted through the same optimistic
  // working-draft endpoint used by an ordinary completed analysis.
  await page.waitForFunction(() => window.TaxonomyAnalysisSession?.state?.().ready === true,
    null, { timeout: 30_000 });
  const originalState = await page.evaluate(workingStateExpression());
  await page.evaluate(() => {
    const text = 'QA preference preservation sentinel';
    const state = window.TaxonomyState;
    const input = document.getElementById('businessText');
    if (input) input.value = text;
    state.currentScores = { BP: 77 };
    state.currentRawScores = { BP: 77 };
    state.currentEffectiveScores = { BP: 77 };
    state.currentScoreDetails = {
      BP: {
        nodeCode: 'BP',
        kind: 'HIERARCHICAL_RELEVANCE',
        rawScore: 77,
        effectiveRelevance: 77,
        parentCode: null,
        parentScore: null
      }
    };
    state.currentProductSuitabilityScores = {};
    state.scoreSemanticsVersion = 1;
    state.currentScoreSemanticsWarnings = [];
    state.currentReasons = { BP: text };
    state.currentDiscrepancies = [{ message: text }];
    state.currentProductCoverageGaps = [];
    state.currentArchView = {
      viewTitle: text,
      includedElements: [{
        nodeCode: 'BP',
        title: 'Business Processes',
        taxonomySheet: 'BP',
        relevance: 0.77,
        anchor: true,
        taxonomyDepth: 0
      }],
      includedRelationships: []
    };
    state.storedBusinessText = text;
    state.lastAnalyzedText = text;
    state.lastAnalysisProvider = 'MOCK';
    state.lastAnalysisStatus = 'SUCCESS';
    state.lastAnalysisDurationMillis = 1234;
    state.evaluatedNodes = new Set(['BP']);
    state.currentView = 'summary';
    window._currentProvisionalRelations = [];
    window._taxonomyCurrentScores = state.currentScores;
  });
  assert(await saveDraftNow(page) === true,
    'Unable to persist the preference-preservation analysis draft');
  let sentinelState = await page.evaluate(workingStateExpression());

  const field = page.locator('#pref-max-arch-nodes');
  const originalLimit = Number(await field.inputValue());
  assert(Number.isFinite(originalLimit), 'Architecture-node preference is not numeric');
  const changedLimit = originalLimit >= 1000 ? originalLimit - 1 : originalLimit + 1;
  const failedLimit = originalLimit <= 998 ? originalLimit + 2 : originalLimit - 2;
  let preferenceRestored = false;

  try {
    // Hold the real draft PUT open while Preferences are persisted. This catches
    // races between the autosave lifecycle and the independent preferences
    // repository instead of exercising only a quiescent page.
    const autosaveGate = await installRequestGate(
      page, '**/api/analysis-drafts/**', 'PUT');
    try {
      await page.evaluate(() => {
        window.TaxonomyState.currentReasons = {
          BP: 'QA preference preservation sentinel — autosave pending'
        };
        window.__taxonomyQaPendingDraftSave = window.TaxonomyAnalysisSession.saveNow();
      });
      await autosaveGate.seen;
      sentinelState = await page.evaluate(workingStateExpression());

      await saveArchitectureLimit(page, changedLimit);
      const afterSave = await page.evaluate(workingStateExpression());
      assert(afterSave === sentinelState,
        'Saving Preferences during draft autosave changed or cleared the working state');

      autosaveGate.release();
      assert(await page.evaluate(async () => window.__taxonomyQaPendingDraftSave) === true,
        'The delayed analysis draft autosave did not complete successfully');
    } finally {
      await autosaveGate.dispose();
    }

    // Reproduce the reported user path, not only the state while Preferences is
    // still visible. Returning to both architecture and analysis must retain the
    // same working result; page activation must not replace it with an empty or
    // older draft.
    await navigateToPage(page, 'architecture');
    await page.locator('#tab-architecture').waitFor({ state: 'visible', timeout: 20_000 });
    const afterArchitectureReturn = await page.evaluate(workingStateExpression());
    assert(afterArchitectureReturn === sentinelState,
      'Returning to Architecture after saving Preferences cleared or replaced the working state');

    await navigateToPage(page, 'analyze');
    await page.locator('#tab-analyze').waitFor({ state: 'visible', timeout: 20_000 });
    const afterAnalyzeReturn = await page.evaluate(workingStateExpression());
    assert(afterAnalyzeReturn === sentinelState,
      'Returning to Analyze after saving Preferences cleared or replaced the working state');

    await navigateToPage(page, 'preferences');
    await waitForPreferenceLoad(page);

    // A failed persistence attempt must be visibly failed and must not leak the
    // attempted runtime value or damage the current analysis state.
    await saveArchitectureLimitExpectFailure(page, failedLimit);
    const afterFailedSave = await page.evaluate(workingStateExpression());
    assert(afterFailedSave === sentinelState,
      'Failed Preferences PUT changed or cleared the active analysis/architecture state');
    await navigateToPage(page, 'architecture');
    const afterFailedSaveReturn = await page.evaluate(workingStateExpression());
    assert(afterFailedSaveReturn === sentinelState,
      'Returning to Architecture after a failed Preferences PUT changed the working state');

    await navigateToPage(page, 'preferences');
    await waitForPreferenceLoad(page);
    assert(Number(await page.locator('#pref-max-arch-nodes').inputValue()) === changedLimit,
      'Failed Preferences PUT leaked its attempted architecture-node limit into runtime state');

    await saveArchitectureLimit(page, originalLimit);
    preferenceRestored = true;
    const afterRestore = await page.evaluate(workingStateExpression());
    assert(afterRestore === sentinelState,
      'Restoring a Preference changed or cleared the active analysis/architecture state');

    // Hold the initial draft GET open after reload. Preferences must remain
    // independently usable while restoration is pending, and releasing the GET
    // must hydrate the exact current draft without an obsolete restore choice.
    const restoreGate = await installRequestGate(
      page, '**/api/analysis-drafts/**', 'GET');
    try {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await restoreGate.seen;
      await page.evaluate(() => window.TaxonomyI18n?.ready?.());
      await page.waitForFunction(() =>
        window.TaxonomyAnalysisSession?.state?.().restoring === true,
      null, { timeout: 30_000 });
      await waitForPreferenceLoad(page);

      await saveArchitectureLimit(page, changedLimit);
      preferenceRestored = false;

      restoreGate.release();
      await page.waitForFunction(() => {
        const session = window.TaxonomyAnalysisSession;
        const state = window.TaxonomyState;
        return session?.state?.().ready === true
          && state?.lastAnalyzedText === 'QA preference preservation sentinel'
          && state?.lastAnalysisStatus === 'SUCCESS'
          && state?.lastAnalysisProvider === 'MOCK'
          && Boolean(state?.currentArchView);
      }, null, { timeout: 30_000 });
    } finally {
      await restoreGate.dispose();
    }

    const afterReload = await page.evaluate(workingStateExpression());
    assert(afterReload === sentinelState,
      'Pending initial restore produced an older, empty, or incomplete working draft');
    assert(await page.locator('[data-analysis-session-action="load-saved"]').count() === 0,
      'Reload offered an obsolete saved draft instead of restoring the current working draft');

    await navigateToPage(page, 'architecture');
    const afterReloadArchitecture = await page.evaluate(workingStateExpression());
    assert(afterReloadArchitecture === sentinelState,
      'Architecture navigation after pending restore replaced the restored working draft');

    await navigateToPage(page, 'preferences');
    await waitForPreferenceLoad(page);
    await saveArchitectureLimit(page, originalLimit);
    preferenceRestored = true;

    await axeState('preferences-state-preserved', '#tab-preferences');
    await saveState('preferences-state-preserved', '#tab-preferences');
    passed('preferences remain isolated across autosave, failure, return and pending restore');
  } finally {
    if (!preferenceRestored) {
      try {
        await navigateToPage(page, 'preferences');
        await waitForPreferenceLoad(page);
        const currentLimit = Number(await page.locator('#pref-max-arch-nodes').inputValue());
        if (currentLimit !== originalLimit) await saveArchitectureLimit(page, originalLimit);
      } catch (error) {
        console.warn('Could not restore QA architecture-node preference', error);
      }
    }

    // Restore the exact browser working state that existed before this scenario
    // and persist it through the same draft API so the QA run leaves no sentinel.
    await page.evaluate(restoreWorkingStateExpression(), originalState);
    const restoredDraft = await saveDraftNow(page);
    if (!restoredDraft) {
      console.warn('Could not persist the pre-QA analysis draft during cleanup');
    }

    await navigateToPage(page, 'analyze');
  }
}
