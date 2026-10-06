import { csrfJson, navigateArchitectureSubtab, navigateToPage } from './ui-role-fixtures.mjs';

async function provisionAnalysisWorkspace(page, workspaceId, assert) {
  // A new explicit workspace cannot load its draft until its repository is
  // READY. Provision that fixture before selecting/reloading the browser tab.
  const { headers, path } = await page.evaluate(id => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const headers = { 'X-Taxonomy-Workspace-Id': id };
    if (token) headers[header] = token;
    const path = '/api/workspace/provision';
    return { headers, path: window.TaxonomyI18n?.resolveUrl?.(path) || path };
  }, workspaceId);
  const response = await page.request.post(new URL(path, page.url()).href, {headers});
  const status = response.status();
  const body = status === 200 ? await response.json() : null;
  assert(status === 200 && body?.status === 'READY',
    `Preferences fixture workspace provisioning failed: HTTP ${status}, status ${body?.status || 'missing'}`);
}

async function selectAnalysisWorkspace(page, workspaceId, assert) {
  // The production fetch wrapper reloads automatically on /switch. Use the
  // authenticated request context so it cannot race our explicit reload below.
  const { headers, path } = await page.evaluate(id => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const headers = { 'X-Taxonomy-Workspace-Id': window.TaxonomyAnalysisSession.state().workspaceId };
    if (token) headers[header] = token;
    const path = `/api/workspace/${encodeURIComponent(id)}/switch`;
    return { headers, path: window.TaxonomyI18n?.resolveUrl?.(path) || path };
  }, workspaceId);
  const endpoint = new URL(path, page.url()).href;
  const switched = await page.request.post(endpoint, {headers});
  assert(switched.status() === 200, `Unable to select QA analysis workspace: HTTP ${switched.status()}`);
  await page.evaluate(id => {
    const context = window.__TaxonomyAnalysisSessionContext;
    context.rememberWorkspaceId(id);
  }, workspaceId);
  // Background status reads do not define analysis readiness. Wait for the
  // document, then require the visible UI and exact ready workspace below.
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.locator('#mainContent').waitFor({ state: 'visible', timeout: 60_000 });
  await page.evaluate(() => window.TaxonomyI18n?.ready?.());
  await page.waitForFunction(id => {
    const session = window.TaxonomyAnalysisSession?.state?.();
    return session?.ready === true && session.workspaceId === id;
  }, workspaceId, { timeout: 30_000 });
}

async function createPersistedHypotheses(page, assert) {
  // Materialization is deterministic: these are authored UI fixtures, not model
  // findings. Discover every identifier from the actual loaded catalogue.
  const identities = await page.evaluate(async () => {
    const response = await fetch('/api/taxonomy');
    if (!response.ok) throw new Error(`Catalogue GET failed: ${response.status}`);
    const nodes = [];
    function visit(values) {
      for (const node of values || []) { nodes.push(node); visit(node.children); }
    }
    visit(await response.json());
    const processes = nodes.filter(node => /^BP-/.test(node.code)).slice(0, 2);
    const capability = nodes.find(node => /^CP-/.test(node.code));
    if (processes.length !== 2 || !capability) throw new Error('Catalogue lacks QA fixture endpoints');
    return [...processes.map(node => ({code: node.code, type: 'Process', title: node.nameEn || node.code})),
      {code: capability.code, type: 'Capability', title: capability.nameEn || capability.code}];
  });
  const before = await csrfJson(page, '/api/dsl/hypotheses', { method: 'GET' });
  assert(before.status === 200 && Array.isArray(before.json), 'Unable to inspect existing hypothesis identities');
  const dsl = identities.map(node =>
    `element ${node.code} type ${node.type} {\n  title: ${JSON.stringify(node.title)};\n}`).join('\n')
    + '\n' + identities.slice(0, 2).map(node =>
      `relation ${node.code} REALIZES ${identities[2].code} {\n  status: provisional;\n  confidence: 0.82;\n}`).join('\n');
  const materialized = await page.evaluate(async text => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const headers = { 'Content-Type': 'text/plain' };
    if (token) headers[header] = token;
    const response = await fetch('/api/dsl/materialize?path=qa-preferences-hypotheses.tax', {
      method: 'POST', headers, body: text
    });
    return {status: response.status, body: await response.json()};
  }, dsl);
  assert(materialized.status === 200 && materialized.body.valid === true
      && materialized.body.hypothesesCreated === 2,
  `Unable to materialize two persisted QA hypotheses: ${JSON.stringify(materialized)}`);
  const after = await csrfJson(page, '/api/dsl/hypotheses', { method: 'GET' });
  assert(after.status === 200 && Array.isArray(after.json), 'Unable to read materialized hypotheses');
  const previousIds = new Set(before.json.map(hypothesis => hypothesis.id));
  const workspaceId = await page.evaluate(() => window.TaxonomyAnalysisSession.state().workspaceId);
  const created = after.json.filter(hypothesis => !previousIds.has(hypothesis.id)
    && identities.slice(0, 2).some(node => node.code === hypothesis.sourceNodeId)
    && hypothesis.targetNodeId === identities[2].code && hypothesis.relationType === 'REALIZES');
  assert(created.length === 2 && created.every(hypothesis => Number.isSafeInteger(hypothesis.id)
      && hypothesis.id > 0 && hypothesis.status === 'PROVISIONAL' && hypothesis.workspaceId === workspaceId),
  'Materialization did not return two actual persisted provisional review identities');
  return created.sort((left, right) => left.id - right.id).map(hypothesis => ({
    hypothesisId: hypothesis.id, sourceCode: hypothesis.sourceNodeId,
    targetCode: hypothesis.targetNodeId, relationType: hypothesis.relationType,
    confidence: hypothesis.confidence, status: hypothesis.status,
    appliedInCurrentAnalysis: hypothesis.appliedInCurrentAnalysis,
    reasoning: 'Authored QA preference preservation fixture'
  }));
}

