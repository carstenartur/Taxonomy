import test from 'node:test';
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

function createStartupHarness(workspaceResponse, loadDraft = async () => null) {
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
    }
  };
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    TaxonomyI18n: null,
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

  return { context, runtime, pendingStates, observations, document, session: window.TaxonomyAnalysisSession,
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
  const events = [], documentListeners = new Map();
  const bar = { innerHTML: '', classList: { remove() {} } };
  const document = {
    addEventListener(name, listener) { documentListeners.set(name, listener); },
    getElementById: () => bar,
    dispatchEvent(event) { events.push(event); }
  };
  const window = { addEventListener() {}, TaxonomyUtils: { escapeHtml: String },
    TaxonomyI18n: { t: key => key } };
  const sandbox = { window, document, fetch: fetcher, TaxonomyUtils: window.TaxonomyUtils,
    TaxonomyI18n: window.TaxonomyI18n, AbortController, CustomEvent,
    setInterval() { return 1; }, clearInterval() {} };
  vm.runInNewContext(gitStatusSource, sandbox);
  return { events, refresh: () => { documentListeners.get('DOMContentLoaded')(); }, window };
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
