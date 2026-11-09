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

function loadClient(fetchImpl, bootstrap) {
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
  bootstrap?.(context);
  vm.runInContext(core, context, { filename: 'taxonomy-api-client.js' });
  return { client: window.TaxonomyApiClient, events, context };
}

async function loadWorkspaceFeatures(fetchImpl, bootstrap) {
  const app = loadClient(fetchImpl, bootstrap);
  const feature = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/workspace-api.js', import.meta.url), 'utf8')
    .catch(() => null);
  assert.ok(feature, 'workspace and context features must use the named API boundary');
  app.context.window.__TaxonomyAnalysisSessionContext = { runtime: { workspaceId: 'workspace A/1' } };
  app.context.URLSearchParams = URLSearchParams;
  vm.runInContext(feature, app.context, { filename: 'workspace-api.js' });
  return app;
}

test('the comparison dialog reads one authorized workspace and ignores results from the previous workspace', async () => {
  const calls = [];
  const scripts = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
  const i18n = await readFile(new URL('taxonomy-i18n.js', scripts), 'utf8');
  const app = await loadWorkspaceFeatures((url, init) => {
    if (url === '/taxonomy/api/i18n/en') return Promise.resolve(Response.json({}));
    return new Promise(resolve => calls.push({url, init, resolve}));
  }, context => {
    context.document.currentScript = {src: 'https://taxonomy.example.test/taxonomy/js/taxonomy-i18n.js'};
    context.document.documentElement = {lang: 'en'};
    vm.runInContext(i18n, context, {filename: 'taxonomy-i18n.js'});
  });
  const {context} = app;
  const {window, document} = context;
  const results = {innerHTML: '', textContent: ''};
  const nodes = {contextCompareModal: {querySelector: () => null, addEventListener() {}}, contextCompareResults: results};
  for (const id of ['compareLeftBranch', 'compareRightBranch', 'compareLeftCommit', 'compareRightCommit']) {
    nodes[id] = {value: '', replaceChildren() {}, appendChild() {}, addEventListener() {}};
  }
  document.getElementById = id => nodes[id] || null;
  document.createElement = () => ({});
  context.bootstrap = {Modal: {getOrCreateInstance: () => ({show() {}})}};
  await window.TaxonomyI18n.ready();
  context.TaxonomyI18n = window.TaxonomyI18n;
  context.TaxonomyUtils = {escapeHtml: String};
  const script = new URL('versioning/taxonomy-context-compare.js', scripts);
  vm.runInContext(await readFile(script, 'utf8'), context);

  const opening = window.TaxonomyContextCompare.showDialog({branch: 'review/one + two', commitId: 'left'}, 'draft', false, 'right');
  calls[0].resolve(Response.json({branches: ['review/one + two', 'draft']}));
  await opening;
  window.TaxonomyContextCompare.doCompare();
  const loading = results.innerHTML;
  window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace B';
  calls[1].resolve(Response.json({summary: {}, changes: [], left: {branch: 'old workspace'}}));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(results.innerHTML, loading);
  window.TaxonomyContextCompare.doCompare();
  assert.equal(calls.length, 2, 'the previous workspace branch selection cannot start another compare');
  for (const call of calls) {
    assert.equal(call.init.headers.get('X-Taxonomy-Workspace-Id'), 'workspace A/1');
    assert.equal(new URL(call.url, window.location.href).searchParams.get('workspaceId'), 'workspace A/1');
    assert.equal(call.init.headers.get('X-Request-ID'), 'client-request-id');
  }
  const url = new URL(calls[1].url, window.location.href);
  assert.equal(url.pathname, '/taxonomy/api/context/compare');
  assert.deepEqual(Object.fromEntries(url.searchParams), {
    leftBranch: 'review/one + two', rightBranch: 'draft', leftCommit: 'left', rightCommit: 'right', workspaceId: 'workspace A/1'
  });
});