async function hypothesisCommand(page, id, action, click, assert) {
  const response = page.waitForResponse(candidate => candidate.request().method() === 'POST'
    && new URL(candidate.url()).pathname.endsWith(`/api/dsl/hypotheses/${id}/${action}`));
  await click();
  const result = await response;
  assert(result.status() === 200, `Hypothesis ${action} did not complete: HTTP ${result.status()}`);
  if (action === 'apply-session') {
    // The application only needs the command status and does not consume this
    // response body. Chromium can therefore leave Playwright's response.json()
    // waiting for body completion. Verify the actual persisted decision in a
    // fresh workspace-pinned read instead of depending on that browser detail.
    const current = await csrfJson(page, '/api/dsl/hypotheses', { method: 'GET' });
    assert(current.status === 200 && Array.isArray(current.json)
        && current.json.some(hypothesis => hypothesis.id === id
          && hypothesis.appliedInCurrentAnalysis === true),
    'Apply-session did not persist the expected hypothesis decision');
    return;
  }
  const body = await result.json();
  assert(body.id === id, `Hypothesis ${action} addressed an unexpected persisted identity`);
  const request = result.request();
  const headers = await request.allHeaders();
  assert(Boolean(headers['if-match']) && Boolean(headers['idempotency-key'])
      && Boolean(result.headers().etag), `Git ${action} omitted its optimistic authority headers`);
  assert(body.action === action.toUpperCase() && body.commitCreated === true
      && /^[0-9a-f]{40}$/.test(body.authoritativeCommitId)
      && result.headers().etag === `"${body.authoritativeCommitId}"`,
  `Hypothesis ${action} did not create the expected authoritative Git revision`);
}

async function assertHypothesisDecisions(page, hypotheses, assert) {
  await navigateArchitectureSubtab(page, 'overview');
  const rejected = page.locator('#suggested-row-0');
  const applied = page.locator('#suggested-row-1');
  await rejected.waitFor({ state: 'visible' });
  await applied.waitFor({ state: 'visible' });
  assert((await rejected.locator('td:last-child').innerText()).includes('Rejected'),
    'Restored rejected hypothesis lost its visible decision');
  assert((await applied.locator('td:last-child').innerText()).includes('Session only'),
    'Restored applied hypothesis lost its visible session decision');
  assert(await rejected.locator('.hypothesis-undo').count() === 1,
    'Restored rejected hypothesis lost its Git Undo control');
  assert(await page.locator('#suggestedRelationsContent button[onclick]').count() === 0,
    'Restored completed hypotheses offer repeat application/review controls');
  const current = await csrfJson(page, '/api/dsl/hypotheses', { method: 'GET' });
  assert(current.status === 200 && current.json.some(h => h.id === hypotheses[0].hypothesisId
      && h.status === 'REJECTED') && current.json.some(h => h.id === hypotheses[1].hypothesisId
      && h.appliedInCurrentAnalysis === true), 'Visible decisions differ from persisted hypothesis authority');
}

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

