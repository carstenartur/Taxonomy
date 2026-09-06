import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const mediaType = 'application/vnd.openxmlformats-officedocument.wordprocessingml.template';
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

/** Continue the existing disposable local-edit session; do not start another application or browser. */
export async function verifyRestore({ context, page, expect, baseUrl, templateId, language,
  outputDir, originalRevision, expectedRevision, originalFile, concurrentFile, csrf }) {
  const base = new URL(baseUrl);
  assert.ok(['http:', 'https:'].includes(base.protocol));
  assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(base.hostname));
  assert.equal(base.username + base.password + base.search + base.hash, '');
  assert.match(templateId, /^qa-local-[a-f0-9]{32}$/);
  for (const revision of [originalRevision, expectedRevision]) assert.match(revision, /^[a-f0-9]{40}$/);
  const endpoint = suffix => baseUrl.replace(/\/+$/, '') + suffix;
  const detailUrl = endpoint('/admin/document-templates/' + templateId);
  const restoreUrl = detailUrl + '/restore';
  const api = endpoint('/api/admin/document-templates/' + templateId);
  const directory = path.join(outputDir, 'restore');
  await mkdir(directory, { recursive: true });
  const evidence = { schemaVersion: 1, templateId, language, originalRevision, expectedRevision,
    posts: [], screenshots: [], error: null };
  const observedErrors = [];
  const observe = request => {
    if (request.method() !== 'POST' || request.url().split('?')[0] !== restoreUrl) return;
    try {
      const fields = new URLSearchParams(request.postData() || '');
      // Retain preconditions, never the CSRF token or the complete form body.
      evidence.posts.push({ revision: fields.get('revision'), expectedHead: fields.get('expectedHead'),
        confirmed: fields.get('confirmed'), hasCsrf: Boolean(fields.get('_csrf')) });
    } catch (error) {
      observedErrors.push(error.message || String(error));
    }
  };
  page.on('request', observe);
  try {
    const before = await current();
    assert.equal(before.revision, expectedRevision);
    assert.equal((await history()).length, 2);
    const original = await readFile(originalFile);
    // Enter through the real history action, not a hand-built confirmation URL.
    const historyRow = page.locator('section[aria-labelledby="historyHeading"] tr')
      .filter({ hasText: originalRevision.slice(0, 12) });
    const restoreLink = historyRow.locator('a[href*="/restore?"]');
    await expect(restoreLink).toHaveCount(1);
    const oldUrl = sameOrigin(await restoreLink.getAttribute('href'), '/restore');
    assert.equal(oldUrl.searchParams.get('revision'), originalRevision);
    assert.equal(oldUrl.searchParams.get('expectedHead'), expectedRevision);
    await restoreLink.focus();
    await expect(restoreLink).toBeFocused();
    await Promise.all([page.waitForURL(url => url.href === oldUrl.href), page.keyboard.press('Enter')]);
    await assertConfirmation(expectedRevision, false);
    await expect(page.locator('#restoreHeading')).toHaveText(language === 'de'
      ? 'Wiederherstellung bestätigen' : 'Confirm restoration');
    assert.equal(await page.locator('#restoreConfirmationForm').evaluate(form => form.checkValidity()), false);
    const downloadUrl = new URL(await page.locator('#restoreTargetDownload').getAttribute('href'), page.url());
    assert.equal(downloadUrl.origin, base.origin);
    assert.equal(downloadUrl.pathname, new URL(api + '/download').pathname);
    assert.equal(downloadUrl.searchParams.get('revision'), originalRevision);
    const [download] = await Promise.all([
      page.waitForEvent('download'), page.locator('#restoreTargetDownload').click()
    ]);
    assert.equal(await download.failure(), null);
    const targetFile = path.join(directory, 'selected-original.dotx');
    await download.saveAs(targetFile);
    assert.deepEqual(await readFile(targetFile), original);
    const proposed = sameOrigin(await page.locator('#restoreCompare').getAttribute('href'), '');
    assert.equal(proposed.searchParams.get('from'), expectedRevision);
    assert.equal(proposed.searchParams.get('to'), originalRevision);
    evidence.partComparisons = await compareContents(proposed, before.bytes, original);
    assert.deepEqual(await current(), before);
    assert.equal((await history()).length, 2);
    await page.goto(oldUrl.href);
    await assertConfirmation(expectedRevision, false);
    await capture('confirmation');

    // Checkbox and submission are operated with Space/Tab/Enter, without synthetic submit events.
    await confirmWithKeyboard();
    const concurrent = await context.request.put(api + '?displayName=Local-edit-QA-' + language, {
      data: await readFile(concurrentFile),
      headers: { 'Content-Type': mediaType, 'If-Match': '"' + expectedRevision + '"', [csrf.name]: csrf.value }
    });
    assert.equal(concurrent.status(), 201);
    const concurrentTag = concurrent.headers().etag;
    assert.match(concurrentTag || '', /^"[a-f0-9]{40}"$/);
    const concurrentRevision = concurrentTag.slice(1, -1);
    assert.notEqual(concurrentRevision, expectedRevision);
    evidence.concurrentRevision = concurrentRevision;
    const winner = await current();
    assert.equal(winner.revision, concurrentRevision);
    const winnerHistory = await history();
    assert.equal(winnerHistory.length, 3);
    const [conflict] = await Promise.all([
      page.waitForResponse(isRestore), page.keyboard.press('Enter')
    ]);
    assert.equal(conflict.status(), 412);
    await assertConfirmation(expectedRevision, true, concurrentRevision);
    assert.deepEqual(await current(), winner);
    assert.deepEqual(await history(), winnerHistory);
    await capture('conflict');

    // A bookmarkable GET stays stale and cannot silently adopt a more recent precondition.
    const reload = await page.goto(oldUrl.href);
    assert.equal(reload.status(), 200);
    await assertConfirmation(expectedRevision, true, concurrentRevision);
    const compare = sameOrigin(await page.locator('#restoreCompareConcurrent').getAttribute('href'), '');
    assert.equal(compare.searchParams.get('from'), expectedRevision);
    assert.equal(compare.searchParams.get('to'), concurrentRevision);
    const renewed = sameOrigin(await page.locator('#restoreReviewCurrent').getAttribute('href'), '/restore');
    assert.equal(renewed.searchParams.get('revision'), originalRevision);
    assert.equal(renewed.searchParams.get('expectedHead'), concurrentRevision);
    await capture('reloaded-conflict');
    await page.locator('#restoreReviewCurrent').focus();
    await expect(page.locator('#restoreReviewCurrent')).toBeFocused();
    await Promise.all([page.waitForURL(url => url.href === renewed.href), page.keyboard.press('Enter')]);
    await assertConfirmation(concurrentRevision, false);
    await expect(page.locator('#restoreConfirmed')).not.toBeChecked();
    await capture('renewed-confirmation');
    await confirmWithKeyboard();
    const [receipt] = await Promise.all([
      page.waitForResponse(isRestore),
      page.waitForURL(url => url.pathname === new URL(detailUrl).pathname),
      page.keyboard.press('Enter')
    ]);
    assert.equal(receipt.status(), 302);
    assert.equal(new URL(receipt.headers().location, restoreUrl).pathname, new URL(detailUrl).pathname);
    await expect(page.locator('#restoreSuccess')).toBeVisible();
    const successText = await page.locator('#restoreSuccess').innerText();
    assert.ok(successText.includes(language === 'de' ? 'wiederhergestellt' : 'Restored'));
    const revisions = successText.match(/\b[a-f0-9]{40}\b/g) || [];
    assert.equal(revisions.length, 1, 'The flash receipt must identify one actual restore commit');
    const restored = await current();
    assert.equal(restored.revision, revisions[0]);
    assert.ok(![originalRevision, expectedRevision, concurrentRevision].includes(restored.revision));
    assert.deepEqual(restored.bytes, original);
    const after = await history();
    assert.equal(after.length, 4);
    for (const revision of [originalRevision, expectedRevision, concurrentRevision, restored.revision]) {
      assert.equal(after.filter(entry => entry.commitId === revision).length, 1);
    }
    const historicalWinner = await context.request.get(api + '/download?revision=' + concurrentRevision);
    assert.equal(historicalWinner.status(), 200);
    assert.deepEqual(await historicalWinner.body(), winner.bytes);
    await capture('restored');
    assert.deepEqual(observedErrors, []);
    assert.deepEqual(evidence.posts, [expectedRevision, concurrentRevision].map(expectedHead => ({
      revision: originalRevision, expectedHead, confirmed: 'true', hasCsrf: true
    })));
    Object.assign(evidence, { restoredRevision: restored.revision, historyEntries: after.length,
      conflictStatus: conflict.status(), originalSha256: sha256(original),
      restoredSha256: sha256(restored.bytes), winnerSha256: sha256(winner.bytes),
      staleReloadPreservesSelection: true, keyboardConfirmationAndSubmission: true,
      previousVersionsPreserved: true });
    assert.equal(evidence.screenshots.length, 5);
    return evidence;
  } catch (error) {
    evidence.error = error.stack || String(error);
    throw error;
  } finally {
    page.off('request', observe);
    await writeFile(path.join(directory, 'restore-evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
  }

  async function compareContents(proposed, editedBytes, originalBytes) {
    await page.locator('#restoreCompare').focus();
    await Promise.all([page.waitForURL(proposed.href), page.keyboard.press('Enter')]);
    const results = [];
    // Start with the reversed comparison so each submitted selection changes the URL.
    for (const pair of [
      { from: originalRevision, to: expectedRevision, kind: 'ADDED', oldBytes: originalBytes, newBytes: editedBytes },
      { from: expectedRevision, to: originalRevision, kind: 'DELETED', oldBytes: editedBytes, newBytes: originalBytes }
    ]) {
      await page.locator('#fromRevision').selectOption(pair.from);
      await page.locator('#toRevision').selectOption(pair.to);
      const submit = page.locator('section[aria-labelledby="compareHeading"] button[type="submit"]');
      await submit.focus();
      await Promise.all([
        page.waitForURL(url => url.searchParams.get('from') === pair.from && url.searchParams.get('to') === pair.to),
        page.keyboard.press('Enter')
      ]);
      const link = page.locator('section[aria-labelledby="diffHeading"] tr')
        .filter({ hasText: 'word/document.xml' }).locator('.part-comparison-link');
      await expect(link).toHaveCount(1);
      const target = sameOrigin(await link.getAttribute('href'), '/compare-part');
      assert.equal(target.searchParams.get('from'), pair.from);
      assert.equal(target.searchParams.get('to'), pair.to);
      assert.equal(target.searchParams.get('partPath'), 'word/document.xml');
      await link.focus();
      await expect(link).toBeFocused();
      await Promise.all([page.waitForURL(target.href), page.keyboard.press('Enter')]);
      await expect(page.locator('#partComparisonHeading')).toHaveText(language === 'de'
        ? 'Vergleich des Paketbestandteils' : 'Package-part comparison');
      await expect(page.locator('#partBeforeRevision')).toHaveText(pair.from);
      await expect(page.locator('#partAfterRevision')).toHaveText(pair.to);
      await expect(page.locator('#partDiffTable tr[data-kind="' + pair.kind + '"]')
        .filter({ hasText: 'accepted-local-edit-' + language })).toHaveCount(1);
      // Inspect the actual rendered fixture before accepting the comparison UX.
      const changed = page.locator('#partDiffTable tr[data-kind="' + pair.kind + '"]')
        .filter({ hasText: 'accepted-local-edit-' + language });
      await expect(changed).toBeVisible();
      const disclosures = page.locator('#partDiffTable details.diff-context');
      assert.ok(await disclosures.count() > 0, 'The representative fixture must fold its long unchanged context');
      await expect(page.locator('#partDiffTable details[open]')).toHaveCount(0);
      const allRows = await page.locator('#partDiffTable tr[data-kind]').count();
      const visibleRows = await page.locator('#partDiffTable tr[data-kind]:visible').count();
      assert.ok(visibleRows < allRows / 2, 'Unchanged XML must not dominate the initial view');
      const pageHeight = await page.evaluate(() => document.documentElement.scrollHeight);
      const disclosure = disclosures.first();
      const summary = disclosure.locator('summary');
      const contextRow = disclosure.locator('tr[data-kind="CONTEXT"]').first();
      await expect(contextRow).not.toBeVisible();
      await summary.focus();
      await expect(summary).toBeFocused();
      await page.keyboard.press('Enter');
      await expect(contextRow).toBeVisible();
      await expect(summary).toBeFocused();
      await page.keyboard.press('Enter');
      await expect(contextRow).not.toBeVisible();
      await expect(changed).toBeVisible();
      assert.equal(await page.locator('#partDiffTable tr[data-kind]').count(), allRows,
        'Folding is presentation only; no source row is discarded');
      assert.ok((await page.locator('#partDiffTable code').allTextContents()).some(text => text.includes('<w:')));
      await expect(page.locator('#partDiffTable script')).toHaveCount(0);
      for (const [selector, revision, bytes] of [
        ['#partBeforeDownload', pair.from, pair.oldBytes], ['#partAfterDownload', pair.to, pair.newBytes]
      ]) {
        const url = new URL(await page.locator(selector).getAttribute('href'), page.url());
        assert.equal(url.origin, base.origin);
        assert.equal(url.pathname, new URL(api + '/download').pathname);
        assert.equal(url.searchParams.get('revision'), revision);
        const response = await context.request.get(url.href);
        assert.equal(response.status(), 200);
        assert.deepEqual(await response.body(), bytes);
        const control = page.locator(selector);
        await control.scrollIntoViewIfNeeded();
        assert.ok(await control.evaluate(element => {
          const rect = element.getBoundingClientRect();
          const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
          return hit === element || element.contains(hit);
        }));
      }
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2));
      await page.evaluate(() => scrollTo(0, 0));
      const screenshot = 'part-comparison-' + pair.kind.toLowerCase() + '.png';
      await page.screenshot({ path: path.join(directory, screenshot), fullPage: true });
      results.push({ from: pair.from, to: pair.to, markerKind: pair.kind, screenshot,
        pageHeight, allRows, visibleRows, contextToggleWithKeyboard: true,
        immutableDownloadsVerified: true, keyboardNavigation: true, horizontalOverflow: false });
      const back = sameOrigin(await page.locator('#partComparisonBack').getAttribute('href'), '');
      await page.locator('#partComparisonBack').focus();
      await Promise.all([page.waitForURL(back.href), page.keyboard.press('Enter')]);
      await expect(page.locator('#fromRevision')).toHaveValue(pair.from);
      await expect(page.locator('#toRevision')).toHaveValue(pair.to);
    }
    return results;
  }

  function sameOrigin(href, suffix) {
    assert.ok(href);
    const url = new URL(href, page.url());
    assert.equal(url.origin, base.origin);
    assert.equal(url.pathname, new URL(detailUrl + suffix).pathname);
    return url;
  }
  function isRestore(response) {
    return response.request().method() === 'POST' && response.url().split('?')[0] === restoreUrl;
  }
  async function current() {
    const response = await context.request.get(api + '/download');
    assert.equal(response.status(), 200);
    assert.match(response.headers().etag || '', /^"[a-f0-9]{40}"$/);
    return { revision: response.headers().etag.slice(1, -1), bytes: await response.body() };
  }
  async function history() {
    const response = await context.request.get(api + '/history');
    assert.equal(response.status(), 200);
    return response.json();
  }
  async function assertConfirmation(expected, stale, currentRevision = expected) {
    await expect(page.locator('#restoreTargetRevision')).toHaveText(originalRevision);
    await expect(page.locator('#restoreExpectedRevision')).toHaveText(expected);
    await expect(page.locator('#restoreCurrentRevision')).toHaveText(currentRevision);
    await expect(page.locator('#restoreConflict')).toHaveCount(stale ? 1 : 0);
    await expect(page.locator('#restoreConfirmationForm')).toHaveCount(stale ? 0 : 1);
    await expect(page.locator('#restoreSubmit')).toHaveCount(stale ? 0 : 1);
    if (!stale) {
      await expect(page.locator('input[name="revision"]')).toHaveValue(originalRevision);
      await expect(page.locator('input[name="expectedHead"]')).toHaveValue(expected);
    }
  }
  async function confirmWithKeyboard() {
    await page.locator('#restoreConfirmed').focus();
    await expect(page.locator('#restoreConfirmed')).toBeFocused();
    await page.keyboard.press('Space');
    await expect(page.locator('#restoreConfirmed')).toBeChecked();
    await page.keyboard.press('Tab');
    await expect(page.locator('#restoreSubmit')).toBeFocused();
  }
  async function capture(name) {
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2),
      'Restore page must not overflow the viewport');
    for (const selector of ['#restoreTargetDownload', '#restoreCompare', '#restoreConfirmed',
      '#restoreSubmit', '#restoreCompareConcurrent', '#restoreReviewCurrent']) {
      const control = page.locator(selector);
      if (!(await control.count())) continue;
      await expect(control).toBeVisible();
      await control.scrollIntoViewIfNeeded();
      assert.ok(await control.evaluate(element => {
        const rect = element.getBoundingClientRect();
        const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
        return hit === element || element.contains(hit);
      }), 'Restore control is obscured: ' + selector);
    }
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({ path: path.join(directory, name + '.png'), fullPage: true });
    evidence.screenshots.push(name + '.png');
  }
}
