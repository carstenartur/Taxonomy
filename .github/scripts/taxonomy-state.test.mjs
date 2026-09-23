import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const stateScript = await readFile(path.join(
  repoRoot,
  'taxonomy-app/src/main/resources/static/js/core/taxonomy-state.js'
), 'utf8');

function createHarness({ renderedCodes = [], currentView = 'list' } = {}) {
  const scheduled = [];
  const allCodes = ['BP', 'BR'];
  const nodes = allCodes.map(code => ({
    dataset: { code },
    querySelector: () => renderedCodes.includes(code) ? { className: 'tax-pct' } : null
  }));
  let renderCalls = 0;

  const window = {
    requestAnimationFrame: callback => scheduled.push(callback),
    setTimeout: callback => scheduled.push(callback)
  };
  const document = {
    // This harness verifies score-render reconciliation only. Pretend the
    // independently tested analysis-session loader is already present so the
    // state module does not need a full DOM implementation here.
    querySelector: selector => {
      assert.equal(selector, 'script[data-taxonomy-analysis-session]');
      return {};
    },
    querySelectorAll: selector => {
      assert.equal(selector, '#taxonomyTree .tax-node[data-code]');
      return nodes;
    }
  };

  vm.runInNewContext(stateScript, {
    window,
    document,
    console,
    Array,
    Map,
    Number,
    Object,
    Set
  }, { filename: 'taxonomy-state.js' });

  window.TaxonomyState.taxonomyData = [{ code: 'BP' }];
  window.TaxonomyState.currentView = currentView;
  window.TaxonomyBrowse = {
    renderView(data, scores) {
      renderCalls += 1;
      assert.equal(data, window.TaxonomyState.taxonomyData);
      assert.equal(scores, window.TaxonomyState.currentScores);
    }
  };

  return {
    state: window.TaxonomyState,
    flush() {
      while (scheduled.length > 0) scheduled.shift()();
    },
    renderCalls: () => renderCalls
  };
}

test('reconciles an authoritative final score map when badges are missing', () => {
  const harness = createHarness();
  harness.state.currentScores = { BP: 85, BR: 0 };
  harness.flush();
  assert.equal(harness.renderCalls(), 1);
});

test('does not render twice when the caller already rendered the replacement map', () => {
  const harness = createHarness({ renderedCodes: ['BP'] });
  harness.state.currentScores = { BP: 85 };
  harness.flush();
  assert.equal(harness.renderCalls(), 0);
});

test('does not interfere with incremental mutations of the streaming score object', () => {
  const harness = createHarness();
  harness.state.currentScores = {};
  harness.flush();
  Object.assign(harness.state.currentScores, { BP: 85 });
  harness.flush();
  assert.equal(harness.renderCalls(), 0);
});

test('leaves graphical views to their dedicated renderers', () => {
  const harness = createHarness({ currentView: 'sunburst' });
  harness.state.currentScores = { BP: 85 };
  harness.flush();
  assert.equal(harness.renderCalls(), 0);
});


test('measured analysis duration belongs to its score envelope, not the next result', () => {
  const { state } = createHarness();
  assert.equal(state.lastAnalysisDurationMillis, null);
  state.currentScores = { BP: 85 };
  state.lastAnalysisDurationMillis = 123456;
  assert.equal(state.lastAnalysisDurationMillis, 123456);
  state.currentScores = { BP: 80 };
  assert.equal(state.lastAnalysisDurationMillis, null);
});
for (const invalid of [-1, NaN, Infinity, '1200', undefined, null]) {
  test(`duration cannot manufacture a measurement from ${String(invalid)}`, () => {
    const { state } = createHarness();
    state.lastAnalysisDurationMillis = invalid;
    assert.equal(state.lastAnalysisDurationMillis, null);
  });
}
test('a measured zero duration is distinct from missing evidence', () => {
  const { state } = createHarness();
  state.lastAnalysisDurationMillis = 0;
  assert.equal(state.lastAnalysisDurationMillis, 0);
});
