import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const draftSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-draft.js',
  import.meta.url), 'utf8');
const projectsSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-projects.js',
  import.meta.url), 'utf8');
const [coreSource, uiSource, recoverySource, acceptanceSource] = await Promise.all([
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-core.js',
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-ui.js',
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-recovery.js',
  './ui-role-state-acceptance.mjs'
].map(path => readFile(new URL(path, import.meta.url), 'utf8')));

import * as isolation from './ui-role-state-isolation.mjs';
const { isolateRoleStateScenario } = isolation;
const recoveryStorageKey = 'taxonomy.recovery.active.v1';

function installHarness(options = {}) {
  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  const previousEvent = globalThis.Event;
  const calls = [];
  let stale = true;
  let currentStage = 'analyze';
  let restoring = false;
  let conflict = false;
  let visibleConflict = Boolean(options.conflictMarker);
  let currentWorkspaceId = options.workspaceId === undefined
    ? 'workspace-42' : options.workspaceId;
  const remoteStatus = options.remoteStatus === undefined
    ? 204 : options.remoteStatus;
  const storage = new Map([['unrelated-ui-preference', 'retained']]);

  const input = {
    value: 'Restored requirement from another browser profile',
    classList: {
      contains(name) {
        return name === 'stale-results' && stale;
      },
      remove(name) { if (name === 'stale-results') stale = false; },
      add(name) { if (name === 'stale-results') stale = true; }
    },
    dispatchEvent(event) {
      calls.push(`event:${event.type}`);
      if (event.type === 'input' && this.value === '') currentStage = 'describe';
      return true;
    }
  };
  const tree = {
    dataset: { viewRendered: 'sunburst' },
    querySelector(selector) {
      return selector === '[role="treeitem"]' && this.dataset.viewRendered === 'list'
        ? { role: 'treeitem' }
        : null;
    }
  };
  const taxonomyState = {
    taxonomyData: [{ code: 'BP' }],
    currentView: 'sunburst'
  };

  globalThis.Event = class TestEvent {
    constructor(type, eventOptions = {}) {
      this.type = type;
      this.bubbles = Boolean(eventOptions.bubbles);
    }
  };
  globalThis.window = {
    TaxonomyState: taxonomyState,
    __TaxonomyAnalysisSessionContext: { runtime: {} },
    sessionStorage: {
      getItem: key => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: key => storage.delete(key)
    },
    TaxonomyBrowse: {
      switchView(view) {
        calls.push(`view:${view}`);
        taxonomyState.currentView = view;
        tree.dataset.viewRendered = view;
      }
    },
    TaxonomyAnalysisSessionApi: {
      async request(path, requestOptions) {
        calls.push({ request: path, options: requestOptions });
        if (options.remoteError) throw options.remoteError;
        return { status: remoteStatus, json: async () => options.remoteDraft };
      }
    },
    TaxonomyAnalysisSession: {
      whenInitialized: () => Promise.resolve(true),
      state: () => ({ restoring, conflict, workspaceId: currentWorkspaceId,
        version: options.version ?? null, ready: options.ready ? options.ready() : true }),
      async reload() {
        calls.push('reload');
        restoring = true;
        restoring = false;
        if (options.conflictAfterReload) conflict = true;
      },
      invalidate(invalidateOptions) {
        calls.push({ invalidate: invalidateOptions });
        input.value = '';
        stale = false;
        conflict = false;
        if (options.retainedLocalReference === 'analysisReference') {
          window.__TaxonomyAnalysisSessionContext.runtime.analysisReference = { continuationId: 'old-run' };
        } else if (options.retainedLocalReference === 'recoveryPointer') {
          storage.set(recoveryStorageKey, JSON.stringify({ id: 'old-run', workspaceId: currentWorkspaceId }));
        } else if (options.retainedLocalReference) {
          taxonomyState[options.retainedLocalReference] = { id: 'old-run' };
        }
      },
      async saveNow() {
        calls.push('save');
        if (options.conflictAfterSave) conflict = true;
        if (options.workspaceIdAfterSave !== undefined) {
          currentWorkspaceId = options.workspaceIdAfterSave;
        }
        return options.saveResult ?? true;
      }
    }
  };
  globalThis.document = {
    getElementById(id) {
      if (id === 'taxonomyTree') return tree;
      if (id === 'businessText') return input;
      return null;
    },
    querySelector(selector) {
      if (selector === '#statusArea[data-analysis-session-message="conflict"]') {
        return visibleConflict ? { id: 'statusArea' } : null;
      }
      return selector === '#taskStageDescribe[data-state="current"]'
        && currentStage === 'describe'
        ? { id: 'taskStageDescribe' }
        : null;
    }
  };

  const page = {
    waitForFunction(predicate, argument, waitOptions = {}) {
      const deadline = Date.now() + (waitOptions.timeout ?? 1_000);
      // Match Playwright 1.61.1 frames.ts: the immediate predicate result is
      // tested for truthiness before its promise, if any, is assimilated.
      return new Promise((resolve, reject) => {
        function poll() {
          try {
            const result = predicate(argument);
            if (result) { resolve(result); return; }
            if (Date.now() >= deadline) throw new Error('Readiness predicate timed out');
            setImmediate(poll);
          } catch (error) { reject(error); }
        }
        poll();
      });
    },
    async evaluate(callback, argument) {
      // Only the mutation receives the captured workspace string. Other browser
      // evaluations may await initialization or read the authoritative identity.
      if (typeof argument === 'string') {
        if (options.conflictBeforeMutation) conflict = true;
        if (options.conflictMarkerBeforeMutation) visibleConflict = true;
        if (options.workspaceIdBeforeMutation !== undefined) {
          currentWorkspaceId = options.workspaceIdBeforeMutation;
        }
      }
      return callback(argument);
    }
  };

  return {
    page,
    calls,
    input,
    tree,
    taxonomyState,
    storage,
    restore() {
      globalThis.window = previousWindow;
      globalThis.document = previousDocument;
      globalThis.Event = previousEvent;
    }
  };
}

