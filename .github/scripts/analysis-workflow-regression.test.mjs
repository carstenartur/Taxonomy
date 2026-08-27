import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const transportSource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/'
      + 'taxonomy-analysis-session-transport.js',
    import.meta.url
  ),
  'utf8'
);

function mutableClassList(initial = []) {
  const values = new Set(initial);
  return {
    add(value) {
      values.add(value);
    },
    remove(value) {
      values.delete(value);
    },
    contains(value) {
      return values.has(value);
    }
  };
}

function createHarness({
  interactive = true,
  provider = '',
  currentScores = null,
  copilotBusy = false,
  analysisBusy = false,
  lastAnalysisStatus = null
} = {}) {
  const listeners = new Map();
  const intervals = new Map();
  const clearedIntervals = [];
  let nextIntervalId = 1;
  let analysisCalls = 0;

  const elements = {
    analyzeBtn: {
      id: 'analyzeBtn',
      disabled: analysisBusy
    },
    analyzeSpinner: {
      classList: mutableClassList(analysisBusy ? [] : ['d-none'])
    },
    copilotBtn: {
      id: 'copilotBtn',
      disabled: copilotBusy
    },
    copilotSpinner: {
      classList: mutableClassList(copilotBusy ? [] : ['d-none'])
    },
    copilotPanel: {
      style: {
        display: 'none'
      }
    },
    copilotContent: {
      rendered: null,
      replaceChildren(node) {
        this.rendered = node;
      }
    },
    interactiveMode: {
      checked: interactive
    },
    includeArchitectureView: {
      checked: false
    },
    providerSelect: {
      value: provider,
      options: []
    }
  };

  const runtime = {
    analysisGeneration: 0,
    activeAnalysisControllers: new Set(),
    copilotIntervals: new Set(),
    draftMutationQueue: Promise.resolve(),
    invalidating: false,
    conflict: false,
    draftDecisionPending: false,
    version: null
  };

  const context = {
    runtime,
    S: {
      lastAnalysisStatus
    },
    requestUrl(input) {
      const value = typeof input === 'string' ? input : input.url;
      return new URL(value, 'https://taxonomy.example/');
    },
    installWorkspaceFetchRouting() {},
    installWorkspaceEventSourceRouting() {},
    jsonRequest: async () => null,
    currentPayload: () => ({}),
    language: () => 'en'
  };

  const document = {
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(listener);
    },
    getElementById(id) {
      return elements[id] || null;
    },
    querySelectorAll() {
      return [];
    },
    createElement() {
      return {
        className: '',
        textContent: ''
      };
    }
  };

  function nativeSetInterval(callback) {
    const id = nextIntervalId++;
    intervals.set(id, callback);
    return id;
  }

  function nativeClearInterval(id) {
    intervals.delete(id);
    clearedIntervals.push(id);
  }

  const window = {
    location: {
      origin: 'https://taxonomy.example',
      href: 'https://taxonomy.example/'
    },
    fetch: async () => ({ ok: true }),
    setInterval: nativeSetInterval,
    clearInterval: nativeClearInterval,
    setTimeout() {
      return 1;
    },
    _taxonomyCurrentScores: currentScores,
    TaxonomyScoring: {
      runAnalysis() {
        analysisCalls += 1;
      },
      setAnalyzing(on) {
        elements.analyzeBtn.disabled = on;
        if (on) elements.analyzeSpinner.classList.remove('d-none');
        else elements.analyzeSpinner.classList.add('d-none');
      }
    },
    __TaxonomyAnalysisSessionContext: context
  };

  vm.runInNewContext(transportSource, {
    window,
    document,
    console,
    AbortController,
    Array,
    Boolean,
    JSON,
    Map,
    Object,
    Promise,
    Proxy,
    Reflect,
    Set,
    String,
    URL
  }, { filename: 'taxonomy-analysis-session-transport.js' });

  function dispatchClick(selector, element) {
    const event = {
      target: {
        closest(requested) {
          return requested === selector ? element : null;
        }
      },
      defaultPrevented: false,
      immediatePropagationStopped: false,
      preventDefault() {
        this.defaultPrevented = true;
      },
      stopImmediatePropagation() {
        this.immediatePropagationStopped = true;
      }
    };
    const clickListeners = listeners.get('click') || [];
    assert.equal(clickListeners.length, 1);
    clickListeners[0](event);
    return event;
  }

  return {
    context,
    runtime,
    elements,
    intervals,
    clearedIntervals,
    window,
    dispatchAnalyzeClick() {
      return dispatchClick('#analyzeBtn', elements.analyzeBtn);
    },
    dispatchCopilotClick() {
      return dispatchClick('#copilotBtn', elements.copilotBtn);
    },
    analysisCalls: () => analysisCalls
  };
}

