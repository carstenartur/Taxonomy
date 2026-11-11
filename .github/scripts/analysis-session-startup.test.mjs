import test, {afterEach} from 'node:test';
import canonicalFixture from './canonical-api-fixture.cjs';
afterEach(() => canonicalFixture.dispose());
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const sourceRoot = '../../taxonomy-app/src/main/resources/static/js/core/';
const draftSource = await readFile(
  new URL(`${sourceRoot}taxonomy-analysis-session-draft.js`, import.meta.url),
  'utf8'
);
const projectsSource = await readFile(
  new URL(`${sourceRoot}taxonomy-analysis-session-projects.js`, import.meta.url),
  'utf8'
);

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function createProbeHarness(request) {
  const runtime = {
    workspaceId: 'ws-1',
    version: null,
    restoring: false,
    invalidating: false,
    conflict: false,
    saveTimer: null
  };
  const context = {
    S: { taxonomyData: [] },
    runtime,
    AUTOSAVE_DELAY_MS: 900,
    MAX_RESTORE_ATTEMPTS: 1,
    text: key => key,
    businessTextElement: () => ({ value: '', classList: { add() {} } }),
    currentPayload: () => ({ businessText: '' }),
    comparable: value => JSON.stringify(value),
    meaningful: () => false,
    draftEndpoint: () => '/api/analysis-drafts/ws-1',
    jsonRequest: request,
    hasDerivedAnalysis: () => false,
    isStale: () => false,
    showActionAlert() {},
    showStaleActions() {},
    openRequirementDialog() {}
  };
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    TaxonomyBrowse: null,
    TaxonomyScoring: null,
    _taxonomyCurrentScores: null,
    _currentProvisionalRelations: [],
    console,
    clearTimeout() {},
    setTimeout() {
      return 1;
    }
  };
  const document = {
    addEventListener() {},
    dispatchEvent() {},
    getElementById() {
      return null;
    },
    querySelector() {
      return null;
    },
    querySelectorAll() {
      return [];
    }
  };

  vm.runInNewContext(draftSource, {
    window,
    document,
    console,
    Array,
    Boolean,
    CustomEvent: class CustomEvent {},
    JSON,
    Object,
    Promise,
    Set,
    String,
    encodeURIComponent
  }, { filename: 'taxonomy-analysis-session-draft.js' });

  return { context, runtime };
}

function createStartupHarness(workspaceResponse, loadDraft = async () => null, metadata = {}) {
  const pendingStates = [], documentListeners = new Map(), windowListeners = new Map();
  const observations = [];
  const runtime = {
    initialized: false,
    workspaceId: null,
    version: null,
    restoring: false,
    invalidating: false,
    conflict: false,
    draftDecisionPending: false,
    lastObservedComparable: null
  };
  const context = {
    S: {},
    runtime,
    CHANGE_POLL_MS: 1500,
    language: () => 'en',
    text: key => key,
    businessTextElement: () => null,
    jsonRequest(url, options) {
      assert.equal(url, '/api/workspace/current');
      assert.equal(options.method, 'GET');
      return typeof workspaceResponse === 'function' ? workspaceResponse() : workspaceResponse;
    },
    rememberedWorkspaceId: () => null,
    rememberWorkspaceId() {},
    installWorkspaceFetchRouting() {},
    installWorkspaceEventSourceRouting() {},
    currentPayload: () => ({ businessText: '' }),
    comparable: value => JSON.stringify(value),
    isStale: () => false,
    statusArea: () => null,
    showActionAlert() {},
    invalidate() {},
    queueStaleActions() {},
    queueSave() {},
    deleteDraft: async () => undefined,
    saveDraft: async () => undefined,
    loadDraft(options) {
      return Promise.resolve(loadDraft(options)).then(view => {
        runtime.restoring = false;
        context.setDraftDecisionPending(false);
        return view;
      });
    },
    reconcileDraft() { observations.push("check"); return Promise.resolve(true); },
    serverUnavailable() { observations.push("unavailable"); },
    setDraftDecisionPending(pending) {
      runtime.draftDecisionPending = pending;
      pendingStates.push(pending);
    }
  };
  const document = {
    readyState: 'complete',
    visibilityState: 'visible',
    addEventListener(name, listener) {
      if (!documentListeners.has(name)) documentListeners.set(name, []);
      documentListeners.get(name).push(listener);
    },
    getElementById() {
      return null;
    },
    dispatchEvent(event) {
      for (const listener of documentListeners.get(event.type) || []) listener(event);
      return true;
    }
  };
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    TaxonomyI18n: null,
    ...metadata,
    console,
    location: { assign() {} },
    setInterval() {
      return 1;
    },
    addEventListener(name, listener) {
      if (!windowListeners.has(name)) windowListeners.set(name, []);
      windowListeners.get(name).push(listener);
    }
  };
  class MutationObserver {
    observe() {}
  }

  vm.runInNewContext(projectsSource, {
    CustomEvent: class {constructor(type, options) {this.type = type; this.detail = options?.detail;}},
    window,
    document,
    console,
    Array,
    Boolean,
    Date,
    JSON,
    Math,
    MutationObserver,
    Object,
    Promise,
    Set,
    String,
    encodeURIComponent
  }, { filename: 'taxonomy-analysis-session-projects.js' });

  return { context, runtime, pendingStates, observations, document, window, session: window.TaxonomyAnalysisSession,
    async emit(name, detail, target = 'document') {
      const listeners = (target === 'document' ? documentListeners : windowListeners).get(name) || [];
      for (const listener of listeners) await listener({ type: name, detail, persisted: true });
    } };
}

