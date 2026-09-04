import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const sessionUiSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-copilot-session-ui.js',
  import.meta.url), 'utf8');

test('delayed translations recover initialization after bounded polling', async () => {
  let resolveTranslations;
  const translationsReady = new Promise(resolve => { resolveTranslations = resolve; });
  let readyCalls = 0;
  let timers = 0;
  let animationFrames = 0;
  const elements = {
    businessText: {
      value: '',
      classList: { contains: () => false },
      addEventListener() {}
    }
  };
  const document = {
    readyState: 'complete',
    head: null,
    body: {},
    documentElement: {},
    activeElement: null,
    addEventListener() {},
    getElementById(id) { return elements[id] || null; }
  };
  const window = {
    __TaxonomyAnalysisSessionContext: {
      runtime: {},
      language: () => 'en',
      isStale: () => false
    },
    TaxonomyI18n: {
      ready() {
        readyCalls += 1;
        return translationsReady;
      }
    },
    setTimeout(callback) {
      timers += 1;
      callback();
    },
    requestAnimationFrame(callback) {
      animationFrames += 1;
      callback();
      return animationFrames;
    },
    addEventListener() {},
    innerWidth: 1280,
    innerHeight: 720
  };

  vm.runInNewContext(sessionUiSource, {
    window,
    document,
    Object,
    Array,
    Boolean,
    String
  }, { filename: 'taxonomy-copilot-session-ui.js' });

  assert.equal(timers, 30, 'polling must remain bounded');
  assert.equal(readyCalls, 1, 'one translation-completion fallback is sufficient');

  const classList = { contains: () => true };
  elements.taskNextAction = {
    disabled: false,
    dataset: { action: 'analyze' },
    textContent: ''
  };
  elements.copilotBtn = { disabled: false, click() {} };
  elements.copilotSpinner = { classList };
  elements.taxonomyOverlayLane = {
    children: [],
    dataset: {},
    contains: () => false
  };
  window.TaxonomyOnboarding = { syncTaskProgress() {} };

  resolveTranslations();
  await translationsReady;
  await Promise.resolve();

  assert.equal(
    window.TaxonomyOnboarding.syncTaskProgress.__taxonomyCopilotTaskRoutingPatched,
    true,
    'routing must initialize after onboarding creates its delayed DOM'
  );
  assert.equal(elements.taskNextAction.dataset.action, 'copilot');
  assert.equal(timers, 30, 'translation completion must not restart polling');
  assert.ok(animationFrames >= 2,
    'the fallback and overlay hardening should run after promise handlers settle');
});