function deferred() {
  let resolve;
  const promise = new Promise(complete => { resolve = complete; });
  return { promise, resolve };
}

function installProductionSessionHarness({ unavailableBeforeMutation = false } = {}) {
  const harness = installHarness();
  const commands = window.TaxonomyAnalysisSession;
  const workspaceSource = deferred();
  const repositoryContext = deferred();
  const runtime = {
    initialized: false, workspaceResolved: false, workspaceId: null, version: null,
    restoring: false, invalidating: false, resetting: false, conflict: false,
    serverUnconfirmed: false
  };
  const context = {
    S: harness.taxonomyState, runtime, CHANGE_POLL_MS: 1500,
    language: () => 'en', text: key => key,
    businessTextElement: () => null, statusArea: () => null,
    currentPayload: () => ({ businessText: harness.input.value }),
    comparable: JSON.stringify, isStale: () => false,
    rememberedWorkspaceId: () => null, rememberWorkspaceId() {},
    installWorkspaceFetchRouting() {}, installWorkspaceEventSourceRouting() {},
    async jsonRequest(path, options) {
      assert.equal(path, '/api/workspace/current');
      assert.equal(options.method, 'GET');
      return { workspaceId: 'workspace-42' };
    },
    async loadDraft(options = {}) {
      if (options.force) harness.calls.push('reload');
      runtime.restoring = false;
      return null;
    },
    invalidate: commands.invalidate, saveDraft: commands.saveNow,
    setDraftDecisionPending(value) { runtime.draftDecisionPending = value; },
    queueSave() {}, queueStaleActions() {}, showActionAlert() {},
    serverUnavailable() { runtime.serverUnconfirmed = true; }
  };
  Object.assign(document, {
    readyState: 'complete', visibilityState: 'visible',
    addEventListener() {}, dispatchEvent() {}
  });
  Object.assign(window, {
    __TaxonomyAnalysisSessionContext: context,
    TaxonomyArchitectureLifecycle: { ready: async () => true },
    TaxonomyArchitectureWorkspace: { ready: () => workspaceSource.promise },
    TaxonomyContextBar: { fetchAndRender: () => repositoryContext.promise },
    setInterval() {}, addEventListener() {}, console
  });
  vm.runInNewContext(projectsSource, {
    window, document, console,
    MutationObserver: class { observe() {} },
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options?.detail; } }
  }, { filename: 'taxonomy-analysis-session-projects.js' });
  const evaluate = harness.page.evaluate;
  harness.page.evaluate = async (callback, argument) => {
    if (unavailableBeforeMutation && typeof argument === 'string') {
      runtime.serverUnconfirmed = true;
    }
    return evaluate(callback, argument);
  };
  return { ...harness, runtime, session: window.TaxonomyAnalysisSession,
    releaseWorkspace: () => workspaceSource.resolve(true),
    releaseContext: () => repositoryContext.resolve({ contextId: 'ctx-42', branch: 'draft/current' }) };
}

