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
    ReadableStream,
    WritableStream,
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

test('JSON timeout remains active while the response body is pending', async () => {
  const app = loadClient(async () => ({
    ok: true, status: 200, headers: new Headers(),
    json: () => new Promise(() => {})
  }));
  const outcome = await Promise.race([
    app.client.getJson('/api/context/current', {timeoutMillis: 10})
      .then(() => 'delivered', error => error.code),
    new Promise(resolve => setTimeout(() => resolve('body timeout was cleared at headers'), 80))
  ]);
  assert.equal(outcome, 'TIMEOUT');
});

test('a body from a superseded navigation selection cannot repaint the same workspace', async () => {
  let releaseBody;
  const app = loadClient(async () => ({
    ok: true, status: 200, headers: new Headers(),
    json: () => new Promise(resolve => { releaseBody = resolve; })
  }));
  const runtime = {workspaceId: 'personal', analysisGeneration: 1};
  let context = {contextId: 'older', branch: 'draft', commitId: 'a'.repeat(40)};
  app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
  app.context.window.TaxonomyContextBar = {getCurrentContext: () => context};
  const request = app.client.getJson('/api/context/compare');
  await new Promise(resolve => setImmediate(resolve));
  context = {contextId: 'newer', branch: 'review', commitId: 'b'.repeat(40)};
  releaseBody({changes: ['old navigation']});
  await assert.rejects(request, error => error.code === 'ABORTED');
});

test('expected domain statuses are inspectable while authentication errors always reject', async () => {
  let status = 409;
  const app = loadClient(async () => Response.json({detail: 'Domain response'}, {status}));
  const response = await app.client.request('/api/architecture/relations/source/type/target',
    {method: 'PUT'}, {acceptedStatuses: [409, 401, 403]});
  assert.equal(response.status, 409);
  assert.equal((await response.json()).detail, 'Domain response');
  for (status of [401, 403]) {
    await assert.rejects(app.client.request('/api/architecture/relations/source/type/target',
      {method: 'PUT'}, {acceptedStatuses: [409, 401, 403]}), error => error.status === status);
  }
  assert.deepEqual(app.events.filter(event => event.type === 'taxonomy-api-auth-failure')
    .map(event => event.detail.status), [401, 403]);
});

for (const source of ['init', 'Request']) {
  test(`the canonical request retains a caller signal supplied by ${source}`, async () => {
    const app = loadClient(abortingFetch());
    const controller = new AbortController();
    const input = source === 'Request'
      ? new Request('https://taxonomy.example.test/api/context/current', {signal: controller.signal})
      : '/api/context/current';
    const pending = app.client.request(input, source === 'init' ? {signal: controller.signal} : {}, {timeoutMillis: 20});
    controller.abort();
    const outcome = await Promise.race([
      pending.then(() => 'delivered', error => error.code),
      new Promise(resolve => setTimeout(() => resolve('caller signal was lost'), 80))
    ]);
    assert.equal(outcome, 'ABORTED');
  });
}

test('authentication failure invalidates another pending interactive body without navigation', async () => {
  let releaseBody;
  const app = loadClient(async url => String(url).includes('/denied')
    ? Response.json({detail: 'Session expired'}, {status: 401})
    : {ok: true, status: 200, headers: new Headers(), json: () => new Promise(resolve => {releaseBody = resolve;})});
  const pending = app.client.getJson('/api/context/current');
  await new Promise(resolve => setImmediate(resolve));
  await assert.rejects(app.client.getJson('/api/denied'), error => error.status === 401);
  releaseBody({branch: 'private old session'});
  await assert.rejects(pending, error => error.code === 'ABORTED');
});

test('account context can finish during initial workspace hydration', async () => {
  let releaseBody;
  const app = loadClient(async () => ({ok: true, status: 200, headers: new Headers(),
    json: () => new Promise(resolve => {releaseBody = resolve;})}));
  const runtime = {workspaceId: null, analysisGeneration: 0};
  app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
  const pending = app.client.getAccountContext();
  await new Promise(resolve => setImmediate(resolve));
  runtime.workspaceId = 'hydrated';
  releaseBody({administrator: false});
  assert.deepEqual(await pending, {administrator: false});
});

test('raw response clones retain guards on every supported body reader', async () => {
  for (const reader of ['json', 'text', 'blob', 'arrayBuffer', 'formData']) {
    const app = loadClient(async () => Response.json({value: 'old'}));
    const runtime = {workspaceId: 'original', analysisGeneration: 0};
    app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
    const response = await app.client.request('/api/relations');
    const clone = response.clone();
    runtime.workspaceId = 'changed';
    await assert.rejects(clone[reader](), error => error.code === 'ABORTED', reader);
  }
});

