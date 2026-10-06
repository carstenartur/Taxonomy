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

test('Copilot preflight rejects manual provider when authoritative scores are missing', () => {
  const missing = createHarness({ provider: 'MANUAL' });
  const blocked = missing.clickCopilot();
  assert.equal(blocked.defaultPrevented, true);
  assert.match(missing.elements.copilotContent.rendered.textContent, /requires an AI provider/i);

  const completed = createHarness({ provider: 'MANUAL', status: 'SUCCESS', currentScores: { IP: 100 } });
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

const browseSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js', import.meta.url), 'utf8');
const sessionCoreSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-core.js', import.meta.url), 'utf8');
const sessionUiSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-ui.js', import.meta.url), 'utf8');
const sessionDraftSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-draft.js', import.meta.url), 'utf8');

// Load the complete browser module and dispatch its registered input listener.
// Controlled timers make completion before the 300 ms stale check deterministic.
const sessionProjectsSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-projects.js', import.meta.url), 'utf8');
const scoringSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js', import.meta.url), 'utf8');

function createStatusHarness({ session = false, observers = false, draftResponse = null } = {}) {
  class Element {
    constructor() {
      this.dataset = {};
      this.children = [];
      this.listeners = new Map();
      this.classList = mutableClassList();
      this.classList.toggle = (name, enabled) => enabled
        ? this.classList.add(name) : this.classList.remove(name);
      this.value = '';
      this.textContent = '';
      this.markup = '';
    }
    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, []);
      this.listeners.get(type).push(listener);
    }
    dispatch(type) { (this.listeners.get(type) || []).forEach(listener => listener()); }
    setAttribute() {}
    focus() {}
    appendChild(child) { child.parent = this; this.children.push(child); }
    remove() { this.parent.children = this.parent.children.filter(child => child !== this); }
    replaceChildren(...children) { this.markup = ''; this.children = children; }
    set innerHTML(value) {
      this.markup = value;
      this.children = [];
      if (value.includes('class="alert ')) {
        const alert = new Element();
        alert.className = value.match(/class="([^"]+)"/)[1];
        this.children.push(alert);
      }
    }
    get innerHTML() { return this.markup; }
    get firstChild() { return this.children[0] || null; }
    querySelector(selector) {
      for (const child of this.children) {
        if (selector === '.alert' && child.className?.split(' ').includes('alert')) return child;
        if (selector === '.btn-warning' && child.className?.split(' ').includes('btn-warning')) return child;
        if (selector === '[data-analysis-session-feedback]' && child.dataset.analysisSessionFeedback) return child;
        const action = selector.match(/^\[data-analysis-session-action="([^"]+)"\]$/);
        if (action && child.dataset.analysisSessionAction === action[1]) return child;
        const found = child.querySelector(selector);
        if (found) return found;
      }
      return null;
    }
  }
  const elements = Object.fromEntries(['analyzeBtn', 'expandAll', 'collapseAll',
    'businessText', 'statusArea', 'a11yStatus', 'a11yAlert'].map(id => [id, new Element()]));
  const listeners = new Map();
  const document = {
    readyState: 'loading', documentElement: { lang: 'en' }, body: new Element(),
    getElementById: id => elements[id] || null,
    dispatchEvent(event) { (listeners.get(event.type) || []).forEach(listener => listener(event)); },
    querySelectorAll: () => [], createElement: () => new Element(),
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(listener);
    }
  };
  const mutations = [];
  class MutationObserver {
    constructor(callback) { this.callback = callback; }
    observe(target) { mutations.push({ target, callback: this.callback }); }
  }
  const timers = new Map();
  let serial = 0;
  const setTimeout = (callback, delay) => {
    const id = ++serial;
    timers.set(id, { callback, delay });
    return id;
  };
  const clearTimeout = id => timers.delete(id);
  const state = { currentScores: { IP: 80 }, lastAnalyzedText: 'Original requirement' };
  const window = { TaxonomyState: state, setTimeout, clearTimeout,
    setInterval() {}, addEventListener() {},
    requestAnimationFrame: callback => callback(), location: { search: '' } };
  const sandbox = vm.createContext({ window, document, URLSearchParams, MutationObserver,
    CustomEvent: class CustomEvent {
      constructor(type, { detail } = {}) { this.type = type; this.detail = detail; }
    },
    TaxonomyI18n: { t: key => key }, TaxonomyUtils: { escapeHtml: value => value },
    fetch: () => new Promise(() => {}), setInterval: () => {},
    setTimeout, clearTimeout, requestAnimationFrame: callback => callback() });
  vm.runInContext(browseSource, sandbox, { filename: 'taxonomy-browse.js' });
  if (session) {
    vm.runInContext(sessionCoreSource, sandbox, { filename: 'taxonomy-analysis-session-core.js' });
    vm.runInContext(sessionUiSource, sandbox, { filename: 'taxonomy-analysis-session-ui.js' });
    if (draftResponse) {
      window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'ws-1';
      window.__TaxonomyAnalysisSessionContext.jsonRequest = async (...args) =>
        typeof draftResponse === 'function' ? draftResponse(...args) : draftResponse;
      vm.runInContext(sessionDraftSource, sandbox, { filename: 'taxonomy-analysis-session-draft.js' });
    }
  }
  (listeners.get('DOMContentLoaded') || []).forEach(listener => listener());
  if (observers) {
    window.__TaxonomyAnalysisSessionContext.queueSave = () => {};
    vm.runInContext(sessionProjectsSource, sandbox, { filename: 'taxonomy-analysis-session-projects.js' });
    window.__TaxonomyAnalysisSessionContext.installObservers();
  }
  return {
    elements, state, window,
    edit(value) { elements.businessText.value = value; elements.businessText.dispatch('input'); },
    rejectPreflight() {
      vm.runInContext(scoringSource, sandbox, { filename: 'taxonomy-scoring.js' });
      window.TaxonomyScoring.runAnalysis();
    },
    notifyStatusMutation() {
      const observer = mutations.find(entry => entry.target === elements.statusArea);
      assert.ok(observer, 'Actual status MutationObserver must be installed');
      observer.callback();
    },
    runModernCheck() { this.runInputCheck(340); },
    runInputCheck(delay = 300) {
      const entry = [...timers].find(([, timer]) => timer.delay === delay);
      assert.ok(entry, 'The real input handler must schedule its stale check');
      timers.delete(entry[0]);
      entry[1].callback();
    }
  };
}

