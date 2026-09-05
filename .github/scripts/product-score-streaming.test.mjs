import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js',
  import.meta.url), 'utf8');
// These identities are taken from the real catalogue; no provider is called.
const product = 'IP-1286';
const family = 'IP-2072';
const hint = { nodeCode: product, kind: 'PRODUCT_SUITABILITY', parentCode: family, rawScore: 80 };

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
  const controls = { businessText: input, analyzeBtn: element() };
  const handlers = new Map();
  const state = { taxonomyData: [], currentScores: {}, currentReasons: {} };
  const document = {
    documentElement: { lang: locale },
    getElementById: id => controls[id] || null,
    createElement: () => element(), createTextNode: value => ({ textContent: value }),
    querySelector(selector) { return nodes.get(selector.match(/data-code="([^"]+)"/)?.[1]) || null; },
    querySelectorAll(selector) {
      assert.equal(selector, '.tax-node[data-code]'); return [...nodes.values()];
    }
  };
  const window = { TaxonomyState: state, TaxonomyI18n: { getLocale: () => locale },
    TaxonomyBrowse: {
      renderView() {}, ensureNodeRendered() {}, showStatus() {}, clearStatus() {},
      updateExportGroupVisibility() {}
    }
  };
  vm.runInNewContext(source, {
    window, document, console,
    TaxonomyI18n: { t: key => key }, TaxonomyUtils: { escapeHtml: value => String(value) },
    CSS: { escape: value => value },
    EventSource: class {
      addEventListener(name, listener) { handlers.set(name, listener); }
      close() {}
    }
  });
  window.TaxonomyScoring.runStreamingAnalysis();
  return {
    state, nodes, api: window.TaxonomyScoring,
    send(type, data) { handlers.get(type)({ data: JSON.stringify(data) }); },
    score(scores, details = {}) { this.send('scores', { scores, rawScores: scores, scoreDetails: details }); },
    badge(code = product) { return nodes.get(code).header.querySelector('.tax-pct'); },
    aria(code = product) { return nodes.get(code).getAttribute('aria-label'); }
  };
}

for (const language of ['en', 'de']) {
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