test('named requests preserve prefix and exact central/header pins without rerouting durable work', async () => {
  const calls = [];
  const app = loadClient(async (url, init) => {calls.push({url, init}); return Response.json({ok: true});});
  app.context.window.TaxonomyI18n = {resolveUrl: url => url.startsWith('/api/') ? '/taxonomy' + url : url};
  app.context.window.__TaxonomyAnalysisSessionContext = {runtime: {workspaceId: 'active'}};
  await app.client.sendJson('/api/projects/1/requirements', {text: 'review'});
  assert.equal(calls[0].url, '/taxonomy/api/projects/1/requirements?workspaceId=active');
  assert.equal(calls[0].init.headers.get('X-Taxonomy-Workspace-Id'), 'active');
  await app.client.getJson('/api/context/current?workspaceId=stale', {
    headers: {'X-Taxonomy-Workspace-Id': ''}, pinnedWorkspaceId: ''
  });
  await app.client.requestJson('/api/context/current?workspaceId=stale', {
    headers: {'X-Taxonomy-Workspace-Id': ''}
  });
  assert.equal(calls[2].init.headers.get('X-Taxonomy-Workspace-Id'), '');
  assert.equal(new URL(calls[2].url, app.context.window.location.href).searchParams.get('workspaceId'), '');
  await app.client.getJson('/api/analysis-runs/retained', {pinnedWorkspaceId: 'original', contextSensitive: false});
  assert.equal(calls[3].init.headers.get('X-Taxonomy-Workspace-Id'), 'original');
  assert.equal(new URL(calls[3].url, app.context.window.location.href).searchParams.get('workspaceId'), 'original');
  await app.client.getJson('/api/account/me', {workspaceScoped: false, contextSensitive: false});
  assert.equal(calls[4].init.headers.has('X-Taxonomy-Workspace-Id'), false);
  assert.equal(new URL(calls[4].url, app.context.window.location.href).searchParams.has('workspaceId'), false);
});

test('late transport adapters compose once without consulting the legacy global fetch chain', async () => {
  let network = 0, admission = 0, duplicate = 0;
  const app = loadClient(async () => {network++; return Response.json({ok: true});});
  assert.equal(app.client.installTransportAdapter('fixture', next => (url, init) => {
    admission++; return next(url, init);
  }), true);
  assert.equal(app.client.installTransportAdapter('fixture', next => {duplicate++; return next;}), false);
  app.context.window.fetch = () => {throw new Error('Named calls must not delegate through global wrappers');};
  await app.client.getJson('/api/context/current');
  assert.equal(network, 1); assert.equal(admission, 1); assert.equal(duplicate, 0);
});

test('named main analysis emits one real operation admission and terminal event', async () => {
  const app = loadClient(async () => Response.json({status: 'SUCCESS', scores: {BP: 80}}));
  app.context.window.__TaxonomyAnalysisSessionContext = {runtime: {}, S: {}, language: () => 'en'};
  app.context.window.setTimeout = setTimeout;
  app.context.document.addEventListener = () => {};
  app.context.document.getElementById = () => null;
  const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-operation-coordinator.js', import.meta.url), 'utf8');
  vm.runInContext(source, app.context);
  await app.client.sendJson('/api/analyze', {text: 'deterministic fixture'});
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.deepEqual(app.events.filter(event => event.type === 'taxonomy:operation-state')
    .map(event => event.detail.status), ['RUNNING', 'SUCCEEDED']);
});

test('raw stream readers reject bytes from a changed workspace and retain the original deadline', async () => {
  for (const changed of [true, false]) {
    let controller;
    const body = new ReadableStream({start(value) {controller = value;}});
    const app = loadClient(async () => new Response(body));
    const runtime = {workspaceId: 'original', analysisGeneration: 0};
    app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
    const response = await app.client.request('/api/retained-data', {}, {timeoutMillis: changed ? 100 : 15});
    const reader = response.body.getReader();
    const pending = reader.read();
    if (changed) {
      runtime.workspaceId = 'changed';
      controller.enqueue(new TextEncoder().encode('old private bytes'));
    }
    await assert.rejects(pending, error => error.code === (changed ? 'ABORTED' : 'TIMEOUT'));
    controller.close();
    reader.releaseLock();
  }
});

test('raw stream readers deliver current bytes without JSON buffering', async () => {
  const app = loadClient(async () => new Response(new Uint8Array([1, 2, 3])));
  const response = await app.client.request('/api/retained-data');
  const reader = response.body.getReader();
  assert.deepEqual((await reader.read()).value, new Uint8Array([1, 2, 3]));
  assert.equal((await reader.read()).done, true);
  reader.releaseLock();
});

test('derived tee branches fence bytes buffered before a workspace change', async () => {
  const app = loadClient(async () => new Response(new Uint8Array([1, 2, 3])));
  const runtime = {workspaceId: 'before'};
  app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
  const response = await app.client.request('/api/retained-data');
  const [left, right] = response.body.tee();
  const first = left.getReader();
  await first.read();
  runtime.workspaceId = 'after';
  const second = right.getReader();
  await assert.rejects(second.read(), error => error.code === 'ABORTED');
  await Promise.allSettled([first.cancel(), second.cancel()]);
});

