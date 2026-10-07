import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(
  new URL('../../taxonomy-app/src/main/resources/static/js/api/taxonomy-api-client.js', import.meta.url),
  'utf8'
);
const authenticatedSurfaceMarker = '(function loadAuthenticatedUiSurfaces';
const authenticatedSurfaceIndex = source.indexOf(authenticatedSurfaceMarker);
assert.notEqual(
  authenticatedSurfaceIndex,
  -1,
  `Missing API client bootstrap marker: ${authenticatedSurfaceMarker}`
);
const core = source.slice(0, authenticatedSurfaceIndex);

class TestCustomEvent {
  constructor(type, options = {}) {
    this.type = type;
    this.detail = options.detail;
  }
}

function abortingFetch() {
  return (_input, init) => new Promise((_resolve, reject) => {
    const rejectAbort = () => reject(new DOMException('Aborted', 'AbortError'));
    if (init.signal.aborted) rejectAbort();
    else init.signal.addEventListener('abort', rejectAbort, { once: true });
  });
}

function loadClient(fetchImpl) {
  const events = [];
  const csrf = { content: 'csrf-token' };
  const csrfHeader = { content: 'X-CSRF-TOKEN' };
  const document = {
    querySelector(selector) {
      if (selector === 'meta[name="_csrf"]') return csrf;
      if (selector === 'meta[name="_csrf_header"]') return csrfHeader;
      return null;
    },
    dispatchEvent(event) {
      events.push(event);
      return true;
    }
  };
  const window = {
    fetch: fetchImpl,
    location: {
      href: 'https://taxonomy.example.test/taxonomy/',
      origin: 'https://taxonomy.example.test'
    }
  };
  const context = vm.createContext({
    window,
    document,
    URL,
    Request,
    Response,
    Headers,
    AbortController,
    DOMException,
    CustomEvent: TestCustomEvent,
    crypto: { randomUUID: () => 'client-request-id' },
    setTimeout,
    clearTimeout,
    Date,
    Math,
    Number,
    Object,
    JSON,
    Promise,
    console
  });
  context.fetch = (...args) => window.fetch(...args);
  vm.runInContext(core, context, { filename: 'taxonomy-api-client.js' });
  return { client: window.TaxonomyApiClient, events, context };
}

