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
  const interactive = page.locator('#interactiveMode');
  if (await interactive.isChecked()) await interactive.uncheck();
  await page.locator('#businessText').fill(
    'Provide resilient hospital communications with traceable architecture decisions.');
  await page.locator('#analyzeBtn').focus();
  await page.keyboard.press('Enter');
  await page.locator('#statusArea').waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForFunction(() => {
    const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
    return text.includes('complete') || text.includes('completed');
  }, null, { timeout: 120_000 });
  assert(await page.locator('.tax-pct').count() > 0, 'Analysis produced no scored nodes');
  passed('analysis loading and success');
  await runAxe('analysis-success');
  await saveState('analysis-success');

  await page.locator('#businessText').fill(
    'Provide resilient hospital communications with an emergency notification capability.');
  await page.locator('#businessText.stale-results').waitFor({ state: 'visible', timeout: 10_000 });
  passed('stale-result indication');

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
  passed('analysis provider error state');
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

  const expectedHttpFailure = failure => {
    if (failure.status === 503 && failure.path === '/api/analyze') return true;
    return role === 'USER' && failure.status === 403
      && failure.path === '/api/proposals/from-hypothesis';
  };
  const unexpected = httpFailures.filter(failure => !expectedHttpFailure(failure));
  assert(unexpected.length === 0, `Unexpected HTTP failures: ${JSON.stringify(unexpected)}`);
  passed('expected HTTP error states only');
  assert(externalRequests.length === 0,
    `External browser requests detected: ${externalRequests.join(', ')}`);
  assert(consoleErrors.length === 0,
    `Browser console errors: ${consoleErrors.join(' | ')}`);
  passed('local assets and clean console');
}