test('a delayed transform cannot publish a chunk after its context is superseded', async () => {
  let emit;
  const app = loadClient(async () => new Response(new Uint8Array([1, 2, 3])));
  const runtime = {workspaceId: 'before'};
  app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
  const response = await app.client.request('/api/retained-data');
  const transform = new TransformStream({transform(chunk, controller) {
    return new Promise(resolve => {emit = () => {controller.enqueue(chunk); resolve();};});
  }});
  const reader = response.body.pipeThrough(transform).getReader();
  const pending = reader.read();
  pending.catch(() => {});
  while (!emit) await new Promise(resolve => setTimeout(resolve, 0));
  runtime.workspaceId = 'after'; emit();
  await assert.rejects(pending, error => error.code === 'ABORTED');
  await reader.cancel().catch(() => {});
});

test('network and timeout errors report the effective outgoing request ID', async () => {
  for (const kind of ['network', 'timeout']) {
    const app = loadClient(kind === 'timeout' ? abortingFetch() : async () => {throw new TypeError('offline');});
    await assert.rejects(app.client.request('/api/correlated', {headers: {'X-Request-ID': 'retained-correlation'}},
      {timeoutMillis: 10}), error => error.requestId === 'retained-correlation' && error.code === (kind === 'timeout' ? 'TIMEOUT' : 'NETWORK_ERROR'));
  }
});

test('all caller signals remain effective when options and Request/init signals coexist', async () => {
  for (const abortOptions of [true, false]) {
    const app = loadClient(abortingFetch());
    const optionController = new AbortController();
    const initController = new AbortController();
    const pending = app.client.request('/api/pending', {signal: initController.signal},
      {signal: optionController.signal});
    (abortOptions ? optionController : initController).abort();
    await assert.rejects(pending, error => error.code === 'ABORTED');
  }
});

test('project selection in the same workspace fences interactive bodies but retains durable monitoring', async () => {
  for (const durable of [false, true]) {
    let release;
    const app = loadClient(async () => ({ok: true, status: 200, headers: new Headers(),
      json: () => new Promise(resolve => {release = resolve;})}));
    let projectId = 1;
    app.context.window.TaxonomyAppChrome = {getContext: () => ({project: {id: projectId}})};
    const pending = app.client.getJson('/api/projects', {contextSensitive: !durable});
    while (!release) await new Promise(resolve => setTimeout(resolve, 0));
    projectId = 2;
    release({projectId: 1});
    if (durable) assert.deepEqual(await pending, {projectId: 1});
    else await assert.rejects(pending, error => error.code === 'ABORTED');
  }
});

test('authentication headers invalidate pending bodies before a stalled problem body finishes', async () => {
  let releaseOther, enterOther;
  const entered = new Promise(resolve => {enterOther = resolve;});
  const app = loadClient(async url => String(url).includes('/denied') ? {
    ok: false, status: 403, headers: new Headers({'X-Request-ID': 'denied-request'}),
    clone: () => ({text: () => new Promise(() => {})})
  } : {ok: true, status: 200, headers: new Headers(),
    json: () => {enterOther(); return new Promise(resolve => {releaseOther = resolve;});}});
  const other = app.client.getJson('/api/other');
  other.catch(() => {});
  await entered;
  const denied = app.client.request('/api/denied', {}, {timeoutMillis: 15});
  denied.catch(() => {});
  await new Promise(resolve => setTimeout(resolve, 0));
  const auth = app.events.filter(event => event.type === 'taxonomy-api-auth-failure');
  releaseOther({private: 'old'});
  await assert.rejects(other, error => error.code === 'ABORTED');
  assert.equal(auth.length, 1);
  assert.equal(auth[0].detail.status, 403);
  assert.equal(auth[0].detail.requestId, 'denied-request');
  await assert.rejects(denied, error => error.status === 403);
});

test('actual search handlers leave newer results untouched after old-context success or failure', async () => {
  const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-search.js', import.meta.url), 'utf8');
  for (const failure of [false, true]) {
    let release, reject;
    const app = loadClient(() => new Promise((resolve, fail) => {release = resolve; reject = fail;}));
    const runtime = {workspaceId: 'old'};
    app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
    app.context.TaxonomyI18n = {t: key => key};
    app.context.TaxonomyUtils = {escapeHtml: String};
    const area = {innerHTML: '', style: {}, dataset: {}, classList: {remove() {}, add() {}}};
    app.context.document.getElementById = id => id === 'searchResultsArea' ? area : null;
    app.context.document.addEventListener = () => {};
    app.context.document.querySelectorAll = () => [];
    await loadDomain(app, 'search-api.js', 'TaxonomySearchApi');
    vm.runInContext(source, app.context);
    app.context.window.TaxonomySearch.performSearch('query', 'graph', 10);
    runtime.workspaceId = 'new'; area.innerHTML = 'new context results';
    if (failure) reject(new Error('old context failure'));
    else release(Response.json([]));
    await new Promise(resolve => setTimeout(resolve, 0));
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(area.innerHTML, 'new context results');
  }
});

