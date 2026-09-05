import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { pathToFileURL } from 'node:url';

const exec = promisify(execFile);
const mediaType = 'application/vnd.openxmlformats-officedocument.wordprocessingml.template';
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

/** Modify only the document part of a server-validated, downloaded QA fixture. */
export async function editDownloadedTemplate(original, edited, marker) {
  assert.match(marker, /^[a-z0-9-]+$/);
  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-local-edit-'));
  try {
    await exec('jar', ['--extract', '--file', path.resolve(original), 'word/document.xml'],
      { cwd: directory });
    const document = path.join(directory, 'word/document.xml');
    const xml = await readFile(document, 'utf8');
    // Body-level section properties must remain the final child of w:body.
    const bodyEnd = xml.lastIndexOf('</w:body>');
    assert.ok(bodyEnd >= 0, 'Downloaded template has no Word document body');
    const sectionStart = xml.lastIndexOf('<w:sectPr', bodyEnd);
    const sectionTail = xml.slice(sectionStart, bodyEnd);
    const finalSection = sectionStart >= 0
      && /^<w:sectPr\b(?:[^>]*\/>|[^>]*>[\s\S]*<\/w:sectPr>)\s*$/.test(sectionTail);
    const insertion = finalSection ? sectionStart : bodyEnd;
    const paragraph = `<w:p><w:r><w:t>${marker}</w:t></w:r></w:p>`;
    await writeFile(document, xml.slice(0, insertion) + paragraph + xml.slice(insertion));
    await copyFile(original, edited);
    await exec('jar', ['--update', '--no-manifest', '--file', path.resolve(edited),
      '-C', directory, 'word/document.xml']);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

export async function readDocumentPart(archive) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-local-inspect-'));
  try {
    await exec('jar', ['--extract', '--file', path.resolve(archive), 'word/document.xml'],
      { cwd: directory });
    return await readFile(path.join(directory, 'word/document.xml'), 'utf8');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

export async function verifyLocalEditing({ baseUrl, outputDir, username, password }) {
  const base = new URL(baseUrl);
  assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(base.hostname),
    'This mutating acceptance requires an isolated loopback application');
  assert.equal(base.search + base.hash + base.username + base.password, '');
  assert.ok(username && password, 'Explicit disposable-instance credentials are required');
  const { chromium, expect } = await import('@playwright/test');
  const browser = await chromium.launch({ headless: true });
  const evidence = { schemaVersion: 1, cases: [], error: null };
  await mkdir(outputDir, { recursive: true });
  try {
    for (const configuration of [
      { language: 'de', viewport: { width: 1280, height: 900 } },
      { language: 'en', viewport: { width: 390, height: 844 } }
    ]) {
      const context = await browser.newContext({
        viewport: configuration.viewport, acceptDownloads: true, reducedMotion: 'reduce'
      });
      try {
        evidence.cases.push(await journey(context, configuration));
      } finally {
        await context.close();
      }
    }
  } catch (error) {
    evidence.error = error.stack || String(error);
    throw error;
  } finally {
    await writeFile(path.join(outputDir, 'local-edit-evidence.json'),
      JSON.stringify(evidence, null, 2) + '\n');
    await browser.close();
  }
  return evidence;

  async function journey(context, configuration) {
    const { language } = configuration;
    const directory = path.join(outputDir, language);
    await mkdir(directory, { recursive: true });
    const endpoint = suffix => baseUrl.replace(/\/+$/, '') + suffix;
    const id = 'qa-local-' + randomUUID().replaceAll('-', '');
    const api = endpoint('/api/admin/document-templates/' + id);
    const uploads = [];
    const browserErrors = [];
    const expectedConsoleErrors = [];
    context.on('page', target => {
      target.on('pageerror', error => browserErrors.push(error.message));
      target.on('console', message => {
        if (message.type() !== 'error') return;
        const record = { text: message.text(), url: message.location().url };
        // Only the two deliberately rejected uploads may generate HTTP console errors.
        if (record.url?.split('?')[0] === api
            && /Failed to load resource:.*status of (400|412)\b/.test(record.text)) {
          expectedConsoleErrors.push(record);
        } else browserErrors.push(JSON.stringify(record));
      });
      target.on('request', request => {
        if (request.method() === 'PUT' && request.url().split('?')[0] === api) {
          uploads.push({ ifMatch: request.headers()['if-match'],
            sha256: sha256(request.postDataBuffer()) });
        }
      });
    });
    const first = await context.newPage();
    await first.goto(endpoint('/login'), { waitUntil: 'domcontentloaded' });
    await first.locator('input[name="username"]').fill(username);
    await first.locator('input[name="password"]').fill(password);
    await Promise.all([
      first.waitForURL(url => !url.pathname.endsWith('/login')),
      first.keyboard.press('Enter')
    ]);
    await expect.poll(async () => {
      const response = await context.request.get(endpoint('/api/admin/document-templates'));
      return response.ok() && (await response.json())
        .some(template => template.templateId === 'decision-rationale-report');
    }, { timeout: 120_000 }).toBe(true);
    await first.goto(endpoint('/admin/document-templates?lang=' + language));
    const csrf = await first.evaluate(() => ({
      name: document.querySelector('meta[name="_csrf_header"]').content,
      value: document.querySelector('meta[name="_csrf"]').content
    }));
    const source = await context.request.get(
      endpoint('/api/admin/document-templates/decision-rationale-report/download'));
    assert.equal(source.status(), 200);
    const seeded = await context.request.put(api + '?displayName=Local-edit-QA-' + language, {
      data: await source.body(), headers: { 'Content-Type': mediaType, [csrf.name]: csrf.value }
    });
    assert.equal(seeded.status(), 201);
    const initial = (await seeded.json()).headCommit;
    assert.match(initial, /^[a-f0-9]{40}$/);
    await first.reload();
    const row = first.locator(`tr[data-template-id="${id}"]`);
    const link = row.locator('a[href*="/local-edit?"]');
    await expect(link).toHaveText(language === 'de'
      ? 'Lokal bearbeiten (ohne WebDAV)' : 'Edit locally (without WebDAV)');
    assert.equal(new URL(await link.getAttribute('href')).searchParams.get('revision'), initial);
    await link.click();
    const checkout = first.url();
    await expect(first.locator('#localTemplateFields')).toBeEnabled();
    await expect(first.locator('#documentTemplateLocalEdit')).toHaveAttribute('data-revision', initial);
    const second = await context.newPage();
    await second.goto(checkout);
    await expect(second.locator('#localTemplateFields')).toBeEnabled();
    const original = path.join(directory, 'original.dotx');
    const secondOriginal = path.join(directory, 'second-original.dotx');
    await download(first, original);
    await download(second, secondOriginal);
    assert.equal(sha256(await readFile(original)), sha256(await readFile(secondOriginal)));
    const winnerFile = path.join(directory, 'winner.dotx');
    const staleFile = path.join(directory, 'stale.dotx');
    const winnerMarker = 'accepted-local-edit-' + language;
    const staleMarker = 'rejected-local-edit-' + language;
    await editDownloadedTemplate(original, winnerFile, winnerMarker);
    await editDownloadedTemplate(secondOriginal, staleFile, staleMarker);
    await capture(first, directory, 'checkout');

    // Real server rejection, not a stubbed response: the chosen file survives.
    await first.locator('#localTemplateFile').setInputFiles({
      name: 'invalid.dotx', mimeType: mediaType, buffer: Buffer.from('not an OOXML archive')
    });
    const invalid = await save(first);
    assert.equal(invalid.status(), 400);
    await expect(first.locator('#localTemplateMessage')).toContainText(language === 'de'
      ? 'gültige, nicht leere DOTX' : 'valid, non-empty DOTX');
    await expect(first.locator('#localTemplateFields')).toBeEnabled();
    assert.equal(await selectedName(first), 'invalid.dotx');
    assert.equal((await history()).length, 1);
    await capture(first, directory, 'invalid-file-retained');

    await first.locator('#localTemplateFile').setInputFiles(winnerFile);
    let release;
    const held = new Promise(resolve => { release = resolve; });
    const routePattern = api + '?*';
    const holdUpload = async route => {
      if (route.request().method() === 'PUT') await held;
      await route.continue(); // Never supply a synthetic response.
    };
    await first.route(routePattern, holdUpload);
    let saved;
    try {
      const received = first.waitForResponse(isUpload);
      await first.locator('#localTemplateSave').click();
      await expect(first.locator('#localTemplateForm')).toHaveAttribute('aria-busy', 'true');
      await expect(first.locator('#localTemplateFields')).toBeDisabled();
      await expect(first.locator('#localTemplateSpinner')).toBeVisible();
      await first.locator('#localTemplateForm').evaluate(form => form.requestSubmit());
      await capture(first, directory, 'upload-progress');
      release();
      saved = await received;
    } finally {
      release();
      await first.unroute(routePattern, holdUpload);
    }
    assert.equal(saved.status(), 201);
    const head = (await saved.json()).headCommit;
    assert.match(head, /^[a-f0-9]{40}$/);
    assert.notEqual(head, initial);
    await expect(first.locator('#localTemplateResult')).toBeVisible();
    await expect(first.locator('#localTemplateSavedRevision')).toHaveText(head);
    await expect(first.locator('#localTemplateFields')).toBeDisabled();
    await first.locator('#localTemplateForm').evaluate(form => form.requestSubmit());
    await capture(first, directory, 'saved');
    const beforeConflict = await currentBytes();
    const current = path.join(directory, 'server-winner.dotx');
    await writeFile(current, beforeConflict);
    assert.ok((await readDocumentPart(current)).includes(winnerMarker));
    assert.ok(!(await readDocumentPart(current)).includes(staleMarker));
    const beforeHistory = await history();
    assert.equal(beforeHistory.length, 2);

    // Reload the OLD address after the other tab saved. It must not adopt HEAD.
    await second.reload();
    assert.equal(second.url(), checkout);
    await expect(second.locator('#documentTemplateLocalEdit')).toHaveAttribute('data-revision', initial);
    await download(second, secondOriginal);
    assert.equal(sha256(await readFile(secondOriginal)), sha256(await readFile(original)));
    await second.locator('#localTemplateFile').setInputFiles(staleFile);
    const conflict = await save(second);
    assert.equal(conflict.status(), 412);
    await expect(second.locator('#localTemplateMessage')).toContainText(language === 'de'
      ? 'hat nichts überschrieben' : 'Nothing was overwritten');
    await expect(second.locator('#localTemplateFields')).toBeEnabled();
    await expect(second.locator('#localTemplateResult')).toBeHidden();
    assert.equal(await selectedName(second), 'stale.dotx');
    assert.equal(sha256(await currentBytes()), sha256(beforeConflict));
    assert.deepEqual(await history(), beforeHistory);
    await capture(second, directory, 'conflict-after-reload');

    const compare = new URL(await first.locator('#localTemplateCompare').getAttribute('href'));
    assert.equal(compare.searchParams.get('from'), initial);
    assert.equal(compare.searchParams.get('to'), head);
    await first.locator('#localTemplateCompare').click();
    await expect(first.locator('#fromRevision')).toHaveValue(initial);
    await expect(first.locator('#toRevision')).toHaveValue(head);
    const changes = first.locator('section[aria-labelledby="diffHeading"]');
    await expect(changes).toContainText('word/document.xml');
    await capture(first, directory, 'saved-diff');
    await changes.locator('tr').filter({ hasText: 'word/document.xml' })
      .locator('a[href*="partRevision="]').click();
    await expect(first.locator('section[aria-labelledby="partHeading"]')).toContainText(winnerMarker);

    const routeStatuses = [];
    for (const query of ['', '?revision=main', '?revision=aaaaaaa', '?revision=' + 'f'.repeat(40)]) {
      const response = await context.request.get(endpoint('/admin/document-templates/' + id + '/local-edit') + query);
      const expected = query.includes('f'.repeat(40)) ? 404 : 400;
      assert.equal(response.status(), expected);
      routeStatuses.push({ query, status: response.status() });
    }
    // A protocol-only credential is never used; these are ordinary browser sessions.
    assert.equal(uploads.length, 3, 'Exactly invalid, accepted and conflicting uploads; no retries');
    assert.ok(uploads.every(upload => upload.ifMatch === '"' + initial + '"'));
    assert.equal(uploads[1].sha256, sha256(await readFile(winnerFile)));
    assert.equal(uploads[2].sha256, sha256(await readFile(staleFile)));
    assert.deepEqual(browserErrors, []);
    return { ...configuration, templateId: id, initial, head, uploads, routeStatuses,
      historyEntries: beforeHistory.length, winnerSha256: sha256(beforeConflict),
      originalDownloadSha256: sha256(await readFile(original)),
      conflictStatus: conflict.status(), invalidStatus: invalid.status(),
      originalRevisionSurvivesReload: true, conflictPreservesFileAndHistory: true,
      browserErrors, expectedConsoleErrors };

    function isUpload(response) {
      return response.request().method() === 'PUT' && response.url().split('?')[0] === api;
    }
    async function save(target) {
      const [response] = await Promise.all([
        target.waitForResponse(isUpload), target.locator('#localTemplateSave').click()
      ]);
      await expect(target.locator('#localTemplateForm')).toHaveAttribute('aria-busy', 'false');
      return response;
    }
    async function currentBytes() {
      const response = await context.request.get(api + '/download');
      assert.equal(response.status(), 200);
      return response.body();
    }
    async function history() {
      const response = await context.request.get(api + '/history');
      assert.equal(response.status(), 200);
      return response.json();
    }
    async function download(target, destination) {
      const [file] = await Promise.all([
        target.waitForEvent('download'), target.locator('#localTemplateDownload').click()
      ]);
      assert.equal(await file.failure(), null);
      await file.saveAs(destination);
    }
    async function selectedName(target) {
      return target.locator('#localTemplateFile').evaluate(input => input.files[0]?.name);
    }
  }

  async function capture(target, directory, name) {
    assert.ok(await target.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 2),
      'Local-edit workflow must not require horizontal page scrolling');
    for (const selector of ['#localTemplateDownload', '#localTemplateSave', '#localTemplateHistory']) {
      const control = target.locator(selector);
      if (!(await control.count()) || !(await control.isVisible()) || await control.isDisabled()) continue;
      await control.scrollIntoViewIfNeeded();
      assert.ok(await control.evaluate(element => {
        const rect = element.getBoundingClientRect();
        const top = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
        return top === element || element.contains(top);
      }), 'A required control is covered: ' + selector);
    }
    await target.evaluate(() => window.scrollTo(0, 0));
    await target.screenshot({ path: path.join(directory, name + '.png'), fullPage: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  assert.equal(process.env.TAXONOMY_UI_DISPOSABLE_INSTANCE, 'true',
    'Only run this mutation test against the disposable test application');
  await verifyLocalEditing({
    baseUrl: process.env.TAXONOMY_BASE_URL,
    outputDir: path.resolve(process.env.TAXONOMY_UI_OUTPUT_DIR, 'local-edit'),
    username: process.env.TAXONOMY_UI_USERNAME,
    password: process.env.TAXONOMY_UI_PASSWORD
  });
}
