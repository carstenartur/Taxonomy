import {
  csrfJson,
  navigateArchitectureSubtab,
  navigateToPage
} from './ui-role-fixtures.mjs';

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function createReviewCandidate(page, role) {
  const rationalePrefix = `Role acceptance ${Date.now().toString(36)}`;
  if (role === 'USER') {
    return csrfJson(page, '/api/proposals/from-hypothesis', {
      body: { sourceCode: 'BP', targetCode: 'BR', relationType: 'RELATED_TO',
        confidence: 0.72, rationale: rationalePrefix }
    });
  }

  const codes = await page.locator('#taxonomyTree [data-code]').evaluateAll(nodes =>
    [...new Set(nodes.map(node => node.dataset.code).filter(Boolean))].slice(0, 14));
  const relationTypes = [
    'RELATED_TO', 'SUPPORTS', 'REALIZES', 'USES', 'DEPENDS_ON', 'CONSUMES',
    'FULFILLS', 'PRODUCES', 'COMMUNICATES_WITH', 'CONTAINS', 'ASSIGNED_TO'
  ];
  let lastConflict = null;
  for (let sourceIndex = 0; sourceIndex < codes.length; sourceIndex += 1) {
    for (let targetIndex = sourceIndex + 1; targetIndex < codes.length; targetIndex += 1) {
      for (const relationType of relationTypes) {
        const rationale = `${rationalePrefix} ${sourceIndex}-${targetIndex}-${relationType}`;
        const response = await csrfJson(page, '/api/proposals/from-hypothesis', {
          body: {
            sourceCode: codes[sourceIndex], targetCode: codes[targetIndex], relationType,
            confidence: 0.72, rationale
          }
        });
        if (response.status === 200) return { ...response, rationale };
        if (response.status === 409) {
          lastConflict = response;
          continue;
        }
        return response;
      }
    }
  }
  return lastConflict || { status: 409, json: null, text: 'No unique proposal candidate' };
}

