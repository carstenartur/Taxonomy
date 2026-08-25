import test from 'node:test';
import assert from 'node:assert/strict';

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
        return { status: remoteStatus };
      }
    },
    TaxonomyAnalysisSession: {
      state: () => ({ restoring, conflict, workspaceId: currentWorkspaceId }),
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
  'reloads the exact revision, deletes it and verifies uncached authoritative absence',
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

test('fails closed when optimistic deletion enters conflict state', async () => {
  const harness = installHarness({ conflictAfterSave: true });
  try {
    await assert.rejects(
      () => isolateRoleStateScenario(harness.page, 1_000),
      /could not delete the authoritative draft revision/);
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
      /returned HTTP 200, expected 204/);
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
