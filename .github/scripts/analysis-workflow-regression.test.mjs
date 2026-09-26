import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const authoritySource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/'
      + 'taxonomy-copilot-terminal-state.js',
    import.meta.url
  ),
  'utf8'
);

function mutableClassList(initial = []) {
  const values = new Set(initial);
  return {
    add(value) { values.add(value); },
    remove(value) { values.delete(value); },
    contains(value) { return values.has(value); }
  };
}

function createHarness({
  interactive = true,
  provider = '',
  currentScores = null,
  analysisBusy = false,
  copilotBusy = false,
  status = null
} = {}) {
  const listeners = new Map();
  const intervals = new Map();
  const cleared = [];
  let nextIntervalId = 1;
  let analysisCalls = 0;

  const elements = {
    analyzeBtn: {
      disabled: analysisBusy,
      getAttribute() { return null; }
    },
    analyzeSpinner: {
      classList: mutableClassList(analysisBusy ? [] : ['d-none'])
    },
    copilotBtn: {
      disabled: copilotBusy,
      getAttribute() { return null; }
    },
    copilotSpinner: {
      classList: mutableClassList(copilotBusy ? [] : ['d-none'])
    },
    copilotPanel: { style: { display: 'none' } },
    copilotContent: {
      rendered: null,
      replaceChildren(node) { this.rendered = node; }
    },
    interactiveMode: { checked: interactive },
    providerSelect: { value: provider },
    manualApplyBtn: { disabled: false }
  };

  const document = {
    addEventListener(type, listener, capture = false) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push({ listener, capture });
    },
    dispatchEvent(event) { (listeners.get(event.type) || []).forEach(entry => entry.listener(event)); return true; },
    getElementById(id) { return elements[id] || null; },
    createElement() { return { className: '', textContent: '' }; }
  };

  function nativeSetInterval(callback) {
    const id = nextIntervalId++;
    intervals.set(id, callback);
    return id;
  }

  function nativeClearInterval(id) {
    intervals.delete(id);
    cleared.push(id);
  }

  const context = {
    runtime: {
      draftDecisionPending: false,
      conflict: false,
      invalidating: false
    },
    S: {
      currentScores: currentScores,
      lastAnalysisStatus: status
    },
    language: () => 'en'
  };

  const window = {
    setInterval: nativeSetInterval,
    clearInterval: nativeClearInterval,
    _taxonomyCurrentScores: currentScores,
    TaxonomyScoring: {
      runAnalysis() { analysisCalls += 1; }
    },
    __TaxonomyAnalysisSessionContext: context
  };

  vm.runInNewContext(authoritySource, {
    window,
    document,
    CustomEvent: class CustomEvent { constructor(type) { this.type = type; } },
    Boolean,
    Object,
    String
  }, { filename: 'taxonomy-copilot-terminal-state.js' });

  function dispatch(selector, element, targetAction) {
    const event = {
      target: {
        closest(requested) { return requested === selector ? element : null; }
      },
      defaultPrevented: false,
      immediatePropagationStopped: false,
      preventDefault() { this.defaultPrevented = true; },
      stopImmediatePropagation() { this.immediatePropagationStopped = true; }
    };
    const clickListeners = listeners.get('click') || [];
    clickListeners.filter(entry => entry.capture).forEach(entry => entry.listener(event));
    if (!event.immediatePropagationStopped && targetAction) targetAction();
    if (!event.immediatePropagationStopped) {
      clickListeners.filter(entry => !entry.capture).forEach(entry => entry.listener(event));
    }
    return event;
  }

  return {
    window,
    context,
    elements,
    intervals,
    cleared,
    analysisCalls: () => analysisCalls,
    clickAnalyze() { return dispatch('#analyzeBtn', elements.analyzeBtn); },
    clickCopilot() { return dispatch('#copilotBtn', elements.copilotBtn); },
    applyManualScores(scores) {
      return dispatch('#manualApplyBtn', elements.manualApplyBtn, () => {
        context.S.currentScores = scores;
        window._taxonomyCurrentScores = scores;
      });
    },
    finishAnalysis(nextStatus) {
      elements.analyzeBtn.disabled = false;
      elements.analyzeSpinner.classList.add('d-none');
      context.S.lastAnalysisStatus = nextStatus;
    }
  };
}

test('ordinary interactive analysis remains on its existing listener', () => {
  const harness = createHarness({ interactive: true });
  const event = harness.clickAnalyze();
  assert.equal(harness.analysisCalls(), 0);
  assert.equal(event.defaultPrevented, false);
});

test('ordinary manual scoring remains on its existing provider branch', () => {
  const harness = createHarness({ interactive: false, provider: 'MANUAL' });
  const event = harness.clickAnalyze();
  assert.equal(harness.analysisCalls(), 0);
  assert.equal(event.defaultPrevented, false);
});

test('ordinary non-interactive analysis uses one complete response', () => {
  const harness = createHarness({ interactive: false });
  const event = harness.clickAnalyze();
  assert.equal(harness.analysisCalls(), 1);
  assert.equal(event.defaultPrevented, true);
});

test('Copilot forces complete analysis without changing interactive preference', () => {
  const harness = createHarness({ interactive: true, copilotBusy: true });
  const event = harness.clickAnalyze();
  assert.equal(harness.analysisCalls(), 1);
  assert.equal(harness.elements.interactiveMode.checked, true);
  assert.equal(event.defaultPrevented, true);
});

test('Copilot preflight rejects manual provider only when scores are missing', () => {
  const missing = createHarness({ provider: 'MANUAL' });
  const blocked = missing.clickCopilot();
  assert.equal(blocked.defaultPrevented, true);
  assert.match(missing.elements.copilotContent.rendered.textContent, /requires an AI provider/i);

  const completed = createHarness({ provider: 'MANUAL', currentScores: { IP: 100 } });
  const allowed = completed.clickCopilot();
  assert.equal(allowed.defaultPrevented, false);
});

test('Copilot preflight rejects a disabled or running analysis', () => {
  const harness = createHarness({ analysisBusy: true });
  const event = harness.clickCopilot();
  assert.equal(event.defaultPrevented, true);
  assert.match(harness.elements.copilotContent.rendered.textContent, /cannot start yet/i);
});

test('Copilot terminal authority leaves the browser timer API untouched', () => {
  const harness = createHarness({ analysisBusy: true, copilotBusy: true, status: 'IN_PROGRESS' });
  let ticks = 0;
  const id = harness.window.setInterval(() => { ticks += 1; }, 1000);

  harness.intervals.get(id)();

  assert.equal(ticks, 1);
  assert.deepEqual(harness.cleared, []);
});

test('shared state remains authoritative when the legacy score alias is stale', () => {
  const harness = createHarness({
    status: 'SUCCESS',
    currentScores: { IP: 100 },
    analysisBusy: false,
    copilotBusy: false
  });
  harness.window._taxonomyCurrentScores = {};

  const event = harness.clickCopilot();

  assert.equal(event.defaultPrevented, false);
  assert.equal(event.immediatePropagationStopped, false);
});

test('manual scores explicitly replace an earlier failed AI authority', () => {
  const harness = createHarness({ status: 'PARTIAL' });
  harness.applyManualScores({ IP: 100 });
  assert.equal(harness.context.S.lastAnalysisProvider, 'MANUAL');
  assert.equal(harness.context.S.lastAnalysisStatus, 'SUCCESS');
});