async function measureArchitectTask(page, proposal) {
  const startedAt = Date.now();
  await navigateArchitectureSubtab(page, 'relations');
  const pendingFilter = page.locator('#filterPending');
  await pendingFilter.waitFor({ state: 'visible', timeout: 20_000 });
  await pendingFilter.click();
  await page.waitForFunction(() => {
    const container = document.getElementById('proposalsTableContainer');
    return container && !/Loading proposals|Loading…/i.test(container.textContent || '');
  }, null, { timeout: 20_000 });

  const primarySurface = await page.evaluate(() => {
    const table = document.getElementById('proposalsTableContainer');
    const filter = document.getElementById('filterPending');
    const rect = filter?.getBoundingClientRect();
    return {
      tableVisible: Boolean(table && table.getClientRects().length),
      primaryVisible: Boolean(rect && rect.width > 0 && rect.height > 0),
      primaryFocusable: Boolean(filter && !filter.disabled && filter.tabIndex >= 0),
      primaryInsideViewport: Boolean(rect && rect.top >= 0 && rect.bottom <= window.innerHeight),
      preTaskPixels: Math.max(0, Math.round(
        document.getElementById('proposalsPanel')?.getBoundingClientRect().top || 0)),
      viewportHeight: window.innerHeight
    };
  });
  assert(primarySurface.tableVisible && primarySurface.primaryVisible
    && primarySurface.primaryFocusable,
  'ARCHITECT proposal decision surface is not visible and keyboard reachable');
  const timeToPrimaryActionMs = Date.now() - startedAt;

  if (proposal.status === 200 && proposal.json?.id) {
    const accept = page.getByRole('button', {
      name: `Accept proposal ${proposal.json.id}`
    });
    await accept.waitFor({ state: 'visible', timeout: 20_000 });
    const rowText = await accept.locator('xpath=ancestor::tr').textContent();
    assert(rowText?.includes(proposal.rationale || 'Role acceptance'),
      'Proposal review row does not expose its rationale');
    await accept.click();
    await page.waitForFunction(id =>
      !document.querySelector(`[aria-label="Accept proposal ${id}"]`),
    proposal.json.id, { timeout: 20_000 });
  } else {
    assert(proposal.status === 409,
      `ARCHITECT proposal creation returned ${proposal.status}, expected 200 or 409`);
    await page.locator('#filterAll').click();
    await page.waitForFunction(() => {
      const container = document.getElementById('proposalsTableContainer');
      return container && !/Loading proposals|Loading…/i.test(container.textContent || '');
    }, null, { timeout: 20_000 });
  }

  const variantName = `qa-variant-${Date.now().toString(36)}-${Math.random()
    .toString(36).slice(2, 7)}`;
  await page.evaluate(() => window.TaxonomyContextBar.showVariantDialog());
  const variantInput = page.locator('#variantNameInput');
  await variantInput.waitFor({ state: 'visible', timeout: 10_000 });
  await variantInput.fill(variantName);
  await page.locator('#createVariantModal .btn-success').click();
  await page.locator('#createVariantModal').waitFor({ state: 'hidden', timeout: 20_000 });
  const currentContext = await page.evaluate(async () => {
    const response = await fetch('/api/context/current');
    return response.json();
  });
  assert(currentContext.branch?.includes(variantName),
    `Variant creation did not switch context to ${variantName}`);
  const returned = await csrfJson(page, '/api/context/return-to-origin', { body: {} });
  assert(returned.status === 200, `Return from QA variant returned ${returned.status}`);

  return {
    id: 'architect-review-and-variant',
    role: 'ARCHITECT',
    taskCompleted: true,
    failedStep: null,
    timeToPrimaryActionMs,
    timeToTaskCompletionMs: Date.now() - startedAt,
    preTaskViewportPixels: primarySurface.preTaskPixels,
    preTaskViewportRatio: Number((primarySurface.preTaskPixels
      / primarySurface.viewportHeight).toFixed(4)),
    pageTransitionsBeforePrimaryAction: 1,
    navigationErrors: 0,
    primaryActionInsideInitialViewport: primarySurface.primaryInsideViewport,
    nextActionInsideInitialViewport: true,
    proposalDecisionExercised: proposal.status === 200,
    variantCreatedAndReturned: true
  };
}

async function measureAdminTask(page) {
  const startedAt = Date.now();
  await navigateToPage(page, 'admin');
  const healthPanel = page.locator('#healthDashboard');
  const summary = healthPanel.locator('summary');
  await summary.waitFor({ state: 'visible', timeout: 20_000 });
  const primary = await summary.evaluate(element => {
    const rect = element.getBoundingClientRect();
    return {
      insideViewport: rect.top >= 0 && rect.bottom <= window.innerHeight,
      top: Math.max(0, Math.round(rect.top)),
      viewportHeight: window.innerHeight,
      focusable: element.tabIndex >= 0
    };
  });
  assert(primary.focusable, 'ADMIN health summary is not keyboard focusable');
  const timeToPrimaryActionMs = Date.now() - startedAt;
  await summary.click();
  await page.waitForFunction(() => {
    const ids = ['healthOverallBadge', 'healthAiBadge', 'healthEmbeddingBadge',
      'healthMemoryBadge'];
    return ids.every(id => {
      const value = document.getElementById(id)?.textContent?.trim() || '';
      return value && !value.includes('⏳');
    });
  }, null, { timeout: 20_000 });
  const statuses = await page.evaluate(() => Object.fromEntries([
    ['overall', document.getElementById('healthOverallBadge')?.textContent?.trim() || ''],
    ['ai', document.getElementById('healthAiBadge')?.textContent?.trim() || ''],
    ['embedding', document.getElementById('healthEmbeddingBadge')?.textContent?.trim() || ''],
    ['memory', document.getElementById('healthMemoryBadge')?.textContent?.trim() || '']
  ]));
  assert(Object.values(statuses).every(Boolean),
    `ADMIN health task returned incomplete component states: ${JSON.stringify(statuses)}`);
  await Promise.all([
    page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/admin/health-summary'
        && response.status() === 200,
    { timeout: 20_000 }),
    page.locator('#healthRefreshBtn').click()
  ]);

  return {
    id: 'admin-diagnose-availability',
    role: 'ADMIN',
    taskCompleted: true,
    failedStep: null,
    timeToPrimaryActionMs,
    timeToTaskCompletionMs: Date.now() - startedAt,
    preTaskViewportPixels: primary.top,
    preTaskViewportRatio: Number((primary.top / primary.viewportHeight).toFixed(4)),
    pageTransitionsBeforePrimaryAction: 1,
    navigationErrors: 0,
    primaryActionInsideInitialViewport: primary.insideViewport,
    nextActionInsideInitialViewport: await page.locator('#healthRefreshBtn').evaluate(element => {
      const rect = element.getBoundingClientRect();
      return Boolean(rect && rect.top >= 0 && rect.bottom <= window.innerHeight);
    }),
    componentStates: statuses
  };
}

