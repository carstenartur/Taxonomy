import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';

// Check the observed environment, not an assumed profile or database brand.
export function validateSystemSnapshot(snapshot) {
  assert.equal(snapshot.database.status, 'AVAILABLE');
  assert.equal(snapshot.database.versionSource, 'DATABASE_QUERY');
  assert.ok(snapshot.database.product && snapshot.database.version);
  assert.ok(Number.isInteger(snapshot.runtime.availableProcessors)
    && snapshot.runtime.availableProcessors > 0);
  assert.ok(snapshot.instanceId && Number.isFinite(Date.parse(snapshot.timestamp)));
  const {identity, engineStart} = snapshot.database;
  assert.equal(identity.status, 'AVAILABLE');
  assert.equal(identity.source, 'DATABASE_QUERY');
  for (const key of ['user', 'sessionUser', 'database', 'schema']) {
    assert.ok(typeof identity[key] === 'string' && identity[key].length > 0);
  }
  if (engineStart.status === 'AVAILABLE') {
    assert.equal(engineStart.source, 'DATABASE_QUERY');
    assert.ok(['UTC', 'SERVER_LOCAL_TIME'].includes(engineStart.timeBasis));
    if (engineStart.timeBasis === 'UTC') {
      assert.match(engineStart.value, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/);
      assert.ok(Number.isFinite(Date.parse(engineStart.value)));
    } else {
      assert.match(engineStart.value, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?$/);
    }
  } else {
    assert.ok(['NOT_SUPPORTED', 'NOT_PERMITTED', 'UNAVAILABLE'].includes(engineStart.status));
    assert.equal(engineStart.value, null);
    assert.equal(engineStart.timeBasis, null);
    assert.equal(engineStart.source, 'UNAVAILABLE');
  }
  const { storage, lifetime, schemaAction, warnings } = snapshot.database;
  assert.notEqual(storage, 'UNKNOWN');
  assert.notEqual(schemaAction, 'UNKNOWN');
  if (storage === 'IN_MEMORY') {
    assert.ok(['APPLICATION_PROCESS', 'DATABASE_PROCESS'].includes(lifetime));
    assert.ok(warnings.includes(`IN_MEMORY_${lifetime}`));
  }
  if (['CREATE', 'CREATE_DROP', 'DROP', 'TRUNCATE'].includes(schemaAction)) {
    assert.ok(warnings.includes('DESTRUCTIVE_SCHEMA_ACTION'));
  }
  if (['FILE_BACKED', 'SERVER_MANAGED'].includes(storage)) {
    assert.ok(warnings.includes('STORAGE_DURABILITY_UNVERIFIED'));
  }
  for (const disk of snapshot.disks) {
    if (disk.status === 'AVAILABLE') {
      assert.ok(Number.isFinite(disk.totalBytes) && disk.totalBytes > 0);
      assert.ok(Number.isFinite(disk.usableBytes) && disk.usableBytes >= 0);
    } else {
      assert.equal(disk.totalBytes, null);
      assert.equal(disk.usableBytes, null);
    }
  }
}