test('the real draft-only session cannot be reloaded or cleared before its source is confirmed', async () => {
  const harness = installProductionSessionHarness();
  const isolation = isolateRoleStateScenario(harness.page, 1_000).then(
    () => ({ error: null }), error => ({ error }));
  try {
    assert.equal(await harness.session.whenInitialized(), true);
    assert.equal(harness.session.state().workspaceId, 'workspace-42');
    assert.equal(harness.session.state().ready, false);
    await new Promise(resolve => setImmediate(resolve));
    const beforeWorkspace = [...harness.calls];
    harness.releaseWorkspace();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(harness.session.state().ready, false);
    const beforeContext = [...harness.calls];
    harness.releaseContext();
    const outcome = await isolation;

    assert.deepEqual(beforeWorkspace, [], 'Draft-only initialization cannot admit reload or destructive cleanup');
    assert.deepEqual(beforeContext, [], 'Workspace initialization still requires the authoritative repository context');
    assert.equal(outcome.error, null);
    assert.equal(harness.session.state().ready, true);
    assert.equal(harness.calls.filter(call => call === 'reload').length, 1);
    assert.equal(harness.calls.filter(call => call === 'save').length, 1);
    assert.equal(harness.input.value, '');
    assert.equal(harness.tree.dataset.viewRendered, 'list');
  } finally {
    harness.releaseWorkspace(); harness.releaseContext();
    await isolation;
    harness.restore();
  }
});

test('real session readiness lost after polling still blocks authoritative cleanup', async () => {
  const harness = installProductionSessionHarness({ unavailableBeforeMutation: true });
  try {
    harness.releaseWorkspace(); harness.releaseContext();
    await harness.session.whenInitialized();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(harness.session.state().ready, true);
    await assert.rejects(() => isolateRoleStateScenario(harness.page, 1_000),
      /source became unavailable before authoritative cleanup/);
    assert.equal(harness.session.state().ready, false);
    assert.deepEqual(harness.calls, ['reload']);
  } finally {
    harness.restore();
  }
});

test(
  'reloads an absent draft and verifies its uncached authoritative absence',
  async () => {
    const harness = installHarness();
    try {
      await isolateRoleStateScenario(harness.page, 1_000);

      assert.deepEqual(harness.calls, [
        'reload',
        {
          invalidate: {
            keepText: false,
            silent: true,
            reason: 'role-state-acceptance-isolation'
          }
        },
        'save',
        {
          request: '/api/analysis-drafts/workspace-42',
          options: {
            method: 'GET',
            headers: { Accept: 'application/json' },
            cache: 'no-store'
          }
        },
        'event:input',
        'view:list'
      ]);
      assert.equal(harness.input.value, '');
      assert.equal(harness.taxonomyState.currentView, 'list');
      assert.equal(harness.tree.dataset.viewRendered, 'list');
    } finally {
      harness.restore();
    }
  }
);

test('fails before mutation when reload leaves a conflict state', async () => {
  const harness = installHarness({ conflictAfterReload: true });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /cannot mutate while a draft conflict is active/);
    assert.deepEqual(harness.calls, ['reload']);
  } finally {
    harness.restore();
  }
});

test('fails before mutation when visible conflict evidence remains', async () => {
  const harness = installHarness({ conflictMarker: true });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /cannot mutate while a draft conflict is active/);
    assert.deepEqual(harness.calls, ['reload']);
  } finally {
    harness.restore();
  }
});

test('fails before mutation without an authoritative workspace identity', async () => {
  const harness = installHarness({ workspaceId: null });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /without an authoritative workspace ID/);
    assert.deepEqual(harness.calls, ['reload']);
  } finally {
    harness.restore();
  }
});

test('initial source readiness settles before isolation reload can invalidate its protected reads', async () => {
  let sourceReady = false, release;
  const source = new Promise(resolve => { release = resolve; });
  const harness = installHarness({ready: () => sourceReady, waitForReady: () => source});
  try {
    const isolation = isolateRoleStateScenario(harness.page, 1_000).then(
      () => ({ error: null }), error => ({ error }));
    await new Promise(resolve => setImmediate(resolve));
    const earlyCalls = [...harness.calls];
    sourceReady = true; release();
    const outcome = await isolation;
    assert.deepEqual(earlyCalls, [], 'The fixture cannot reload, invalidate or write while first source publication is pending');
    assert.equal(outcome.error, null);
    assert.equal(harness.calls.filter(call => call === 'reload').length, 1);
    assert.equal(harness.calls.filter(call => call === 'save').length, 1);
    assert.equal(harness.input.value, '');
  } finally {
    sourceReady = true; release(); harness.restore();
  }
});

test('fails before mutation when a conflict appears between checks', async () => {
  const harness = installHarness({ conflictBeforeMutation: true });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /cannot mutate while a draft conflict is active/);
    assert.deepEqual(harness.calls, ['reload']);
  } finally {
    harness.restore();
  }
});

