import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { normalizeCopilotEndpoint } from './ui-primary-session-workflow.mjs';

const sessionUiSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-copilot-session-ui.js',
  import.meta.url), 'utf8');
const loaderSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session.js',
  import.meta.url), 'utf8');
const browserWorkflowSource = await readFile(new URL(
  './ui-primary-session-workflow.mjs', import.meta.url), 'utf8');
const acceptanceSource = await readFile(new URL(
  './ui-primary-workflow-acceptance.mjs', import.meta.url), 'utf8');
const analysisSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis.js',
  import.meta.url), 'utf8');
const workflowCssSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/css/taxonomy-analysis-workflow.css',
  import.meta.url), 'utf8');

function checkbox(checked) {
  return { checked, dispatchEvent() {} };
}

function createPreflightHarness({ stale = false, invalidationSucceeds = true } = {}) {
  const listeners = new Map();
  const interactive = checkbox(true);
  const architecture = checkbox(false);
  const copilot = {};
  const elements = {
    interactiveMode: interactive,
    includeArchitectureView: architecture,
    copilotBtn: copilot
  };
  const invalidations = [];
  const context = {
    runtime: {
      draftDecisionPending: false,
      conflict: false,
      invalidating: false
    },
    language: () => 'en',
    isStale: () => stale,
    invalidate(options) {
      invalidations.push(options);
      if (invalidationSucceeds) window._taxonomyCurrentScores = null;
    }
  };
  const document = {
    readyState: 'loading',
    addEventListener(type, listener, capture = false) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push({ listener, capture });
    },
    getElementById(id) { return elements[id] || null; },
    createElement() { return { className: '', textContent: '' }; }
  };
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    _taxonomyCurrentScores: stale ? { IP: 92 } : null
  };
  vm.runInNewContext(sessionUiSource, {
    window,
    document,
    Event: class Event {},
    Object,
    Array,
    Boolean,
    String
  }, { filename: 'taxonomy-copilot-session-ui.js' });

  return {
    interactive,
    architecture,
    invalidations,
    dispatchCopilot() {
      const event = {
        target: {
          closest(selector) { return selector === '#copilotBtn' ? copilot : null; }
        },
        defaultPrevented: false,
        immediatePropagationStopped: false,
        preventDefault() { this.defaultPrevented = true; },
        stopImmediatePropagation() { this.immediatePropagationStopped = true; }
      };
      for (const entry of listeners.get('click') || []) {
        if (entry.capture) entry.listener(event);
      }
      return event;
    }
  };
}

function initializationTimerCount(hasAnalysisSurface) {
  let timers = 0;
  const context = {
    runtime: {},
    language: () => 'en',
    isStale: () => false
  };
  const document = {
    readyState: 'complete',
    head: null,
    body: {},
    documentElement: {},
    addEventListener() {},
    getElementById(id) {
      return hasAnalysisSurface && id === 'businessText' ? {} : null;
    }
  };
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    setTimeout(callback) {
      timers += 1;
      callback();
    }
  };
  vm.runInNewContext(sessionUiSource, {
    window,
    document,
    Object,
    Array,
    Boolean,
    String
  }, { filename: 'taxonomy-copilot-session-ui.js' });
  return timers;
}

test('session UI loads before the operation coordinator', () => {
  const loaded = [];
  const document = {
    createElement() {
      const listeners = new Map();
      return {
        dataset: {},
        addEventListener(type, listener) { listeners.set(type, listener); },
        fire(type) { listeners.get(type)?.(); }
      };
    },
    head: {
      firstChild: null,
      appendChild(script) { loaded.push(script.src); script.fire('load'); },
      insertBefore(script) { loaded.push(script.src); script.fire('load'); }
    }
  };
  const window = {
    console,
    TaxonomyI18n: { resolveUrl: value => `/taxonomy${value}` }
  };
  vm.runInNewContext(loaderSource, { window, document, console, String }, {
    filename: 'taxonomy-analysis-session.js'
  });
  const sessionIndex = loaded.indexOf('/taxonomy/js/core/taxonomy-copilot-session-ui.js');
  const terminalIndex = loaded.indexOf('/taxonomy/js/core/taxonomy-copilot-terminal-state.js');
  const coordinatorIndex = loaded.indexOf('/taxonomy/js/core/taxonomy-operation-coordinator.js');
  assert.ok(terminalIndex >= 0 && sessionIndex > terminalIndex);
  assert.ok(coordinatorIndex > sessionIndex);
});

test('Copilot always selects complete analysis and architecture output', () => {
  const harness = createPreflightHarness();
  const event = harness.dispatchCopilot();
  assert.equal(event.defaultPrevented, false);
  assert.equal(harness.interactive.checked, false);
  assert.equal(harness.architecture.checked, true);
  assert.equal(harness.invalidations.length, 0);
});