for (const [kind, message] of [['success', 'Analysis complete'],
  ['warning', 'Partial analysis: provider unavailable'], ['danger', 'Analysis failed']]) {
  test(`delayed input check preserves newer ${kind} feedback after analysis completes`, () => {
    const h = createStatusHarness({ session: true });
    h.edit('Updated requirement');
    h.window.__TaxonomyAnalysisSessionContext.showStaleActions();
    h.state.lastAnalyzedText = h.elements.businessText.value;
    h.window.TaxonomyBrowse.showStatus(kind, message);
    const completion = h.elements.statusArea.innerHTML;

    h.runInputCheck();

    assert.equal(h.elements.statusArea.innerHTML, completion);
    assert.match(completion, new RegExp(message));
    assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, undefined);
    assert.equal(h.elements.businessText.classList.contains('stale-results'), false);
    assert.equal(h.elements[kind === 'danger' ? 'a11yAlert' : 'a11yStatus'].textContent, message);
  });
}

test('no-session input callback preserves completed feedback', () => {
  const h = createStatusHarness();
  h.edit('Updated requirement');
  h.state.lastAnalyzedText = h.elements.businessText.value;
  h.window.TaxonomyBrowse.showStatus('success', 'Analysis complete');
  h.runInputCheck();
  assert.match(h.elements.statusArea.innerHTML, /Analysis complete/);
});

test('legacy stale warning retains reset control and clears when text is reverted', () => {
  const h = createStatusHarness();
  h.edit('Updated requirement');
  h.runInputCheck();
  assert.equal(h.elements.businessText.classList.contains('stale-results'), true);
  assert.match(h.elements.statusArea.innerHTML, /browse.stale.warning/);
  const reset = h.elements.statusArea.querySelector('.btn-warning');
  assert.ok(reset);
  assert.equal(reset.listeners.get('click').length, 1);

  h.edit('Original requirement');
  h.runInputCheck();

  assert.equal(h.elements.statusArea.innerHTML, '');
  assert.equal(h.elements.statusArea.children.length, 0);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, undefined);
  assert.equal(h.elements.businessText.classList.contains('stale-results'), false);
});