test('fails before mutation when the workspace changes between checks', async () => {
  const harness = installHarness({ workspaceIdBeforeMutation: 'workspace-other' });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /workspace changed before authoritative cleanup/);
    assert.deepEqual(harness.calls, ['reload']);
  } finally {
    harness.restore();
  }
});

test('fails closed when optimistic clearing enters conflict state', async () => {
  const harness = installHarness({ conflictAfterSave: true });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /could not clear the authoritative draft revision/);
    assert.deepEqual(
      harness.calls.map(call => typeof call === 'string'
        ? call : Object.keys(call)[0]),
      ['reload', 'invalidate', 'save']);
  } finally {
    harness.restore();
  }
});

test('fails closed when the workspace changes during cleanup', async () => {
  const harness = installHarness({ workspaceIdAfterSave: 'workspace-other' });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /workspace changed during authoritative cleanup/);
    assert.equal(
      harness.calls.some(call => typeof call === 'object' && call.request),
      false);
  } finally {
    harness.restore();
  }
});

test('fails closed when remote cleanup verification finds a surviving draft', async () => {
  const harness = installHarness({ remoteStatus: 200 });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /did not return the authoritative empty draft/);
    assert.equal(harness.calls.some(call => call === 'event:input'), false);
    assert.equal(harness.calls.some(call => call === 'view:list'), false);
  } finally {
    harness.restore();
  }
});

test('fails closed when remote cleanup verification returns HTTP 503', async () => {
  const unavailable = Object.assign(
    new Error('HTTP 503: Service Unavailable'),
    { status: 503 }
  );
  const harness = installHarness({ remoteError: unavailable });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /HTTP 503: Service Unavailable/);
    assert.equal(harness.calls.some(call => call === 'event:input'), false);
    assert.equal(harness.calls.some(call => call === 'view:list'), false);
  } finally {
    harness.restore();
  }
});

test('fails closed when remote cleanup verification cannot be completed', async () => {
  const harness = installHarness({
    remoteError: new Error('draft cleanup verification unavailable')
  });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /draft cleanup verification unavailable/);
    assert.equal(harness.calls.some(call => call === 'event:input'), false);
    assert.equal(harness.calls.some(call => call === 'view:list'), false);
  } finally {
    harness.restore();
  }
});

function emptyDraft(overrides = {}) {
  return {
    workspaceId: 'workspace-42', version: 4,
    payload: { draftState: 'EMPTY', businessText: '', scores: {},
      architectureView: null, discrepancies: [], productCoverageGaps: [],
      provisionalRelations: [] },
    ...overrides
  };
}

test('accepts the retained empty revision of an existing draft', async () => {
  const harness = installHarness({ remoteStatus: 200, version: 4,
    remoteDraft: emptyDraft() });
  try {
    await isolateRoleStateScenario(harness.page, 1_000);
    assert.equal(harness.input.value, '');
    assert.equal(harness.tree.dataset.viewRendered, 'list');
  } finally {
    harness.restore();
  }
});

for (const [label, remoteDraft] of [
  ['wrong workspace', emptyDraft({ workspaceId: 'workspace-other' })],
  ['different revision', emptyDraft({ version: 5 })],
  ['missing revision', emptyDraft({ version: null })],
  ['missing payload', emptyDraft({ payload: null })],
  ['active state', emptyDraft({ payload: { draftState: 'ACTIVE', businessText: '' } })],
  ...Object.entries({ businessText: 'Previous requirement', scores: { BP: 90 },
    architectureView: { nodes: ['BP'] }, discrepancies: ['old'],
    productCoverageGaps: ['old'], provisionalRelations: ['old'],
    analysisRecovery: { id: 'old-run' }, recoveryContext: { id: 'old-run' },
    analysisReference: { continuationId: 'old-run' }, analysisOperationId: 'old-operation' })
    .map(([key, value]) => [key, emptyDraft({
      payload: { ...emptyDraft().payload, [key]: value } })])
]) {
  test(`rejects retained draft with ${label}`, async () => {
    const harness = installHarness({ remoteStatus: 200, version: 4, remoteDraft });
    try {
      await assert.rejects(() => isolateRoleStateScenario(harness.page, 1_000),
        /did not return the authoritative empty draft/);
      assert.equal(harness.calls.includes('event:input'), false);
    } finally {
      harness.restore();
    }
  });
}

test('rejects absence after saving a versioned draft', async () => {
  const harness = installHarness({ version: 4 });
  try {
    await assert.rejects(() => isolateRoleStateScenario(harness.page, 1_000),
      /lost the authoritative draft revision/);
  } finally {
    harness.restore();
  }
});

