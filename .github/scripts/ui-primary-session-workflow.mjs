import { navigateToPage } from './ui-role-fixtures.mjs';

const COPILOT_ENDPOINTS = Object.freeze([
  '/api/analyze',
  '/api/gap/analyze',
  '/api/patterns/detect',
  '/api/recommend'
]);

export function normalizeCopilotEndpoint(pathname) {
  const value = String(pathname || '');
  return COPILOT_ENDPOINTS.find(endpoint =>
    value === endpoint || value.endsWith(endpoint)) || '';
}

function requestPath(request) {
  try {
    return normalizeCopilotEndpoint(new URL(request.url()).pathname);
  } catch {
    return '';
  }
}

async function assertControlsUnobscured(page, selectors, label, assert) {
  const result = await page.evaluate(selectorsToCheck => {
    function visible(element) {
      if (!element || element.hidden || !element.isConnected) return false;
      const style = getComputedStyle(element);
      return style.display !== 'none' && style.visibility !== 'hidden'
        && element.getClientRects().length > 0;
    }
    function overlaps(left, right) {
      return left.left < right.right - 1 && left.right > right.left + 1
        && left.top < right.bottom - 1 && left.bottom > right.top + 1;
    }

    const failures = [];
    const controls = [];
    for (const selector of selectorsToCheck) {
      const element = document.querySelector(selector);
      if (!visible(element)) {
        failures.push(`${selector}: not visible`);
        continue;
      }
      const rect = element.getBoundingClientRect();
      if (rect.left < -1 || rect.top < -1
          || rect.right > window.innerWidth + 1 || rect.bottom > window.innerHeight + 1) {
        failures.push(`${selector}: outside viewport ${JSON.stringify({
          left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
          width: window.innerWidth, height: window.innerHeight
        })}`);
        continue;
      }
      const expectedDisabled = element.matches(':disabled')
        || element.getAttribute('aria-disabled') === 'true';
      const pointerEventsSuppressed = getComputedStyle(element).pointerEvents === 'none';
      if (pointerEventsSuppressed && !expectedDisabled) {
        failures.push(`${selector}: pointer events disabled while actionable`);
        continue;
      }
      const inlinePointerEvents = element.style.getPropertyValue('pointer-events');
      const inlinePointerEventsPriority = element.style.getPropertyPriority('pointer-events');
      if (pointerEventsSuppressed) {
        // Bootstrap deliberately removes disabled buttons from hit testing. Re-enable
        // pointer events only for this synchronous geometry probe so overlays above
        // the disabled control remain detectable without changing application state.
        element.style.setProperty('pointer-events', 'auto', 'important');
      }
      try {
        const inset = Math.min(6, rect.width / 4, rect.height / 4);
        const points = [
          [rect.left + rect.width / 2, rect.top + rect.height / 2],
          [rect.left + inset, rect.top + inset],
          [rect.right - inset, rect.top + inset],
          [rect.left + inset, rect.bottom - inset],
          [rect.right - inset, rect.bottom - inset]
        ];
        for (const [x, y] of points) {
          const top = document.elementFromPoint(x, y);
          if (!top || (top !== element && !element.contains(top))) {
            failures.push(`${selector}: obscured at ${Math.round(x)},${Math.round(y)} by ${
              top ? `${top.tagName.toLowerCase()}#${top.id || ''}.${top.className || ''}` : 'nothing'}`);
            break;
          }
        }
      } finally {
        if (pointerEventsSuppressed) {
          if (inlinePointerEvents) {
            element.style.setProperty(
              'pointer-events', inlinePointerEvents, inlinePointerEventsPriority);
          } else {
            element.style.removeProperty('pointer-events');
          }
        }
      }
      controls.push({ selector, rect });
    }

    for (let left = 0; left < controls.length; left += 1) {
      for (let right = left + 1; right < controls.length; right += 1) {
        if (overlaps(controls[left].rect, controls[right].rect)) {
          failures.push(`${controls[left].selector} overlaps ${controls[right].selector}`);
        }
      }
    }
    return {
      failures,
      horizontalOverflow: document.documentElement.scrollWidth
        - document.documentElement.clientWidth
    };
  }, selectors);

  assert(result.failures.length === 0,
    `${label} contains obscured or colliding controls: ${result.failures.join(' | ')}`);
  assert(result.horizontalOverflow <= 2,
    `${label} introduces horizontal overflow of ${result.horizontalOverflow}px`);
}

async function waitForCopilotComplete(page, expectedText) {
  await page.waitForFunction(text => {
    const state = window.TaxonomyState;
    const scores = window._taxonomyCurrentScores || state?.currentScores;
    const input = document.getElementById('businessText');
    const button = document.getElementById('copilotBtn');
    const spinner = document.getElementById('copilotSpinner');
    const summary = document.querySelector('#copilotContent .alert-success');
    return state?.lastAnalyzedText === text
      && state?.lastAnalysisStatus === 'SUCCESS'
      && scores && Object.keys(scores).length > 0
      && Boolean(state.currentArchView)
      && input && !input.classList.contains('stale-results')
      && button && !button.disabled
      && spinner?.classList.contains('d-none')
      && Boolean(summary);
  }, expectedText, { timeout: 180_000 });
}

function endpointCounts(requests) {
  return Object.fromEntries(COPILOT_ENDPOINTS.map(endpoint => [
    endpoint,
    requests.filter(request => request.path === endpoint).length
  ]));
}

export async function runAnalysisSessionWorkflow({ page, evidence }) {
  const {
    assert,
    passed,
    axeState,
    saveRequiredViewportState
  } = evidence;
  const firstRequirement =
    'Provide resilient hospital communications with traceable architecture decisions.';
  const changedRequirement =
    'Provide resilient hospital communications with traceable architecture decisions '
      + 'and an emergency notification capability.';

  await page.setViewportSize({ width: 1440, height: 1000 });
  await navigateToPage(page, 'analyze');
  await page.locator('.analysis-command-grid').waitFor({ state: 'visible', timeout: 20_000 });
  await page.waitForFunction(() => {
    const grid = document.querySelector('.analysis-command-grid');
    return Boolean(grid) && getComputedStyle(grid).display === 'grid';
  }, null, { timeout: 20_000 });

  const meaningful = await page.evaluate(() => {
    const context = window.__TaxonomyAnalysisSessionContext;
    return Boolean(context?.meaningful?.(context.currentPayload()));
  });
  if (meaningful) {
    page.once('dialog', dialog => dialog.accept());
    const reset = await page.evaluate(() => window.TaxonomyAnalysisSession.startNewAnalysis());
    assert(reset === true, 'Unable to establish an empty analysis session before the workflow');
  }
  await page.waitForFunction(() => {
    const state = window.TaxonomyState;
    return document.getElementById('businessText')?.value === ''
      && !state?.currentScores
      && document.querySelector('#taskStageDescribe[data-state="current"]');
  });

  const initialControls = await page.evaluate(() => ({
    interactive: document.getElementById('interactiveMode')?.checked,
    architecture: document.getElementById('includeArchitectureView')?.checked,
    cancelVisible: document.getElementById('cancelAnalysisBtn')?.getClientRects().length > 0,
    order: [...document.querySelector('.analysis-command-grid')?.children || []]
      .map(element => element.id).filter(Boolean)
  }));
  assert(initialControls.interactive === true,
    'Ordinary analysis preference should remain interactive before Copilot starts');
  assert(initialControls.architecture === false,
    'Architecture expansion should remain opt-in before Copilot starts');
  assert(initialControls.cancelVisible === false,
    'Cancel must not occupy the idle action surface');
  assert(initialControls.order.indexOf('copilotBtn') < initialControls.order.indexOf('analyzeBtn'),
    `Copilot is not the first analysis action: ${JSON.stringify(initialControls.order)}`);

  await page.locator('.analysis-command-grid').scrollIntoViewIfNeeded();
  await assertControlsUnobscured(page,
    ['#providerSelect', '#copilotBtn', '#analyzeBtn'], 'empty desktop session', assert);
  await saveRequiredViewportState('session-empty');

  const requests = [];
  const recordRequest = request => {
    const path = requestPath(request);
    if (request.method() !== 'POST' || !path) return;
    let body = null;
    try {
      body = request.postDataJSON();
    } catch {
      body = request.postData();
    }
    requests.push({ path, body });
  };
  page.on('request', recordRequest);

  await page.evaluate(() => {
    window.__taxonomyQaOperationStates = [];
    document.addEventListener('taxonomy:operation-state', event => {
      const detail = event.detail || {};
      window.__taxonomyQaOperationStates.push({
        operationId: detail.operationId || null,
        status: detail.status || null,
        phase: detail.phase || null
      });
    });
  });

  await page.route('**/api/analyze', async route => {
    await new Promise(resolve => setTimeout(resolve, 3_000));
    await route.continue();
  }, { times: 1 });

  await page.locator('#businessText').fill(firstRequirement);
  await page.locator('.analysis-command-grid').scrollIntoViewIfNeeded();
  await page.locator('#copilotBtn').click();
  await page.waitForFunction(() => {
    const button = document.getElementById('copilotBtn');
    const spinner = document.getElementById('copilotSpinner');
    const cancel = document.getElementById('cancelAnalysisBtn');
    return button?.getAttribute('aria-busy') === 'true'
      && !spinner?.classList.contains('d-none')
      && cancel?.getClientRects().length > 0;
  }, null, { timeout: 20_000 });

  const completeMode = await page.evaluate(() => ({
    interactive: document.getElementById('interactiveMode')?.checked,
    architecture: document.getElementById('includeArchitectureView')?.checked,
    nextAction: document.getElementById('taskNextAction')?.dataset.action,
    stage: document.querySelector('#analysisTaskProgress [aria-current="step"]')?.id
  }));
  assert(completeMode.interactive === false && completeMode.architecture === true,
    `Copilot did not select complete architecture mode: ${JSON.stringify(completeMode)}`);
  assert(completeMode.nextAction === 'running' && completeMode.stage === 'taskStageAnalyze',
    `Copilot progress hierarchy is inconsistent: ${JSON.stringify(completeMode)}`);
  await assertControlsUnobscured(page,
    ['#providerSelect', '#copilotBtn', '#analyzeBtn', '#cancelAnalysisBtn'],
    'running desktop Copilot', assert);
  await saveRequiredViewportState('session-copilot-running');

  await waitForCopilotComplete(page, firstRequirement);
  let counts = endpointCounts(requests);
  for (const endpoint of COPILOT_ENDPOINTS) {
    assert(counts[endpoint] === 1,
      `First Copilot run called ${endpoint} ${counts[endpoint]} times`);
  }
  assert(await page.evaluate(() => {
    const ids = ['gapAnalysisContent', 'patternDetectionContent', 'recommendationContent'];
    return ids.every(id => {
      const content = document.getElementById(id);
      return content && content.children.length > 0
        && !/^Run |^Detect |^Generate /i.test(content.textContent.trim());
    });
  }), 'Copilot completed without populated gap, pattern and recommendation results');
  assert(await page.evaluate(() => window.TaxonomyAnalysisSession.saveNow()),
    'Completed Copilot state was not persisted to the analysis draft');
  await axeState('session-copilot-complete', '#tab-analyze');
  await page.locator('#copilotPanel').scrollIntoViewIfNeeded();
  await saveRequiredViewportState('session-copilot-complete');
  passed('complete Copilot analysis with architecture and derived results');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('#mobileMainNavigation').waitFor({ state: 'visible', timeout: 10_000 });
  await navigateToPage(page, 'analyze');
  await page.locator('#businessText').fill(changedRequirement);
  await page.locator('#businessText.stale-results').waitFor({ state: 'visible', timeout: 10_000 });
  await page.waitForFunction(() =>
    document.querySelector('#statusArea [data-analysis-session-action="discard-analysis"]'));
  await page.locator('#statusArea').scrollIntoViewIfNeeded();
  await saveRequiredViewportState('session-requirement-stale-mobile');

  await page.locator('.analysis-command-grid').scrollIntoViewIfNeeded();
  await assertControlsUnobscured(page,
    ['#providerSelect', '#copilotBtn', '#analyzeBtn'], 'stale mobile session', assert);
  await page.locator('#copilotBtn').click();
  await page.waitForFunction(() =>
    document.getElementById('copilotBtn')?.getAttribute('aria-busy') === 'true',
  null, { timeout: 20_000 });
  await waitForCopilotComplete(page, changedRequirement);

  counts = endpointCounts(requests);
  for (const endpoint of COPILOT_ENDPOINTS) {
    assert(counts[endpoint] === 2,
      `Changed Copilot run left ${endpoint} at ${counts[endpoint]} calls`);
  }
  const analyzeBodies = requests
    .filter(request => request.path === '/api/analyze')
    .map(request => request.body);
  assert(JSON.stringify(analyzeBodies.at(-1)).includes(changedRequirement),
    `Second Copilot request did not carry the changed requirement: ${JSON.stringify(analyzeBodies)}`);
  const operationIds = await page.evaluate(() => [...new Set(
    (window.__taxonomyQaOperationStates || [])
      .filter(state => state.status === 'RUNNING' && state.operationId)
      .map(state => state.operationId)
  )]);
  assert(operationIds.length >= 2,
    `Changed requirement did not start a distinct authoritative operation: ${operationIds}`);
  await page.locator('.analysis-command-grid').scrollIntoViewIfNeeded();
  await assertControlsUnobscured(page,
    ['#providerSelect', '#copilotBtn', '#analyzeBtn'], 'updated mobile session', assert);
  await page.locator('#copilotPanel').scrollIntoViewIfNeeded();
  await saveRequiredViewportState('session-copilot-updated-mobile');
  passed('changed requirement reruns the complete Copilot instead of reusing stale scores');

  page.off('request', recordRequest);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.locator('#fileMenuButton').click();
  await page.locator('#fileNewAnalysisAction').waitFor({ state: 'visible' });
  await saveRequiredViewportState('session-reset-command-mobile', 'body');

  const resetWasMeaningful = await page.evaluate(() => {
    const context = window.__TaxonomyAnalysisSessionContext;
    return Boolean(context?.meaningful?.(context.currentPayload()));
  });
  assert(resetWasMeaningful, 'Reset confirmation was not required for the completed session');
  let confirmationMessage = '';
  const confirmationHandled = new Promise((resolve, reject) => {
    page.once('dialog', async dialog => {
      try {
        confirmationMessage = dialog.message();
        assert(dialog.type() === 'confirm', `Unexpected reset dialog type: ${dialog.type()}`);
        await dialog.accept();
        resolve();
      } catch (error) {
        reject(error);
      }
    });
  });
  await page.locator('#fileNewAnalysisAction').click();
  await confirmationHandled;
  assert(/new analysis|discard|neue analyse|verworfen/i.test(confirmationMessage),
    `Reset confirmation was not explicit: ${confirmationMessage}`);

  await page.waitForFunction(() => {
    const state = window.TaxonomyState;
    const input = document.getElementById('businessText');
    const cancel = document.getElementById('cancelAnalysisBtn');
    return input?.value === ''
      && !state?.currentScores
      && !state?.currentArchView
      && !window._taxonomyCurrentScores
      && document.querySelector('#taskStageDescribe[data-state="current"]')
      && document.getElementById('copilotPanel')?.getClientRects().length === 0
      && cancel?.getClientRects().length === 0
      && document.getElementById('statusArea')?.dataset.analysisSessionMessage === 'new-analysis';
  }, null, { timeout: 30_000 });

  const serverDraft = await page.evaluate(async () => {
    const state = window.TaxonomyAnalysisSession.state();
    const path = `/api/analysis-drafts/${encodeURIComponent(state.workspaceId)}`;
    const url = window.TaxonomyI18n?.resolveUrl?.(path) || path;
    const response = await fetch(url, {
      headers: { Accept: 'application/json' },
      cache: 'no-store'
    });
    return { status: response.status, view: await response.json() };
  });
  assert(serverDraft.status === 200, `Reset draft read returned ${serverDraft.status}`);
  assert(serverDraft.view?.payload?.draftState === 'EMPTY'
      && serverDraft.view.payload.businessText === ''
      && (!serverDraft.view.payload.scores
        || Object.keys(serverDraft.view.payload.scores).length === 0)
      && !serverDraft.view.payload.architectureView,
  `Server draft retained analysis state after reset: ${JSON.stringify(serverDraft.view?.payload)}`);
  await page.locator('#analysisTaskProgress').scrollIntoViewIfNeeded();
  await saveRequiredViewportState('session-reset-complete-mobile');
  passed('visible reset command clears browser and authoritative server draft');

  await page.reload({ waitUntil: 'networkidle' });
  await page.evaluate(() => window.TaxonomyI18n?.ready?.());
  await page.locator('#analysisTaskProgress').waitFor({ state: 'visible', timeout: 30_000 });
  const onboardingDismiss = page.locator('#onboardingDismiss');
  if (await onboardingDismiss.isVisible().catch(() => false)) {
    await onboardingDismiss.click();
  }
  await page.waitForFunction(() => {
    const session = window.TaxonomyAnalysisSession;
    const state = window.TaxonomyState;
    return session?.state().workspaceId
      && session.state().restoring === false
      && document.getElementById('businessText')?.value === ''
      && !state?.currentScores
      && !state?.currentArchView
      && !window._taxonomyCurrentScores
      && document.querySelector('#taskStageDescribe[data-state="current"]');
  }, null, { timeout: 30_000 });
  await page.locator('.analysis-command-grid').scrollIntoViewIfNeeded();
  await assertControlsUnobscured(page,
    ['#providerSelect', '#copilotBtn', '#analyzeBtn'], 'reloaded mobile reset state', assert);
  await axeState('session-reset-reloaded-mobile', '#tab-analyze');
  await saveRequiredViewportState('session-reset-reloaded-mobile');
  passed('reset remains empty after reload');

  await page.setViewportSize({ width: 1440, height: 1000 });
}
