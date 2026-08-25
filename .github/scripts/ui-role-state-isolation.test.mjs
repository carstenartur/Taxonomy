import test from 'node:test';
import assert from 'node:assert/strict';

import { isolateRoleStateScenario } from './ui-role-state-isolation.mjs';

test('loads the server revision before clearing a restored draft and normalizes the role task', async () => {
  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  const previousEvent = globalThis.Event;
  const calls = [];
  let stale = true;
  let currentStage = 'analyze';
  let restoring = false;
  let conflict = false;

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
    constructor(type, options = {}) {
      this.type = type;
      this.bubbles = Boolean(options.bubbles);
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
    TaxonomyAnalysisSession: {
      state: () => ({ restoring, conflict }),
      async reload() {
        calls.push('reload');
        restoring = true;
        conflict = false;
        restoring = false;
      },
      invalidate(options) {
        calls.push({ invalidate: options });
        input.value = '';
        stale = false;
      },
      async saveNow() {
        calls.push('save');
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
    async evaluate(callback) {
      return callback();
    }
  };

  try {
    await isolateRoleStateScenario(page, 1_000);
  } finally {
    globalThis.window = previousWindow;
    globalThis.document = previousDocument;
    globalThis.Event = previousEvent;
  }

  assert.deepEqual(calls, [
    'reload',
    {
      invalidate: {
        keepText: false,
        silent: true,
        reason: 'role-state-acceptance-isolation'
      }
    },
    'save',
    'event:input',
    'view:list'
  ]);
  assert.equal(input.value, '');
  assert.equal(stale, false);
  assert.equal(currentStage, 'describe');
  assert.equal(taxonomyState.currentView, 'list');
  assert.equal(tree.dataset.viewRendered, 'list');
});
