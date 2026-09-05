import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-copilot-session-ui.js',
  import.meta.url), 'utf8');

function element() {
  const listeners = new Map();
  const attributes = new Map();
  const classes = new Set();
  return {
    dataset: {}, style: {}, value: '', firstElementChild: null,
    addEventListener(name, handler) {
      const handlers = listeners.get(name) || [];
      handlers.push(handler); listeners.set(name, handlers);
    },
    emit(name) { for (const handler of listeners.get(name) || []) handler({ target: this }); },
    listenerCount(name) { return (listeners.get(name) || []).length; },
    setAttribute(name, value) { attributes.set(name, value); },
    getAttribute(name) { return attributes.get(name); },
    classList: {
      contains(name) { return classes.has(name); },
      toggle(name, on) { if (on) classes.add(name); else classes.delete(name); }
    },
    querySelector() { return null; }
  };
}

function header(kind = 'success') {
  const result = element();
  let value = 'Copilot complete';
  result.textWrites = 0;
  result.title = {};
  Object.defineProperty(result.title, 'textContent', {
    get() { return value; },
    set(next) { result.textWrites++; value = next; }
  });
  result.classList.toggle('alert-' + kind, true);
  result.querySelector = selector => selector === 'strong' ? result.title : null;
  return result;
}

function fixture({ sourceText = 'Original requirement', language = 'en', hasSummary = true } = {}) {
  const input = element(); input.value = 'Original requirement';
  const content = element(); content.firstElementChild = hasSummary ? header() : null;
  const document = element(); document.readyState = 'complete'; document.head = null;
  document.getElementById = id => ({ businessText: input, copilotContent: content }[id] || null);
  document.querySelector = () => null;
  const context = { S: { lastAnalyzedText: sourceText }, runtime: {}, language: () => language };
  context.isStale = () => context.S.lastAnalyzedText !== null
    && input.value !== context.S.lastAnalyzedText;
  const observers = [];
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    requestAnimationFrame() { return 1; },
    addEventListener() {},
    TaxonomyI18n: { t(key) { return key === 'browse.stale.warning'
      ? language === 'de' ? 'Die bisherigen Ergebnisse sind veraltet.' : 'Previous results are stale.'
      : key; } }
  };
  vm.runInNewContext(source, {
    window, document,
    MutationObserver: class {
      constructor(callback) { this.callback = callback; }
      observe(target) { observers.push({ target, callback: this.callback }); }
    }
  });
  return {
    input, content, document, context,
    observe(target) { for (const item of observers) if (item.target === target) item.callback(); }
  };
}

function assertStale(h) {
  assert.equal(h.dataset.copilotResultState, 'stale');
  assert.equal(h.classList.contains('alert-success'), false);
  assert.equal(h.classList.contains('alert-warning'), true);
  assert.equal(h.getAttribute('role'), 'status');
  assert.equal(h.getAttribute('aria-live'), 'polite');
}

test('completed summary starts current and editing removes the success claim', () => {
  const f = fixture(); const h = f.content.firstElementChild;
  assert.equal(h.dataset.copilotResultState, 'current');
  f.input.value = 'Changed requirement'; f.input.emit('input');
  assertStale(h);
  assert.equal(h.title.textContent, 'Previous results are stale.');
});

test('discarding the edit restores the original summary, not a new result', () => {
  const f = fixture(); const h = f.content.firstElementChild;
  f.input.value = 'Changed requirement'; f.input.emit('input');
  f.input.value = 'Original requirement'; f.observe(f.input);
  assert.equal(h.dataset.copilotResultState, 'current');
  assert.equal(h.title.textContent, 'Copilot complete');
  assert.equal(h.classList.contains('alert-warning'), false);
  assert.equal(h.classList.contains('alert-success'), true);
});

test('a newer standalone analysis cannot relabel the old Copilot summary as current', () => {
  const f = fixture(); const h = f.content.firstElementChild;
  f.input.value = 'New requirement'; f.input.emit('input');
  f.context.S.lastAnalyzedText = 'New requirement';
  f.document.emit('taxonomy:view-rendered');
  assertStale(h);
});

test('a genuinely new summary gets its own requirement identity', () => {
  const f = fixture();
  f.input.value = 'New requirement'; f.context.S.lastAnalyzedText = 'New requirement';
  f.content.firstElementChild = header(); f.observe(f.content);
  assert.equal(f.content.firstElementChild.dataset.copilotResultState, 'current');
  f.input.value = 'Original requirement'; f.input.emit('change');
  assertStale(f.content.firstElementChild);
});

test('summary arriving after module initialization is observed', () => {
  const f = fixture({ hasSummary: false });
  f.content.firstElementChild = header(); f.observe(f.content);
  f.input.value = 'Changed requirement'; f.input.emit('input');
  assertStale(f.content.firstElementChild);
});

test('unknown source requirement fails closed rather than claiming success', () => {
  const f = fixture({ sourceText: null });
  assertStale(f.content.firstElementChild);
});

test('draft restoration synchronizes the retained result state', () => {
  const f = fixture(); f.input.value = 'Restored changed requirement';
  f.document.emit('taxonomy:analysis-draft-restored');
  assertStale(f.content.firstElementChild);
});

test('reset does not recreate a removed result or mutate analysis evidence', () => {
  const f = fixture(); const old = f.content.firstElementChild;
  f.content.firstElementChild = null; f.input.value = '';
  f.document.emit('taxonomy:analysis-invalidated');
  f.observe(f.content);
  assert.equal(f.content.firstElementChild, null);
  assert.equal(old.dataset.copilotResultState, 'current');
  assert.equal(f.context.S.lastAnalyzedText, 'Original requirement');
});

test('running, failed, partial and cancellation surfaces are not rewritten', () => {
  for (const kind of ['info', 'danger', 'warning']) {
    const f = fixture({ hasSummary: false }); const h = header(kind);
    f.content.firstElementChild = h; f.input.value = 'Changed requirement';
    f.observe(f.content); f.input.emit('input');
    assert.equal(h.textWrites, 0);
    assert.equal(h.dataset.copilotResultState, undefined);
    assert.equal(h.classList.contains('alert-' + kind), true);
  }
});

test('repeated observations converge without repeated live-region text mutations', () => {
  const f = fixture(); const h = f.content.firstElementChild;
  f.input.value = 'Changed requirement'; f.input.emit('input');
  const writes = h.textWrites;
  for (let index = 0; index < 20; index++) f.observe(f.content);
  assert.equal(h.textWrites, writes);
  assert.equal(f.input.listenerCount('input'), 1);
});

test('the warning uses the existing German translation', () => {
  const f = fixture({ language: 'de' });
  f.input.value = 'Geänderte Anforderung'; f.input.emit('input');
  assertStale(f.content.firstElementChild);
  assert.equal(f.content.firstElementChild.title.textContent, 'Die bisherigen Ergebnisse sind veraltet.');
});

test('both Maven-owned npm entry points run this regression suite', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
  assert.equal(pkg.scripts['test:copilot-summary-freshness'],
    'node --test scripts/copilot-summary-freshness.test.mjs');
  for (const entry of ['verify:ui', 'verify:ui-contracts']) {
    assert.ok(pkg.scripts[entry].split(' && ').includes('npm run test:copilot-summary-freshness'));
  }
});