async function loadVersioningFeatures(fetchImpl) {
  const scripts = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
  const i18n = await readFile(new URL('taxonomy-i18n.js', scripts), 'utf8');
  const timeline = {setAttribute() {}, querySelectorAll: () => [variantButton]};
  let clickVariant;
  const variantButton = {
    addEventListener: (_type, listener) => { clickVariant = listener; },
    getAttribute: name => ({
      'data-action': 'variant', 'data-branch': 'draft/one + two', 'data-commit': '7654321'
    })[name]
  };
  const contextBar = {classList: {remove() {}}, setAttribute() {}};
  const search = {innerHTML: ''};
  const variantModal = {};
  let openedVariantModal = 0;
  const errors = [];
  const lifecycle = new EventTarget();
  const app = await loadWorkspaceFeatures((url, init) => {
    if (url === '/taxonomy/api/i18n/en') return Promise.resolve(Response.json({}));
    return fetchImpl(url, init);
  }, context => {
    context.document.currentScript = {src: 'https://taxonomy.example.test/taxonomy/js/taxonomy-i18n.js'};
    context.document.documentElement = {lang: 'en'};
    vm.runInContext(i18n, context, {filename: 'taxonomy-i18n.js'});
  });
  const {context} = app;
  const {window, document} = context;
  await window.TaxonomyI18n.ready();
  context.TaxonomyI18n = window.TaxonomyI18n;
  context.TaxonomyUtils = {escapeHtml: String};
  context.bootstrap = {Modal: class { show() { openedVariantModal++; } }};
  context.clearInterval = clearInterval;
  context.fetch = url => {
    assert.equal(url, '/api/dsl/history?branch=draft', 'context navigation must use the named API client');
    return Promise.resolve(Response.json({commits: [{commitId: '7654321'}]}));
  };
  document.getElementById = id => ({
    contextBar, versionsTimeline: timeline, versionedSearch: search, createVariantModal: variantModal
  })[id] || null;
  document.addEventListener = () => {};
  window.addEventListener = lifecycle.addEventListener.bind(lifecycle);
  window.TaxonomyOperationResult = {showError: (...args) => errors.push(args)};
  for (const name of ['context-bar', 'history-search', 'variants', 'versions']) {
    const path = 'versioning/taxonomy-' + name + '.js';
    vm.runInContext(await readFile(new URL(path, scripts), 'utf8'), context, {filename: path});
  }
  window.TaxonomyVersions.loadTimeline();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(typeof clickVariant, 'function');
  return {...app, window, contextBar, search, errors, lifecycle, openedVariantModal: () => openedVariantModal,
    clickVariant: () => clickVariant({currentTarget: variantButton})};
}

test('history, variants and timeline context opens keep exact source and handle denied writes', async () => {
  const calls = [];
  let status = 401;
  const app = await loadVersioningFeatures(async (url, init) => {
    calls.push({url, init});
    return status === 200 ? Response.json({branch: 'draft/one + two', commitId: '7654321', mode: 'EDITABLE'})
      : Response.json({detail: 'Context access denied'}, {status});
  });
  const w = app.window;
  const branch = 'draft/one + two';
  const actions = [
    [() => w.TaxonomyHistorySearch.openReadOnly(branch, '1234567'), '1234567', 'true'],
    [() => w.TaxonomyHistorySearch.switchTo(branch, '2345678'), '2345678', 'false'],
    [() => w.TaxonomyVariants.switchTo(branch), null, 'false'],
    [app.clickVariant, '7654321', 'false']
  ];
  for (const [action, commitId, readOnly] of actions) {
    await action();
    const {url, init} = calls.at(-1);
    const target = new URL(url, w.location.href);
    assert.equal(target.pathname, '/taxonomy/api/context/open');
    assert.equal(target.searchParams.get('branch'), branch);
    assert.equal(target.searchParams.get('commitId'), commitId);
    assert.equal(target.searchParams.get('readOnly'), readOnly);
    assert.equal(target.searchParams.get('workspaceId'), 'workspace A/1');
    assert.equal(init.method, 'POST');
    assert.equal(init.credentials, 'same-origin');
    assert.equal(init.headers.get('X-Taxonomy-Workspace-Id'), 'workspace A/1');
    assert.equal(init.headers.get('X-CSRF-TOKEN'), 'csrf-token');
    assert.equal(init.headers.get('X-Request-ID'), 'client-request-id');
    status = 403;
  }
  assert.equal(calls.length, actions.length, 'denial neither refreshes nor retries a context command');
  assert.deepEqual(app.events.filter(event => event.type === 'taxonomy-api-auth-failure')
    .map(event => event.detail.status), [401, 403, 403, 403]);
  assert.equal(app.errors.length, actions.length);
  assert.equal(app.openedVariantModal(), 0);
  assert.equal(w.TaxonomyContextBar.getCurrentContext(), null);

  status = 200;
  await app.clickVariant();
  assert.equal(app.openedVariantModal(), 1, 'variant dialog opens after acknowledged selection');
  assert.equal(w.TaxonomyContextBar.getCurrentContext().commitId, '7654321');
  assert.ok(app.contextBar.innerHTML.includes(branch));
});

