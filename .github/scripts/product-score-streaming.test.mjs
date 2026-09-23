import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js',
  import.meta.url), 'utf8');
const viewsSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-views.js',
  import.meta.url), 'utf8');
const durationBrowseSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js',
  import.meta.url), 'utf8');
const catalogue = JSON.parse(await readFile(new URL(
  '../../taxonomy-knowledge/src/main/resources/data/nato-taxonomy.json',
  import.meta.url), 'utf8'));
// These identities are taken from the real catalogue; no provider is called.
const product = 'IP-1286';
const family = 'IP-2072';
const hint = { nodeCode: product, kind: 'PRODUCT_SUITABILITY', parentCode: family, rawScore: 80 };

test('streaming reconciliation does not require Object.hasOwn browser support', () => {
  assert.doesNotMatch(source, /\bObject\.hasOwn\s*\(/);
  assert.match(source, /Object\.prototype\.hasOwnProperty\.call\(S\.currentRawScores, code\)/);
  assert.match(source, /Object\.prototype\.hasOwnProperty\.call\(rawScores, detail\.parentCode\)/);
});

function element(className = '') {
  const attributes = new Map();
  const el = { className, style: {}, children: [], textContent: '',
    setAttribute(key, value) { attributes.set(key, String(value)); },
    getAttribute(key) { return attributes.get(key) ?? null; },
    appendChild(child) { child.parent = this; this.children.push(child); },
    replaceChildren(...children) { this.children = children; },
    remove() { this.parent.children = this.parent.children.filter(child => child !== this); },
    querySelector(selector) { return this.children.find(child =>
      child.className.split(' ').includes(selector.slice(1))) || null; }
  };
  el.classList = {
    add() {}, remove() {}, toggle() {}, contains() { return false; }
  };
  return el;
}

function harness(locale = 'en') {
  const nodes = new Map();
  for (const code of [product, family, 'IP', 'BP']) {
    const node = element('tax-node'); node.setAttribute('data-code', code);
    node.header = element('tax-node-header');
    const name = element('tax-name'); name.textContent = code;
    node.header.appendChild(name);
    node.querySelector = selector => selector === ':scope > .tax-node-header' ? node.header : null;
    nodes.set(code, node);
  }
  const input = element(); input.value = 'A requirement';
  const controls = { businessText: input, analyzeBtn: element(), analysisDurationDisplay: element() };
  controls.analysisDurationDisplay.hidden = true;
  const focused = element();
  let renders = 0;
  const handlers = new Map();
  let transport;
  const statuses = [];
  const state = { taxonomyData: [], currentScores: {}, currentReasons: {} };
  const document = {
    documentElement: { lang: locale }, activeElement: focused, addEventListener() {},
    getElementById: id => controls[id] || null,
    createElement: () => element(), createTextNode: value => ({ textContent: value }),
    querySelector(selector) { return nodes.get(selector.match(/data-code="([^"]+)"/)?.[1]) || null; },
    querySelectorAll(selector) {
      assert.equal(selector, '.tax-node[data-code]'); return [...nodes.values()];
    }
  };
  const window = { TaxonomyState: state, TaxonomyI18n: { getLocale: () => locale },
    TaxonomyBrowse: {
      renderView() { renders++; }, ensureNodeRendered() {}, showStatus(...args) { statuses.push(args); }, clearStatus() {},
      updateExportGroupVisibility() {}
    }
  };
  const context = vm.createContext({
    window, document, console,
    TaxonomyI18n: { t: key => key }, TaxonomyUtils: { escapeHtml: value => String(value) },
    CSS: { escape: value => value },
    EventSource: class {
      constructor() { transport = this; }
      addEventListener(name, listener) { handlers.set(name, listener); }
      close() {}
    }
  });
  const browse = window.TaxonomyBrowse;
  vm.runInContext(durationBrowseSource, context);
  browse.refreshAnalysisDuration = window.TaxonomyBrowse.refreshAnalysisDuration;
  window.TaxonomyBrowse = browse;
  vm.runInContext(source, context);
  vm.runInContext(viewsSource, context);
  window.TaxonomyScoring.runStreamingAnalysis();
  return {
    state, nodes, statuses, controls, document, focused, renders: () => renders, api: window.TaxonomyScoring, views: window.TaxonomyViews,
    send(type, data) {
      const event = { data: JSON.stringify(data) };
      handlers.get(type)(event);
      if (type === 'error' && transport.onerror) transport.onerror(event);
    },
    malformedError() {
      const event = { data: 'not-json' };
      handlers.get('error')(event);
      if (transport.onerror) transport.onerror(event);
    },
    score(scores, details = {}) { this.send('scores', { scores, rawScores: scores, scoreDetails: details }); },
    badge(code = product) { return nodes.get(code).header.querySelector('.tax-pct'); },
    aria(code = product) { return nodes.get(code).getAttribute('aria-label'); }
  };
}

for (const language of ['en', 'de']) {
  test(`${language}: tree exports retain pending and zero-relevance product evidence`, () => {
    const h = harness(language);
    const productNode = catalogue.nodePatches.find(node => node.code === product);
    assert.equal(productNode.analysisRole, 'PRODUCT');
    h.score({ [product]: 80 }, { [product]: hint });
    const exported = () => h.views.buildMermaidTreeExport([productNode], h.state.currentScores);
    assert.match(exported(), language === 'en' ? /80%.*pending/ : /80%.*ausstehend/);
    h.score({ [family]: 0 });
    assert.match(exported(), /80%.*0\/100/);
    h.score({ [product]: 0 });
    assert.match(exported(), /0%.*0\/100/);
    for (const scores of [null, {}, Object.create({ [product]: 0 })]) {
      assert.doesNotMatch(h.views.buildMermaidTreeExport([productNode], scores), /Suitability|Eignung/);
    }
    const ordinaryNode = catalogue.nodePatches.find(node => node.analysisRole === 'PRODUCT_FAMILY');
    assert.doesNotMatch(h.views.buildMermaidTreeExport([ordinaryNode], { [ordinaryNode.code]: 0 }), /0%/);
  });

  test(`${language}: rebuilt views receive the same typed labels as incremental updates`, () => {
    const h = harness(language);
    h.score({ [family]: 40, [product]: 80 }, { [product]: hint });
    const description = h.api.describeScore(product, h.state.currentScores[product]);
    assert.equal(description.label, h.badge().textContent);
    assert.match(description.label, /80%.*32\/100/);
    assert.match(description.label, language === 'en' ? /Suitability/ : /Eignung/);
    assert.ok(h.aria().endsWith(description.ariaLabel));
    assert.equal(description.tooltip, 'scoring.score.tooltip.product');
    assert.equal(h.api.describeScore(family, 40).label, '40%');
  });

  test(`${language}: early product evidence remains visible without claiming effective zero`, () => {
    const h = harness(language); h.score({ [product]: 80 }, { [product]: hint });
    assert.match(h.badge().textContent, /80%/);
    assert.match(h.badge().textContent, language === 'en' ? /pending/ : /ausstehend/);
    assert.doesNotMatch(h.badge().textContent, /0\/100/);
    assert.match(h.aria(), language === 'en' ? /pending/ : /ausstehend/);
    assert.equal(h.nodes.get(product).header.style.backgroundColor, '');
    assert.equal(h.state.currentScores[product], 0);
    assert.equal(h.state.currentProductSuitabilityScores[product], 80);
  });
}

test('later family evidence recomputes the earlier product without another product batch', () => {
  const h = harness(); h.score({ [product]: 80 }, { [product]: hint });
  h.score({ [family]: 40 });
  assert.equal(h.state.currentScores[product], 32);
  assert.equal(h.state.currentEffectiveScores[product], 32);
  assert.equal(h.state.currentRawScores[product], 80);
  assert.match(h.badge().textContent, /80%.*32\/100/);
  assert.doesNotMatch(h.aria(), /pending/);
});

test('explicit family zero is resolved evidence, not missing evidence', () => {
  const h = harness(); h.score({ [product]: 80 }, { [product]: hint });
  h.score({ [family]: 0 });
  assert.equal(h.state.currentScores[product], 0);
  assert.match(h.badge().textContent, /80%.*0\/100/);
  assert.doesNotMatch(h.badge().textContent, /pending/);
});

test('family-first, same-batch and corrected family scores converge', () => {
  for (const mode of ['family-first', 'same-batch']) {
    const h = harness();
    if (mode === 'family-first') h.score({ [family]: 40 });
    h.score(mode === 'same-batch' ? { [product]: 80, [family]: 40 } : { [product]: 80 },
      { [product]: hint });
    assert.equal(h.state.currentScores[product], 32);
    h.score({ [family]: 50 });
    assert.equal(h.state.currentScores[product], 40);
    assert.match(h.badge().textContent, /80%.*40\/100/);
  }
});

test('repeated product values use retained type hints rather than becoming raw relevance', () => {
  const h = harness(); h.score({ [family]: 40, [product]: 80 }, { [product]: hint });
  h.score({ [product]: 50 });
  assert.equal(h.state.currentScores[product], 20);
  assert.equal(h.state.currentProductSuitabilityScores[product], 50);
  assert.match(h.badge().textContent, /50%.*20\/100/);
});

test('null, empty and boolean parent hints do not masquerade as measured zero', () => {
  for (const parentScore of [null, '', false, '40']) {
    const h = harness(); h.score({ [product]: 80 }, { [product]: { ...hint, parentScore } });
    assert.match(h.badge().textContent, /pending/);
    assert.equal(h.state.currentScores[product], 0);
  }
});

test('numeric parent evidence carried in details is usable without accumulated parent', () => {
  const h = harness(); h.score({ [product]: 80 }, { [product]: { ...hint, parentScore: 40 } });
  assert.equal(h.state.currentScores[product], 32);
  assert.match(h.badge().textContent, /80%.*32\/100/);
});

for (const terminal of ['complete', 'error']) {
  test(`${terminal}: authoritative envelope reconciles an already visible pending badge`, () => {
    const h = harness(); h.score({ [product]: 80 }, { [product]: hint });
    h.send(terminal, {
      totalScores: { [family]: 40, [product]: 32 },
      partialScores: { [family]: 40, [product]: 32 },
      rawScores: { [family]: 40, [product]: 80 },
      effectiveScores: { [family]: 40, [product]: 32 },
      scoreDetails: { [product]: { ...hint, parentScore: 40, effectiveRelevance: 32 } },
      productSuitabilityScores: { [product]: 80 }, errorMessage: 'Partial analysis'
    });
    assert.equal(h.state.currentScores[product], 32);
    assert.match(h.badge().textContent, /80%.*32\/100/);
    assert.doesNotMatch(h.aria(), /pending/);
  });
}

test('terminal unresolved-parent zero remains explicit and clears the provisional label', () => {
  const h = harness(); h.score({ [product]: 80 }, { [product]: hint });
  h.send('complete', {
    totalScores: { [product]: 0 }, rawScores: { [product]: 80 },
    effectiveScores: { [product]: 0 },
    scoreDetails: { [product]: { ...hint, parentScore: null, effectiveRelevance: 0 } },
    productSuitabilityScores: { [product]: 80 }, scoreSemanticsWarnings: ['Missing family']
  });
  assert.match(h.badge().textContent, /80%.*0\/100/);
  assert.doesNotMatch(h.badge().textContent, /pending/);
  assert.equal(h.state.currentScoreSemanticsWarnings.length, 1);
});

test('a zero product suitability remains visible and a normal zero removes an old badge', () => {
  const h = harness(); h.score({ [family]: 40, [product]: 0, BP: 75 }, { [product]: hint });
  assert.match(h.badge().textContent, /0%.*0\/100/);
  assert.equal(h.badge('BP').textContent, '75%');
  h.score({ BP: 0 });
  assert.equal(h.badge('BP'), null);
  assert.equal(h.nodes.get('BP').header.style.backgroundColor, '');
});

test('both Maven-owned entry points execute the streaming regression suite', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
  assert.equal(pkg.scripts['test:product-score-streaming'],
    'node --test scripts/product-score-streaming.test.mjs');
  for (const name of ['verify:ui', 'verify:ui-contracts']) {
    assert.ok(pkg.scripts[name].split(' && ').includes('npm run test:product-score-streaming'));
  }
});

for (const locale of ['en', 'de']) {
  test(`${locale}: raw-only terminal fallback retains locally derived product semantics and reasons`, () => {
    const h = harness(locale);
    h.state.taxonomyData = [{ code: 'IP', analysisRole: 'CATEGORY', children: [{ code: family,
      analysisRole: 'PRODUCT_FAMILY', parentCode: 'IP', children: [
        { code: product, analysisRole: 'PRODUCT', parentCode: family, children: [] }
      ] }] }];
    h.score({ [family]: 40, [product]: 80 }, { [product]: hint });
    h.state.currentReasons[product] = 'Earlier product explanation';
    h.send('error', { status: 'PARTIAL', errorMessage: 'CANCELLED; terminal metadata unavailable',
      rawScores: { IP: 100, [family]: 50, [product]: 80 },
      reasons: { [family]: 'Final family explanation' }, scoreSemanticsUnavailable: true });
    assert.equal(h.state.currentRawScores[product], 80);
    assert.equal(h.state.currentScores[product], 40);
    assert.equal(h.state.currentProductSuitabilityScores[product], 80);
    assert.equal(h.state.currentReasons[product], 'Earlier product explanation');
    assert.equal(h.state.currentReasons[family], 'Final family explanation');
    assert.match(h.badge().textContent, /80%.*40\/100/);
    assert.equal(h.state.lastAnalysisStatus, 'PARTIAL');
  });
}
test('application SSE errors are not overwritten by the native transport error callback', () => {
  const h = harness();
  h.send('error', { status: 'PARTIAL', errorMessage: 'Intentional stop', rawScores: {}, effectiveScores: {} });
  assert.equal(h.state.lastAnalysisStatus, 'PARTIAL');
});

test('a cancelled complete event reports a partial outcome rather than UI success', () => {
  const h = harness();
  h.send('complete', { status: 'PARTIAL', totalScores: { BP: 50 }, rawScores: { BP: 50 },
    effectiveScores: { BP: 50 }, warnings: ['CANCELLED'] });
  assert.equal(h.state.lastAnalysisStatus, 'PARTIAL');
  assert.equal(h.statuses.at(-1)[0], 'warning');
});

test('malformed application error payload still terminates as a transport error', () => {
  const h = harness(); h.malformedError();
  assert.equal(h.state.lastAnalysisStatus, 'ERROR');
  assert.equal(h.statuses.at(-1)[0], 'danger');
});


for (const terminal of ['complete', 'error', 'mapping-error']) {
  test(`${terminal}: streaming refreshes duration without rebuilding the diagram or moving focus`, () => {
    const h = harness('de');
    h.state.currentView = 'sunburst';
    const renderedBefore = h.renders();
    const score = { [family]: 60 };
    const event = { status: terminal === 'complete' ? 'SUCCESS' : 'PARTIAL',
      analysisDurationMillis: 62500, totalScores: score, partialScores: score,
      rawScores: score, errorMessage: 'controlled test failure',
      scoreSemanticsUnavailable: terminal === 'mapping-error' };
    h.send(terminal === 'complete' ? 'complete' : 'error', event);
    assert.equal(h.controls.analysisDurationDisplay.hidden, false);
    assert.match(h.controls.analysisDurationDisplay.textContent, /1 min 02 s/);
    assert.equal(h.state.lastAnalysisDurationMillis, 62500);
    assert.equal(h.renders(), renderedBefore, 'Do not repair metadata by re-rendering the graph');
    assert.equal(h.document.activeElement, h.focused);
    assert.equal(h.state.currentView, 'sunburst');
  });
}
test('duration header distinguishes zero duration from an unknown replacement result', () => {
  const h = harness();
  h.state.lastAnalyzedText = 'A requirement';
  h.send('complete', { totalScores: {}, analysisDurationMillis: 0 });
  assert.match(h.controls.analysisDurationDisplay.textContent, /0 min 00 s/);
  h.send('complete', { totalScores: {} });
  assert.match(h.controls.analysisDurationDisplay.textContent, /analysis.duration.unknown/);
  assert.doesNotMatch(h.controls.analysisDurationDisplay.textContent, /0 min 00 s/);
});

for (const rawScores of [{}, null, { [family]: 50 }]) {
  test(`raw-only fallback retains collected evidence for ${JSON.stringify(rawScores)}`, () => {
    const h = harness();
    h.state.taxonomyData = [{ code: family, analysisRole: 'PRODUCT_FAMILY', children: [
      { code: product, analysisRole: 'PRODUCT', parentCode: family, children: [] }
    ] }];
    h.score({ [family]: 40, [product]: 80 }, { [product]: hint });
    h.state.currentReasons[product] = 'Retained product reason';
    h.send('error', { status: 'ERROR', rawScores, scoreSemanticsUnavailable: true,
      errorMessage: 'Projection unavailable' });
    assert.equal(h.state.currentRawScores[product], 80);
    assert.equal(h.state.currentProductSuitabilityScores[product], 80);
    assert.equal(h.state.currentScores[product], rawScores?.[family] === 50 ? 40 : 32);
    assert.equal(h.state.currentReasons[product], 'Retained product reason');
    assert.equal(h.state.lastAnalysisStatus, 'ERROR');
  });
}