async function authoritativeDraft(page) {
  return page.evaluate(async () => {
    const workspaceId = window.TaxonomyAnalysisSession?.state?.().workspaceId;
    if (!workspaceId) throw new Error('No resolved analysis workspace');
    const response = await fetch(`/api/analysis-drafts/${encodeURIComponent(workspaceId)}`);
    if (!response.ok) throw new Error(`Draft GET failed with HTTP ${response.status}`);
    return response.json();
  });
}

function assertDraftEvidence(draft, assert, expectedReason) {
  const payload = draft?.payload || {};
  assert(payload.businessText === 'QA preference preservation sentinel'
      && payload.lastAnalyzedText === payload.businessText
      && payload.lastAnalysisStatus === 'SUCCESS',
  `Authoritative draft lost the completed text/status: ${JSON.stringify(payload)}`);
  assert(payload.scores?.BP === 77 && payload.rawScores?.BP === 91
      && payload.effectiveScores?.BP === 77 && payload.reasons?.BP === expectedReason,
  `Authoritative draft lost score/reason evidence: ${JSON.stringify(payload)}`);
  assert(payload.architectureView?.includedElements?.some(element => element.nodeCode === 'BP')
      && payload.evaluatedNodes?.includes('BP'),
  `Authoritative draft lost architecture/evaluated nodes: ${JSON.stringify(payload)}`);
}