test('a context open from a departed workspace never adopts its result', async () => {
  let complete;
  const app = await loadVersioningFeatures(() => new Promise(resolve => { complete = resolve; }));
  const w = app.window;
  const opened = w.TaxonomyHistorySearch.switchTo('other', '2345678');
  w.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace B';
  complete(Response.json({branch: 'other', commitId: '2345678', mode: 'EDITABLE'}));
  await opened;
  assert.equal(w.TaxonomyContextBar.getCurrentContext(), null);
  assert.equal(app.contextBar.innerHTML, undefined);
  assert.equal(app.errors.length, 0);
});

for (const staleStatus of [200, 403]) {
  test(`a superseded context open cannot repaint or show an error (HTTP ${staleStatus})`, async () => {
    const pending = [];
    const app = await loadVersioningFeatures(() => new Promise(resolve => pending.push(resolve)));
    const w = app.window;
    const first = w.TaxonomyHistorySearch.switchTo('older-selection', '1234567');
    const second = w.TaxonomyHistorySearch.switchTo('newer-selection', '2345678');
    pending[1](Response.json({branch: 'newer-selection', commitId: '2345678', mode: 'EDITABLE'}));
    await second;
    pending[0](staleStatus === 200
      ? Response.json({branch: 'older-selection', commitId: '1234567', mode: 'EDITABLE'})
      : Response.json({detail: 'Previous selection is unavailable'}, {status: staleStatus}));
    await first;
    assert.equal(w.TaxonomyContextBar.getCurrentContext().commitId, '2345678');
    assert.match(app.contextBar.innerHTML, /newer-selection/);
    assert.equal(app.errors.length, 0);
  });
}

test('versioned history search uses the current pin and ignores a superseded response', async () => {
  const reads = [];
  const app = await loadVersioningFeatures((url, init) => new Promise(resolve => reads.push({url, init, resolve})));
  const w = app.window;
  const first = w.TaxonomyHistorySearch.search('old', 'versionedSearch', 'draft/one');
  const second = w.TaxonomyHistorySearch.search('R&D + support', 'versionedSearch', 'draft/two');
  assert.equal(reads.length, 2);
  const target = new URL(reads[1].url, w.location.href);
  assert.equal(target.pathname, '/taxonomy/api/dsl/history/search-versioned');
  assert.equal(target.searchParams.get('query'), 'R&D + support');
  assert.equal(target.searchParams.get('currentBranch'), 'draft/two');
  assert.equal(target.searchParams.get('workspaceId'), 'workspace A/1');
  assert.equal(reads[1].init.headers.get('X-Taxonomy-Workspace-Id'), 'workspace A/1');
  assert.equal(reads[0].init.signal.aborted, true);
  reads[1].resolve(Response.json([{branch: 'draft/two', matchedText: 'accepted result'}]));
  await second;
  reads[0].resolve(Response.json([{branch: 'draft/one', matchedText: 'obsolete result'}]));
  await first;
  assert.match(app.search.innerHTML, /accepted result/);
  assert.doesNotMatch(app.search.innerHTML, /obsolete result/);

  const hidden = w.TaxonomyHistorySearch.search('hidden', 'versionedSearch');
  app.lifecycle.dispatchEvent(new Event('pagehide'));
  assert.equal(reads[2].init.signal.aborted, true);
  reads[2].resolve(Response.json([{branch: 'draft', matchedText: 'after departure'}]));
  await hidden;
  assert.match(app.search.innerHTML, /accepted result/);
});