test('actual relation handlers leave a new workspace panel untouched after old read success or failure', async () => {
  const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/relations/taxonomy-relations.js', import.meta.url), 'utf8');
  for (const failure of [false, true]) {
    let release, reject;
    const app = loadClient(() => new Promise((resolve, fail) => {release = resolve; reject = fail;}));
    const runtime = {workspaceId: 'old'};
    app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
    app.context.TaxonomyI18n = {t: key => key};
    app.context.TaxonomyUtils = {escapeHtml: String};
    const content = {innerHTML: '', setAttribute() {}, replaceChildren() {this.innerHTML = '';},
      appendChild() {this.innerHTML = 'old read error';}};
    app.context.document.getElementById = id => id === 'relationsTableContainer' ? content : null;
    app.context.document.addEventListener = () => {};
    app.context.document.createElement = () => ({setAttribute() {}, appendChild() {}, append() {}, addEventListener() {}});
    await loadDomain(app, 'relations-api.js', 'TaxonomyRelationsApi');
    vm.runInContext(source, app.context);
    const pending = app.context.window.TaxonomyRelations.loadRelations(false);
    while (!release) await new Promise(resolve => setTimeout(resolve, 0));
    runtime.workspaceId = 'new'; content.innerHTML = 'new relation results';
    if (failure) reject(new Error('old read failure')); else release(Response.json([]));
    await pending.catch(() => {});
    assert.equal(content.innerHTML, 'new relation results');
  }
});

test('named global reference reads survive initial hydration but still dispatch authentication failures', async () => {
  let release;
  const app = loadClient(() => new Promise(resolve => {release = resolve;}));
  const api = await loadDomain(app, 'reference-api.js', 'TaxonomyReferenceApi');
  const pending = api.startup();
  app.context.window.__TaxonomyAnalysisSessionContext = {runtime:{workspaceId:'hydrated'}};
  release(Response.json({initialized:true}));
  assert.deepEqual(await (await pending).json(), {initialized:true});
  const denied = loadClient(async () => Response.json({detail:'expired'}, {status:403}));
  await assert.rejects((await loadDomain(denied, 'reference-api.js', 'TaxonomyReferenceApi')).about(), error => error.status === 403);
  assert.equal(denied.events.filter(event => event.type === 'taxonomy-api-auth-failure').length, 1);
});

test('a source snapshot selection fences a pending body within the same project and workspace', async () => {
  let release;
  const app = loadClient(async () => ({ok:true, status:200, headers:new Headers(),
    json: () => new Promise(resolve => {release = resolve;})}));
  let snapshotId = 'old-snapshot';
  app.context.window.TaxonomyAppChrome = {getContext: () => ({project:{id:1}, source:{snapshotId}})};
  const pending = app.client.getJson('/api/projects/1/snapshots/old-snapshot');
  while (!release) await new Promise(resolve => setTimeout(resolve, 0));
  snapshotId = 'new-snapshot'; release({snapshot:'old'});
  await assert.rejects(pending, error => error.code === 'ABORTED');
});

test('actual session routing retains query pins and central scope beneath explicit header and policy pins', async () => {
  const calls = [];
  const app = loadClient(async (url, init) => {calls.push({url,init}); return Response.json({ready:true});});
  app.context.window.TaxonomyState = {};
  app.context.window.TaxonomyI18n = {getBasePath: () => '/taxonomy',
    resolveUrl: path => String(path).startsWith('/api/') ? '/taxonomy' + path : path};
  for (const name of ['taxonomy-analysis-session-core.js','taxonomy-analysis-session-api-routing.js']) {
    const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/' + name, import.meta.url), 'utf8');
    vm.runInContext(source, app.context, {filename:name});
  }
  app.context.window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'active-B';
  await app.client.requestJson('/api/projects?workspaceId=retained-A', {method:'GET'});
  await app.client.requestJson('/api/projects?workspaceId=', {method:'GET'});
  await app.client.requestJson('/api/projects?workspaceId=retained-A', {headers:{'X-Taxonomy-Workspace-Id':'header-C'}}, {pinnedWorkspaceId:'policy-D'});
  await app.client.requestJson('/api/projects?workspaceId=retained-A', {}, {pinnedWorkspaceId:null});
  for (const [index,pin] of ['retained-A','','header-C',''].entries()) {
    assert.equal(new URL(calls[index].url, app.context.window.location.href).pathname, '/taxonomy/api/projects');
    assert.equal(new URL(calls[index].url, app.context.window.location.href).searchParams.get('workspaceId'), pin);
    assert.equal(calls[index].init.headers.get('X-Taxonomy-Workspace-Id'), pin);
  }
});

