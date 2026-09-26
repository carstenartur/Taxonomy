import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const guardPath = process.env.TAXONOMY_COPILOT_TERMINAL_SOURCE
  || new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/'
      + 'taxonomy-copilot-terminal-state.js',
    import.meta.url
  );
const guardSource = await readFile(guardPath, 'utf8');

function mutableClassList(initial = []) {
  const values = new Set(initial);
  return {
    add(value) { values.add(value); },
    remove(value) { values.delete(value); },
    contains(value) { return values.has(value); }
  };
}

function createHarness({
  status = 'IN_PROGRESS',
  currentScores = null,
  analysisBusy = true,
  copilotBusy = true
} = {}) {
  const intervals = new Map();
  const clearedIntervals = [];
  const listeners = new Map();
  let nextIntervalId = 1;
  const elements = {
    analyzeBtn: { disabled: analysisBusy },
    analyzeSpinner: {
      classList: mutableClassList(analysisBusy ? [] : ['d-none'])
    },
    copilotBtn: { disabled: copilotBusy },
    copilotSpinner: {
      classList: mutableClassList(copilotBusy ? [] : ['d-none'])
    },
    manualApplyBtn: { disabled: false },
    copilotPanel: { style: { display: 'none' } },
    copilotContent: {
      rendered: null,
      replaceChildren(node) { this.rendered = node; }
    }
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
  function setInterval(callback) {
    const id = nextIntervalId++;
    intervals.set(id, callback);
    return id;
  }
  function clearInterval(id) {
    intervals.delete(id);
    clearedIntervals.push(id);
  }
  const context = {
    S: { lastAnalysisStatus: status, currentScores },
    language: () => 'en'
  };
  const window = {
    setInterval,
    clearInterval,
    _taxonomyCurrentScores: currentScores,
    __TaxonomyAnalysisSessionContext: context
  };
  vm.runInNewContext(guardSource, {
    window,
    document,
    CustomEvent: class CustomEvent { constructor(type) { this.type = type; } },
    Boolean,
    Object,
    String,
    Error
  }, { filename: 'taxonomy-copilot-terminal-state.js' });

  function dispatchClick(selector, element, targetAction) {
    const event = {
      target: {
        closest(requested) {
          return requested === selector ? element : null;
        }
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
    nativeSetInterval: setInterval,
    nativeClearInterval: clearInterval,
    context,
    elements,
    intervals,
    clearedIntervals,
    dispatchCopilotClick() {
      return dispatchClick('#copilotBtn', elements.copilotBtn);
    },
    applyManualScores(scores) {
      return dispatchClick('#manualApplyBtn', elements.manualApplyBtn, () => {
        context.S.currentScores = scores;
        window._taxonomyCurrentScores = scores;
      });
    }
  };
}

function finishAnalysis(harness, status) {
  harness.elements.analyzeBtn.disabled = false;
  harness.elements.analyzeSpinner.classList.add('d-none');
  harness.context.S.lastAnalysisStatus = status;
}

test('terminal-state validation leaves the global timer API untouched', () => {
  const harness = createHarness();

  assert.equal(harness.window.setInterval, harness.nativeSetInterval);
  assert.equal(harness.window.clearInterval, harness.nativeClearInterval);
});

test('a partial terminal result stays non-authoritative', () => {
  const harness = createHarness({
    status: 'PARTIAL',
    currentScores: { IP: 40 },
    analysisBusy: false,
    copilotBusy: false
  });

  const event = harness.dispatchCopilotClick();

  assert.equal(event.defaultPrevented, true);
  assert.equal(event.immediatePropagationStopped, true);
  assert.match(
    harness.elements.copilotContent.rendered.textContent,
    /main analysis failed or did not complete successfully/i
  );
});

test('a later Copilot click rejects any known non-authoritative score status', () => {
  for (const status of ['PARTIAL', 'ERROR', 'IN_PROGRESS', 'UNKNOWN', 'CANCELLED']) {
    const harness = createHarness({
      status,
      currentScores: { IP: 40 },
      analysisBusy: false,
      copilotBusy: false
    });

    const event = harness.dispatchCopilotClick();

    assert.equal(event.defaultPrevented, true, status);
    assert.equal(event.immediatePropagationStopped, true, status);
    assert.match(
      harness.elements.copilotContent.rendered.textContent,
      /did not complete successfully/i
    );
  }
});

test('legacy manual and imported scores remain reusable', () => {
  for (const status of [null, 'IMPORTED', 'SUCCESS']) {
    const harness = createHarness({
      status,
      currentScores: { IP: 100 },
      analysisBusy: false,
      copilotBusy: false
    });

    const event = harness.dispatchCopilotClick();

    assert.equal(event.defaultPrevented, false, String(status));
    assert.equal(event.immediatePropagationStopped, false, String(status));
  }
});

test('manual scores override a prior partial status with explicit authority', () => {
  const harness = createHarness({
    status: 'PARTIAL',
    analysisBusy: false,
    copilotBusy: false
  });

  harness.applyManualScores({ IP: 100 });

  assert.equal(harness.context.S.lastAnalysisProvider, 'MANUAL');
  assert.equal(harness.context.S.lastAnalysisStatus, 'SUCCESS');
  const event = harness.dispatchCopilotClick();
  assert.equal(event.defaultPrevented, false);
});