test('rejects a failed save even if the remote draft appears empty', async () => {
  const harness = installHarness({ saveResult: false });
  try {
    await assert.rejects(() => isolateRoleStateScenario(harness.page, 1_000),
      /could not clear the authoritative draft revision/);
    assert.equal(harness.calls.some(call => call.request), false);
  } finally {
    harness.restore();
  }
});

test('isolates successive profiles using the production versioned draft writer', async () => {
  const harness = installHarness();
  let persisted = null;
  const writes = [];
  const runtime = { workspaceId: 'workspace-42', version: null, conflict: false, ready: true,
    restoring: false, saveInFlight: null, saveQueued: false };
  const context = {
    S: {}, runtime,
    currentPayload: () => ({ businessText: harness.input.value,
      draftState: harness.input.value ? 'ACTIVE' : 'EMPTY' }),
    comparable: JSON.stringify,
    meaningful: payload => Boolean(payload.businessText),
    draftEndpoint: () => '/api/analysis-drafts/workspace-42',
    jsonRequest: async (_url, options) => {
      assert.equal(options.method, 'PUT');
      const body = JSON.parse(options.body);
      assert.equal(body.expectedVersion, persisted?.version ?? null);
      persisted = { workspaceId: runtime.workspaceId,
        version: (persisted?.version ?? -1) + 1, payload: body.payload };
      writes.push(structuredClone(persisted));
      return persisted;
    }
  };
  try {
    vm.runInNewContext(draftSource, {
      window: { __TaxonomyAnalysisSessionContext: context, clearTimeout() {} },
      document: { addEventListener() {}, dispatchEvent() {} },
      CustomEvent: class {}, console
    });
    window.TaxonomyAnalysisSession.state = () => runtime;
    window.TaxonomyAnalysisSession.saveNow = context.saveDraft;
    window.TaxonomyAnalysisSession.reload = async () => {
      runtime.version = persisted?.version ?? null;
      runtime.lastSavedComparable = persisted ? JSON.stringify(persisted.payload) : null;
    };
    window.TaxonomyAnalysisSessionApi.request = async () => ({
      status: persisted ? 200 : 204, json: async () => persisted
    });
    // A fresh profile has no row. A later profile restores the prior analysis.
    await isolateRoleStateScenario(harness.page, 1_000);
    assert.equal(persisted, null);
    harness.input.value = 'Analysis saved by the first browser';
    assert.equal(await context.saveDraft(), true);
    assert.equal(persisted.payload.draftState, 'ACTIVE');
    await isolateRoleStateScenario(harness.page, 1_000);
    assert.equal(persisted.payload.draftState, 'EMPTY');
    assert.equal(persisted.payload.businessText, '');
    assert.equal(persisted.version, 1);
    assert.equal(writes.length, 2);
  } finally {
    harness.restore();
  }
});

// Execute the actual acceptance boundary without importing its Playwright entry
// point. The role flow and browser/HTTP/DOM boundaries are doubled; serialization,
// invalidation, CAS saving, restoration and recovery use the production modules.
const boundaryStart = acceptanceSource.indexOf('  taskMeasurements = await runRoleStateFlow({');
const boundaryEnd = acceptanceSource.indexOf("  if (role === 'ADMIN') {", boundaryStart);
assert.ok(boundaryStart >= 0 && boundaryEnd > boundaryStart, 'Acceptance boundary must remain identifiable');
const runAcceptanceBoundary = new (Object.getPrototypeOf(async function () {}).constructor)(
  'environment', `
  const { page, role, zoom, forcedColors, checks, httpFailures, externalRequests,
    consoleErrors, evidence, runRoleStateFlow, isolateCompletedRoleStateScenario } = environment;
  let taskMeasurements = {};
  ${acceptanceSource.slice(boundaryStart, boundaryEnd)}
  return taskMeasurements;
`);

function boundaryEnvironment(harness, overrides = {}) {
  return { page: harness.page, role: 'USER', zoom: 1, forcedColors: false,
    checks: [], httpFailures: [], externalRequests: [], consoleErrors: [], evidence: {},
    runRoleStateFlow: async () => ({ failedStep: null }),
    isolateCompletedRoleStateScenario: isolation.isolateCompletedRoleStateScenario,
    ...overrides };
}

