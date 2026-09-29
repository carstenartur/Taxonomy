import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const draftSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-draft.js',
  import.meta.url), 'utf8');

import { isolateRoleStateScenario } from './ui-role-state-isolation.mjs';

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
  let evaluateCount = 0;

  const input = {
    value: 'Restored requirement from another browser profile',
    classList: {
      contains(name) {
        return name === 'stale-results' && stale;
      }
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
      state: () => ({ restoring, conflict, workspaceId: currentWorkspaceId,
        version: options.version ?? null }),
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
    async waitForFunction(predicate) {
      assert.equal(predicate(), true);
    },
    async evaluate(callback, argument) {
      evaluateCount++;
      // Evaluation 1 reloads, evaluation 2 captures the authoritative identity,
      // and evaluation 3 performs the mutation. Inject races immediately before 3.
      if (evaluateCount === 3) {
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
    restore() {
      globalThis.window = previousWindow;
      globalThis.document = previousDocument;
      globalThis.Event = previousEvent;
    }
  };
}

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
    productCoverageGaps: ['old'], provisionalRelations: ['old'] })
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
  const runtime = { workspaceId: 'workspace-42', version: null, conflict: false,
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
