import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Validate the public projection without writing identities or credentials to evidence. */
export function validateSessionSnapshot(snapshot, forbiddenValues = []) {
  assert.deepEqual(Object.keys(snapshot).sort(), [
    'scope', 'sessionCount', 'timestamp', 'truncated', 'unidentifiedSessionCount', 'userCount', 'users'
  ].sort());
  assert.equal(snapshot.scope, 'LOCAL_INSTANCE');
  assert.ok(Number.isFinite(Date.parse(snapshot.timestamp)));
  for (const key of ['userCount', 'sessionCount', 'unidentifiedSessionCount']) {
    assert.ok(Number.isSafeInteger(snapshot[key]) && snapshot[key] >= 0, `Invalid ${key}`);
  }
  assert.equal(typeof snapshot.truncated, 'boolean');
  assert.ok(Array.isArray(snapshot.users) && snapshot.users.length <= 200);
  let visibleSessions = snapshot.unidentifiedSessionCount;
  for (const user of snapshot.users) {
    assert.deepEqual(Object.keys(user).sort(), [
      'authenticationType', 'lastRequest', 'sessionCount', 'username'
    ].sort());
    assert.ok(typeof user.username === 'string' && user.username.length > 0);
    assert.ok(['LOCAL', 'OIDC'].includes(user.authenticationType));
    assert.ok(Number.isSafeInteger(user.sessionCount) && user.sessionCount > 0);
    assert.ok(Number.isFinite(Date.parse(user.lastRequest)));
    visibleSessions += user.sessionCount;
  }
  assert.ok(snapshot.sessionCount >= snapshot.userCount + snapshot.unidentifiedSessionCount);
  if (snapshot.truncated) {
    assert.equal(snapshot.users.length, 200);
    assert.ok(snapshot.userCount > 200);
    assert.ok(visibleSessions < snapshot.sessionCount);
  } else {
    assert.equal(snapshot.users.length, snapshot.userCount);
    assert.equal(visibleSessions, snapshot.sessionCount);
  }
  const serialized = JSON.stringify(snapshot);
  for (const secret of forbiddenValues) {
    assert.ok(typeof secret === 'string' && secret.length > 0);
    assert.ok(!serialized.includes(secret), 'Session projection exposed a private test value');
  }
}

