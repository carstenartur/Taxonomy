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

/** Continue in the existing authenticated browser/app; no extra service or LLM calls. */
export async function runSystemInformationAcceptance({ page, evidence, outputDir }) {
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
      await page.goto(url.toString(), { waitUntil: 'domcontentloaded' });
      await navigateToPage(page, 'admin');
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
      await page.waitForFunction(() => document.getElementById('systemInformationContent')
        ?.getAttribute('aria-busy') === 'false');
      const content = page.locator('#systemInformationContent');
      const rendered = await content.innerText();
      assert.ok(rendered.includes(snapshot.database.product));
      assert.ok(rendered.includes(snapshot.database.version));
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
      await page.waitForFunction(() => document.getElementById('systemInformationContent')
        ?.getAttribute('aria-busy') === 'false');
      assert.equal(requests, 2, 'manual refresh must not introduce polling or duplicate reads');
      await evidence.runAxe(`system-information-${locale}`);
      const dimensions = await panel.boundingBox();
      assert.ok(dimensions && dimensions.width > 0 && dimensions.height < 8000);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth
        <= document.documentElement.clientWidth + 2), true, 'system information must reflow');
      // Two bounded, focused images, rather than another full application-page capture.
      const screenshot = `system-information-${locale}.png`;
      await panel.screenshot({ path: path.join(outputDir, screenshot), animations: 'disabled' });
      cases.push({ locale, screenshot, dimensions, requests,
        databaseVersionSource: next.database.versionSource, storage: next.database.storage,
        warnings: next.database.warnings, refreshed: true, keyboardOperated: true });
    } finally {
      page.off('request', observe);
    }
  }
  await writeFile(path.join(outputDir, 'system-information.json'),
    `${JSON.stringify({ cases }, null, 2)}\n`, 'utf8');
  return cases;
}