{
  const calls = [];
  const { client } = loadClient(async (input, init) => {
    calls.push({ input, init });
    return new Response('{"ok":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  });
  assert.deepEqual(await client.getJson('/api/status'), { ok: true });
  assert.equal(calls[0].input, '/api/status');
  assert.equal(calls[0].init.credentials, 'same-origin');
  assert.equal(calls[0].init.headers.get('X-Request-ID'), 'client-request-id');
  assert.equal(calls[0].init.headers.has('X-CSRF-TOKEN'), false);
}

{
  const calls = [];
  const { client } = loadClient(async (input, init) => {
    calls.push({ input, init });
    return new Response('{"saved":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  });
  assert.deepEqual(await client.sendJson('/api/items', { name: 'A' }), { saved: true });
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.credentials, 'same-origin');
  assert.equal(calls[0].init.headers.get('Content-Type'), 'application/json');
  assert.equal(calls[0].init.headers.get('X-CSRF-TOKEN'), 'csrf-token');
  assert.equal(calls[0].init.headers.get('X-Request-ID'), 'client-request-id');
  assert.equal(calls[0].init.body, '{"name":"A"}');
}

{
  const { client } = loadClient(async () => new Response(JSON.stringify({
    type: 'https://taxonomy.example.test/problems/invalid',
    title: 'Invalid request',
    status: 400,
    detail: 'The supplied project is invalid',
    instance: '/api/projects/42'
  }), {
    status: 400,
    headers: {
      'Content-Type': 'application/problem+json',
      'X-Request-ID': 'server-request-id'
    }
  }));
  await assert.rejects(client.getJson('/api/projects/42'), error => {
    assert.equal(error.name, 'ApiError');
    assert.equal(error.status, 400);
    assert.equal(error.type, 'https://taxonomy.example.test/problems/invalid');
    assert.equal(error.title, 'Invalid request');
    assert.equal(error.detail, 'The supplied project is invalid');
    assert.equal(error.instance, '/api/projects/42');
    assert.equal(error.requestId, 'server-request-id');
    return true;
  });
}

{
  const { client, events } = loadClient(async () => new Response('{"detail":"Forbidden"}', {
    status: 403,
    headers: { 'Content-Type': 'application/problem+json' }
  }));
  await assert.rejects(client.getJson('/api/admin'), error => error.status === 403);
  assert.equal(events.length, 1);
  assert.equal(events[0].type, 'taxonomy-api-auth-failure');
  assert.equal(events[0].detail.status, 403);
  assert.equal(events[0].detail.url, '/api/admin');
  assert.equal(events[0].detail.requestId, 'client-request-id');
  assert.equal(events[0].detail.code, 'HTTP_ERROR');
}


{
  const { client } = loadClient(async () => new Response('not-json', {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  }));
  await assert.rejects(
    client.getJson('/api/invalid-json', { requestId: 'invalid-json-request' }),
    error => {
      assert.equal(error.code, 'INVALID_JSON');
      assert.equal(error.requestId, 'invalid-json-request');
      assert.equal(error.url, '/api/invalid-json');
      return true;
    }
  );
}

{
  const { client } = loadClient(abortingFetch());
  await assert.rejects(
    client.getJson('/api/slow', { timeoutMillis: 5 }),
    error => error.code === 'TIMEOUT' && error.retryable === true
  );
}

{
  const controller = new AbortController();
  const { client } = loadClient(abortingFetch());
  const pending = client.getJson('/api/cancelled', {
    signal: controller.signal,
    timeoutMillis: 1000
  });
  controller.abort();
  await assert.rejects(
    pending,
    error => error.code === 'ABORTED' && error.retryable === false
  );
}

{
  const { client } = loadClient(async () => new Response('{}', { status: 200 }));
  assert.throws(
    () => client.sendJson('/api/mutate', {}, 'POST', { retries: 1 }),
    /requires idempotent: true/
  );
}

{
  let attempts = 0;
  const { client } = loadClient(async () => {
    attempts += 1;
    if (attempts === 1) throw new TypeError('transient network failure');
    return new Response('{"recovered":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  });
  assert.deepEqual(
    await client.getJson('/api/retry', { retries: 1 }),
    { recovered: true }
  );
  assert.equal(attempts, 2);
}

const integrationSource = await readFile(
  new URL('../../taxonomy-app/src/main/resources/static/js/api/integration-api.js', import.meta.url), 'utf8'
);
for (const operation of ['write', 'upload']) {
  for (const status of [200, 204, 503]) {
    const calls = [];
    const { context } = loadClient(async (input, init) => {
      calls.push({ input, init });
      return new Response(status === 204 ? null : 'not-json', {
        status, headers: { 'Content-Type': 'application/json', 'X-Request-ID': 'integration-request' }
      });
    });
    Object.assign(context, {
      URLSearchParams, FormData, Blob,
      location: { search: '?repositoryId=repo%2Fa&workspaceId=team+space&branch=draft&lang=en' }
    });
    vm.runInContext(integrationSource, context, { filename: 'integration-api.js' });
    const body = { rationale: 'Reviewed import' };
    const file = new Blob(['<model/>'], { type: 'application/xml' });
    const pending = context.window.IntegrationApi[operation]('/connection/previews', body, file);
    if (status === 204) assert.equal(await pending, null);
    else await assert.rejects(pending, error => {
      assert.equal(error.name, 'ApiError');
      assert.equal(error.code, status === 200 ? 'INVALID_JSON' : 'HTTP_ERROR');
      assert.equal(error.status, status);
      assert.equal(error.requestId, 'integration-request');
      assert.match(error.url, /^\/api\/integrations\/connection\/previews\?/);
      return true;
    });
    assert.equal(calls.length, 1, `${operation} must not retry a mutation`);
    assert.equal(calls[0].input, '/api/integrations/connection/previews?repositoryId=repo%2Fa&workspaceId=team+space&branch=draft');
    assert.equal(calls[0].init.method, 'POST');
    assert.equal(calls[0].init.credentials, 'same-origin');
    assert.equal(calls[0].init.headers.get('X-CSRF-TOKEN'), 'csrf-token');
    if (operation === 'write') assert.equal(calls[0].init.body, JSON.stringify(body));
    else {
      assert.equal(await calls[0].init.body.get('request').text(), JSON.stringify(body));
      assert.equal(await calls[0].init.body.get('file').text(), '<model/>');
      assert.equal(calls[0].init.headers.has('Content-Type'), false);
    }
  }
}

console.log('Taxonomy canonical API transport and integration mutation tests passed.');

const i18nSource = await readFile(
  new URL('../../taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js', import.meta.url), 'utf8'
);
const surfaceOrigin = 'https://taxonomy.example.test';
const surfaces = [
  { global: 'TaxonomyRoleSurface', marker: 'data-taxonomy-role-surface', path: '/js/security/taxonomy-role-surface.js' },
  { global: 'TaxonomyUiSemantics', marker: 'data-taxonomy-ui-semantics', path: '/js/security/taxonomy-ui-semantics.js' }
];

async function surfaceHarness({ prefix = '', bootstrap = false, scriptUrl,
  globals = [], pending = [], i18n } = {}) {
  const scripts = [];
  const appended = [];
  const fetchCalls = [];
  const createScript = () => {
    const attributes = new Map();
    return { src: '', async: true,
      setAttribute(name, value) { attributes.set(name, String(value)); },
      getAttribute(name) { return attributes.get(name) ?? null; }
    };
  };
  const document = {
    documentElement: { lang: 'en' }, cookie: '', currentScript: null,
    getElementById() { return null; },
    createElement(tag) { assert.equal(tag, 'script'); return createScript(); },
    querySelector(selector) {
      const marker = selector.match(/^script\[([^\]]+)\]$/)?.[1];
      return marker ? scripts.find(script => script.getAttribute(marker) !== null) || null : null;
    },
    dispatchEvent() { return true; },
    head: { appendChild(script) { scripts.push(script); appended.push(script); return script; } }
  };
  const window = {
    // This unrelated page path must never be guessed as the application prefix.
    location: new URL(surfaceOrigin + '/unrelated/deep/page?lang=en'),
    async fetch(input) {
      fetchCalls.push(String(input));
      return new Response('{}', { headers: { 'Content-Type': 'application/json' } });
    }
  };
  for (const name of globals) window[name] = {};
  if (i18n !== undefined) window.TaxonomyI18n = i18n;
  for (const marker of pending) {
    const script = createScript();
    script.setAttribute(marker, 'true');
    scripts.push(script);
  }
  const context = vm.createContext({ window, document, URL, Request, Response, Headers,
    AbortController, DOMException, CustomEvent: TestCustomEvent, setTimeout, clearTimeout, console });
  context.fetch = (...args) => window.fetch(...args);
  if (bootstrap) {
    document.currentScript = { src: surfaceOrigin + prefix + '/js/taxonomy-i18n.js?build=loader-test' };
    vm.runInContext(i18nSource, context, { filename: 'taxonomy-i18n.js' });
    await window.TaxonomyI18n.ready();
    assert.equal(window.TaxonomyI18n.getBasePath(), prefix);
    assert.deepEqual(fetchCalls, [prefix + '/api/i18n/en'], 'the real URL bootstrap must execute');
  }
  document.currentScript = scriptUrl === null ? null
    : { src: scriptUrl ?? surfaceOrigin + prefix + '/js/api/taxonomy-api-client.js' };
  return { window, document, scripts, appended,
    run() { vm.runInContext(source, context, { filename: 'taxonomy-api-client.js' }); } };
}

function assertSurfaces(app, prefix = '', expected = surfaces) {
  assert.deepEqual(app.appended.map(script => new URL(script.src, app.window.location.href).href),
    expected.map(surface => surfaceOrigin + prefix + surface.path));
  for (const [index, surface] of expected.entries()) {
    assert.equal(app.appended[index].getAttribute(surface.marker), 'true');
    assert.equal(app.appended[index].async, false, 'surface execution order stays explicit');
  }
}

for (const prefix of ['', '/taxonomy', '/teams/blue/taxonomy']) {
  test(`authenticated surfaces prefer the real i18n resolver at ${prefix || '/'}`, async () => {
    const app = await surfaceHarness({ prefix, bootstrap: true,
      scriptUrl: surfaceOrigin + '/different-fallback/js/api/taxonomy-api-client.js?revision=7' });
    app.run();
    assertSurfaces(app, prefix);
  });
  for (const query of ['', '?revision=7&mode=qa']) {
    test(`authenticated surfaces derive ${prefix || '/'} from the API script${query ? ' with a query' : ''} without i18n`, async () => {
      const app = await surfaceHarness({ prefix,
        scriptUrl: surfaceOrigin + prefix + '/js/api/taxonomy-api-client.js' + query });
      app.run();
      assertSurfaces(app, prefix);
    });
  }
}

for (const [name, scriptUrl] of [
  ['missing currentScript', null],
  ['malformed script URL', 'https://['],
  ['unrelated script', surfaceOrigin + '/taxonomy/js/api/portfolio-api.js'],
  ['non-exact filename suffix', surfaceOrigin + '/taxonomy/js/api/taxonomy-api-client.js.extra'],
  ['extra path after the filename', surfaceOrigin + '/taxonomy/js/api/taxonomy-api-client.js/extra'],
  ['filename only in the query', surfaceOrigin + '/assets/client.js?next=/taxonomy/js/api/taxonomy-api-client.js'],
  ['cross-origin script', 'https://elsewhere.example.test/taxonomy/js/api/taxonomy-api-client.js?revision=7'],
  ['protocol-relative cross-origin script', '//elsewhere.example.test/taxonomy/js/api/taxonomy-api-client.js'],
  ['prefix resembling a protocol-relative URL', surfaceOrigin + '//elsewhere.example.test/js/api/taxonomy-api-client.js']
]) {
  test(`authenticated surfaces keep root URLs for ${name}`, async () => {
    const app = await surfaceHarness({ scriptUrl });
    app.run();
    assertSurfaces(app);
  });
}

for (const [name, i18n] of [['missing resolver', {}], ['non-callable resolver', { resolveUrl: 'not a function' }]]) {
  test(`authenticated surfaces use the script fallback with a ${name}`, async () => {
    const app = await surfaceHarness({ prefix: '/taxonomy', i18n });
    app.run();
    assertSurfaces(app, '/taxonomy');
  });
}

for (const surface of surfaces) {
  test(`authenticated loader respects the existing ${surface.global} global independently`, async () => {
    const app = await surfaceHarness({ globals: [surface.global] });
    app.run();
    assertSurfaces(app, '', surfaces.filter(item => item !== surface));
  });
  test(`authenticated loader respects the pending ${surface.marker} script independently`, async () => {
    const app = await surfaceHarness({ pending: [surface.marker] });
    app.run();
    assertSurfaces(app, '', surfaces.filter(item => item !== surface));
  });
}

test('authenticated loader skips both loaded globals and mixed loaded/pending surfaces', async () => {
  for (const existing of [
    { globals: surfaces.map(surface => surface.global) },
    { pending: surfaces.map(surface => surface.marker) },
    { globals: [surfaces[0].global], pending: [surfaces[1].marker] }
  ]) {
    const app = await surfaceHarness(existing);
    app.run();
    assertSurfaces(app, '', []);
  }
});

test('rerunning the full API client never duplicates pending or loaded surface scripts', async () => {
  const app = await surfaceHarness();
  app.run();
  assertSurfaces(app);
  app.run();
  assertSurfaces(app);
  assert.equal(app.scripts.length, 2);
  for (const surface of surfaces) app.window[surface.global] = {};
  app.run();
  assertSurfaces(app);
  assert.equal(app.scripts.length, 2);
});