test('remembered-workspace probe failure keeps the restoration barrier active', async () => {
  const failure = Object.assign(new Error('Remembered workspace unavailable'), {
    status: 403
  });
  const harness = createProbeHarness(async () => {
    throw failure;
  });

  await assert.rejects(
    harness.context.loadDraft({ probe: true }),
    /Remembered workspace unavailable/
  );

  assert.equal(harness.runtime.restoring, true);
  assert.equal(harness.runtime.draftDecisionPending, true);
});

test('workspace discovery is guarded before analysis or initial autosave can start', async () => {
  const workspace = deferred();
  const harness = createStartupHarness(workspace.promise);

  assert.equal(harness.runtime.initialized, true);
  assert.equal(harness.runtime.restoring, true);
  assert.equal(harness.runtime.draftDecisionPending, true);

  workspace.resolve(null);
  await workspace.promise;
  await Promise.resolve();
  await Promise.resolve();

  assert.equal(harness.runtime.restoring, false);
  assert.equal(harness.runtime.draftDecisionPending, false);
  assert.equal(harness.pendingStates.at(0), true);
  assert.equal(harness.pendingStates.at(-1), false);
});


test('analysis readiness waits for workspace discovery, including an explicitly central result', async () => {
  const pending = deferred();
  const h = createStartupHarness(pending.promise);
  assert.equal(h.session.state().ready, false);
  pending.resolve(null);
  await pending.promise;
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.session.state().ready, true);
  assert.equal(h.session.state().workspaceId, null);
});

test('failed workspace discovery is not confused with a ready central context', async () => {
  const pending = deferred();
  const h = createStartupHarness(pending.promise);
  pending.reject(new Error('workspace service unavailable'));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.session.state().ready, false);
});


test('workspace readiness closes again for every unresolved mutation or conflict barrier', async () => {
  const workspace = deferred();
  const harness = createStartupHarness(workspace.promise);
  workspace.resolve(null);
  await workspace.promise;
  await Promise.resolve();
  assert.equal(harness.session.state().ready, true);
  for (const barrier of ['restoring', 'invalidating', 'resetting', 'conflict', 'serverUnconfirmed']) {
    harness.runtime[barrier] = true;
    assert.equal(harness.session.state().ready, false, barrier);
    harness.runtime[barrier] = false;
    assert.equal(harness.session.state().ready, true, barrier);
  }
});


test('initialization promise waits for workspace discovery and accepts explicit central context', async () => {
  const pending = deferred(), h = createStartupHarness(pending.promise);
  assert.equal(typeof h.session.whenInitialized, 'function');
  let settled = false;
  const ready = h.session.whenInitialized().then(value => { settled = true; return value; });
  await Promise.resolve(); assert.equal(settled, false);
  pending.resolve(null);
  assert.equal(await ready, true);
  assert.equal(h.session.state().ready, true);
});