function installRecoveryDraftHarness({ holdFirstWrite = false } = {}) {
  const harness = installHarness();
  const listeners = new Map(), pendingEvents = [], requests = [], writes = [];
  const timers = new Map();
  const pendingWrite = holdFirstWrite ? deferred() : null;
  let firstWrite = true;
  let timerId = 0, persisted;
  const addEventListener = (type, listener) => {
    if (!listeners.has(type)) listeners.set(type, []);
    listeners.get(type).push(listener);
  };
  Object.assign(document, {
    readyState: 'loading', documentElement: { lang: 'en' },
    body: { classList: { toggle() {} } }, querySelectorAll: () => [],
    addEventListener,
    dispatchEvent(event) {
      for (const listener of listeners.get(event.type) || []) {
        const result = listener(event);
        if (result?.then) pendingEvents.push(result);
      }
      return true;
    }
  });
  harness.input.addEventListener = addEventListener;
  const dispatchInput = harness.input.dispatchEvent;
  harness.input.dispatchEvent = event => {
    dispatchInput.call(harness.input, event);
    event.target = harness.input;
    return document.dispatchEvent(event);
  };
  harness.tree.querySelectorAll = () => [];
  Object.assign(window.TaxonomyBrowse, {
    renderView() { harness.tree.dataset.viewRendered = harness.taxonomyState.currentView; },
    updateExportGroupVisibility() {}
  });
  Object.assign(window, {
    console, setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
    clearTimeout(id) { timers.delete(id); },
    TaxonomyScoring: { renderArchitectureView() {}, renderSuggestedRelations() {} },
    TaxonomyRecoveryViewport: { mount: () => ({ update() {}, close() {}, isOpen: () => false }) }
  });
  delete window.TaxonomyAnalysisSession;
  delete window.__TaxonomyAnalysisSessionContext;
  const realm = vm.createContext({ window, document, console, Set,
    sessionStorage: window.sessionStorage,
    clearTimeout: window.clearTimeout, setTimeout: window.setTimeout,
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options?.detail; } }
  });
  for (const source of [coreSource, uiSource, draftSource, projectsSource, recoverySource]) {
    vm.runInContext(source, realm);
  }
  const context = window.__TaxonomyAnalysisSessionContext;
  Object.assign(context.runtime, { initialized: true, workspaceResolved: true,
    workspaceId: 'workspace-42', version: 4 });
  const continuationId = '0bc655c5-864b-4e65-b1cb-86228af07436';
  const rejectedRequest = { businessText: harness.input.value, resumable: true,
    continuationAction: 'START', continuationId };
  Object.assign(harness.taxonomyState, {
    analysisRecovery: { id: continuationId, state: 'RUNNING', version: 0, openQuestions: [] },
    recoveryContext: { id: continuationId, workspaceId: 'workspace-42',
      request: rejectedRequest, syncState: 'UNAVAILABLE', confirmed: false, followups: {} }
  });
  harness.storage.set(recoveryStorageKey, JSON.stringify({ id: continuationId,
    workspaceId: 'workspace-42', pendingRequest: rejectedRequest }));
  persisted = { workspaceId: 'workspace-42', version: 4,
    payload: JSON.parse(JSON.stringify(context.currentPayload())) };
  context.runtime.lastSavedComparable = context.comparable(persisted.payload);
  window.TaxonomyAnalysisSessionApi.request = async (path, options = {}) => {
    requests.push({ path, ...options });
    if (path === '/api/analysis-continuations/' + continuationId) {
      return { ok: false, status: 404, json: async () => ({ detail: 'Unknown continuation' }) };
    }
    assert.equal(path, '/api/analysis-drafts/workspace-42');
    if (options.method === 'PUT') {
      const body = JSON.parse(options.body);
      if (firstWrite && pendingWrite) {
        firstWrite = false;
        await pendingWrite.promise;
      }
      if (body.expectedVersion !== persisted.version) {
        return { ok: false, status: 409, json: async () => ({ detail: 'Draft revision conflict' }) };
      }
      persisted = { workspaceId: persisted.workspaceId, version: persisted.version + 1,
        payload: body.payload };
      writes.push({ expectedVersion: body.expectedVersion, draft: structuredClone(persisted) });
    } else assert.equal(options.method, 'GET');
    return { ok: true, status: 200, json: async () => structuredClone(persisted) };
  };
  return { ...harness, context, requests, writes, continuationId,
    session: window.TaxonomyAnalysisSession,
    persisted: () => structuredClone(persisted),
    advanceRemoteRevision: () => { persisted.version++; },
    releaseWrite: () => pendingWrite?.resolve(),
    async flushEvents() {
      while (pendingEvents.length) await Promise.all(pendingEvents.splice(0));
    }
  };
}