test('installation database wizard discards only the inferred workspace pin', async () => {
  const calls = [];
  const app = loadClient(async (url, init) => { calls.push({url, init}); return Response.json({}); });
  app.context.window.__TaxonomyAnalysisSessionContext = {runtime: {workspaceId: 'retained'}};
  for (const route of ['/api/admin/database-setup', '/Taxonomy/api/admin/database-setup/status']) {
    await app.client.getJson(route);
    const call = calls.at(-1);
    assert.equal(new URL(call.url, app.context.window.location.href).searchParams.has('workspaceId'), false);
    assert.equal(call.init.headers.has('X-Taxonomy-Workspace-Id'), false);
  }
  await app.client.sendJson('/Taxonomy/api/admin/database-setup/test', {}, 'POST', {pinnedWorkspaceId: 'explicit'});
  await app.client.getJson('/Taxonomy/api/admin/database-setup/status?workspaceId=');
  await app.client.getJson('/Taxonomy/api/admin/database-setup-extra');
  for (const [index, pin] of ['explicit', '', 'retained'].entries()) {
    const call = calls[index + 2];
    assert.equal(new URL(call.url, app.context.window.location.href).searchParams.get('workspaceId'), pin);
    assert.equal(call.init.headers.get('X-Taxonomy-Workspace-Id'), pin);
  }
});

function navigationEvents(context) {
  const listeners = new Map(), dispatch = context.document.dispatchEvent;
  context.document.addEventListener = (type, callback) => {
    if (!listeners.has(type)) listeners.set(type, []);
    listeners.get(type).push(callback);
  };
  context.document.dispatchEvent = event => {
    dispatch(event);
    for (const callback of listeners.get(event.type) || []) callback(event);
  };
}

test('accepted ContextBar navigation cancels idle interactive streams while retaining explicit durable pins', async () => {
  const signals = [];
  const app = await loadWorkspaceFeatures(async (url, init) => {
    if (String(url).includes('/api/context/current')) return Response.json({contextId:'accepted-context', branch:'review', commitId:'new'});
    signals.push(init.signal);
    return new Response(new ReadableStream({}));
  }, navigationEvents);
  app.context.document.getElementById = () => null;
  app.context.TaxonomyI18n = {t:key => key};
  app.context.TaxonomyUtils = {escapeHtml:String};
  app.context.clearInterval = clearInterval;
  app.context.window.addEventListener = () => {};
  const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/versioning/taxonomy-context-bar.js', import.meta.url), 'utf8');
  vm.runInContext(source, app.context);
  const interactive = await app.client.request('/api/idle-stream');
  const durable = await app.client.request('/api/job-stream', {}, {contextSensitive:false, pinnedWorkspaceId:'retained'});
  await app.context.window.TaxonomyContextBar.fetchAndRender('contextBar');
  try {
    assert.equal(signals[0].aborted, true, 'accepted navigation must stop the idle interactive network resource');
    assert.equal(signals[1].aborted, false, 'the retained durable stream must remain alive');
  } finally {
    await Promise.allSettled([interactive.body.cancel(), durable.body.cancel()]);
  }
});

test('a body child still owns navigation cancellation after another clone completes', async () => {
  let release, signal;
  let projectId = 1;
  const app = loadClient(async (_url, init) => {
    signal = init.signal;
    return {ok:true, status:200, headers:new Headers(),
      clone: () => ({ok:true, status:200, headers:new Headers(), json:async () => ({ready:true})}),
      json: () => new Promise(resolve => {release = resolve;})};
  }, navigationEvents);
  app.context.window.TaxonomyAppChrome = {getContext: () => ({project:{id:projectId}})};
  const response = await app.client.request('/api/pending-body');
  await response.clone().json();
  const pending = response.json(); pending.catch(() => {});
  while (!release) await new Promise(resolve => setTimeout(resolve, 0));
  projectId = 2;
  app.context.document.dispatchEvent(new TestCustomEvent('taxonomy:entity-context-changed'));
  const abortedAtNavigation = signal.aborted;
  release({private:'old'});
  await assert.rejects(pending, error => error.code === 'ABORTED');
  assert.equal(abortedAtNavigation, true, 'the remaining child must abort its underlying request immediately');
});