test('stale scores are invalidated before the coordinator can reuse them', () => {
  const harness = createPreflightHarness({ stale: true });
  const event = harness.dispatchCopilot();
  assert.equal(event.defaultPrevented, false);
  assert.equal(harness.interactive.checked, false);
  assert.equal(harness.architecture.checked, true);
  assert.deepEqual(JSON.parse(JSON.stringify(harness.invalidations)), [{
    keepText: true,
    silent: true,
    reason: 'copilot-reanalysis'
  }]);
});

test('blocked stale invalidation preserves the user mode choices', () => {
  const harness = createPreflightHarness({
    stale: true,
    invalidationSucceeds: false
  });
  const event = harness.dispatchCopilot();
  assert.equal(event.defaultPrevented, true);
  assert.equal(event.immediatePropagationStopped, true);
  assert.equal(harness.interactive.checked, true);
  assert.equal(harness.architecture.checked, false);
  assert.equal(harness.invalidations.length, 1);
});

test('session UI retries are bounded and skipped outside the analysis surface', () => {
  assert.equal(initializationTimerCount(false), 0);
  assert.equal(initializationTimerCount(true), 30);
  assert.match(sessionUiSource, /var INITIALIZATION_RETRY_LIMIT = 30;/);
  assert.match(sessionUiSource,
    /function analysisSurfacePresent\(\)[\s\S]*?tab-analyze[\s\S]*?businessText[\s\S]*?copilotBtn/);
  assert.match(sessionUiSource,
    /initializationRetryCount >= INITIALIZATION_RETRY_LIMIT/);
  assert.match(sessionUiSource,
    /window\.addEventListener\('load', initializeUi, \{ once: true \}\)/);
  assert.doesNotMatch(sessionUiSource,
    /window\.setTimeout\(initializeUi,\s*100\)/);
});

test('Copilot request accounting normalizes supported application base paths', () => {
  for (const endpoint of [
    '/api/analyze', '/api/gap/analyze', '/api/patterns/detect', '/api/recommend'
  ]) {
    assert.equal(normalizeCopilotEndpoint(endpoint), endpoint);
    assert.equal(normalizeCopilotEndpoint(`/taxonomy${endpoint}`), endpoint);
    assert.equal(normalizeCopilotEndpoint(`/nested/context${endpoint}`), endpoint);
  }
  assert.equal(normalizeCopilotEndpoint('/api/unrelated'), '');
  assert.equal(normalizeCopilotEndpoint(''), '');
});

