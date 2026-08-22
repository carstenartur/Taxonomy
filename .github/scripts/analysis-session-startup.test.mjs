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

function createStartupHarness(workspaceResponse) {
  const pendingStates = [];
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
      return workspaceResponse;
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
    loadDraft: async () => null,
    setDraftDecisionPending(pending) {
      runtime.draftDecisionPending = pending;
      pendingStates.push(pending);
    }
  };
  const document = {
    readyState: 'complete',
    visibilityState: 'visible',
    addEventListener() {},
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
    addEventListener() {}
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

  return { runtime, pendingStates };
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