async function loadDomain(app, file, globalName) {
  const script = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/' + file, import.meta.url), 'utf8').catch(() => '');
  if (script) vm.runInContext(script, app.context, {filename: file});
  const api = app.context.window[globalName];
  assert.ok(api, globalName + ' must be a named canonical API');
  return api;
}
const remainingDomains = [
  ['search-api.js', 'TaxonomySearchApi', api => api.search('term', 'graph', 10)],
  ['dsl-api.js', 'TaxonomyDslApi', api => api.parse('architecture {}')],
  ['relations-api.js', 'TaxonomyRelationsApi', api => api.readSnapshot()],
  ['proposals-api.js', 'TaxonomyProposalsApi', api => api.review(1, 'accept', {})],
  ['hypotheses-api.js', 'TaxonomyHypothesesApi', api => api.applyForSession(1)]
];
for (const [file, name, invoke] of remainingDomains) {
  test(name + ' dispatches shared authentication failures', async () => {
    for (const status of [401, 403]) {
      const app = loadClient(async () => Response.json({detail: 'expired'}, {status}));
      const api = await loadDomain(app, file, name);
      await assert.rejects(invoke(api), error => error.status === status);
      assert.equal(app.events.filter(event => event.type === 'taxonomy-api-auth-failure').length, 1);
    }
  });
  test(name + ' rejects its actual response body after workspace changes', async () => {
    let release;
    const app = loadClient(async () => ({ok: true, status: 200, headers: new Headers(),
      json: () => new Promise(resolve => {release = resolve;})}));
    const runtime = {workspaceId: 'one'};
    app.context.window.__TaxonomyAnalysisSessionContext = {runtime};
    const api = await loadDomain(app, file, name);
    const response = await invoke(api);
    const pending = response.json();
    while (!release) await new Promise(resolve => setTimeout(resolve, 0));
    runtime.workspaceId = 'two';
    release({old: 'workspace one'});
    await assert.rejects(pending, error => error.code === 'ABORTED');
  });
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
  const lifecycle = new EventTarget();
  window.addEventListener = lifecycle.addEventListener.bind(lifecycle);
  const documentEvents = new EventTarget();
  document.addEventListener = documentEvents.addEventListener.bind(documentEvents);
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
    if (new URL(url, 'https://taxonomy.example.test').pathname === '/taxonomy/api/dsl/history') {
      return Promise.resolve(Response.json({commits:[{commitId:'7654321'}]}));
    }
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
  context.fetch = () => {throw new Error('Versioning must use named clients');};
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
  await new Promise(resolve => setImmediate(resolve));
  w.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace B';
  complete(Response.json({branch: 'other', commitId: '2345678', mode: 'EDITABLE'}));
  await opened;
  assert.equal(w.TaxonomyContextBar.getCurrentContext(), null);
  assert.equal(app.contextBar.innerHTML, undefined);
  assert.equal(app.errors.length, 0);
});

for (const staleStatus of [200, 403]) {
  test(`context opens preserve server order and the latest variant intent (earlier HTTP ${staleStatus})`, async () => {
    const pending = [];
    const committed = [];
    const app = await loadVersioningFeatures(url => new Promise(resolve => {
      const target = new URL(url, 'https://taxonomy.example.test');
      const branch = target.searchParams.get('branch');
      const commitId = target.searchParams.get('commitId');
      pending.push({branch, complete(status) {
        if (status === 200) committed.push(branch);
        resolve(status === 200 ? Response.json({branch, commitId, mode: 'EDITABLE'})
          : Response.json({detail: 'Previous selection is unavailable'}, {status}));
      }});
    }));
    const bar = app.window.TaxonomyContextBar;
    const first = app.window.TaxonomyHistorySearch.switchTo('older-selection', '1234567');
    const second = app.clickVariant();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(pending.map(open => open.branch), ['older-selection'],
      'the server cannot commit the newer open while the older open is in flight');

    pending[0].complete(staleStatus);
    await first;
    assert.equal(bar.getCurrentContext(), null);
    assert.equal(app.openedVariantModal(), 0);
    await new Promise(resolve => setImmediate(resolve));

    pending[1].complete(200);
    await second;
    assert.deepEqual(committed, staleStatus === 200 ? ['older-selection', 'draft/one + two'] : ['draft/one + two']);
    assert.equal(bar.getCurrentContext().commitId, '7654321');
    assert.ok(app.contextBar.innerHTML.includes('draft/one + two'));
    assert.equal(app.openedVariantModal(), 1);
    assert.equal(app.errors.length, 0);
  });
}

test('back follows a pending context open without showing its superseded variant dialog', async () => {
  const pending = [];
  const app = await loadVersioningFeatures(url => new Promise(resolve => {
    pending.push({path: new URL(url, 'https://taxonomy.example.test').pathname, resolve});
  }));
  const bar = app.window.TaxonomyContextBar;
  const opening = app.clickVariant();
  const backing = bar.back();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(pending.map(request => request.path), ['/taxonomy/api/context/open']);

  pending[0].resolve(Response.json({branch: 'draft/one + two', commitId: '7654321', mode: 'EDITABLE'}));
  await opening;
  assert.equal(app.openedVariantModal(), 0);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(pending[1].path, '/taxonomy/api/context/back');
  pending[1].resolve(Response.json({branch: 'original-branch', commitId: '1234567', mode: 'EDITABLE'}));
  await backing;
  assert.equal(bar.getCurrentContext().branch, 'original-branch');
});

test('a queued context open cannot mutate a different workspace after a pin change', async () => {
  const pending = [];
  const app = await loadVersioningFeatures(() => new Promise(resolve => pending.push(resolve)));
  const bar = app.window.TaxonomyContextBar;
  const first = bar.open('older-selection', '1234567', false);
  const queued = app.clickVariant();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(pending.length, 1);
  app.window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace B';
  pending[0](Response.json({branch: 'older-selection', commitId: '1234567', mode: 'EDITABLE'}));
  await Promise.all([first, queued]);
  assert.equal(pending.length, 1);
  assert.equal(bar.getCurrentContext(), null);
  assert.equal(app.openedVariantModal(), 0);
});

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