test('pending legacy input callback retains existing modern stale actions', () => {
  const h = createStatusHarness({ session: true });
  h.edit('Updated requirement');
  h.window.__TaxonomyAnalysisSessionContext.showStaleActions();
  const discardEdit = h.elements.statusArea.querySelector('[data-analysis-session-action="discard-edit"]');
  const discardAnalysis = h.elements.statusArea.querySelector('[data-analysis-session-action="discard-analysis"]');
  assert.ok(discardEdit);
  assert.ok(discardAnalysis);

  h.runInputCheck();

  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="discard-edit"]'), discardEdit);
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="discard-analysis"]'), discardAnalysis);
  assert.equal(discardEdit.listeners.get('click').length, 1);
});

test('pending input callback clears modern stale feedback after a genuine text revert', () => {
  const h = createStatusHarness({ session: true });
  h.edit('Updated requirement');
  h.window.__TaxonomyAnalysisSessionContext.showStaleActions();
  h.edit('Original requirement');
  h.runInputCheck();
  assert.equal(h.elements.statusArea.children.length, 0);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, undefined);
});

test('matching-text input check preserves modern non-stale action feedback', () => {
  const h = createStatusHarness({ session: true });
  h.edit('Original requirement');
  h.window.__TaxonomyAnalysisSessionContext.showActionAlert('danger', 'Draft conflict',
    'Choose a version', [{ id: 'reload', label: 'Reload', handler() {} }], 'conflict');
  const reload = h.elements.statusArea.querySelector('[data-analysis-session-action="reload"]');
  h.runInputCheck();
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="reload"]'), reload);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, 'conflict');
});

test('further input cannot replace an unresolved saved-draft choice with stale actions', async () => {
  const h = createStatusHarness({ session: true, observers: true,
    draftResponse: { version: 8, payload: { businessText: 'Remote saved requirement' } } });
  const context = h.window.__TaxonomyAnalysisSessionContext;
  h.edit('New local requirement');
  await context.loadDraft();
  const keepLocal = h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]');
  assert.ok(keepLocal);

  h.edit('Continued local requirement');
  h.runInputCheck();
  h.runModernCheck();

  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, 'resume-choice');
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]'), keepLocal);
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="discard-analysis"]'), null);
  assert.equal(context.runtime.restoring, true);
  assert.equal(h.elements.businessText.value, 'Continued local requirement');
  assert.equal(h.state.currentScores.IP, 80);
});

test('keeping a local draft resolves its choice and exposes the retained analysis stale actions', async () => {
  const h = createStatusHarness({ session: true, observers: true,
    draftResponse: { version: 8, payload: { businessText: 'Remote saved requirement' } } });
  const context = h.window.__TaxonomyAnalysisSessionContext;
  h.edit('New local requirement');
  await context.loadDraft();
  h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]').dispatch('click');

  assert.equal(context.runtime.restoring, false);
  assert.equal(context.runtime.version, 8);
  assert.equal(h.elements.businessText.value, 'New local requirement');
  assert.equal(h.state.currentScores.IP, 80);
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]'), null);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, 'stale');
  assert.ok(h.elements.statusArea.querySelector('[data-analysis-session-action="discard-analysis"]'));
});

test('saving during an unresolved draft choice preserves the decision and can still recover', async () => {
  const h = createStatusHarness({ session: true, observers: true,
    draftResponse: { version: 8, payload: { businessText: 'Remote saved requirement' } } });
  const context = h.window.__TaxonomyAnalysisSessionContext;
  h.edit('New local requirement');
  await context.loadDraft();
  const keepLocal = h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]');

  assert.equal(await context.saveDraftNow(), false);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, 'resume-choice');
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]'), keepLocal);
  assert.equal(context.runtime.restoring, true);
  assert.equal(context.runtime.draftDecisionPending, true);
  assert.equal(h.elements.businessText.value, 'New local requirement');
  assert.equal(h.state.currentScores.IP, 80);

  keepLocal.dispatch('click');
  assert.equal(context.runtime.restoring, false);
  assert.equal(context.runtime.draftDecisionPending, false);
  assert.equal(await context.saveDraftNow(), true);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, 'draft-saved-now');
});