/** Confirm the previous editing task before a deliberate hard navigation. */
export async function settleDraftBeforeNavigation(page) {
  await page.evaluate(async () => {
    const session = window.TaxonomyAnalysisSession;
    const before = session?.state?.();
    if (!session?.saveNow || !before?.workspaceId || !before.ready
        || before.restoring || before.conflict) {
      const runtime = window.__TaxonomyAnalysisSessionContext?.runtime || {};
      const context = window.TaxonomyContextBar?.getCurrentContext?.() || {};
      const version = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
      const scope = value => typeof value === 'string' ? value.slice(0, 256) : null;
      const trace = Array.isArray(runtime.draftMutationTrace) ? runtime.draftMutationTrace : [];
      const acknowledgements = Array.isArray(runtime.acknowledgedDraftWrites)
        ? runtime.acknowledgedDraftWrites : [];
      const diagnostics = {
        workspaceId: scope(before?.workspaceId), contextId: scope(context.contextId), branch: scope(context.branch),
        version: version(before?.version), lastAcknowledgedVersion: version(acknowledgements.at(-1)?.version),
        ready: before?.ready === true, restoring: before?.restoring === true, conflict: before?.conflict === true,
        initialized: runtime.initialized === true, workspaceResolved: runtime.workspaceResolved === true,
        sourceReady: runtime.sourceReady !== false,
        serverUnconfirmed: runtime.serverUnconfirmed === true, draftDecisionPending: runtime.draftDecisionPending === true,
        saveInFlight: Boolean(runtime.saveInFlight), saveQueued: runtime.saveQueued === true,
        resetting: runtime.resetting === true, invalidating: runtime.invalidating === true,
        analysisGeneration: version(runtime.analysisGeneration), draftGeneration: version(runtime.draftGeneration),
        recentMutations: trace.slice(-4).map(entry => ({
          mutationId: version(entry?.mutationId),
          phase: ['started', 'succeeded', 'failed', 'conflict', 'conflict-read', 'retry', 'reset-retry', 'reconciled']
            .includes(entry?.phase) ? entry.phase : null,
          method: ['GET', 'PUT', 'POST', 'DELETE'].includes(entry?.method) ? entry.method : null,
          expectedVersion: version(entry?.expectedVersion), currentVersion: version(entry?.currentVersion)
        }))
      };
      throw new Error('Analysis draft is not ready for navigation; draftDiagnostics=' + JSON.stringify(diagnostics));
    }
    if (await session.saveNow() !== true) {
      throw new Error('Analysis draft was not saved before navigation');
    }
    const after = session.state();
    if (after.workspaceId !== before.workspaceId || !after.ready
        || after.restoring || after.conflict) {
      throw new Error('Analysis draft or workspace changed during navigation preparation');
    }
  });
}

export const settleDraftBeforeLocaleNavigation = settleDraftBeforeNavigation;

