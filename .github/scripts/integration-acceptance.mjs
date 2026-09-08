import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Uses actual authenticated forms, durable operations and the source-controlled Archi producer fixture. */
export async function runIntegrationAcceptance({ page, role, baseUrl, evidence, outputDir, httpFailures }) {
  const failuresBefore = httpFailures.length;
  const expectedFailures = [];
  const previous = new URL(page.url());
  const url = new URL(`${baseUrl.replace(/\/$/, '')}/integrations`);
  for (const key of ['repositoryId', 'branch']) if (previous.searchParams.has(key)) url.searchParams.set(key, previous.searchParams.get(key));
  if (previous.searchParams.has('workspaceScopeKey')) url.searchParams.set('workspaceId', previous.searchParams.get('workspaceScopeKey'));
  url.searchParams.set('lang', 'en');
  const profiles = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/integrations/profiles'));
  await page.goto(url.toString());
  const initial = await profiles;
  const measurements = { role, expectedFailures, reflow: [], sourceFixture: 'archi-sample.xml' };
  if (role === 'USER') {
    assert.equal(await page.locator('#integrationApply').isDisabled(), true);
    if (initial.status() === 403) {
      const problem = await initial.json(); assert.equal(problem.code, 'PRIVATE_WORKSPACE_REQUIRED');
      const headers = initial.headers(), request = initial.request().headers();
      expectedFailures.push({ path: new URL(initial.url()).pathname, status: 403, code: problem.code,
        requestId: headers['x-request-id'] || headers['x-correlation-id'] || request['x-request-id'] || request['x-correlation-id'] });
      await page.locator('#integrationError:not([hidden])').waitFor();
    } else assert.equal(initial.status(), 200, await initial.text());
    measurements.readOnly = true;
  } else {
    assert.equal(initial.status(), 200, await initial.text());
    await page.waitForFunction(() => !document.getElementById('integrationRefresh').disabled);
    await page.locator('#integrationCreate').locator('..').locator('summary').click();
    await page.locator('#connectionName').fill(`Archi browser ${role}`);
    await page.locator('#connectionProfile').selectOption('archimate-3.1');
    await page.locator('#connectionAuthority').selectOption('BIDIRECTIONAL');
    await page.locator('#externalSystem').fill('Archi reference fixture');
    await page.locator('#externalRepository').fill('archi-model-browser');
    const creation = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/integrations') && r.request().method() === 'POST');
    await page.locator('#integrationCreate button[type=submit]').click();
    const created = await creation; assert.equal(created.status(), 200, await created.text());
    const connection = (await created.json()).id;
    await page.waitForFunction(id => document.getElementById('integrationConnection').value === id && !document.getElementById('integrationRefresh').disabled, connection);
    const content = await readFile('taxonomy-export/src/test/resources/interoperability/archi-sample.xml');
    await page.locator('#integrationFile').setInputFiles({ name: 'archi-sample.xml', mimeType: 'application/xml', buffer: content });
    await page.locator('#integrationComplete').check();
    async function preview() {
      const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(`/${connection}/previews`));
      await page.locator('#integrationImport button[type=submit]').click();
      const result = await response; assert.equal(result.status(), 200, await result.text());
      const operation = await result.json();
      await page.waitForFunction(() => !document.getElementById('integrationApply').disabled); return operation;
    }
    const operation = await preview(); assert.equal(operation.status, 'PREVIEWED');
    assert.equal(operation.context.authority, 'BIDIRECTIONAL');
    await page.locator('#integrationAccept').focus(); await page.keyboard.press('Enter');
    await page.locator('#integrationRationale').fill('Reviewed Archi exchange in the real browser');
    const apply = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(`/${connection}/apply`));
    await page.locator('#integrationApply').focus(); await page.keyboard.press('Enter');
    const accepted = await apply; assert.equal(accepted.status(), 200, await accepted.text());
    const applied = await accepted.json(); assert.equal(applied.status, 'COMPLETED'); assert.ok(applied.resultCommit);
    await page.waitForFunction(() => !document.getElementById('integrationRefresh').disabled);
    const replay = await page.evaluate(async ({ connection, review }) => window.IntegrationApi.write(`/${connection}/apply`, review), { connection, review: applied.review });
    assert.equal(replay.id, applied.id); assert.equal(replay.resultCommit, applied.resultCommit);
    await page.reload();
    await page.waitForFunction(id => document.getElementById('integrationOperation').textContent.includes(id) && !document.getElementById('integrationRefresh').disabled, applied.id);
    assert.ok((await page.locator('#integrationHistory').innerText()).includes(applied.id));
    assert.equal(await page.locator('#integrationApply').isDisabled(), true);
    assert.ok((await page.locator('#integrationEvents').innerText()).includes('CHECKPOINT_COMPLETED'));
    measurements.import = { operationId: applied.id, commit: applied.resultCommit, semanticRevision: applied.resultRevision };
    measurements.idempotentRetry = true; measurements.reloadRestored = true;

    await page.locator('#integrationFile').setInputFiles({ name: 'archi-sample.xml', mimeType: 'application/xml', buffer: content });
    const unchanged = await preview(); assert.ok(unchanged.changes.every(c => c.kind === 'UNCHANGED'));
    await page.locator('#integrationRationale').fill('Cancel unchanged synchronization preview');
    const cancelled = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(`/${unchanged.id}/cancel`));
    await page.locator('#integrationCancel').click();
    assert.equal((await (await cancelled).json()).status, 'CANCELLED');
    await page.waitForFunction(() => !document.getElementById('integrationRefresh').disabled);

    const exportResponse = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(`/${connection}/export-previews`));
    await page.locator('#integrationExport').click();
    const exported = await exportResponse; assert.equal(exported.status(), 200, await exported.text());
    const exportPreview = await exported.json();
    await page.waitForFunction(() => !document.getElementById('integrationApply').disabled);
    for (let index = 0; index < Math.ceil(exportPreview.changes.length / 40); index++) {
      await page.locator('#integrationAccept').click();
      if (index + 1 < Math.ceil(exportPreview.changes.length / 40)) await page.locator('#integrationNext').click();
    }
    await page.locator('#integrationRationale').fill('Reviewed exact workspace export');
    const prepared = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(`/${connection}/files`));
    await page.locator('#integrationApply').click();
    const preparedResponse = await prepared; assert.equal(preparedResponse.status(), 200, await preparedResponse.text());
    await page.locator('#integrationDownload:not([hidden])').waitFor();
    const download = await page.request.get(new URL(await page.locator('#integrationDownload').getAttribute('href'), page.url()).toString());
    assert.equal(download.status(), 200); assert.match(await download.text(), /id-37d5bc4b/);
    assert.equal(download.headers()['x-taxonomy-checkpoint'], applied.resultCommit);
    measurements.frozenExport = true; measurements.exportItems = exportPreview.changes.length;
  }
  const viewport = page.viewportSize();
  for (const width of [720, 360]) {
    await page.setViewportSize({ width, height: 800 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 2), `Integration page overflows at ${width}px`);
    await page.locator('#integrationRefresh').focus();
    assert.ok(await page.locator('#integrationRefresh').evaluate(node => { const r = node.getBoundingClientRect(); return node.contains(document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)); }));
    measurements.reflow.push(width);
  }
  await page.setViewportSize(viewport);
  await evidence.runAxe('integrations');
  // Real translated labels survive a reload of the frozen operation URL.
  if (role !== 'USER') {
    const german = new URL(page.url()); german.searchParams.set('lang', 'de'); await page.goto(german.toString());
    await page.waitForFunction(() => !document.getElementById('integrationRefresh').disabled && document.querySelector('h1').textContent !== 'Tool integrations');
    assert.equal(await page.locator('html').getAttribute('lang'), 'de'); measurements.germanReload = true;
  }
  for (const failure of httpFailures.slice(failuresBefore)) assert.ok(expectedFailures.some(e => e.requestId && e.requestId === failure.requestId && e.status === failure.status && e.path === failure.path), `Unexpected integration HTTP failure: ${JSON.stringify(failure)}`);
  await page.locator('main').screenshot({ path: path.join(outputDir, 'integrations.png'), animations: 'disabled' });
  await writeFile(path.join(outputDir, 'integrations.json'), `${JSON.stringify(measurements, null, 2)}\n`);
  return measurements;
}