test('initialization promise also waits for the saved draft and reports workspace failures', async () => {
  const workspace = deferred(), draft = deferred();
  const h = createStartupHarness(workspace.promise, () => draft.promise);
  assert.equal(typeof h.session.whenInitialized, 'function');
  let settled = false;
  const ready = h.session.whenInitialized().then(value => { settled = true; return value; });
  workspace.resolve({ workspaceId: 'ws-ready' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(settled, false);
  draft.resolve(null); assert.equal(await ready, true);
  const failed = deferred(), unavailable = createStartupHarness(failed.promise);
  failed.reject(new Error('workspace unavailable'));
  assert.equal(await unavailable.session.whenInitialized(), false);
});

const loaderSource = await readFile(new URL(`${sourceRoot}taxonomy-analysis-session.js`, import.meta.url), 'utf8');
function loaderHarness() {
  const scripts = [], window = { console: { error() {} } };
  const document = { head: { appendChild(script) { scripts.push(script); } },
    createElement() { const script = new EventTarget(); script.dataset = {}; return script; } };
  vm.runInNewContext(loaderSource, { window, document });
  return { window, scripts };
}
test('ordered loader readiness includes the last module asynchronous initialization', async () => {
  const h = loaderHarness(), initialization = deferred();
  assert.equal(typeof h.window.TaxonomyAnalysisSessionReady?.then, 'function');
  let settled = false;
  const ready = h.window.TaxonomyAnalysisSessionReady.then(value => { settled = true; return value; });
  for (let index = 0; index < h.scripts.length; index++) {
    if (h.scripts[index].src.endsWith('/taxonomy-analysis-session-projects.js')) {
      h.window.TaxonomyAnalysisSession = { whenInitialized: () => initialization.promise };
    }
    h.scripts[index].dispatchEvent(new Event('load'));
  }
  await Promise.resolve(); assert.equal(settled, false);
  initialization.resolve(true); assert.equal(await ready, true);
});
test('mandatory session module failure settles loader readiness as false', async () => {
  const h = loaderHarness();
  assert.equal(typeof h.window.TaxonomyAnalysisSessionReady?.then, 'function');
  h.scripts[0].dispatchEvent(new Event('error'));
  assert.equal(await h.window.TaxonomyAnalysisSessionReady, false);
});


test('server observation, visible-tab return and reconnection trigger read-only draft checks', async () => {
  const h = createStartupHarness(Promise.resolve({ workspaceId: 'ws-ready' }));
  await h.session.whenInitialized();
  await h.emit('taxonomy:server-contact', { available: true });
  await h.emit('visibilitychange');
  await h.emit('online', null, 'window');
  await h.emit('pageshow', null, 'window');
  assert.deepEqual(h.observations, ['check', 'check', 'check', 'check']);
  h.document.visibilityState = 'hidden';
  await h.emit('visibilitychange');
  assert.equal(h.observations.length, 4);
  h.document.visibilityState = 'visible';
  await h.emit('taxonomy:server-contact', { available: false });
  assert.equal(h.observations.at(-1), 'unavailable');
});


test('an initial workspace outage is retried when server observation reconnects', async () => {
  const initial = deferred();
  let connected = false;
  const h = createStartupHarness(() => connected
    ? Promise.resolve({ workspaceId: 'reconnected-workspace' }) : initial.promise);
  initial.reject(new Error('server offline during page load'));
  await h.session.whenInitialized();
  assert.equal(h.session.state().ready, false);
  connected = true;
  await h.emit('taxonomy:server-contact', { available: true });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.session.state().ready, true);
  assert.equal(h.runtime.workspaceId, 'reconnected-workspace');
});

const gitStatusSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/workspace/taxonomy-git-status.js', import.meta.url), 'utf8');
function gitStatusHarness(fetcher) {
  const events = [], authEvents = [], documentListeners = new Map();
  const bar = { innerHTML: '', classList: { remove() {} }, querySelector() { return null; } };
  const document = {
    addEventListener(name, listener) { documentListeners.set(name, listener); },
    getElementById: () => bar,
    dispatchEvent(event) {
      if (event.type === 'taxonomy:server-contact') events.push(event);
      else if (event.type === 'taxonomy-api-auth-failure') authEvents.push(event);
    }
  };
  const window = { addEventListener() {}, TaxonomyUtils: { escapeHtml: String },
    TaxonomyI18n: { t: key => key } };
  const sandbox = { window, document, fetch: fetcher, TaxonomyUtils: window.TaxonomyUtils,
    TaxonomyI18n: window.TaxonomyI18n, AbortController, CustomEvent,
    setInterval() { return 1; }, clearInterval() {} };
  canonicalFixture.install(sandbox, ['api/workspace-api.js']);
  vm.runInContext(gitStatusSource, sandbox);
  return { events, authEvents, bar, refresh: () => { documentListeners.get('DOMContentLoaded')(); }, window };
}

test('existing git observation publishes successful and failed server contact without caching', async () => {
  let connected = false;
  const h = gitStatusHarness(async (url, options) => {
    assert.equal(options.cache, 'no-store');
    if (!connected) return { ok: false, status: 403 };
    return { ok: true, json: async () => ({}) };
  });
  h.refresh();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.events.length, 1);
  assert.equal(h.events[0].type, 'taxonomy:server-contact');
  assert.equal(h.events[0].detail.available, false);
  assert.equal(h.events[0].detail.status, 403);
  assert.equal(h.authEvents.length, 1, 'Central authentication handling still observes the forbidden response');
  assert.equal(h.authEvents[0].detail.status, 403);
  assert.equal(h.bar.innerHTML, '', 'Canonical authentication does not paint a Git error view');
  connected = true;
  h.window.TaxonomyGitStatus.refresh();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.events[1].detail.available, true);
});

