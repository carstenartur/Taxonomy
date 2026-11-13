import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const root = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const transportSource = await readFile(new URL('api/taxonomy-api-client.js', root), 'utf8');
const apiSource = await readFile(new URL('api/relations-api.js', root), 'utf8');
const uiSource = await readFile(new URL('relations/taxonomy-quality.js', root), 'utf8');
const endpoints = ['/api/relations/metrics/dashboard?limit=5'];
const metrics = { totalProposals: 12, accepted: 6, rejected: 3, pending: 3, acceptanceRate: 0.5 };

function response(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json', 'X-Request-ID': 'response-id' }
  });
}

function snapshot(summary = metrics, topRejected = []) {
  return { metrics: { byRelationType: [], ...summary }, topRejected };
}

function environment(handler = () => response(snapshot())) {
  const calls = [], events = [], listeners = new Map();
  const content = { innerHTML: '', set textContent(value) {this.innerHTML = value;} };
  const panel = {open: true, addEventListener() {}};
  function emit(event) { (listeners.get(event.type) || []).forEach(fn => fn(event)); }
  const document = {
    currentScript: null,
    querySelector: () => null,
    getElementById(id) {return {qualityDashboardContent: content, qualityDashboard: panel}[id];},
    addEventListener(type, fn) {listeners.set(type, [...(listeners.get(type) || []), fn]);},
    dispatchEvent(event) {events.push(event); emit(event);}
  };
  const context = vm.createContext({
    console, URL, Headers, Request, Response, AbortController, DOMException, setTimeout, clearTimeout,
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
  emit({type: 'DOMContentLoaded'});
  return { context, calls, events, content, panel, emit };
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

// Actual API+canonical transport+UI code executes; HTTP replies are deterministic fixtures.
test('the dashboard renders a single scoped snapshot through the canonical request policy', async () => {
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
    const e = environment(() => response({ title: 'private-diagnostic', detail: 'private-diagnostic' }, status));
    await e.context.TaxonomyQuality.loadQualityDashboard();
    assert.match(e.content.innerHTML, /quality.load.failed/);
    assert.doesNotMatch(e.content.innerHTML, /quality.total|quality.no.data|private-diagnostic/);
    assert.equal(e.calls.length, 1, 'no implicit retry');
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
  assert.equal(e.calls.length, 1);
});

test('existing asynchronous client readiness is awaited before any request', async () => {
  const e = environment(), ready = deferred();
  e.context.TaxonomyRelationsApiReady = ready.promise;
  const loading = e.context.TaxonomyQuality.loadQualityDashboard();
  await Promise.resolve();
  assert.equal(e.calls.length, 0);
  ready.resolve(e.context.TaxonomyRelationsApi);
  await loading;
  assert.equal(e.calls.length, 1);
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
      : Promise.resolve(snapshot({ ...metrics, totalProposals: 99 }));
    const older = e.context.TaxonomyQuality.loadQualityDashboard();
    await Promise.resolve();
    await e.context.TaxonomyQuality.loadQualityDashboard();
    if (oldFails) old.reject(new Error('stale failure'));
    else old.resolve(snapshot());
    await older;
    assert.match(e.content.innerHTML, /quality.total: 99/);
    assert.doesNotMatch(e.content.innerHTML, /quality.load.failed|quality.total: 12/);
  });
}

test('quality UI contains neither direct requests nor feature-level API URLs', () => {
  assert.doesNotMatch(uiSource, /\bfetch\s*\(/);
  assert.doesNotMatch(uiSource, /['"`]\/api\//);
});


test('the resolved ready client is used even if the global instance differs', async () => {
  const e = environment();
  const readyClient = { readQualityDashboard: () =>
    Promise.resolve(snapshot({ ...metrics, totalProposals: 77 })) };
  e.context.TaxonomyRelationsApiReady = Promise.resolve(readyClient);
  e.context.TaxonomyRelationsApi = { readQualityDashboard() {
    throw new Error('must not reread the global after readiness');
  } };
  await e.context.TaxonomyQuality.loadQualityDashboard();
  assert.match(e.content.innerHTML, /quality.total: 77/);
  assert.doesNotMatch(e.content.innerHTML, /quality.load.failed/);
  assert.equal(e.calls.length, 0);
});


test('a workspace change clears the previous inbox and fences its outstanding load', async () => {
  const old = deferred(), latest = deferred(), e = environment();
  let reads = 0;
  e.context.TaxonomyRelationsApi.readQualityDashboard = () => ++reads === 1 ? old.promise : latest.promise;
  const first = e.context.TaxonomyQuality.loadQualityDashboard();
  await Promise.resolve();
  e.emit({type:'taxonomy:repository-context-changed'});
  await Promise.resolve();
  latest.resolve(snapshot({ ...metrics, totalProposals: 98 }));
  await new Promise(resolve => setImmediate(resolve));
  old.resolve(snapshot());
  await first;
  assert.match(e.content.innerHTML, /quality.total: 98/);
  assert.doesNotMatch(e.content.innerHTML, /quality.total: 12/);
  e.panel.open = false;
  e.emit({type:'taxonomy:entity-context-changed'});
  assert.equal(e.content.innerHTML, '');
  assert.equal(reads, 2);
});
