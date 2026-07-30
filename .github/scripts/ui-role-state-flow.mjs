import { csrfJson } from './ui-role-fixtures.mjs';

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

export async function runRoleStateFlow({
  page, role, zoom, forcedColors, checks, httpFailures,
  externalRequests, consoleErrors, evidence
}) {
  const passed = name => checks.push(name);
  const { runAxe, saveState } = evidence;
  const taskStartedAt = Date.now();
  const taskMeasurements = {
    roleTask: role === 'ADMIN' ? 'diagnose availability'
      : role === 'ARCHITECT' ? 'review relation and continue architecture work'
        : 'analyze a requirement',
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
    secondaryToolsCollapsedByDefault: false
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

  // Reused applications can complete a warm mock analysis before Playwright's next
  // wait begins. Observe the real feedback surface before activation and verify the
  // final visible score state independently.
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
  // The final model and rendered badges remain authoritative even when the
  // transient completion message has already been cleared.
  await page.waitForFunction(() => {
    const scores = window.TaxonomyState?.currentScores;
    return scores && Object.keys(scores).length > 0
      && document.querySelectorAll('.tax-pct').length > 0;
  }, null, { timeout: 120_000 });
  await page.waitForFunction(() =>
    document.querySelector('#taskStageReview[data-state="current"]')
      && !document.getElementById('taskNextAction')?.disabled,
  null, { timeout: 10_000 });
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
      nextActionText: nextAction?.textContent?.trim() || ''
    };
  });
  assert(completedTaskState.currentStage === 'taskStageReview',
    `Completed analysis stage was ${completedTaskState.currentStage}, expected review`);
  assert(completedTaskState.nextActionVisible && completedTaskState.nextActionText,
    'Completed analysis has no visible contextual next action');
  taskMeasurements.taskCompleted = true;
  taskMeasurements.timeToTaskCompletionMs = Date.now() - taskStartedAt;
  taskMeasurements.nextActionInsideInitialViewport = completedTaskState.nextActionInsideViewport;
  passed('analysis loading, success and contextual next action');
  await runAxe('analysis-success');
  await saveState('analysis-success');

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

  const proposal = await csrfJson(page, '/api/proposals/from-hypothesis', {
    body: { sourceCode: 'BP', targetCode: 'BR', relationType: 'RELATED_TO',
      confidence: 0.72, rationale: 'Role acceptance test' }
  });
  if (role === 'USER') {
    assert(proposal.status === 403, `USER proposal mutation returned ${proposal.status}, expected 403`);
    passed('user proposal mutation forbidden');
  } else {
    assert([200, 409].includes(proposal.status),
      `${role} proposal creation returned ${proposal.status}`);
    if (proposal.status === 200 && proposal.json?.id) {
      const accepted = await csrfJson(page, `/api/proposals/${proposal.json.id}/accept`, { body: {} });
      assert(accepted.status === 200, `Proposal acceptance returned ${accepted.status}`);
    }
    passed('architectural proposal review');
  }
  await runAxe('role-operation-feedback');
  await saveState('role-operation-feedback');

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
  // Bootstrap's lg columns become the desktop two-column layout at exactly 992px.
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
      // Reused role applications intentionally preserve accepted proposals. A
      // later scenario may therefore receive the already-supported idempotency
      // conflict instead of creating a duplicate proposal.
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