test('workspace/context/preferences feature writes retain CSRF, source, request IDs and structured auth errors without retry', async () => {
  const calls = [];
  const app = await loadWorkspaceFeatures(async (url, init) => {
    calls.push({url, init});
    return new Response(JSON.stringify({title: 'Forbidden', detail: 'Source permission revoked'}), {status: 403});
  });
  const w = app.context.window;
  const actions = [() => w.TaxonomyWorkspaceApi.archive('other/id'),
    () => w.TaxonomyWorkspaceApi.create('Example'), () => w.TaxonomyWorkspaceApi.syncFromShared('draft/a'),
    () => w.TaxonomyWorkspaceApi.publish('draft/a'), () => w.TaxonomyContextApi.back(),
    () => w.TaxonomyContextApi.returnToOrigin(), () => w.TaxonomyContextApi.createVariant('my variant'),
    () => w.TaxonomyPreferencesApi.save({limit: 3}), () => w.TaxonomyPreferencesApi.reset()];
  for (const action of actions) await assert.rejects(action(), error => error.status === 403
    && error.detail === 'Source permission revoked' && error.requestId === 'client-request-id');
  assert.equal(calls.length, actions.length, 'non-idempotent feature writes are never retried');
  for (const {url, init} of calls) {
    assert.equal(new URL(url, w.location.href).searchParams.get('workspaceId'), 'workspace A/1');
    assert.equal(init.headers.get('X-Taxonomy-Workspace-Id'), 'workspace A/1');
    assert.equal(init.headers.get('X-CSRF-TOKEN'), 'csrf-token');
    assert.equal(init.headers.get('X-Request-ID'), 'client-request-id');
    assert.equal(init.credentials, 'same-origin');
  }
  assert.equal(app.events.length, actions.length);
});