test('session UI makes Copilot primary and hardens responsive geometry', () => {
  for (const required of [
    "analysis-command-grid",
    "analysis-primary-action",
    "cancel.classList.toggle('d-none', !visible)",
    "taskNextAction[data-action=\"copilot\"]",
    "document.elementFromPoint",
    "['bottom-end', 'bottom-start', 'top-end', 'top-start']",
    "function scheduleTaskRewrite()",
    "function scheduleOverlayReposition()",
    "new MutationObserver(scheduleTaskRewrite)",
    "new MutationObserver(scheduleOverlayReposition)",
    "/css/taxonomy-analysis-workflow.css"
  ]) assert.match(sessionUiSource, new RegExp(required.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.doesNotMatch(sessionUiSource, /observer\.observe\(next/);
  assert.doesNotMatch(sessionUiSource,
    /addEventListener\('(focusin|resize|scroll)',\s*repositionOverlay/);
});

test('command grid replaces template children without losing mode controls', () => {
  const modeCapture = sessionUiSource.indexOf(
    'var groups = [interactive, architecture]');
  const replacement = sessionUiSource.indexOf(
    'row.replaceChildren(provider, copilot, analyze, cancel);');
  assert.ok(modeCapture >= 0 && replacement > modeCapture,
    'Mode option wrappers must be captured before command-row replacement');
  assert.equal(sessionUiSource.match(
    /var groups = \[interactive, architecture\]/g)?.length, 1);
  assert.doesNotMatch(sessionUiSource,
    /row\.append\(provider, copilot, analyze, cancel\);/);
});

test('overlay placement ignores document roots as focus targets', () => {
  assert.match(sessionUiSource,
    /var targets = \[document\.activeElement,[\s\S]*?\.filter\(function \(target\) \{[\s\S]*?target !== document\.body[\s\S]*?target !== document\.documentElement[\s\S]*?!lane\.contains\(target\)/);
});

test('Copilot task routing survives late onboarding synchronisation', () => {
  for (const required of [
    'var onboardingTaskProgressPatched = false;',
    'function patchOnboardingTaskProgress()',
    'current.__taxonomyCopilotTaskRoutingPatched === true',
    'syncTaskProgressWithCopilotRouting.__taxonomyCopilotTaskRoutingPatched = true;',
    "event.target.closest(\n                    '#taskNextAction[data-action=\"copilot\"], '\n                    + '#taskNextAction[data-action=\"analyze\"]')"
  ]) assert.ok(sessionUiSource.includes(required), `Missing routing authority: ${required}`);

  assert.match(sessionUiSource,
    /function syncTaskProgressWithCopilotRouting\(\)\s*\{[\s\S]*?current\.apply\(this, arguments\);[\s\S]*?scheduleTaskRewrite\(\);[\s\S]*?\}/);
  assert.match(sessionUiSource,
    /function initializeUi\(\)\s*\{[\s\S]*?patchOnboardingTaskProgress\(\);[\s\S]*?installTaskRouting\(\);/);
  assert.match(sessionUiSource,
    /function initializationComplete\(\)[\s\S]*?taskObserverInstalled[\s\S]*?overlayObserverInstalled[\s\S]*?onboardingTaskProgressPatched/);
});

test('focused Analyze and Copilot use distinct running-state messages', async () => {
  const english = await readFile(new URL(
    '../../taxonomy-app/src/main/resources/i18n/messages_task_focus.properties',
    import.meta.url), 'utf8');
  const german = await readFile(new URL(
    '../../taxonomy-app/src/main/resources/i18n/messages_task_focus_de.properties',
    import.meta.url), 'utf8');
  assert.match(english, /^analysis\.task\.next\.running=Analysis running…$/m);
  assert.match(english, /^analysis\.task\.next\.copilotRunning=Copilot running…$/m);
  assert.match(german, /^analysis\.task\.next\.running=Analyse läuft…$/m);
  assert.match(german, /^analysis\.task\.next\.copilotRunning=Copilot läuft…$/m);
  assert.match(sessionUiSource,
    /text\('analysis\.task\.next\.copilotRunning'/);
  assert.doesNotMatch(sessionUiSource,
    /text\('analysis\.task\.next\.running',[\s\S]*?'Copilot läuft…'/);
});

test('disabled controls remain overlay-testable without weakening application semantics', () => {
  for (const required of [
    "pointerEventsSuppressed && !expectedDisabled",
    "element.style.setProperty('pointer-events', 'auto', 'important')",
    "element.style.removeProperty('pointer-events')",
    "inlinePointerEventsPriority"
  ]) assert.ok(browserWorkflowSource.includes(required), `Missing hit-test guard: ${required}`);
  assert.match(browserWorkflowSource, /finally\s*\{[\s\S]*removeProperty\('pointer-events'\)/);
});

test('short-height task stages stay labelled without a separate horizontal scroll region', () => {
  assert.match(workflowCssSource,
    /@media \(max-height: 32rem\)[\s\S]*?\.analysis-task-stages\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\);[^}]*overflow-x:\s*visible;/s);
  assert.match(workflowCssSource,
    /\.analysis-task-stages li\s*\{[^}]*font-size:\s*0\.75rem;[^}]*\}/s);
  assert.match(workflowCssSource,
    /\.analysis-task-stages \.analysis-task-copy strong\s*\{[^}]*overflow-wrap:\s*anywhere;[^}]*hyphens:\s*auto;[^}]*\}/s);
  assert.doesNotMatch(workflowCssSource,
    /grid-template-columns:\s*repeat\(4,\s*minmax\(7rem,\s*1fr\)\)/);
  assert.doesNotMatch(workflowCssSource,
    /\.analysis-task-stages\s*\{[^}]*overflow-x:\s*auto;/s);
});

test('extreme zoom reserves a side rail instead of covering the focused task', () => {
  assert.match(workflowCssSource,
    /@media \(max-height: 18rem\) and \(max-width: 30rem\)[\s\S]*?\.taxonomy-overlay-lane\[data-position="top-start"\]\s*\{[^}]*flex-direction:\s*column;[^}]*width:\s*6rem;[^}]*height:\s*100vh;[^}]*overflow-y:\s*auto;[^}]*\}/s);
  assert.match(workflowCssSource,
    /\.taxonomy-overlay-lane\[data-position="top-start"\] > \.undo-toast\s*\{[^}]*flex:\s*0 0 calc\(\(100vh - 0\.25rem\) \/ 2\);[^}]*width:\s*6rem;[^}]*overflow:\s*hidden;[^}]*\}/s);
  assert.match(workflowCssSource,
    /\.taxonomy-overlay-lane\[data-position="top-start"\] \.undo-btn\s*\{[^}]*width:\s*100%;[^}]*min-height:\s*44px;[^}]*\}/s);
  assert.match(workflowCssSource,
    /body:has\(\.taxonomy-overlay-lane\[data-position="top-start"\] > \.undo-toast\) #mainContent\s*\{[^}]*width:\s*calc\(100% - 6\.25rem\);[^}]*margin-inline-start:\s*6\.25rem;[^}]*\}/s);
  assert.doesNotMatch(workflowCssSource,
    /\.taxonomy-overlay-lane\[data-position="top-start"\]\s*\{[^}]*flex-direction:\s*row;/s);
  assert.doesNotMatch(workflowCssSource,
    /\.taxonomy-overlay-lane\[data-position="top-start"\]\s*\{[^}]*width:\s*100vw;/s);
});

