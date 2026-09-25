import { navigateToPage } from './ui-role-fixtures.mjs';

function workingStateExpression() {
  return () => {
    const state = window.TaxonomyState || {};
    const snapshot = {
      currentScores: state.currentScores,
      currentRawScores: state.currentRawScores,
      currentScoreDetails: state.currentScoreDetails,
      currentReasons: state.currentReasons,
      currentDiscrepancies: state.currentDiscrepancies,
      currentProductCoverageGaps: state.currentProductCoverageGaps,
      currentArchView: state.currentArchView,
      storedBusinessText: state.storedBusinessText,
      lastAnalyzedText: state.lastAnalyzedText,
      lastAnalysisProvider: state.lastAnalysisProvider,
      lastAnalysisStatus: state.lastAnalysisStatus
    };
    return JSON.stringify(snapshot);
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

  // Use the real application state object so save/reset cannot accidentally pass
  // by mutating a separate fixture. Keep enough analysis evidence to detect the
  // user-visible "analysis disappeared" regression.
  const originalState = await page.evaluate(workingStateExpression());
  await page.evaluate(() => {
    const state = window.TaxonomyState;
    state.currentScores = { QA_PREF_SENTINEL: 77 };
    state.currentRawScores = { QA_PREF_SENTINEL: 77 };
    state.currentScoreDetails = {
      QA_PREF_SENTINEL: {
        nodeCode: 'QA_PREF_SENTINEL',
        kind: 'HIERARCHICAL_RELEVANCE',
        rawScore: 77,
        effectiveRelevance: 77,
        parentCode: null,
        parentScore: null
      }
    };
    state.currentReasons = { QA_PREF_SENTINEL: 'QA preference preservation sentinel' };
    state.currentDiscrepancies = [{ message: 'QA preference preservation sentinel' }];
    state.currentProductCoverageGaps = [];
    state.currentArchView = {
      viewTitle: 'QA preference preservation sentinel',
      includedElements: [{
        nodeCode: 'QA_PREF_SENTINEL',
        title: 'QA preference preservation sentinel',
        taxonomySheet: 'QA',
        relevance: 0.77,
        anchor: true,
        taxonomyDepth: 0
      }],
      includedRelationships: []
    };
    state.storedBusinessText = 'QA preference preservation sentinel';
    state.lastAnalyzedText = 'QA preference preservation sentinel';
    state.lastAnalysisProvider = 'QA';
    state.lastAnalysisStatus = 'SUCCESS';
  });
  const sentinelState = await page.evaluate(workingStateExpression());

  const field = page.locator('#pref-max-arch-nodes');
  const originalLimit = Number(await field.inputValue());
  assert(Number.isFinite(originalLimit), 'Architecture-node preference is not numeric');
  const changedLimit = originalLimit >= 1000 ? originalLimit - 1 : originalLimit + 1;

  try {
    await saveArchitectureLimit(page, changedLimit);
    const afterSave = await page.evaluate(workingStateExpression());
    assert(afterSave === sentinelState,
      'Saving Preferences changed or cleared the active analysis/architecture state');

    await saveArchitectureLimit(page, originalLimit);
    const afterRestore = await page.evaluate(workingStateExpression());
    assert(afterRestore === sentinelState,
      'Restoring a Preference changed or cleared the active analysis/architecture state');

    await axeState('preferences-state-preserved', '#tab-preferences');
    await saveState('preferences-state-preserved', '#tab-preferences');
    passed('preferences save preserves active analysis and architecture state');
  } finally {
    // Leave the browser state as it was before this acceptance scenario. The
    // preference itself is restored above before this assignment.
    await page.evaluate(serialized => {
      const previous = JSON.parse(serialized);
      Object.assign(window.TaxonomyState, previous);
    }, originalState);

    // Return through the public navigation path so this scenario also proves a
    // normal tab transition still works after direct-link loading and writes.
    await navigateToPage(page, 'analyze');
  }
}