test('named workspace switch persists and reloads only after an acknowledged successful mutation', async () => {
  let status = 403;
  const app = await loadWorkspaceFeatures(async () => new Response(JSON.stringify({workspaceId: 'next/workspace'}), {status}));
  const w = app.context.window, stored = new Map([['taxonomy-analysis-workspace-id', 'workspace A/1']]), timers = [];
  delete w.__TaxonomyAnalysisSessionContext;
  w.TaxonomyState = {};
  w.sessionStorage = {setItem: (key, value) => stored.set(key, value)};
  vm.runInContext(await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-core.js', import.meta.url), 'utf8'), app.context);
  w.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace A/1';
  w.setTimeout = callback => timers.push(callback);
  let reloads = 0; w.location.reload = () => reloads++;
  await assert.rejects(w.TaxonomyWorkspaceApi.switchWorkspace('next/workspace'), error => error.status === 403);
  assert.equal(stored.get('taxonomy-analysis-workspace-id'), 'workspace A/1'); assert.equal(timers.length, 0);
  status = 200;
  await w.TaxonomyWorkspaceApi.switchWorkspace('next/workspace');
  assert.equal(stored.get('taxonomy-analysis-workspace-id'), 'next/workspace');
  assert.equal(reloads, 0); assert.equal(timers.length, 1);
  timers[0](); assert.equal(reloads, 1);
});

test('context reads cancel through canonical transport and reject a body from a previous workspace', async () => {
  const app = await loadWorkspaceFeatures(abortingFetch()), controller = new AbortController();
  const read = app.context.window.TaxonomyContextApi.current({signal: controller.signal});
  controller.abort();
  await assert.rejects(read, error => error.code === 'ABORTED' && error.requestId === 'client-request-id');
  let release;
  const late = await loadWorkspaceFeatures(async () => ({ok: true, status: 200,
    json: () => new Promise(resolve => { release = resolve; })}));
  const result = late.context.window.TaxonomyContextApi.current();
  while (!release) await new Promise(resolve => setImmediate(resolve));
  late.context.window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'different-workspace';
  release({branch: 'old-branch'});
  await assert.rejects(result, error => error.code === 'ABORTED');
});

test('a late publish HTTP error is cancelled after the workspace changes', async () => {
  let respond;
  const started = new Promise(resolve => { respond = resolve; });
  const app = await loadWorkspaceFeatures(() => new Promise(resolve => respond(resolve)));
  const w = app.context.window;
  const result = w.TaxonomyWorkspaceApi.publish('draft/a');
  const finish = await started;
  w.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace B';
  finish(new Response('{"detail":"Previous workspace is unavailable"}', {status: 503}));
  await assert.rejects(result, error => error.code === 'ABORTED' && error.status === 0);
});

test('the live context-bar back action handles canonical authorization failure without adopting an error body', async () => {
  const calls = [];
  const app = await loadWorkspaceFeatures(async (url, init) => {
    calls.push({url, init});
    return new Response('{"detail":"Permission revoked"}', {status: 403});
  });
  const w = app.context.window, errors = [];
  w.addEventListener = () => {};
  w.TaxonomyOperationResult = {showError: (...args) => errors.push(args)};
  app.context.TaxonomyI18n = {t: (key, value) => value || key};
  app.context.TaxonomyUtils = {escapeHtml: String};
  app.context.document.getElementById = () => null;
  app.context.fetch = () => { throw new Error('legacy fetch bypass'); };
  vm.runInContext(await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/versioning/taxonomy-context-bar.js', import.meta.url), 'utf8'), app.context);
  await w.TaxonomyContextBar.back();
  assert.equal(calls.length, 1); assert.equal(app.events[0].detail.status, 403);
  assert.equal(w.TaxonomyContextBar.getCurrentContext(), null);
  assert.equal(errors.length, 1);
});

for (const [initialWorkspace, hydratedWorkspace] of [[null, 'workspace-A'], [null, 'workspace-B'], ['workspace-A', 'workspace-B']]) {
  test(`the live workspace badge reconciles ${initialWorkspace || 'unresolved'} into ${hydratedWorkspace}`, async () => {
    const calls = [];
    const app = await loadWorkspaceFeatures((url, init) => new Promise(resolve => calls.push({url, init, resolve})));
    const w = app.context.window, listeners = [], writes = [], classes = new Set(['d-none']);
    w.__TaxonomyAnalysisSessionContext.runtime.workspaceId = initialWorkspace;
    const badge = {title: '', classList: {remove: name => classes.delete(name)},
      set textContent(value) { writes.push(value); }};
    app.context.document.getElementById = id => id === 'workspaceUserBadge' ? badge : null;
    app.context.document.addEventListener = (type, listener) => { if (type === 'DOMContentLoaded') listeners.push(listener); };
    app.context.TaxonomyI18n = {ready: () => Promise.resolve(), t: key => key, formatBranch: branch => branch};
    const index = await readFile(new URL('../../taxonomy-app/src/main/resources/templates/index.html', import.meta.url), 'utf8');
    const marker = index.indexOf('// Load workspace info for the user badge');
    const start = index.lastIndexOf('<script>', marker) + '<script>'.length;
    vm.runInContext(index.slice(start, index.indexOf('</script>', marker)), app.context);
    for (const listener of listeners) listener();
    const settle = async () => { for (let turn = 0; turn < 5; turn++) await new Promise(resolve => setImmediate(resolve)); };
    await settle(); assert.equal(calls.length, 1);
    w.__TaxonomyAnalysisSessionContext.runtime.workspaceId = hydratedWorkspace;
    calls[0].resolve(new Response(JSON.stringify({workspaceId: 'workspace-A', username: 'initial', displayName: 'Initial A'})));
    await settle();
    assert.equal(classes.has('d-none'), true); assert.equal(writes.length, 0, 'Never repaint the superseded workspace');
    if (initialWorkspace !== null) {
      assert.equal(calls.length, 1, 'A genuine workspace departure must remain cancelled without bootstrap retry');
      return;
    }
    assert.equal(calls.length, 2, 'Hydration supersedes the one-shot badge read and starts a source-pinned replacement');
    assert.equal(new URL(calls[1].url, w.location.href).searchParams.get('workspaceId'), hydratedWorkspace);
    assert.equal(calls[1].init.headers.get('X-Taxonomy-Workspace-Id'), hydratedWorkspace);
    calls[1].resolve(new Response(JSON.stringify({workspaceId: hydratedWorkspace, username: 'current', displayName: hydratedWorkspace, currentBranch: 'draft/current'})));
    await settle();
    assert.equal(classes.has('d-none'), false, 'The reconciled badge becomes visible');
    assert.deepEqual(writes, ['👤 current']);
    assert.ok(badge.title.includes(hydratedWorkspace));
    assert.ok(badge.title.includes('draft/current'));
  });
}

test('workspace current still rejects a resolved workspace departure and an aborted hydration read', async () => {
  for (const aborted of [false, true]) {
    let releaseBody;
    const app = await loadWorkspaceFeatures(async () => ({ok: true,
      json: () => new Promise(resolve => { releaseBody = resolve; })}));
    const runtime = app.context.window.__TaxonomyAnalysisSessionContext.runtime;
    runtime.workspaceId = aborted ? null : 'workspace-A';
    const controller = new AbortController();
    const result = app.context.window.TaxonomyWorkspaceApi.current({signal: controller.signal});
    while (!releaseBody) await new Promise(resolve => setImmediate(resolve));
    runtime.workspaceId = 'workspace-B';
    if (aborted) controller.abort();
    releaseBody({workspaceId: 'workspace-B'});
    await assert.rejects(result, error => error.code === 'ABORTED');
  }
});

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
