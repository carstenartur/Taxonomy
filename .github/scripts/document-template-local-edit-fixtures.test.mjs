import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { editDownloadedTemplate, readDocumentPart, recordUploadRequest, assertUploadRequests, createUploadHold, withUploadHold, savedRevisionFromResponse } from './document-template-local-edit-acceptance.mjs';

const original = fileURLToPath(new URL(
  '../../taxonomy-app/src/main/resources/document-templates/decision-rationale-report.dotx', import.meta.url));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

test('browser fixture edits preserve the original archive and final Word section properties', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-fixture-test-'));
  try {
    const originalBytes = await readFile(original);
    const source = await readDocumentPart(original);
    for (const marker of ['accepted-local-edit-de', 'rejected-local-edit-en']) {
      const edited = path.join(directory, marker + '.dotx');
      await editDownloadedTemplate(original, edited, marker);
      const xml = await readDocumentPart(edited);
      assert.equal(digest(await readFile(original)), digest(originalBytes));
      assert.notEqual(digest(await readFile(edited)), digest(originalBytes));
      const inserted = `<w:p><w:r><w:t>${marker}</w:t></w:r></w:p>`;
      assert.equal(xml.replace(inserted, ''), source);
      const end = xml.lastIndexOf('</w:body>');
      const section = xml.lastIndexOf('<w:sectPr', end);
      if (section >= 0) assert.ok(xml.indexOf(inserted) < section);
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('fixture marker cannot inject XML or filesystem syntax', async () => {
  for (const marker of ['<w:p/>', '../unsafe', 'name&value', '']) {
    await assert.rejects(editDownloadedTemplate(original, '/unused.dotx', marker));
  }
});

const revision = 'a'.repeat(40);
const request = body => ({
  headers: () => ({ 'if-match': '"' + revision + '"' }),
  postDataBuffer: () => body
});

test('a File request with an unavailable body records no invented or empty digest', () => {
  const uploads = [];
  const failures = [];
  recordUploadRequest(request(null), uploads, failures);
  assert.deepEqual(failures, []);
  assert.deepEqual(uploads, [{ ifMatch: '"' + revision + '"', bodyCaptured: false, sha256: null }]);
  assertUploadRequests(uploads, revision, [Buffer.from('selected file')]);
});

test('captured bodies including empty buffers retain strict byte verification', () => {
  const uploads = [];
  const failures = [];
  const bodies = [Buffer.from('original bytes'), Buffer.alloc(0)];
  for (const body of bodies) recordUploadRequest(request(body), uploads, failures);
  assert.deepEqual(failures, []);
  assert.ok(uploads.every(upload => upload.bodyCaptured));
  assertUploadRequests(uploads, revision, bodies);
  assert.throws(() => assertUploadRequests(uploads, revision, [Buffer.from('changed'), bodies[1]]));
});

test('missing request bodies do not relax count or original revision checks', () => {
  const uploads = [];
  recordUploadRequest(request(null), uploads, []);
  const bodies = [Buffer.from('selected file')];
  assert.throws(() => assertUploadRequests([], revision, bodies));
  assert.throws(() => assertUploadRequests([...uploads, ...uploads], revision, bodies));
  assert.throws(() => assertUploadRequests(uploads, 'b'.repeat(40), bodies));
  assert.throws(() => assertUploadRequests([{ ...uploads[0], sha256: digest(bodies[0]) }], revision, bodies));
});

test('an observation error is retained as a blocking failure, not an uncaught callback', () => {
  const uploads = [];
  const failures = [];
  assert.doesNotThrow(() => recordUploadRequest({
    postDataBuffer() { throw new Error('capture failed'); }
  }, uploads, failures));
  assert.deepEqual(uploads, []);
  assert.deepEqual(failures, ['Upload observation failed: capture failed']);
});

test('a held upload continues exactly once after release and is awaited before cleanup', async () => {
  const failures = [];
  const hold = createUploadHold(failures);
  let calls = 0;
  let finish;
  const continued = new Promise(resolve => { finish = resolve; });
  const pending = hold.handler({
    request: () => ({ method: () => 'PUT' }),
    async continue() { calls++; await continued; }
  });
  await Promise.resolve();
  assert.equal(calls, 0);
  hold.release();
  hold.release();
  await Promise.resolve();
  assert.equal(calls, 1);
  let done = false;
  pending.then(() => { done = true; });
  await Promise.resolve();
  assert.equal(done, false);
  finish();
  await pending;
  assert.equal(done, true);
  assert.deepEqual(failures, []);
});

test('held-route errors are retained instead of escaping as unhandled rejections', async () => {
  const failures = [];
  const hold = createUploadHold(failures);
  const pending = hold.handler({
    request: () => ({ method: () => 'PUT' }),
    async continue() { throw new Error('Route is already handled!'); }
  });
  hold.release();
  await assert.doesNotReject(pending);
  assert.deepEqual(failures, ['Held upload failed: Route is already handled!']);
});


test('held upload consumes the actual response body before removing network interception', async () => {
  const events = [];
  let interception = false;
  const page = {
    async route(pattern, handler) {
      assert.equal(pattern, '/template?*');
      assert.equal(typeof handler, 'function');
      interception = true;
      events.push('route');
    },
    async unrouteAll(options) {
      assert.deepEqual(options, { behavior: 'wait' });
      interception = false;
      events.push('unroute');
    }
  };
  const receipt = { templateId: 'qa-template', headCommit: 'b'.repeat(40) };
  const actualResponse = {
    async json() {
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(interception, true, 'Unrouting must not evict the response before consumption');
      events.push('body');
      return receipt;
    }
  };
  const result = await withUploadHold(page, '/template?*', [], async hold => {
    hold.release();
    events.push('response');
    return actualResponse.json();
  });
  assert.equal(result, receipt);
  assert.deepEqual(events, ['route', 'response', 'body', 'unroute']);
});

test('held upload cleanup releases an outstanding route and preserves response failures', async () => {
  for (const failure of [new Error('UI assertion failed'), new SyntaxError('Invalid response JSON')]) {
    const failures = [];
    let pending;
    let continued = 0;
    let cleaned = 0;
    const page = {
      async route(_pattern, handler) {
        pending = handler({ request: () => ({ method: () => 'PUT' }),
          async continue() { continued++; } });
      },
      async unrouteAll(options) {
        assert.deepEqual(options, { behavior: 'wait' });
        await pending;
        cleaned++;
      }
    };
    await assert.rejects(withUploadHold(page, '/template?*', failures, async () => {
      await Promise.resolve();
      throw failure;
    }), error => error === failure);
    assert.equal(continued, 1);
    assert.equal(cleaned, 1);
    assert.deepEqual(failures, []);
  }
});


test('saved revision comes from the actual upload ETag without reading the inspector body', () => {
  const head = 'b'.repeat(40);
  const response = {
    status: () => 201,
    headers: () => ({ etag: '"' + head + '"' }),
    json() { throw new Error('Inspector cache must not be used for the receipt'); }
  };
  assert.equal(savedRevisionFromResponse(response), head);
});

test('missing or non-immutable upload receipts and unsuccessful writes fail closed', () => {
  for (const etag of [undefined, '', '*', 'main', 'b'.repeat(40), '"bbbbbbb"',
    'W/"' + 'b'.repeat(40) + '"', '"' + 'B'.repeat(40) + '"', '"' + 'b'.repeat(40) + '","other"']) {
    assert.throws(() => savedRevisionFromResponse({ status: () => 201, headers: () => ({ etag }) }));
  }
  for (const status of [200, 204, 400, 401, 403, 409, 412, 500]) {
    assert.throws(() => savedRevisionFromResponse({
      status: () => status, headers: () => ({ etag: '"' + 'b'.repeat(40) + '"' })
    }));
  }
  assert.throws(() => savedRevisionFromResponse(null));
});