test('interactive drill-down remains on the legacy target listener', () => {
  const harness = createHarness({ interactive: true });

  const event = harness.dispatchAnalyzeClick();

  assert.equal(harness.analysisCalls(), 0);
  assert.equal(event.defaultPrevented, false);
  assert.equal(event.immediatePropagationStopped, false);
});

test('manual scoring remains on the legacy provider branch', () => {
  const harness = createHarness({
    interactive: false,
    provider: 'MANUAL'
  });

  const event = harness.dispatchAnalyzeClick();

  assert.equal(harness.analysisCalls(), 0);
  assert.equal(event.defaultPrevented, false);
  assert.equal(event.immediatePropagationStopped, false);
});

test('manual non-interactive analysis is complete regardless of list rendering', () => {
  const harness = createHarness({ interactive: false });

  const event = harness.dispatchAnalyzeClick();

  assert.equal(harness.analysisCalls(), 1);
  assert.equal(event.defaultPrevented, true);
  assert.equal(event.immediatePropagationStopped, true);
});

test('Copilot forces complete analysis without changing the interactive preference', () => {
  const harness = createHarness({
    interactive: true,
    copilotBusy: true
  });

  const event = harness.dispatchAnalyzeClick();

  assert.equal(harness.analysisCalls(), 1);
  assert.equal(harness.elements.interactiveMode.checked, true);
  assert.equal(event.defaultPrevented, true);
});

test('Copilot preflight rejects manual scoring when no scores exist', () => {
  const harness = createHarness({
    provider: 'MANUAL'
  });

  const event = harness.dispatchCopilotClick();

  assert.equal(harness.analysisCalls(), 0);
  assert.equal(event.defaultPrevented, true);
  assert.equal(event.immediatePropagationStopped, true);
  assert.equal(harness.elements.copilotPanel.style.display, '');
  assert.match(
    harness.elements.copilotContent.rendered.textContent,
    /requires an AI provider/i
  );
});

test('Copilot can reuse an existing manual score set', () => {
  const harness = createHarness({
    provider: 'MANUAL',
    currentScores: { IP: 100 }
  });

  const event = harness.dispatchCopilotClick();

  assert.equal(harness.analysisCalls(), 0);
  assert.equal(event.defaultPrevented, false);
  assert.equal(event.immediatePropagationStopped, false);
});

test('Copilot preflight rejects a disabled or already-running Analyze action', () => {
  const harness = createHarness({ analysisBusy: true });

  const event = harness.dispatchCopilotClick();

  assert.equal(harness.analysisCalls(), 0);
  assert.equal(event.defaultPrevented, true);
  assert.equal(event.immediatePropagationStopped, true);
  assert.match(
    harness.elements.copilotContent.rendered.textContent,
    /cannot start yet/i
  );
});

test('programmatic Copilot flow with manual scoring cannot issue an LLM request', () => {
  const harness = createHarness({
    provider: 'MANUAL',
    copilotBusy: true
  });

  const event = harness.dispatchAnalyzeClick();

  assert.equal(harness.analysisCalls(), 0);
  assert.equal(event.defaultPrevented, true);
  assert.equal(event.immediatePropagationStopped, true);
  assert.equal(harness.elements.copilotBtn.disabled, false);
  assert.equal(harness.elements.copilotSpinner.classList.contains('d-none'), true);
  assert.match(
    harness.elements.copilotContent.rendered.textContent,
    /requires an AI provider/i
  );
});

test('Copilot score poll does not advance while the main analysis is busy', () => {
  const harness = createHarness({
    copilotBusy: true,
    analysisBusy: true,
    lastAnalysisStatus: 'IN_PROGRESS'
  });
  let polls = 0;

  const id = harness.window.setInterval(() => {
    polls += 1;
  }, 1000);
  harness.intervals.get(id)();
  assert.equal(polls, 0);

  harness.elements.analyzeBtn.disabled = false;
  harness.elements.analyzeSpinner.classList.add('d-none');
  harness.context.S.lastAnalysisStatus = 'SUCCESS';
  harness.intervals.get(id)();
  assert.equal(polls, 1);
});

test('main-analysis failure terminates Copilot without waiting for score timeout', () => {
  const harness = createHarness({
    copilotBusy: true,
    analysisBusy: true,
    lastAnalysisStatus: 'IN_PROGRESS'
  });
  let polls = 0;

  const id = harness.window.setInterval(() => {
    polls += 1;
  }, 1000);
  harness.elements.analyzeBtn.disabled = false;
  harness.elements.analyzeSpinner.classList.add('d-none');
  harness.context.S.lastAnalysisStatus = 'ERROR';
  harness.intervals.get(id)();

  assert.equal(polls, 0);
  assert.ok(harness.clearedIntervals.includes(id));
  assert.equal(harness.elements.copilotBtn.disabled, false);
  assert.equal(harness.elements.copilotSpinner.classList.contains('d-none'), true);
  assert.match(
    harness.elements.copilotContent.rendered.textContent,
    /main analysis failed/i
  );
});