test('completed role scenario clears its rejected START before a later production reload', async () => {
  const harness = installRecoveryDraftHarness();
  const priorFailures = [{ method: 'POST', path: '/api/analyze', status: 503 },
    { method: 'GET', path: '/api/analysis-continuations/' + harness.continuationId, status: 404 }];
  const environment = boundaryEnvironment(harness, { httpFailures: [...priorFailures] });
  try {
    await runAcceptanceBoundary(environment);
    await harness.session.reload();
    await harness.flushEvents();
    assert.deepEqual(harness.requests.filter(request => request.path.includes('/analysis-continuations/')), [],
      'The next scenario must not re-read the never-admitted START');
    assert.equal(harness.writes.length, 1);
    assert.equal(harness.writes[0].expectedVersion, 4);
    const saved = harness.persisted();
    assert.equal(saved.version, 5);
    assert.equal(saved.payload.draftState, 'EMPTY');
    for (const field of ['analysisRecovery', 'recoveryContext', 'analysisOperationId', 'analysisReference']) {
      assert.equal(saved.payload[field] ?? null, null, `No persisted ${field}`);
    }
    assert.equal(harness.taxonomyState.recoveryContext, null);
    assert.equal(harness.taxonomyState.analysisRecovery, null);
    assert.equal(harness.context.runtime.analysisReference, null);
    assert.equal(harness.storage.get(recoveryStorageKey), undefined);
    assert.equal(harness.storage.get('unrelated-ui-preference'), 'retained');
    assert.equal(harness.requests.findIndex(request => request.method === 'PUT'), 0,
      'Completion must save the confirmed current state before performing any reload');
    assert.equal(harness.requests[1].cache, 'no-store');
    assert.deepEqual(environment.httpFailures, priorFailures, 'Previously audited failures remain in the report');
  } finally { harness.restore(); }
});

for (const field of ['analysisRecovery', 'recoveryContext', 'analysisOperationId', 'analysisReference', 'recoveryPointer']) {
  test(`rejects a surviving browser ${field} even when the remote draft is empty`, async () => {
    const harness = installHarness({ retainedLocalReference: field });
    try {
      await assert.rejects(() => isolateRoleStateScenario(harness.page, 1_000),
        /retained a browser analysis recovery reference/);
    } finally { harness.restore(); }
  });
}

for (const ledger of ['httpFailures', 'consoleErrors', 'externalRequests']) {
  test(`completed-scenario isolation rejects new ${ledger} without erasing prior evidence`, async () => {
    const harness = installHarness();
    const previous = { message: 'Previously audited evidence' };
    const added = { message: 'Unexpected cleanup evidence', method: 'GET', status: 404 };
    const environment = boundaryEnvironment(harness, {
      [ledger]: [previous],
      isolateCompletedRoleStateScenario: async page => {
        await isolation.isolateCompletedRoleStateScenario(page, 1_000);
        environment[ledger].push(added);
      }
    });
    try {
      await assert.rejects(() => runAcceptanceBoundary(environment),
        /Completed role-state isolation introduced HTTP, console or external-request failures/);
      assert.deepEqual(environment[ledger], [previous, added]);
    } finally { harness.restore(); }
  });
}

test('a failed role flow retains its draft and never enters completion isolation', async () => {
  const harness = installHarness();
  let cleanupCalls = 0;
  try {
    await assert.rejects(() => runAcceptanceBoundary(boundaryEnvironment(harness, {
      runRoleStateFlow: async () => { throw new Error('Role audit failed'); },
      isolateCompletedRoleStateScenario: async () => { cleanupCalls++; }
    })), /Role audit failed/);
    assert.equal(cleanupCalls, 0);
    assert.deepEqual(harness.calls, []);
    assert.notEqual(harness.input.value, '');
  } finally { harness.restore(); }
});

for (const [label, options, message] of [
  ['visible conflict', { conflictMarker: true }, /cannot mutate while a draft conflict is active/],
  ['missing workspace', { workspaceId: null }, /without an authoritative workspace ID/],
  ['conflict before mutation', { conflictBeforeMutation: true }, /cannot mutate while a draft conflict is active/],
  ['visible conflict before mutation', { conflictMarkerBeforeMutation: true }, /cannot mutate while a draft conflict is active/],
  ['workspace changed before mutation', { workspaceIdBeforeMutation: 'workspace-other' }, /workspace changed before authoritative cleanup/],
  ['conflicted save', { conflictAfterSave: true }, /could not clear the authoritative draft revision/],
  ['unsuccessful save', { saveResult: false }, /could not clear the authoritative draft revision/],
  ['workspace changed during save', { workspaceIdAfterSave: 'workspace-other' }, /workspace changed during authoritative cleanup/],
  ['wrong remote revision', { remoteStatus: 200, version: 4, remoteDraft: emptyDraft({ version: 5 }) }, /did not return the authoritative empty draft/],
  ['remote verification failure', { remoteStatus: 503 }, /verification returned HTTP 503/]
]) {
  test(`completed scenario retains the ${label} boundary without reloading`, async () => {
    const harness = installHarness(options);
    try {
      await assert.rejects(() => runAcceptanceBoundary(boundaryEnvironment(harness)), message);
      assert.equal(harness.calls.includes('reload'), false);
      assert.equal(harness.calls.includes('event:input'), false);
      assert.equal(harness.calls.includes('view:list'), false);
    } finally { harness.restore(); }
  });
}