/** Reuse the existing authenticated application/browser; no extra login or application start. */
export async function runBrowserSessionsAcceptance({ page, evidence, outputDir,
  baseUrl, adminUsername, adminPassword }) {
  const { navigateToPage } = await import('./ui-role-fixtures.mjs');
  assert.notEqual(adminPassword, adminUsername, 'Use distinct account/password test values');
  const base = new URL(`${baseUrl.replace(/\/$/, '')}/`);
  const apiUrl = new URL('api/admin/sessions', base);
  const sessionUrl = new URL('admin/sessions', base);
  const cases = [];
  for (const locale of ['en', 'de']) {
    let pageInventoryRequests = 0;
    const observe = request => {
      if (new URL(request.url()).pathname === apiUrl.pathname) pageInventoryRequests += 1;
    };
    page.on('request', observe);
    try {
      const home = new URL(base);
      home.searchParams.set('lang', locale);
      await page.goto(home.href, { waitUntil: 'domcontentloaded' });
      await navigateToPage(page, 'admin');
      const health = page.locator('#healthDashboard');
      if (!(await health.evaluate(element => element.open))) {
        await health.locator(':scope > summary').click();
      }
      const link = page.locator('#browserSessionsLink');
      await link.waitFor({ state: 'visible' });
      const expected = new URL(sessionUrl);
      expected.searchParams.set('lang', locale);
      assert.equal(await link.getAttribute('href'), expected.href);
      assert.equal(pageInventoryRequests, 0, 'Navigation must not eagerly fetch user identities');
      await link.focus();
      const [opened] = await Promise.all([
        page.waitForResponse(response => response.url() === expected.href
          && response.request().isNavigationRequest()),
        page.waitForEvent('domcontentloaded'),
        link.press('Enter')
      ]);
      assert.equal(opened.status(), 200);
      assert.match(opened.headers()['cache-control'] || '', /\bno-store\b/);
      await page.waitForLoadState('domcontentloaded');
      const main = page.locator('#sessionInventory');
      await main.waitFor({ state: 'visible' });
      assert.equal(await page.locator('html').getAttribute('lang'), locale);
      assert.equal(await main.locator('h1').innerText(), locale === 'de'
        ? 'Angemeldete Sitzungen' : 'Signed-in sessions');
      const cookies = await page.context().cookies(base.href);
      const privateValues = [adminPassword, ...cookies.filter(cookie => cookie.name === 'JSESSIONID')
        .map(cookie => cookie.value)];
      const readInventory = async () => {
        const response = await page.context().request.get(apiUrl.href, {
          headers: { Accept: 'application/json' }
        });
        assert.equal(response.status(), 200);
        assert.match(response.headers()['cache-control'] || '', /\bno-store\b/);
        const snapshot = await response.json();
        validateSessionSnapshot(snapshot, privateValues);
        return snapshot;
      };
      const snapshot = await readInventory();
      assert.equal(snapshot.truncated, false, 'Acceptance fixture must fit in the visible inventory');
      assert.ok(snapshot.users.some(user => user.username === adminUsername));
      assert.equal(Number(await page.locator('#sessionsUserCount').innerText()), snapshot.userCount);
      assert.equal(Number(await page.locator('#sessionsSessionCount').innerText()), snapshot.sessionCount);
      assert.equal(await main.locator('article').count(), snapshot.users.length);
      for (const user of snapshot.users) {
        assert.equal(await main.getByRole('heading', { name: user.username, exact: true }).count(), 1);
      }
      const rendered = await main.innerText();
      for (const secret of privateValues) {
        assert.ok(!rendered.includes(secret), 'Session page exposed a private test value');
      }
      const measuredBefore = await main.locator('time').first().getAttribute('datetime');
      const refresh = page.locator('#sessionsRefresh');
      await refresh.scrollIntoViewIfNeeded();
      await refresh.focus();
      assert.equal(await refresh.evaluate(element => {
        const r = element.getBoundingClientRect();
        return element.contains(document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2));
      }), true, 'Session refresh is covered');
      const [refreshed] = await Promise.all([
        page.waitForResponse(response => response.url() === expected.href
          && response.request().isNavigationRequest()),
        page.waitForEvent('domcontentloaded'),
        refresh.press('Enter')
      ]);
      assert.equal(refreshed.status(), 200);
      assert.match(refreshed.headers()['cache-control'] || '', /\bno-store\b/);
      await page.waitForLoadState('domcontentloaded');
      assert.ok(Date.parse(await main.locator('time').first().getAttribute('datetime'))
        >= Date.parse(measuredBefore));
      const next = await readInventory();
      assert.equal(next.userCount, snapshot.userCount);
      assert.equal(next.sessionCount, snapshot.sessionCount, 'Reading/refreshing must not register logins');
      assert.equal(pageInventoryRequests, 0, 'Server-rendered page must not start identity polling');
      await evidence.runAxe(`browser-sessions-${locale}`);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth
        <= document.documentElement.clientWidth + 2), true, 'Session page must reflow');
      const dimensions = await main.boundingBox();
      assert.ok(dimensions && dimensions.width > 0 && dimensions.height < 8000);
      const screenshot = `browser-sessions-${locale}.png`;
      await main.screenshot({ path: path.join(outputDir, screenshot), animations: 'disabled' });
      cases.push({ locale, screenshot, dimensions, scope: next.scope, users: next.userCount,
        sessions: next.sessionCount, keyboardOperated: true, refreshed: true,
        automaticInventoryRequests: pageInventoryRequests });
    } finally {
      page.off('request', observe);
    }
  }
  await writeFile(path.join(outputDir, 'browser-sessions.json'),
    `${JSON.stringify({ cases }, null, 2)}\n`, 'utf8');
  return cases;
}