for (const marker of ['resume-choice', 'conflict']) {
  test(`lifecycle feedback cannot remove a pending ${marker} and replaces only previous feedback`, async () => {
    const h = createStatusHarness({ session: true, observers: true,
      draftResponse: { version: 8, payload: { businessText: 'Remote saved requirement' } } });
    const context = h.window.__TaxonomyAnalysisSessionContext;
    h.edit('New local requirement');
    await context.loadDraft();
    if (marker === 'conflict') context.showDraftConflict();
    const action = marker === 'conflict' ? 'reload-remote' : 'keep-local';
    const decision = h.elements.statusArea.querySelector(`[data-analysis-session-action="${action}"]`);

    context.showActionAlert('info', 'Loading projects', '', [], 'loading-projects');
    context.showActionAlert('danger', 'Projects unavailable', 'Try again', [], 'project-load-error');

    assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, marker);
    assert.equal(h.elements.statusArea.querySelector(`[data-analysis-session-action="${action}"]`), decision);
    assert.equal(h.elements.statusArea.children.length, 2, 'Retain the decision and only the newest feedback');
    assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-feedback]').firstChild.textContent,
      'Projects unavailable');
    assert.equal(h.elements.a11yAlert.textContent, 'Projects unavailable. Try again');
  });
}

test('confirmed New Analysis resolves a pending draft choice after its authoritative reset succeeds', async () => {
  const calls = [];
  const h = createStatusHarness({ session: true, observers: true,
    draftResponse: async (url, options) => {
      calls.push({ url, method: options.method });
      return url.endsWith('/reset')
        ? { version: 9, payload: { businessText: '', draftState: 'EMPTY' } }
        : { version: 8, payload: { businessText: 'Remote saved requirement' } };
    } });
  const context = h.window.__TaxonomyAnalysisSessionContext;
  h.window.confirm = () => false;
  h.edit('New local requirement');
  await context.loadDraft();

  assert.equal(await context.startNewAnalysis(), false);
  assert.equal(context.runtime.draftDecisionPending, true);
  assert.deepEqual(calls.map(call => call.method), ['GET']);
  h.window.confirm = () => true;
  assert.equal(await context.startNewAnalysis(), true);
  assert.equal(context.runtime.restoring, false);
  assert.equal(context.runtime.draftDecisionPending, false);
  assert.equal(context.runtime.version, 9);
  assert.equal(h.elements.businessText.value, '');
  assert.equal(h.state.currentScores, null);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, 'new-analysis');
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]'), null);
  assert.deepEqual(calls.map(call => call.method), ['GET', 'POST']);
});

test('failed New Analysis retains the draft decision and displays its reset failure alongside it', async () => {
  const h = createStatusHarness({ session: true, observers: true,
    draftResponse: async url => {
      if (url.endsWith('/reset')) throw new Error('Reset unavailable');
      return { version: 8, payload: { businessText: 'Remote saved requirement' } };
    } });
  const context = h.window.__TaxonomyAnalysisSessionContext;
  h.window.confirm = () => true;
  h.edit('New local requirement');
  await context.loadDraft();
  const keepLocal = h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]');

  assert.equal(await context.startNewAnalysis(), false);
  assert.equal(context.runtime.restoring, true);
  assert.equal(context.runtime.draftDecisionPending, true);
  assert.equal(context.runtime.resetting, false);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, 'resume-choice');
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="keep-local"]'), keepLocal);
  assert.ok(h.elements.statusArea.querySelector('[data-analysis-session-feedback]'));
  assert.equal(h.elements.businessText.value, 'New local requirement');
  assert.equal(h.state.currentScores.IP, 80);
  assert.match(h.elements.a11yAlert.textContent, /Reset unavailable/);
});

test('failed New Analysis cannot release an existing conflict barrier or permit autosave', async () => {
  const calls = [];
  const h = createStatusHarness({ session: true, observers: true,
    draftResponse: async (url, options) => {
      calls.push({ url, method: options.method });
      throw new Error('Reset unavailable');
    } });
  const context = h.window.__TaxonomyAnalysisSessionContext;
  context.runtime.initialized = true;
  context.runtime.workspaceResolved = true;
  h.window.confirm = () => true;
  h.edit('Conflicting local requirement');
  context.showDraftConflict();

  assert.equal(await context.startNewAnalysis(), false);
  assert.equal(context.runtime.conflict, true);
  assert.equal(context.runtime.restoring, false);
  assert.equal(h.window.TaxonomyAnalysisSession.state().ready, false);
  assert.equal(await context.saveDraft(), false);
  assert.deepEqual(calls.map(call => call.method), ['POST']);
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, 'conflict');
  assert.ok(h.elements.statusArea.querySelector('[data-analysis-session-action="reload-remote"]'));
  assert.equal(h.elements.businessText.value, 'Conflicting local requirement');
});