test('completed scenario waits for the real source readiness without first reloading', async () => {
  const harness = installProductionSessionHarness();
  const completion = runAcceptanceBoundary(boundaryEnvironment(harness)).then(
    () => ({ error: null }), error => ({ error }));
  try {
    await harness.session.whenInitialized();
    await new Promise(resolve => setImmediate(resolve));
    const beforeWorkspace = [...harness.calls];
    harness.releaseWorkspace();
    await new Promise(resolve => setImmediate(resolve));
    const beforeContext = [...harness.calls];
    harness.releaseContext();
    const outcome = await completion;
    assert.deepEqual(beforeWorkspace, []);
    assert.deepEqual(beforeContext, []);
    assert.equal(outcome.error, null);
    assert.equal(harness.calls.includes('reload'), false);
    assert.equal(harness.calls.filter(call => call === 'save').length, 1);
  } finally {
    harness.releaseWorkspace(); harness.releaseContext();
    await completion;
    harness.restore();
  }
});

test('completed scenario rejects real source readiness lost before mutation', async () => {
  const harness = installProductionSessionHarness({ unavailableBeforeMutation: true });
  try {
    harness.releaseWorkspace(); harness.releaseContext();
    await harness.session.whenInitialized();
    await new Promise(resolve => setImmediate(resolve));
    await assert.rejects(() => runAcceptanceBoundary(boundaryEnvironment(harness)),
      /source became unavailable before authoritative cleanup/);
    assert.deepEqual(harness.calls, []);
  } finally { harness.restore(); }
});

test('completed scenario cannot overwrite a newer remote revision through the production CAS writer', async () => {
  const harness = installRecoveryDraftHarness();
  try {
    harness.advanceRemoteRevision();
    const remoteBefore = harness.persisted();
    await assert.rejects(() => runAcceptanceBoundary(boundaryEnvironment(harness)),
      /could not clear the authoritative draft revision/);
    assert.equal(harness.session.state().conflict, true);
    assert.deepEqual(harness.persisted(), remoteBefore);
    assert.equal(harness.writes.length, 0);
    assert.deepEqual(harness.requests.map(({ method }) => method), ['PUT']);
    assert.equal(JSON.parse(harness.requests[0].body).expectedVersion, 4);
  } finally { harness.restore(); }
});

test('completed scenario drains an in-flight production save before verifying its EMPTY revision', async () => {
  const harness = installRecoveryDraftHarness({ holdFirstWrite: true });
  let pendingSave, completion;
  try {
    harness.input.value += ' local edit';
    pendingSave = harness.session.saveNow();
    assert.equal(harness.requests.length, 1);
    completion = runAcceptanceBoundary(boundaryEnvironment(harness));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(harness.writes.length, 0, 'The old save is still waiting for its receipt');
    assert.equal(harness.requests.some(({ method }) => method === 'GET'), false);
    harness.releaseWrite();
    await pendingSave;
    await completion;
    assert.deepEqual(harness.writes.map(write => [write.expectedVersion, write.draft.payload.draftState]),
      [[4, 'ACTIVE'], [5, 'EMPTY']]);
    assert.equal(harness.persisted().version, 6);
    assert.equal(harness.session.state().version, 6);
    assert.deepEqual(harness.requests.map(({ method }) => method), ['PUT', 'PUT', 'GET']);
    assert.equal(harness.requests.at(-1).cache, 'no-store');
    await harness.session.reload();
    await harness.flushEvents();
    assert.equal(harness.requests.some(({ path }) => path.includes('/analysis-continuations/')), false);
  } finally {
    harness.releaseWrite();
    await Promise.allSettled([pendingSave, completion]);
    harness.restore();
  }
});

test('completion verification leaves unrelated storage and a foreign workspace pointer untouched', async () => {
  const harness = installHarness();
  const foreignPointer = JSON.stringify({ id: 'foreign-run', workspaceId: 'workspace-other' });
  harness.storage.set(recoveryStorageKey, foreignPointer);
  try {
    await runAcceptanceBoundary(boundaryEnvironment(harness));
    assert.equal(harness.storage.get(recoveryStorageKey), foreignPointer);
    assert.equal(harness.storage.get('unrelated-ui-preference'), 'retained');
  } finally { harness.restore(); }
});