export async function runRoleStateFlow({
  page, role, zoom, forcedColors, checks, httpFailures,
  externalRequests, consoleErrors, evidence
}) {
  const passed = name => checks.push(name);
  const { runAxe, saveState } = evidence;
  const taskStartedAt = Date.now();
  const taskMeasurements = {
    schemaVersion: 1,
    roleTask: 'analyze a requirement',
    taskCompleted: false,
    failedStep: null,
    timeToPrimaryActionMs: null,
    timeToTaskCompletionMs: null,
    preTaskViewportPixels: null,
    preTaskViewportRatio: null,
    pageTransitionsBeforePrimaryAction: 1,
    navigationErrors: 0,
    primaryActionInsideInitialViewport: false,
    nextActionInsideInitialViewport: false,
    operationalContextCollapsedByDefault: false,
    secondaryToolsCollapsedByDefault: false,
    tasks: []
  };

  const adminTab = page.locator('#adminNavTab');
  if (role === 'ADMIN') {
    await page.waitForFunction(() => {
      const tab = document.querySelector('#adminNavTab');
      return tab && getComputedStyle(tab).display !== 'none';
    }, null, { timeout: 20_000 });
    const adminLink = adminTab.locator('a[data-page="admin"]');
    await adminLink.scrollIntoViewIfNeeded();
    assert(await adminLink.isVisible(), 'ADMIN navigation is unavailable after role authorization');
    passed('admin role navigation');
  } else {
    await page.locator('#adminLockBtn').waitFor({ state: 'visible', timeout: 15_000 });
    assert(!(await adminTab.isVisible().catch(() => false)), `${role} must not see admin navigation`);
    passed('role-specific navigation');
  }

  await page.locator('#mainNavTabs [data-page="analyze"]').click();
  await page.locator('#taxonomyTree [role="treeitem"]').first()
    .waitFor({ state: 'visible', timeout: 90_000 });

  const taskSurface = await page.evaluate(() => {
    const progress = document.getElementById('analysisTaskProgress');
    const primary = document.getElementById('analyzeBtn');
    const operations = document.getElementById('operationalContextDetails');
    const secondary = document.getElementById('analysisSecondaryTools');
    const progressRect = progress?.getBoundingClientRect();
    const primaryRect = primary?.getBoundingClientRect();
    const focusable = Boolean(primary && !primary.disabled
      && primary.tabIndex >= 0 && getComputedStyle(primary).visibility !== 'hidden');
    return {
      progressVisible: Boolean(progressRect && progressRect.width > 0 && progressRect.height > 0),
      progressTop: progressRect?.top ?? Number.POSITIVE_INFINITY,
      viewportHeight: window.innerHeight,
      primaryVisible: Boolean(primaryRect && primaryRect.width > 0 && primaryRect.height > 0),
      primaryEnabled: Boolean(primary && !primary.disabled),
      primaryFocusable: focusable,
      primaryInsideViewport: Boolean(primaryRect && primaryRect.top >= 0
        && primaryRect.bottom <= window.innerHeight),
      currentStage: progress?.querySelector('[aria-current="step"]')?.id || '',
      operationalCollapsed: Boolean(operations && !operations.open),
      operationalContainsOriginalSurfaces: Boolean(operations
        && operations.contains(document.getElementById('gitStatusBar'))
        && operations.contains(document.getElementById('contextBar'))),
      secondaryCollapsed: Boolean(secondary && !secondary.open),
      secondaryContainsExpertTools: Boolean(secondary
        && secondary.contains(document.getElementById('searchPanel'))
        && secondary.contains(document.getElementById('llmCommLog')))
    };
  });
  assert(taskSurface.progressVisible, 'Explicit analysis task progression is not visible');
  assert(taskSurface.currentStage === 'taskStageDescribe',
    `Initial analysis stage was ${taskSurface.currentStage}, expected describe`);
  assert(taskSurface.primaryVisible && taskSurface.primaryEnabled && taskSurface.primaryFocusable,
    'Primary Analyze action is not visible, enabled and focusable');
  assert(taskSurface.operationalCollapsed && taskSurface.operationalContainsOriginalSurfaces,
    'Operational status must be collapsed by default while retaining original detail surfaces');
  assert(taskSurface.secondaryCollapsed && taskSurface.secondaryContainsExpertTools,
    'Secondary analysis tools must be collapsed by default and remain available');
  taskMeasurements.timeToPrimaryActionMs = Date.now() - taskStartedAt;
  taskMeasurements.preTaskViewportPixels = Math.max(0, Math.round(taskSurface.progressTop));
  taskMeasurements.preTaskViewportRatio = Number((Math.max(0, taskSurface.progressTop)
    / taskSurface.viewportHeight).toFixed(4));
  taskMeasurements.primaryActionInsideInitialViewport = taskSurface.primaryInsideViewport;
  taskMeasurements.operationalContextCollapsedByDefault = taskSurface.operationalCollapsed;
  taskMeasurements.secondaryToolsCollapsedByDefault = taskSurface.secondaryCollapsed;
  passed('measured primary task visibility and progressive disclosure');

  const interactive = page.locator('#interactiveMode');
  if (await interactive.isChecked()) await interactive.uncheck();
  await page.locator('#businessText').fill(
    'Provide resilient hospital communications with traceable architecture decisions.');
  await page.waitForFunction(() =>
    document.querySelector('#taskStageAnalyze[data-state="current"]'));
  passed('requirement entry advances explicit task stage');

  await page.evaluate(() => {
    window.__taxonomyQaAnalysisStatusObserver?.disconnect();
    const status = document.getElementById('statusArea');
    if (!status) throw new Error('Missing #statusArea');
    const events = [];
    window.__taxonomyQaAnalysisStatusEvents = events;
    const observer = new MutationObserver(mutations => {
      const mutationText = mutations.flatMap(mutation => {
        if (mutation.type === 'characterData') return [mutation.target.data || ''];
        return Array.from(mutation.addedNodes || [], node => node.textContent || '');
      }).join(' ').trim();
      const currentText = status.textContent?.trim() || '';
      const visible = getComputedStyle(status).display !== 'none'
        && getComputedStyle(status).visibility !== 'hidden'
        && status.getClientRects().length > 0;
      if (mutationText || currentText || visible) {
        events.push({ mutationText, currentText, visible });
      }
    });
    observer.observe(status, {
      attributes: true, childList: true, subtree: true, characterData: true
    });
    window.__taxonomyQaAnalysisStatusObserver = observer;
  });
  await page.locator('#analyzeBtn').focus();
  await page.keyboard.press('Enter');
  // Treat the analysis as complete only when model state, rendered scores and
  // the task hierarchy agree. A separate short follow-up wait raced Firefox on
  // the large taxonomy DOM and could fail even though the captured page had
  // already reached the correct Review state.
  await page.waitForFunction(() => {
    const scores = window.TaxonomyState?.currentScores;
    const reviewStage = document.querySelector('#taskStageReview[data-state="current"]');
    const nextAction = document.getElementById('taskNextAction');
    return scores && Object.keys(scores).length > 0
      && document.querySelectorAll('.tax-pct').length > 0
      && reviewStage
      && nextAction
      && !nextAction.disabled;
  }, null, { timeout: 120_000 });
  const statusEvents = await page.evaluate(() => {
    window.__taxonomyQaAnalysisStatusObserver?.disconnect();
    return window.__taxonomyQaAnalysisStatusEvents || [];
  });
  assert(statusEvents.length > 0,
    'Analysis completed without a perceivable status transition');
  const completedTaskState = await page.evaluate(() => {
    const nextAction = document.getElementById('taskNextAction');
    const rect = nextAction?.getBoundingClientRect();
    return {
      currentStage: document.querySelector('#analysisTaskProgress [aria-current="step"]')?.id || '',
      nextActionVisible: Boolean(rect && rect.width > 0 && rect.height > 0),
      nextActionInsideViewport: Boolean(rect && rect.top >= 0 && rect.bottom <= window.innerHeight),
      nextActionText: nextAction?.textContent?.trim() || '',
      viewportWidth: window.innerWidth,
      rightTop: document.getElementById('rightPanel')?.getBoundingClientRect().top
        ?? Number.POSITIVE_INFINITY,
      leftTop: document.getElementById('leftPanel')?.getBoundingClientRect().top
        ?? Number.POSITIVE_INFINITY
    };
  });
  assert(completedTaskState.currentStage === 'taskStageReview',
    `Completed analysis stage was ${completedTaskState.currentStage}, expected review`);
  assert(completedTaskState.nextActionVisible && completedTaskState.nextActionText,
    'Completed analysis has no visible contextual next action');
  taskMeasurements.taskCompleted = true;
  taskMeasurements.timeToTaskCompletionMs = Date.now() - taskStartedAt;
  taskMeasurements.nextActionInsideInitialViewport = completedTaskState.nextActionInsideViewport;
  taskMeasurements.tasks.push({
    id: 'user-analyze-requirement',
    role,
    taskCompleted: true,
    failedStep: null,
    timeToPrimaryActionMs: taskMeasurements.timeToPrimaryActionMs,
    timeToTaskCompletionMs: taskMeasurements.timeToTaskCompletionMs,
    preTaskViewportPixels: taskMeasurements.preTaskViewportPixels,
    preTaskViewportRatio: taskMeasurements.preTaskViewportRatio,
    pageTransitionsBeforePrimaryAction: 1,
    navigationErrors: 0,
    primaryActionInsideInitialViewport: taskMeasurements.primaryActionInsideInitialViewport,
    nextActionInsideInitialViewport: taskMeasurements.nextActionInsideInitialViewport
  });
  if (completedTaskState.viewportWidth < 992) {
    assert(completedTaskState.rightTop <= completedTaskState.leftTop,
      'Mobile result task appears after the reference taxonomy browser');
    taskMeasurements.tasks.push({
      id: 'mobile-read-existing-result',
      role,
      taskCompleted: true,
      failedStep: null,
      timeToPrimaryActionMs: taskMeasurements.timeToTaskCompletionMs,
      timeToTaskCompletionMs: taskMeasurements.timeToTaskCompletionMs,
      preTaskViewportPixels: taskMeasurements.preTaskViewportPixels,
      preTaskViewportRatio: taskMeasurements.preTaskViewportRatio,
      pageTransitionsBeforePrimaryAction: 0,
      navigationErrors: 0,
      primaryActionInsideInitialViewport: completedTaskState.nextActionInsideViewport,
      nextActionInsideInitialViewport: completedTaskState.nextActionInsideViewport,
      resultPrecedesReferenceTree: true
    });
  }
  passed('analysis loading, success and contextual next action');
  await runAxe('analysis-success');
  await saveState('analysis-success');

  await page.locator('#taskNextAction').click();
  await page.waitForFunction(() => {
    const nextAction = document.getElementById('taskNextAction');
    return document.querySelector('#taskStageContinue[data-state="current"]')
      && nextAction?.dataset.action === 'open-architecture'
      && !nextAction.disabled;
  });
  passed('review action advances explicit continuation stage');

  await page.locator('#businessText').fill(
    'Provide resilient hospital communications with an emergency notification capability.');
  await page.locator('#businessText.stale-results').waitFor({ state: 'visible', timeout: 10_000 });
  await page.waitForFunction(() =>
    document.querySelector('#taskStageAnalyze[data-state="current"]'));
  passed('stale-result indication returns focus to analysis stage');

  const sunburst = page.locator('#viewSunburst');
  await sunburst.scrollIntoViewIfNeeded();
  await sunburst.click();
  await page.waitForFunction(() => window.TaxonomyState.currentView === 'sunburst');
  await page.route('**/api/analyze', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'QA_PROVIDER_UNAVAILABLE', message: 'Deterministic QA failure' })
  }), { times: 1 });
  await page.locator('#analyzeBtn').click();
  await page.waitForFunction(() => {
    const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
    return text.includes('error') || text.includes('failed') || text.includes('503')
      || text.includes('unavailable');
  }, null, { timeout: 30_000 });
  await page.waitForFunction(() =>
    document.querySelector('#taskStageAnalyze[data-state="error"]')
      && !document.getElementById('taskNextAction')?.disabled);
  passed('analysis provider error retains hierarchy and retry action');
  await runAxe('analysis-error');
  await saveState('analysis-error');

  const proposal = await createReviewCandidate(page, role);
  if (role === 'USER') {
    assert(proposal.status === 403, `USER proposal mutation returned ${proposal.status}, expected 403`);
    passed('user proposal mutation forbidden');
  } else if (role === 'ARCHITECT') {
    assert([200, 409].includes(proposal.status),
      `ARCHITECT proposal creation returned ${proposal.status}`);
    taskMeasurements.tasks.push(await measureArchitectTask(page, proposal));
    passed('architectural proposal review and variant creation');
  } else {
    assert([200, 409].includes(proposal.status),
      `ADMIN proposal creation returned ${proposal.status}`);
    if (proposal.status === 200 && proposal.json?.id) {
      const accepted = await csrfJson(page, `/api/proposals/${proposal.json.id}/accept`, { body: {} });
      assert(accepted.status === 200, `ADMIN proposal acceptance returned ${accepted.status}`);
    }
    passed('administrator architectural mutation permission');
    taskMeasurements.tasks.push(await measureAdminTask(page));
    passed('administrator health diagnosis task');
  }
  await runAxe('role-operation-feedback');
  await saveState('role-operation-feedback');

  await navigateToPage(page, 'analyze');
  const businessText = page.locator('#businessText');
  await businessText.focus();
  await page.evaluate(() => window.TaxonomyUtils.showMessage('Accessible QA dialog', 'QA notice'));
  const dialog = page.locator('#taxonomyAccessibleDialog');
  await dialog.waitFor({ state: 'visible' });
  assert(await page.evaluate(() => document.activeElement?.id === 'taxonomyAccessibleDialogConfirm'),
    'Dialog did not move focus to its confirmation control');
  await runAxe('dialog-open');
  await saveState('dialog-open');
  await page.keyboard.press('Enter');
  await dialog.waitFor({ state: 'hidden' });
  assert(await page.evaluate(() => document.activeElement?.id === 'businessText'),
    'Dialog did not restore focus to its invoker');
  passed('dialog focus entry and restoration');

  await page.evaluate(() => {
    localStorage.removeItem('taxonomy_onboarded');
    window.TaxonomyOnboarding.init();
  });
  const onboarding = page.locator('#onboardingOverlay');
  await onboarding.waitFor({ state: 'visible', timeout: 10_000 });
  await page.waitForFunction(() => document.activeElement?.id === 'onboardingDismiss');
  const onboardingState = await onboarding.evaluate(element => ({
    tagName: element.tagName,
    open: element.open,
    role: element.getAttribute('role'),
    ariaModal: element.getAttribute('aria-modal'),
    labelledBy: element.getAttribute('aria-labelledby'),
    describedBy: element.getAttribute('aria-describedby')
  }));
  assert(onboardingState.tagName === 'DIALOG' && onboardingState.open
    && onboardingState.role === 'dialog' && onboardingState.ariaModal === 'true'
    && onboardingState.labelledBy === 'onboardingTitle'
    && onboardingState.describedBy === 'onboardingIntro',
  `Incomplete onboarding dialog semantics: ${JSON.stringify(onboardingState)}`);
  await runAxe('onboarding-open');
  await saveState('onboarding-open');
  await page.keyboard.press('Escape');
  await onboarding.waitFor({ state: 'hidden' });
  await page.waitForFunction(() => document.activeElement?.id === 'businessText');
  passed('onboarding modal semantics, Escape close and focus restoration');

  await page.evaluate(() => {
    for (const index of [1, 2]) {
      const toast = document.createElement('div');
      toast.className = 'undo-toast';
      toast.dataset.qaOverlayToast = String(index);
      const message = document.createElement('span');
      message.textContent = `QA notification ${index}`;
      const undo = document.createElement('button');
      undo.type = 'button';
      undo.className = 'undo-btn';
      undo.textContent = 'Undo';
      toast.append(message, undo);
      document.body.appendChild(toast);
    }
  });
  await page.waitForFunction(() =>
    document.querySelectorAll('#taxonomyOverlayLane > [data-qa-overlay-toast]').length === 2);
  await businessText.scrollIntoViewIfNeeded();
  await businessText.focus();
  const overlayRefreshVersion = await page.evaluate(() => {
    const lane = document.getElementById('taxonomyOverlayLane');
    const previous = Number.parseInt(lane?.dataset.refreshVersion || '0', 10);
    window.TaxonomyOnboarding.refreshOverlayLane();
    return previous;
  });
  await page.waitForFunction(previous => {
    const current = Number.parseInt(
      document.getElementById('taxonomyOverlayLane')?.dataset.refreshVersion || '0', 10);
    return current > previous;
  }, overlayRefreshVersion);
  const overlayGeometry = await page.evaluate(() => {
    const lane = document.getElementById('taxonomyOverlayLane');
    const toasts = [...lane.querySelectorAll('[data-qa-overlay-toast]')];
    const rects = toasts.map(toast => toast.getBoundingClientRect());
    const overlaps = rects.length === 2
      && rects[0].left < rects[1].right && rects[0].right > rects[1].left
      && rects[0].top < rects[1].bottom && rects[0].bottom > rects[1].top;
    const undoRect = toasts[0].querySelector('.undo-btn').getBoundingClientRect();
    return {
      position: lane.dataset.position,
      toastCount: toasts.length,
      overlaps,
      undoWidth: undoRect.width,
      undoHeight: undoRect.height,
      activeUnobscured: window.TaxonomyOnboarding.isElementUnobscured(document.activeElement)
    };
  });
  assert(overlayGeometry.toastCount === 2 && !overlayGeometry.overlaps,
    `Overlay lane collision: ${JSON.stringify(overlayGeometry)}`);
  assert(overlayGeometry.undoWidth >= 44 && overlayGeometry.undoHeight >= 44,
    `Undo touch target is too small: ${JSON.stringify(overlayGeometry)}`);
  assert(overlayGeometry.activeUnobscured,
    `Focused control is obscured by overlay lane at ${overlayGeometry.position}`);
  await runAxe('overlay-lane');
  await saveState('overlay-lane');
  await page.evaluate(() => {
    document.querySelectorAll('[data-qa-overlay-toast]').forEach(toast => toast.remove());
  });
  passed('collision-safe overlay lane and touch targets');

  const operationalWasOpen = await page.locator('#operationalContextDetails').evaluate(el => el.open);
  await page.keyboard.press('Alt+Shift+O');
  await page.waitForFunction(previous =>
    document.getElementById('operationalContextDetails')?.open !== previous,
  operationalWasOpen);
  assert(await page.evaluate(() =>
    document.activeElement === document.querySelector('#operationalContextDetails > summary')),
  'Operational-context shortcut did not move focus to the disclosure');
  await page.keyboard.press('Alt+Shift+O');
  passed('expert keyboard access to primary task and operational context');

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    forcedColors: matchMedia('(forced-colors: active)').matches,
    reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches
  }));
  assert(overflow.scrollWidth <= overflow.clientWidth + 2,
    `Viewport reflow failed at ${zoom}x: ${overflow.scrollWidth} > ${overflow.clientWidth}`);
  assert(overflow.reducedMotion, 'Reduced motion preference was not active');
  assert(forcedColors ? overflow.forcedColors : true, 'Forced-colors profile was not active');
  passed(`reflow at ${zoom}x`);
  if (forcedColors) passed('forced-colors media state');

  const taskHierarchy = await page.evaluate(() => {
    const left = document.getElementById('leftPanel');
    const right = document.getElementById('rightPanel');
    const row = left?.parentElement;
    const navigation = document.getElementById('mainNavTabs');
    const visibleLinks = Array.from(navigation?.querySelectorAll('.nav-link') || [])
      .filter(link => getComputedStyle(link).display !== 'none');
    const maxLinkHeight = visibleLinks.reduce((maximum, link) =>
      Math.max(maximum, link.getBoundingClientRect().height), 0);
    return {
      viewportWidth: window.innerWidth,
      leftTop: left?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
      rightTop: right?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
      rightPrecedesLeftInDom: Boolean(right && left
        && (right.compareDocumentPosition(left) & Node.DOCUMENT_POSITION_FOLLOWING)),
      leftPrecedesRightInDom: Boolean(left && right
        && (left.compareDocumentPosition(right) & Node.DOCUMENT_POSITION_FOLLOWING)),
      taskOrder: row?.dataset.taskOrder || '',
      navigationHeight: navigation?.getBoundingClientRect().height ?? 0,
      maxLinkHeight,
      navigationScrollWidth: navigation?.scrollWidth ?? 0,
      navigationClientWidth: navigation?.clientWidth ?? 0
    };
  });
  if (taskHierarchy.viewportWidth < 992) {
    assert(taskHierarchy.rightTop <= taskHierarchy.leftTop,
      `Primary task must precede taxonomy browser at ${taskHierarchy.viewportWidth}px: `
      + `${taskHierarchy.rightTop} > ${taskHierarchy.leftTop}`);
    assert(taskHierarchy.rightPrecedesLeftInDom && taskHierarchy.taskOrder === 'primary-first',
      'Primary task must also precede the taxonomy browser in reading and focus order');
    passed('primary task precedes taxonomy browser visually and structurally');
    assert(taskHierarchy.navigationHeight <= taskHierarchy.maxLinkHeight + 6,
      `Main navigation wrapped to multiple rows: ${taskHierarchy.navigationHeight} > `
      + `${taskHierarchy.maxLinkHeight + 6}`);
    assert(taskHierarchy.navigationScrollWidth >= taskHierarchy.navigationClientWidth,
      'Main navigation must remain horizontally reachable');
    passed('single-row scrollable main navigation');
  } else {
    assert(taskHierarchy.leftPrecedesRightInDom && taskHierarchy.taskOrder === 'reference-first',
      'Desktop reading and focus order must match the left-to-right panel layout');
    passed('desktop panel reading order matches visual layout');
  }

  const expectedHttpFailure = failure => {
    if (failure.status === 503 && failure.path === '/api/analyze') return true;
    if (failure.path === '/api/proposals/from-hypothesis') {
      if (role === 'USER' && failure.status === 403) return true;
      if (role !== 'USER' && failure.status === 409) return true;
    }
    return false;
  };
  const unexpected = httpFailures.filter(failure => !expectedHttpFailure(failure));
  assert(unexpected.length === 0, `Unexpected HTTP failures: ${JSON.stringify(unexpected)}`);
  passed('expected HTTP error states only');
  assert(externalRequests.length === 0,
    `External browser requests detected: ${externalRequests.join(', ')}`);
  assert(consoleErrors.length === 0,
    `Browser console errors: ${consoleErrors.join(' | ')}`);
  passed('local assets and clean console');

  return taskMeasurements;
}