test('explicit browse clear removes previous session status ownership', () => {
  const h = createStatusHarness({ session: true });
  h.edit('Updated requirement');
  h.window.__TaxonomyAnalysisSessionContext.showStaleActions();
  h.window.TaxonomyBrowse.clearStatus();
  assert.equal(h.elements.statusArea.dataset.analysisSessionMessage, undefined);
  assert.equal(h.elements.statusArea.children.length, 0);
});


for (const kind of ['warning', 'danger']) {
  for (const session of [false, true]) {
    test(`legacy mismatch callback preserves newer ${kind} feedback with session=${session}`, () => {
      const h = createStatusHarness({ session });
      h.edit('Updated requirement');
      h.window.TaxonomyBrowse.showStatus(kind, 'Newer preflight explanation');
      const feedback = h.elements.statusArea.innerHTML;
      h.runInputCheck();
      assert.equal(h.elements.statusArea.innerHTML, feedback);
      assert.equal(h.state.lastAnalyzedText, 'Original requirement');
      assert.equal(h.state.currentScores.IP, 80);
      assert.equal(h.elements.businessText.classList.contains('stale-results'), true);
      assert.equal(h.elements[kind === 'danger' ? 'a11yAlert' : 'a11yStatus'].textContent,
        'Newer preflight explanation');
    });
  }
  test(`modern stale timer and status observer requeue preserve newer ${kind} feedback`, () => {
    const h = createStatusHarness({ session: true, observers: true });
    h.edit('Updated requirement');
    h.window.TaxonomyBrowse.showStatus(kind, 'Newer preflight explanation');
    const feedback = h.elements.statusArea.innerHTML;
    h.notifyStatusMutation();
    h.runInputCheck();
    h.runModernCheck();
    assert.equal(h.elements.statusArea.innerHTML, feedback);
    assert.equal(h.state.lastAnalyzedText, 'Original requirement');
    // A further observer delivery must not grant stale UI fresh ownership.
    h.notifyStatusMutation();
    h.runModernCheck();
    assert.equal(h.elements.statusArea.innerHTML, feedback);
  });
}

test('actual analysis preflight rejection keeps its reason through both registered stale checks', () => {
  const h = createStatusHarness({ session: true, observers: true });
  h.edit('Updated requirement');
  h.rejectPreflight();
  assert.match(h.elements.statusArea.innerHTML, /scoring.lifecycle.not.ready/);
  h.notifyStatusMutation();
  h.runInputCheck();
  h.runModernCheck();
  assert.match(h.elements.statusArea.innerHTML, /scoring.lifecycle.not.ready/);
  assert.equal(h.state.lastAnalyzedText, 'Original requirement');
  assert.equal(h.state.currentScores.IP, 80);
});

test('a new edit releases previous feedback to legacy and modern stale actions', () => {
  const h = createStatusHarness({ session: true, observers: true });
  h.edit('Updated requirement');
  h.window.TaxonomyBrowse.showStatus('warning', 'Preflight rejected');
  h.edit('Another requirement');
  h.runInputCheck();
  assert.match(h.elements.statusArea.innerHTML, /browse.stale.warning/);
  h.runModernCheck();
  assert.ok(h.elements.statusArea.querySelector('[data-analysis-session-action="discard-edit"]'));
  assert.ok(h.elements.statusArea.querySelector('[data-analysis-session-action="discard-analysis"]'));
  h.edit('Original requirement');
  h.runInputCheck();
  h.runModernCheck();
  assert.equal(h.elements.statusArea.children.length, 0);
});

test('modern non-stale action feedback published for edited text survives observer requeue', () => {
  const h = createStatusHarness({ session: true, observers: true });
  h.edit('Updated requirement');
  h.window.__TaxonomyAnalysisSessionContext.showActionAlert('danger', 'New action feedback', '',
    [{ id: 'reload', label: 'Reload', handler() {} }], 'conflict');
  const reload = h.elements.statusArea.querySelector('[data-analysis-session-action="reload"]');
  h.notifyStatusMutation();
  h.runInputCheck();
  h.runModernCheck();
  assert.equal(h.elements.statusArea.querySelector('[data-analysis-session-action="reload"]'), reload);
});
