import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const root = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const transportSource = await readFile(new URL('api/taxonomy-api-client.js', root), 'utf8');
const apiSource = await readFile(new URL('api/relations-api.js', root), 'utf8');
const uiSource = await readFile(new URL('relations/taxonomy-quality.js', root), 'utf8');
const endpoints = ['/api/relations/metrics', '/api/relations/metrics/by-type',
  '/api/relations/metrics/top-rejected?limit=5'];
const metrics = { totalProposals: 12, accepted: 6, rejected: 3, pending: 3, acceptanceRate: 0.5 };

function response(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json', 'X-Request-ID': 'response-id' }
  });
}

function environment(handler = (_, index) => response(index === 0 ? metrics : [])) {
  const calls = [], events = [];
  const content = { innerHTML: '' };
  const document = {
    currentScript: null,
    querySelector: () => null,
    getElementById: id => id === 'qualityDashboardContent' ? content : null,
    addEventListener() {},
    dispatchEvent(event) { events.push(event); }
  };
  const context = vm.createContext({
    console, URL, Headers, Request, Response, AbortController, setTimeout, clearTimeout,
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
    document, location: new URL('https://example.invalid/taxonomy/'),
    TaxonomyI18n: { t: key => key },
    TaxonomyUtils: { escapeHtml: value => String(value).replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;') },
    // Suppress unrelated script loading, not the production transport or API.
    TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
    fetch(input, init) {
      const index = calls.length;
      calls.push({ input, init });
      return Promise.resolve().then(() => handler(input, index, init));
    }
  });
  context.window = context;
  vm.runInContext(transportSource, context, { filename: 'taxonomy-api-client.js' });
  vm.runInContext(apiSource, context, { filename: 'relations-api.js' });
  vm.runInContext(uiSource, context, { filename: 'taxonomy-quality.js' });
  return { context, calls, events, content };
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

// Actual API+canonical transport+UI code executes; HTTP replies are deterministic fixtures.
test('quality reads use the canonical request policy and preserve the three responses', async () => {
  const e = environment();
  await e.context.TaxonomyQuality.loadQualityDashboard();
  assert.deepEqual(e.calls.map(call => call.input), endpoints);
  for (const { init } of e.calls) {
    assert.equal(init.method, 'GET');
    assert.equal(init.credentials, 'same-origin');
    assert.ok(init.headers.get('X-Request-ID'));
    assert.ok(init.signal instanceof AbortSignal);
  }
  assert.match(e.content.innerHTML, /quality.total: 12/);
  assert.doesNotMatch(e.content.innerHTML, /quality.load.failed/);
});

for (const status of [401, 403, 412, 503]) {
  test(`HTTP ${status} is visible as a failure, never a partial success or empty result`, async () => {
    const e = environment((_, index) => index === 1
      ? response({ title: 'private-diagnostic', detail: 'private-diagnostic' }, status)
      : response(index === 0 ? metrics : []));
    await e.context.TaxonomyQuality.loadQualityDashboard();
    assert.match(e.content.innerHTML, /quality.load.failed/);
    assert.doesNotMatch(e.content.innerHTML, /quality.total|quality.no.data|private-diagnostic/);
    assert.equal(e.calls.length, 3, 'no implicit retry');
    assert.equal(e.events.length, [401, 403].includes(status) ? 1 : 0);
    if (e.events.length) assert.equal(e.events[0].type, 'taxonomy-api-auth-failure');
  });
}

test('feature API preserves structured errors and response correlation identity', async () => {
  const e = environment(() => response({ type: 'urn:qa:unavailable', title: 'Unavailable',
    detail: 'fixture detail' }, 503));
  await assert.rejects(e.context.TaxonomyRelationsApi.readQualityDashboard(), error => {
    assert.equal(error.name, 'ApiError');
    assert.equal(error.status, 503);
    assert.equal(error.type, 'urn:qa:unavailable');
    assert.equal(error.requestId, 'response-id');
    return true;
  });
});

test('malformed JSON does not become a success', async () => {
  const e = environment(() => new Response('{broken', { status: 200 }));
  await e.context.TaxonomyQuality.loadQualityDashboard();
  assert.match(e.content.innerHTML, /quality.load.failed/);
});

test('network failure does not become empty metrics or get retried', async () => {
  const e = environment(() => { throw new Error('private network detail'); });
  await e.context.TaxonomyQuality.loadQualityDashboard();
  assert.match(e.content.innerHTML, /quality.load.failed/);
  assert.doesNotMatch(e.content.innerHTML, /private network detail|quality.no.data/);
  assert.equal(e.calls.length, 3);
});

test('existing asynchronous client readiness is awaited before any request', async () => {
  const e = environment(), ready = deferred();
  e.context.TaxonomyRelationsApiReady = ready.promise;
  const loading = e.context.TaxonomyQuality.loadQualityDashboard();
  await Promise.resolve();
  assert.equal(e.calls.length, 0);
  ready.resolve(e.context.TaxonomyRelationsApi);
  await loading;
  assert.equal(e.calls.length, 3);
});

test('client loading failure is visible without falling back to direct fetch', async () => {
  const e = environment();
  e.context.TaxonomyRelationsApiReady = Promise.reject(new Error('private script detail'));
  await e.context.TaxonomyQuality.loadQualityDashboard();
  assert.equal(e.calls.length, 0);
  assert.match(e.content.innerHTML, /quality.load.failed/);
  assert.doesNotMatch(e.content.innerHTML, /private script detail/);
});

for (const oldFails of [false, true]) {
  test(`a late older ${oldFails ? 'failure' : 'success'} cannot replace a newer refresh`, async () => {
    const e = environment(), old = deferred();
    let count = 0;
    e.context.TaxonomyRelationsApi.readQualityDashboard = () => ++count === 1 ? old.promise
      : Promise.resolve([{ ...metrics, totalProposals: 99 }, [], []]);
    const older = e.context.TaxonomyQuality.loadQualityDashboard();
    await Promise.resolve();
    await e.context.TaxonomyQuality.loadQualityDashboard();
    if (oldFails) old.reject(new Error('stale failure'));
    else old.resolve([metrics, [], []]);
    await older;
    assert.match(e.content.innerHTML, /quality.total: 99/);
    assert.doesNotMatch(e.content.innerHTML, /quality.load.failed|quality.total: 12/);
  });
}

test('quality UI contains neither direct requests nor feature-level API URLs', () => {
  assert.doesNotMatch(uiSource, /\bfetch\s*\(/);
  assert.doesNotMatch(uiSource, /['"`]\/api\//);
});