/** Continue in the existing authenticated browser/app; no extra service or LLM calls. */
export async function runSystemInformationAcceptance({
  page, evidence, outputDir, onLocaleNavigationStart, onLocaleNavigationEnd, onLocaleVerified
}) {
  const { navigateToPage } = await import('./ui-role-fixtures.mjs');
  const cases = [];
  for (const locale of ['en', 'de']) {
    let requests = 0;
    const isSystem = url => new URL(url).pathname.endsWith('/api/admin/system-information');
    const observe = request => { if (isSystem(request.url())) requests += 1; };
    page.on('request', observe);
    try {
      const url = new URL(page.url());
      url.searchParams.set('lang', locale);
      await settleDraftBeforeLocaleNavigation(page);
      await onLocaleNavigationStart?.(locale);
      let navigationSucceeded = false;
      try {
        await page.goto(url.toString(), { waitUntil: 'domcontentloaded' });
        navigationSucceeded = true;
      } finally {
        await onLocaleNavigationEnd?.(locale, navigationSucceeded);
      }
      await page.evaluate(() => window.TaxonomyI18n?.ready?.());
      await navigateToPage(page, 'admin');
      try {
        await page.waitForFunction(() => {
          const badge = document.getElementById('aiStatusBadge');
          const value = badge?.textContent?.trim() || '';
          const unknown = window.TaxonomyI18n?.t?.('browse.ai.badge.unknown') || '';
          return Boolean(value
            && value !== 'browse.ai.badge.unknown'
            && unknown
            && value !== unknown);
        }, null, { timeout: 20_000 });
      } catch (cause) {
        throw new Error('AI status bootstrap must settle after locale navigation', { cause });
      }
      // Only a fresh, rendered destination state can reconcile cancellation of
      // the old document's periodic reads. HTTP failures remain audit blockers.
      const operationalBranchHandle = await page.waitForFunction(() => {
        const context = window.TaxonomyContextBar?.getCurrentContext?.();
        const contextBar = document.querySelector('#contextBar[data-context-rendered="true"]');
        const gitBranch = document.querySelector('#gitStatusBar .git-branch');
        return context?.branch && contextBar
          && gitBranch?.textContent?.trim() === context.branch
          && !document.querySelector('#gitStatusBar .dot.error') ? context.branch : null;
      }, null, { timeout: 20_000 });
      const operationalBranch = await operationalBranchHandle.jsonValue();
      await operationalBranchHandle.dispose();
      const panel = page.locator('#systemInformation');
      await panel.waitFor({ state: 'attached' });
      assert.equal(await panel.evaluate(element => element.open), false);
      assert.equal(requests, 0, 'system information must be loaded on demand');
      const health = page.locator('#healthDashboard');
      if (!(await health.evaluate(element => element.open))) {
        await health.locator(':scope > summary').click();
      }
      const heading = panel.locator(':scope > summary');
      assert.equal(await heading.textContent(), locale === 'de'
        ? 'Server und Datenhaltung' : 'Server and data persistence');
      await heading.focus();
      const firstResponse = page.waitForResponse(response => isSystem(response.url()));
      await page.keyboard.press('Enter');
      const response = await firstResponse;
      assert.equal(response.status(), 200);
      assert.match(response.headers()['cache-control'] || '', /\bno-store\b/);
      const snapshot = await response.json();
      validateSystemSnapshot(snapshot);
      // Read the DOM state directly: idle WebKit pages can suspend animation-frame
      // polling even after the HTTP response has rendered successfully.
      await page.locator('#systemInformationContent[aria-busy="false"]')
        .waitFor({ state: 'attached' });
      const content = page.locator('#systemInformationContent');
      const rendered = await content.innerText();
      assert.ok(rendered.includes(snapshot.database.product));
      assert.ok(rendered.includes(snapshot.database.version));
      for (const key of ['user', 'sessionUser', 'database', 'schema']) {
        assert.ok(rendered.includes(snapshot.database.identity[key]));
      }
      const startKey = snapshot.database.engineStart.status === 'AVAILABLE'
        ? `system.timeBasis.${snapshot.database.engineStart.timeBasis}`
        : `system.probe.${snapshot.database.engineStart.status}`;
      const startDescription = await page.evaluate(key => window.TaxonomyI18n.t(key), startKey);
      assert.ok(startDescription && startDescription !== startKey);
      assert.ok(rendered.includes(startDescription));
      const translations = await page.evaluate(warnings => warnings.map(warning =>
        window.TaxonomyI18n.t(`system.warning.${warning}`)), snapshot.database.warnings);
      for (const warning of translations) {
        assert.ok(warning && !warning.startsWith('system.warning.'));
        assert.ok(rendered.includes(warning));
      }
      assert.equal(await content.locator('.alert').count(), snapshot.database.warnings.length);
      assert.equal(requests, 1, 'opening the section must issue exactly one read');

      const refresh = page.locator('#systemInformationRefresh');
      await refresh.scrollIntoViewIfNeeded();
      await refresh.focus();
      assert.equal(await refresh.evaluate(element => {
        const r = element.getBoundingClientRect();
        return !element.disabled && element.contains(document.elementFromPoint(
          r.left + r.width / 2, r.top + r.height / 2));
      }), true, 'refresh must not be covered by another control');
      const refreshResponse = page.waitForResponse(result => isSystem(result.url()));
      await page.keyboard.press('Enter');
      const refreshed = await refreshResponse;
      assert.equal(refreshed.status(), 200);
      assert.match(refreshed.headers()['cache-control'] || '', /\bno-store\b/);
      const next = await refreshed.json();
      validateSystemSnapshot(next);
      assert.equal(next.instanceId, snapshot.instanceId);
      assert.ok(Date.parse(next.timestamp) >= Date.parse(snapshot.timestamp));
      await page.locator('#systemInformationContent[aria-busy="false"]')
        .waitFor({ state: 'attached' });
      assert.equal(requests, 2, 'manual refresh must not introduce polling or duplicate reads');
      await evidence.runAxe(`system-information-${locale}`);
      const dimensions = await panel.boundingBox();
      assert.ok(dimensions && dimensions.width > 0 && dimensions.height < 8000);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth
        <= document.documentElement.clientWidth + 2), true, 'system information must reflow');
      // Two bounded, focused images, rather than another full application-page capture.
      const screenshot = `system-information-${locale}.png`;
      await panel.screenshot({ path: path.join(outputDir, screenshot), animations: 'disabled' });
      await onLocaleVerified?.(locale);
      cases.push({ locale, screenshot, dimensions, requests,
        databaseVersionSource: next.database.versionSource, storage: next.database.storage,
        databaseIdentitySource: next.database.identity.source,
        databaseStartStatus: next.database.engineStart.status, databaseStartTimeBasis: next.database.engineStart.timeBasis,
        warnings: next.database.warnings, refreshed: true, keyboardOperated: true,
        aiStatusSettled: true, operationalStatusSettled: true, operationalBranch });
    } finally {
      page.off('request', observe);
    }
  }
  await writeFile(path.join(outputDir, 'system-information.json'),
    `${JSON.stringify({ cases }, null, 2)}\n`, 'utf8');
  return cases;
}