async function assertVisibleArchitecture(page, assert) {
  // Earlier import acceptance leaves the Export subtab selected. Opening the
  // Architecture page keeps that selection, so select Overview through its UI.
  await navigateArchitectureSubtab(page, 'overview');
  await page.locator('#architectureViewPanel').waitFor({ state: 'visible', timeout: 20_000 });
  // The default network draws labels on a canvas. Open the actual Layer View
  // control so the persisted element can be verified as visible text.
  await page.locator('#architectureViewContent .impact-view-btn[data-mode="swimlane"]').click();
  await page.locator('#impactSwimView').waitFor({ state: 'visible', timeout: 20_000 });
  const visible = await page.locator('#architectureViewContent').innerText();
  assert(visible.includes('QA preference preservation sentinel')
      && visible.includes('Business Processes'),
  `Restored architecture is missing from the visible UI: ${visible.slice(0, 500)}`);
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
    get seen() {
      // Start the deadline only when the caller awaits observation, not when
      // installing the gate before navigation or state setup. The underlying
      // seen promise stays sticky for requests observed before that await.
      let timer;
      return Promise.race([
        seen,
        new Promise((resolve, reject) => {
          timer = setTimeout(() => reject(new Error(
            `Timed out after 30000 ms waiting for ${method} ${pattern} request in Preferences regression`
          )), 30_000);
        })
      ]).finally(() => clearTimeout(timer));
    },
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

async function runPreferencesPreservationWorkflow({ page, baseUrl, evidence }, hypotheses) {
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
  await page.evaluate(hypotheses => {
    const text = 'QA preference preservation sentinel';
    const state = window.TaxonomyState;
    const input = document.getElementById('businessText');
    if (input) input.value = text;
    state.currentScores = { BP: 77 };
    state.currentRawScores = { BP: 91 };
    state.currentEffectiveScores = { BP: 77 };
    state.currentScoreDetails = {
      BP: {
        nodeCode: 'BP',
        kind: 'HIERARCHICAL_RELEVANCE',
        rawScore: 91,
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
    window._currentProvisionalRelations = hypotheses;
    window._taxonomyCurrentScores = state.currentScores;
    window.TaxonomyScoring.renderArchitectureView(state.currentArchView);
    window.TaxonomyScoring.renderSuggestedRelations(hypotheses);
  }, hypotheses);
  await page.waitForFunction(() => Boolean(window.TaxonomyHypothesisReview)
    && Boolean(window.TaxonomyHypothesesApi));
  await navigateArchitectureSubtab(page, 'overview');
  await hypothesisCommand(page, hypotheses[0].hypothesisId, 'reject',
    () => page.locator('#suggested-row-0 button[onclick*="_rejectHypothesis"]').click(), assert);
  await page.waitForFunction(() => window._currentProvisionalRelations?.[0]?.status === 'REJECTED');
  await hypothesisCommand(page, hypotheses[1].hypothesisId, 'apply-session',
    () => page.locator('#suggested-row-1 button[onclick*="_applyForSession"]').click(), assert);
  await page.waitForFunction(() => window._currentProvisionalRelations?.[1]?.appliedInCurrentAnalysis === true);
  assert(await saveDraftNow(page) === true,
    'Unable to persist the preference-preservation analysis draft');
  console.log('Preferences QA: persisted review decisions saved in the analysis draft');
  let sentinelState = await page.evaluate(workingStateExpression());

  await navigateToPage(page, 'preferences');
  await waitForPreferenceLoad(page);
  const field = page.locator('#pref-max-arch-nodes');
  const originalLimit = Number(await field.inputValue());
  assert(Number.isFinite(originalLimit), 'Architecture-node preference is not numeric');
  // The reported sequence is specifically 50 → 150. Establish the starting
  // value through the same visible Preferences control, even if another test
  // changed this isolated application's initial runtime default.
  if (originalLimit !== 50) await saveArchitectureLimit(page, 50);
  assert(Number(await field.inputValue()) === 50,
    'The issue #1100 browser scenario did not start from 50 nodes');
  const changedLimit = 150;
  const failedLimit = 149;
  let preferenceRestored = false;

  try {
    // Hold the real draft PUT open while Preferences are persisted. This catches
    // races between the autosave lifecycle and the independent preferences
    // repository instead of exercising only a quiescent page.
    const autosaveGate = await installRequestGate(
      page, '**/api/analysis-drafts/**', 'PUT');
    console.log('Preferences QA: verifying Preferences during a pending draft save');
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
      assertDraftEvidence(await authoritativeDraft(page), assert,
        'QA preference preservation sentinel — autosave pending');
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
    await assertVisibleArchitecture(page, assert);
    await assertHypothesisDecisions(page, hypotheses, assert);

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
    console.log('Preferences QA: verifying Preferences during initial draft restoration');
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
    assertDraftEvidence(await authoritativeDraft(page), assert,
      'QA preference preservation sentinel — autosave pending');
    assert(await page.locator('[data-analysis-session-action="load-saved"]').count() === 0,
      'Reload offered an obsolete saved draft instead of restoring the current working draft');

    await navigateToPage(page, 'architecture');
    const afterReloadArchitecture = await page.evaluate(workingStateExpression());
    assert(afterReloadArchitecture === sentinelState,
      'Architecture navigation after pending restore replaced the restored working draft');
    await assertVisibleArchitecture(page, assert);

    await assertHypothesisDecisions(page, hypotheses, assert);
    const repeatRequests = [];
    const observeRepeat = request => {
      const path = new URL(request.url()).pathname;
      if ((request.method() === 'GET' && path.endsWith('/api/dsl/hypotheses/head'))
          || (request.method() === 'POST'
            && /\/api\/dsl\/hypotheses\/\d+\/(accept|reject|apply-session)$/.test(path))) {
        repeatRequests.push(request.url());
      }
    };
    page.on('request', observeRepeat);
    try {
      await page.evaluate(async () => {
        for (const index of [0, 1]) {
          window._acceptHypothesis(index);
          window._rejectHypothesis(index);
          window._applyForSession(index);
        }
        window._acceptAllHighConfidence();
        // Review begins with a head GET, Apply with POST: observing both proves
        // suppression without waiting for any asynchronous head response.
        await new Promise(resolve => window.setTimeout(resolve, 100));
      });
      assert(repeatRequests.length === 0,
        `Restored decision permitted repeated server commands: ${repeatRequests.join(', ')}`);
    } finally {
      page.off('request', observeRepeat);
    }
    await saveState('preferences-hypothesis-decisions-restored', '#suggestedRelationsPanel');
    await hypothesisCommand(page, hypotheses[0].hypothesisId, 'revert',
      () => page.locator('#suggested-row-0 .hypothesis-undo').click(), assert);
    await page.waitForFunction(() => window._currentProvisionalRelations?.[0]?.status === 'PROVISIONAL');
    const afterUndo = await csrfJson(page, '/api/dsl/hypotheses', { method: 'GET' });
    assert(afterUndo.status === 200 && afterUndo.json.some(h => h.id === hypotheses[0].hypothesisId
        && h.status === 'PROVISIONAL'), 'Restored Undo did not revert actual Git hypothesis authority');
    assert(await saveDraftNow(page) === true, 'Unable to persist restored Git Undo');
    const undoDraft = await authoritativeDraft(page);
    assert(undoDraft.payload.provisionalRelations[0].status === 'PROVISIONAL'
        && undoDraft.payload.provisionalRelations[1].appliedInCurrentAnalysis === true,
    'Git Undo changed the other restored manual decision');
    passed('Persisted hypothesis reject/apply survive Preferences and reload; restored Git Undo and repeat guards execute');

    await navigateToPage(page, 'preferences');
    await waitForPreferenceLoad(page);
    await saveArchitectureLimit(page, originalLimit);
    preferenceRestored = true;

    await axeState('preferences-state-preserved', '#tab-preferences');
    await saveState('preferences-state-preserved', '#tab-preferences');
    passed('Preferences 50→150 preserves visible architecture and authoritative draft across autosave, failure, return and pending restore');
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
    await page.evaluate(() => {
      window.TaxonomyScoring.renderArchitectureView(window.TaxonomyState.currentArchView);
      window.TaxonomyScoring.renderSuggestedRelations(window._currentProvisionalRelations);
    });
    const restoredDraft = await saveDraftNow(page);
    if (!restoredDraft) {
      console.warn('Could not persist the pre-QA analysis draft during cleanup');
    }

    await navigateToPage(page, 'analyze');
  }
}

export async function runPreferencesWorkflow(workflow) {
  const {page, evidence: {assert}} = workflow;
  await page.waitForFunction(() => window.TaxonomyAnalysisSession?.state?.().ready === true);
  const originalWorkspaceId = await page.evaluate(() => window.TaxonomyAnalysisSession.state().workspaceId);
  const originalState = await page.evaluate(workingStateExpression());
  assert(originalWorkspaceId, 'Preferences browser regression requires a resolved original workspace');
  let fixtureWorkspaceId = null;
  const inferenceRequests = [];
  const observeInference = request => {
    const path = new URL(request.url()).pathname;
    if (/\/api\/(analyze(?:-node|-stream)?|justify-leaf|copilot)(?:\/|$)/.test(path)) {
      inferenceRequests.push(path);
    }
  };
  page.on('request', observeInference);
  try {
    const created = await csrfJson(page, '/api/workspace/create', {body: {
      displayName: 'QA Preferences hypothesis restore',
      description: 'Bounded deterministic browser fixture; never published'
    }});
    assert(created.status === 200 && created.json?.workspaceId,
      `Unable to create isolated Preferences fixture workspace: HTTP ${created.status}`);
    fixtureWorkspaceId = created.json.workspaceId;
    console.log('Preferences QA: provisioning the isolated fixture workspace');
    await provisionAnalysisWorkspace(page, fixtureWorkspaceId, assert);
    await selectAnalysisWorkspace(page, fixtureWorkspaceId, assert);
    console.log('Preferences QA: ready workspace selected; creating persisted hypotheses');
    const hypotheses = await createPersistedHypotheses(page, assert);
    console.log('Preferences QA: persisted hypotheses created; exercising decision restoration');
    await runPreferencesPreservationWorkflow(workflow, hypotheses);
  } finally {
    try {
      if (fixtureWorkspaceId) {
        console.log('Preferences QA: restoring the original workspace and removing the fixture');
        // This Maven UI mutation scenario owns a fresh application/database. Delete
        // the disposable workspace/Git namespace and restore the original selection;
        // fixture hypothesis/document rows expire with the launcher's DB teardown.
        await selectAnalysisWorkspace(page, originalWorkspaceId, assert);
        await page.evaluate(restoreWorkingStateExpression(), originalState);
        await page.evaluate(() => {
          window.TaxonomyScoring.renderArchitectureView(window.TaxonomyState.currentArchView);
          window.TaxonomyScoring.renderSuggestedRelations(window._currentProvisionalRelations);
        });
        assert(await saveDraftNow(page) === true, 'Unable to restore the original workspace working draft');
        assert(await page.evaluate(workingStateExpression()) === originalState,
          'Preferences fixture restoration changed the original workspace analysis evidence');
        const removed = await csrfJson(page, `/api/workspace/${encodeURIComponent(fixtureWorkspaceId)}`, {method: 'DELETE'});
        assert(removed.status === 200 && removed.json?.success === true,
          `Unable to delete isolated Preferences fixture workspace: HTTP ${removed.status}`);
      }
    } finally {
      page.off('request', observeInference);
      assert(inferenceRequests.length === 0,
        `Preferences restoration scenario issued inference requests: ${inferenceRequests.join(', ')}`);
    }
  }
}