async function loadWorkspaceBadgeStartup() {
  const calls = [], writes = [], classes = new Set(['d-none']);
  let finishSource;
  const sourceReady = new Promise(resolve => { finishSource = resolve; });
  const app = await loadWorkspaceFeatures((url, init) => new Promise(resolve => calls.push({url, init, resolve})), scope => {
    for (const target of [scope.window, scope.document]) {
      const listeners = new Map(), originalDispatch = target.dispatchEvent?.bind(target);
      target.addEventListener = (type, callback) => {
        if (!listeners.has(type)) listeners.set(type, new Set());
        listeners.get(type).add(callback);
      };
      target.removeEventListener = (type, callback) => listeners.get(type)?.delete(callback);
      target.dispatchEvent = event => {
        originalDispatch?.(event);
        for (const callback of listeners.get(event.type) || []) callback(event);
        return true;
      };
    }
  });
  const w = app.context.window;
  w.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace-A';
  w.TaxonomyArchitectureWorkspace = {ready: () => sourceReady};
  app.context.setInterval = () => 1;
  app.context.clearInterval = () => {};
  app.context.TaxonomyI18n = {ready: () => Promise.resolve(), t: key => key, formatBranch: branch => branch};
  app.context.TaxonomyUtils = {escapeHtml: String};
  const badge = {title: '', classList: {remove: name => classes.delete(name)}, set textContent(value) { writes.push(value); }};
  app.context.document.getElementById = id => id === 'workspaceUserBadge' ? badge : null;
  vm.runInContext(await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/versioning/taxonomy-context-bar.js', import.meta.url), 'utf8'), app.context);
  const index = await readFile(new URL('../../taxonomy-app/src/main/resources/templates/index.html', import.meta.url), 'utf8');
  const marker = index.indexOf('// Load workspace info for the user badge');
  const start = index.lastIndexOf('<script>', marker) + '<script>'.length;
  vm.runInContext(index.slice(start, index.indexOf('</script>', marker)), app.context);
  const settle = async () => { for (let turn = 0; turn < 5; turn++) await new Promise(resolve => setImmediate(resolve)); };
  app.context.document.dispatchEvent({type: 'DOMContentLoaded'});
  await settle();
  const reads = path => calls.filter(call => new URL(call.url, w.location.href).pathname.endsWith(path));
  return {...app, w, reads, writes, classes, badge, finishSource, settle};
}

async function confirmWorkspaceBadgeSource(f, status = 200) {
  f.finishSource(true);
  await f.settle();
  assert.equal(f.reads('/api/context/current').length, 1, 'Initial readiness consumers share the current repository read');
  f.reads('/api/context/current')[0].resolve(Response.json(status === 200
    ? {contextId: 'current-A', branch: 'draft/current', commitId: '1234567', mode: 'EDITABLE'}
    : {detail: 'Repository context is unavailable'}, {status}));
  await f.settle();
}

test('returning to a branch preserves fresh work and leaves previous callbacks obsolete', async () => {
  const f = await loadWorkspaceBadgeStartup();
  await confirmWorkspaceBadgeSource(f);
  const bar = f.w.TaxonomyContextBar, client = f.w.TaxonomyApiClient;
  const original = bar.getCurrentContext();
  const firstVisit = client.captureContext();
  const page = client.captureContext({contextSensitive: false});

  async function refresh(context) {
    const read = bar.fetchAndRender('contextBar');
    f.reads('/api/context/current').at(-1).resolve(Response.json(context));
    await read;
  }

  await refresh({...original, dirty: true});
  assert.equal(firstVisit(), true, 'A dirty badge update keeps the same selection');
  await refresh({...original, contextId: 'review-B', branch: 'review', mode: 'READ_ONLY'});
  assert.equal(firstVisit(), false);
  await refresh(original);
  assert.equal(firstVisit(), false, 'Returning to the original branch cannot revive completed-work callbacks');
  assert.equal(client.captureContext()(), true);
  assert.equal(page(), true, 'An installation-wide read remains current across repository switches');
});

test('the live workspace badge waits for the initial repository context in the same workspace', async () => {
  const f = await loadWorkspaceBadgeStartup();
  assert.equal(f.reads('/api/workspace/current').length, 0, 'Do not admit the badge read before source readiness');
  await confirmWorkspaceBadgeSource(f);
  assert.equal(f.w.TaxonomyContextBar.getCurrentContext().contextId, 'current-A');
  assert.equal(f.reads('/api/workspace/current').length, 1, 'Initial readiness admits one current workspace read');
  const read = f.reads('/api/workspace/current')[0];
  read.resolve(Response.json({workspaceId: 'workspace-A', username: 'current-user', displayName: 'Current A', currentBranch: 'draft/current'}));
  await f.settle();
  assert.equal(f.classes.has('d-none'), false, 'The first repository context must not leave the current user badge permanently hidden');
  assert.deepEqual(f.writes, ['👤 current-user']);
  assert.ok(f.badge.title.includes('Current A'));
  assert.ok(f.badge.title.includes('draft/current'));
  assert.equal(read.init.signal.aborted, false);
  assert.equal(new URL(read.url, f.w.location.href).searchParams.get('workspaceId'), 'workspace-A');
  assert.equal(read.init.headers.get('X-Taxonomy-Workspace-Id'), 'workspace-A');
});

for (const stayed of [false, true]) {
  test('repository context polling handles beforeunload when the page ' + (stayed ? 'stays open' : 'leaves'), async () => {
    const f = await loadWorkspaceBadgeStartup();
    let nextPoll;
    f.context.setTimeout = (callback, delay) => {
      if (delay === 10_000) { nextPoll = callback; return 1; }
      return setTimeout(callback, delay).unref();
    };
    f.finishSource(true);
    await f.settle();
    const old = f.reads('/api/context/current')[0];
    f.w.dispatchEvent({type:'beforeunload'});
    assert.equal(old.init.signal.aborted, true);
    const whileLeaving = f.w.TaxonomyContextBar.fetchAndRender('contextBar');
    assert.equal(f.reads('/api/context/current').length, 1);
    assert.equal(await whileLeaving, null);
    old.resolve(Response.json({contextId:'old', branch:'draft', mode:'EDITABLE'}));
    await f.settle();
    assert.equal(f.w.TaxonomyContextBar.getCurrentContext(), null);
    if (!stayed) f.w.dispatchEvent({type:'pagehide'});
    nextPoll();
    await f.settle();
    assert.equal(f.reads('/api/context/current').length, stayed ? 2 : 1);
    // A retained page must also be able to poll after the browser restores it.
    if (!stayed) {
      f.w.dispatchEvent({type:'pageshow', persisted:true});
      await f.settle();
    }
    const fresh = f.reads('/api/context/current')[1];
    assert.equal(fresh.init.signal.aborted, false);
    fresh.resolve(Response.json({contextId:'new', branch:'draft', mode:'EDITABLE'}));
    await f.settle();
    assert.equal(f.w.TaxonomyContextBar.getCurrentContext().contextId, 'new');
  });
}

for (const departure of ['workspace', 'pagehide', 'authentication']) {
  test(`the live workspace badge does not start after ${departure} during initial readiness`, async () => {
    const f = await loadWorkspaceBadgeStartup();
    assert.equal(f.reads('/api/workspace/current').length, 0);
    if (departure === 'workspace') f.w.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace-B';
    else if (departure === 'pagehide') f.w.dispatchEvent({type: 'pagehide'});
    else {
      const denied = f.client.getJson('/api/denied');
      f.reads('/api/denied')[0].resolve(Response.json({detail: 'Access revoked'}, {status: 403}));
      await assert.rejects(denied, error => error.status === 403);
    }
    f.finishSource(true);
    await f.settle();
    for (const read of f.reads('/api/context/current')) read.resolve(Response.json({contextId: 'current-B', branch: 'other', commitId: '7654321', mode: 'EDITABLE'}));
    await f.settle();
    assert.equal(f.reads('/api/workspace/current').length, 0, 'A departed owner cannot start a fresh badge read');
    assert.equal(f.classes.has('d-none'), true);
    assert.deepEqual(f.writes, []);
  });
}

test('the live workspace badge remains strict after the initial repository context is confirmed', async () => {
  const f = await loadWorkspaceBadgeStartup();
  await confirmWorkspaceBadgeSource(f);
  const read = f.reads('/api/workspace/current')[0];
  assert.ok(read, 'Initial confirmation admits the current workspace read');
  const moved = f.w.TaxonomyContextBar.fetchAndRender('contextBar');
  f.reads('/api/context/current').at(-1).resolve(Response.json({contextId: 'other-A', branch: 'other', commitId: '7654321', mode: 'READ_ONLY'}));
  await moved;
  read.resolve(Response.json({workspaceId: 'workspace-A', username: 'obsolete-user', displayName: 'Old A', currentBranch: 'draft/current'}));
  await f.settle();
  assert.equal(read.init.signal.aborted, true);
  assert.equal(f.reads('/api/workspace/current').length, 1, 'A real source departure must not retry the badge');
  assert.equal(f.classes.has('d-none'), true);
  assert.deepEqual(f.writes, []);
});

test('the live workspace badge requires a confirmed initial repository context', async () => {
  const f = await loadWorkspaceBadgeStartup();
  await confirmWorkspaceBadgeSource(f, 503);
  assert.equal(f.reads('/api/workspace/current').length, 0);
  assert.equal(f.classes.has('d-none'), true);
  assert.deepEqual(f.writes, []);
});

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