test('an obsolete git observation cannot report loss of the current connection', async () => {
  const first = deferred();
  let calls = 0;
  const h = gitStatusHarness(async () => ++calls === 1 ? first.promise
    : { ok: true, json: async () => ({}) });
  h.refresh();
  h.window.TaxonomyGitStatus.refresh();
  await new Promise(resolve => setImmediate(resolve));
  first.reject(new Error('late failure from old connection'));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.events.length, 1);
  assert.equal(h.events[0].detail.available, true);
});

for (const [name, change] of [
  ['workspace', h => { h.window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'other'; }],
  ['analysis generation', h => { h.window.__TaxonomyAnalysisSessionContext.runtime.analysisGeneration++; }],
  ['runtime replacement', h => { h.window.__TaxonomyAnalysisSessionContext.runtime = {workspaceId:'original',analysisGeneration:1}; }],
  ['same-branch context navigation', h => { h.selection = {contextId:'new-context',branch:'draft',commitId:'new-commit'}; }],
  ['context navigation', h => { h.selection = {contextId:'new-context',branch:'other',commitId:'new-commit'}; }]
]) test(`an obsolete forbidden git observation cannot report connectivity for a new ${name}`, async () => {
  const pending = deferred();
  const h = gitStatusHarness(() => pending.promise);
  h.window.__TaxonomyAnalysisSessionContext = {runtime:{workspaceId:'original',analysisGeneration:1}};
  h.selection = {contextId:'old-context',branch:'draft',commitId:'old-commit'};
  h.window.TaxonomyContextBar = {getCurrentContext:()=>h.selection};
  h.refresh(); await new Promise(resolve=>setImmediate(resolve)); change(h);
  pending.resolve({ok:false,status:403,json:async()=>({})});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.events.length,0,'A forbidden response from an old source does not change current connectivity');
  assert.equal(h.authEvents.length,1,'Source fencing does not bypass central authentication handling');
});

test('an unauthorized current git poll preserves both connectivity and central authentication signals', async () => {
  const h = gitStatusHarness(async () => ({ok:false,status:401,json:async()=>({})}));
  h.refresh(); await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.events.length,1); assert.equal(h.events[0].detail.available,false);
  assert.equal(h.events[0].detail.status,401); assert.equal(h.authEvents.length,1);
  assert.equal(h.authEvents[0].detail.status,401); assert.equal(h.bar.innerHTML,'');
});