test('complete session evidence precedes the deliberately invalid empty-input baseline', () => {
  const sessionIndex = acceptanceSource.indexOf('await runAnalysisSessionWorkflow(workflow);');
  const basicIndex = acceptanceSource.indexOf('await runBasicWorkflows(workflow);');
  assert.ok(sessionIndex >= 0, 'Primary USER workflow does not run the complete session');
  assert.ok(basicIndex > sessionIndex,
    'The empty-input baseline can contaminate the initial session screenshot');
  assert.equal(acceptanceSource.match(/runAnalysisSessionWorkflow\(workflow\)/g)?.length, 1,
    'The complete session must run exactly once');
});

test('pattern percentages preserve the established 0-100 API scale exactly once', () => {
  assert.match(analysisSource,
    /function displayPercent\(value\)[\s\S]*Math\.max\(0,\s*Math\.min\(100,\s*numeric\)\)\.toFixed\(0\)/);
  assert.match(analysisSource, /displayPercent\(data\.patternCoverage\)/);
  assert.match(analysisSource, /displayPercent\(pattern\.completeness\)/);
  assert.match(analysisSource, /displayPercent\(results\.patterns\.patternCoverage\)/);
  assert.doesNotMatch(analysisSource, /patternCoverage\s*\*\s*100/);
  assert.doesNotMatch(analysisSource, /pattern\.completeness\s*\*\s*100/);
});

test('mobile Copilot summary uses readable full-width metric rows', () => {
  assert.match(workflowCssSource,
    /#copilotContent > \.row > \.col-4\s*\{[^}]*flex:\s*0 0 100%;[^}]*width:\s*100%;[^}]*max-width:\s*100%;[^}]*\}/s);
  assert.match(workflowCssSource,
    /#copilotContent > \.row \.card-body\s*\{[^}]*grid-template-columns:\s*2\.25rem minmax\(0,\s*1fr\);[^}]*text-align:\s*left;[^}]*\}/s);
  assert.match(workflowCssSource,
    /#copilotContent > \.row \.card-body > \.fs-4\s*\{[^}]*grid-row:\s*1 \/ 3;[^}]*\}/s);
});

test('Copilot primary action keeps robust AA contrast in enabled and disabled states', () => {
  assert.match(workflowCssSource, /--bs-btn-bg:\s*#146c43;/);
  assert.match(workflowCssSource, /--bs-btn-disabled-bg:\s*#5b7569;/);
  assert.match(workflowCssSource,
    /\.analysis-action-hint\s*\{[^}]*font-weight:\s*600;[^}]*opacity:\s*1;[^}]*\}/s);
  assert.match(workflowCssSource,
    /\.analysis-primary-action \.analysis-action-hint\s*\{[^}]*color:\s*#fff;[^}]*\}/s);
  assert.doesNotMatch(workflowCssSource,
    /\.analysis-action-hint\s*\{[^}]*opacity:\s*0\.[0-9]+;[^}]*\}/s);
});

test('authoritative browser workflow covers both Copilot runs and reset reload', () => {
  for (const endpoint of [
    '/api/analyze', '/api/gap/analyze', '/api/patterns/detect', '/api/recommend'
  ]) assert.ok(browserWorkflowSource.includes(endpoint));
  for (const state of [
    'session-empty',
    'session-copilot-running',
    'session-copilot-complete',
    'session-requirement-stale-mobile',
    'session-copilot-updated-mobile',
    'session-reset-command-mobile',
    'session-reset-complete-mobile',
    'session-reset-reloaded-mobile'
  ]) assert.ok(browserWorkflowSource.includes(state));
  assert.match(browserWorkflowSource, /fileNewAnalysisAction/);
  assert.match(browserWorkflowSource, /page\.reload/);
  assert.match(browserWorkflowSource, /draftState === 'EMPTY'/);
});