test('draft initialization settles independently while initial source publications keep analysis admission closed', async () => {
  const lifecycle = deferred(), workspace = deferred(), bar = deferred(), publications = [];
  const scope = {}, chrome = {}; let repositoryContext = null;
  const h = createStartupHarness(Promise.resolve({workspaceId: 'ws-ready'}), async () => null, {
    TaxonomyArchitectureWorkflow: {getScope: () => scope}, TaxonomyAppChrome: {getContext: () => chrome},
    TaxonomyArchitectureLifecycle: {ready: () => lifecycle.promise.then(() => {scope.repositoryId = 'repo-a'; scope.branch = 'draft'; publications.push('working stand');})},
    TaxonomyArchitectureWorkspace: {ready: () => workspace.promise.then(() => {chrome.architecture = {repositoryId: 'repo-a', workspaceScopeKey: 'ws-ready', branch: 'draft', modelStand: null}; publications.push('analysis source'); return true;})},
    TaxonomyContextBar: {getCurrentContext: () => repositoryContext, fetchAndRender: () => bar.promise.then(value => {repositoryContext = value; publications.push('repository context'); return value;})}
  });
  const notifications = [];
  h.document.addEventListener('taxonomy:analysis-source-ready', event => notifications.push({workspaceId: event.detail.workspaceId, ready: h.session.state().ready}));
  canonicalFixture.install({window: h.window, document: h.document, fetch: async () => Response.json({})});
  assert.equal(await h.session.whenInitialized(), true, 'The draft-only promise must not wait for a source that awaits it');
  let current = h.window.TaxonomyApiClient.captureContext();
  assert.equal(h.session.state().ready, false); assert.deepEqual(notifications, []);
  lifecycle.resolve(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(publications, ['working stand']); assert.equal(current(), false, 'Initial working stand changes the frozen request source');
  current = h.window.TaxonomyApiClient.captureContext(); assert.equal(h.session.state().ready, false);
  workspace.resolve(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(publications, ['working stand', 'analysis source']); assert.equal(current(), false, 'Initial analysis source changes the frozen request source');
  current = h.window.TaxonomyApiClient.captureContext(); assert.equal(h.session.state().ready, false);
  bar.resolve({contextId: 'initial', branch: 'draft', commitId: null}); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(publications, ['working stand', 'analysis source', 'repository context']);
  assert.equal(current(), false, 'Initial ContextBar publication changes the frozen request source');
  assert.equal(h.session.state().ready, true);
  assert.deepEqual(notifications, [{workspaceId: 'ws-ready', ready: true}]);
});

for (const boundary of ['workspace metadata', 'repository context']) {
  test(`unavailable initial ${boundary} fails closed and the existing server check retries it`, async () => {
    let available = false, attempts = 0;
    const h = createStartupHarness(Promise.resolve({workspaceId: 'ws-ready'}), async () => null, {
      TaxonomyArchitectureWorkspace: {ready: async () => { attempts++; return boundary === 'workspace metadata' && !available ? false : true; }},
      TaxonomyContextBar: {fetchAndRender: async () => boundary === 'repository context' && !available ? null : {contextId: 'initial', branch: 'draft'}}
    });
    await h.session.whenInitialized(); await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.session.state().ready, false); assert.ok(h.observations.includes('unavailable'));
    available = true; await h.context.retryServerState();
    assert.equal(h.session.state().ready, true); assert.equal(attempts, 2);
  });
}


test('confirmed source readiness is handed off once the existing draft-confirmation barrier also opens', async () => {
  let reads = 0;
  const h = createStartupHarness(Promise.resolve({workspaceId: 'ws-ready'}), async () => null, {
    TaxonomyContextBar: {fetchAndRender: async () => {reads++; return {contextId: 'initial', branch: 'draft'};}}
  });
  const notifications = [];
  h.document.addEventListener('taxonomy:analysis-source-ready', event => notifications.push({workspaceId: event.detail.workspaceId, ready: h.session.state().ready}));
  h.runtime.serverUnconfirmed = true;
  await h.session.whenInitialized(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.runtime.sourceReady, true); assert.equal(h.session.state().ready, false);
  assert.deepEqual(notifications, [], 'Admission consumers must not lose their one notification while the draft is unconfirmed');
  h.runtime.serverUnconfirmed = false;
  await h.context.retryServerState();
  assert.deepEqual(notifications, [{workspaceId: 'ws-ready', ready: true}]);
  await h.context.retryServerState();
  assert.equal(notifications.length, 1); assert.equal(reads, 1, 'Confirming the draft does not repeat successful source reads');
  h.runtime.serverUnconfirmed = true;
  h.context.reconcileDraft = async () => {h.runtime.serverUnconfirmed = false; return true;};
  await h.context.retryServerState();
  assert.deepEqual(notifications.map(n => n.workspaceId), ['ws-ready', 'ws-ready']);
  await h.context.retryServerState();
  assert.equal(notifications.length, 2, 'Repeated observations after reconnection share one readiness handoff');
});

test('the first ContextBar publication does not discard the authoritative initial workspace', async () => {
  const initialContext = deferred(), initialWorkspace = deferred(), requests = [];
  let h, actualBar, contextReads = 0;
  h = createStartupHarness(() => Promise.resolve().then(async () => {
    const response = await h.window.TaxonomyAnalysisSessionApi.request('/api/workspace/current', {method: 'GET'});
    return response.status === 204 ? null : response.json();
  }), async () => null, {
    TaxonomyContextBar: {fetchAndRender: () => actualBar.fetchAndRender('absent-context-container')},
    TaxonomyArchitectureWorkspace: {ready: () => h.session.whenInitialized()}
  });
  const sandbox = {
    window: h.window, document: h.document, console,
    TaxonomyI18n: {t: key => key}, TaxonomyUtils: {escapeHtml: String},
    setInterval: () => 1, clearInterval() {},
    fetch: async (url, options) => {
      const route = new URL(url, 'https://example.test').pathname;
      requests.push({route, options});
      if (route === '/api/workspace/current') return initialWorkspace.promise;
      assert.equal(route, '/api/context/current');
      if (++contextReads === 1) return initialContext.promise;
      return Response.json({contextId: 'initial-context', branch: 'draft', commitId: null, mode: 'EDITABLE'});
    }
  };
  canonicalFixture.install(sandbox, ['api/analysis-session-api.js', 'api/workspace-api.js', 'versioning/taxonomy-context-bar.js']);
  actualBar = h.window.TaxonomyContextBar;
  actualBar.init('absent-context-container');
  await new Promise(resolve => setImmediate(resolve));
  initialContext.resolve(Response.json({contextId: 'initial-context', branch: 'draft', commitId: null, mode: 'EDITABLE'}));
  await new Promise(resolve => setImmediate(resolve));
  initialWorkspace.resolve(Response.json({workspaceId: 'workspace-initial'}));
  assert.equal(await h.session.whenInitialized(), true,
    'Publishing the initial repository context must not lose the authoritative private workspace');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.session.state().workspaceId, 'workspace-initial');
  assert.equal(h.session.state().ready, true);
  assert.equal(h.observations.includes('unavailable'), false);
});

test('current ContextBar readiness observers share a read while a departed source still aborts it', async () => {
  const responses = [], requests = [], source = {branch: 'draft'}, listeners = new Map();
  const window = {location: new URL('https://example.test/'),
    TaxonomyArchitectureWorkflow: {getScope: () => source}, addEventListener() {}};
  const document = {getElementById: () => null,
    addEventListener(type, listener) {if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(listener);},
    dispatchEvent(event) {for (const listener of listeners.get(event.type) || []) listener(event);}};
  const sandbox = {window, document, console, TaxonomyI18n: {t: key => key}, TaxonomyUtils: {escapeHtml: String},
    setInterval: () => 1, clearInterval() {}, fetch: (url, options) => {
      const response = deferred(); responses.push(response); requests.push({url, options}); return response.promise;
    }};
  canonicalFixture.install(sandbox, ['api/workspace-api.js', 'versioning/taxonomy-context-bar.js']);
  const bar = window.TaxonomyContextBar;
  const first = bar.fetchAndRender('absent-context-container', {joinCurrent: true});
  const sameSource = bar.fetchAndRender('absent-context-container', {joinCurrent: true});
  const firstRequestCount = requests.length;
  const initial = {contextId: 'initial-context', branch: 'draft', commitId: null, mode: 'EDITABLE'};
  for (const response of responses) response.resolve(Response.json(initial));
  assert.deepEqual(await Promise.all([first, sameSource]), [initial, initial],
    'Initial source confirmation must receive the same authoritative result as the polling caller');
  assert.equal(firstRequestCount, 1, 'Concurrent current-source observers must not cancel one another');

  const old = bar.fetchAndRender('absent-context-container', {joinCurrent: true});
  source.branch = 'other';
  document.dispatchEvent({type: 'taxonomy:repository-context-changed'});
  const next = bar.fetchAndRender('absent-context-container', {joinCurrent: true});
  responses.at(-2).resolve(Response.json(initial));
  const changed = {...initial, contextId: 'other-context', branch: 'other'};
  responses.at(-1).resolve(Response.json(changed));
  assert.equal(await old, null, 'A genuinely departed source still cannot publish its response');
  assert.equal(requests.at(-2).options.signal.aborted, true);
  assert.deepEqual(await next, changed);
  assert.deepEqual(bar.getCurrentContext(), changed);
});
