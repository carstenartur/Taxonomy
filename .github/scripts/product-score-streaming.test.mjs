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
const reportBrowserSource = await readFile(new URL(
  './document-template-report-download.mjs', import.meta.url), 'utf8');
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
  const listeners = new Map();
  const dataKey = key => key.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
  const el = { className, style: {}, dataset: {}, children: [], textContent: '',
    setAttribute(key, value) {
      attributes.set(key, String(value));
      if (key.startsWith('data-')) this.dataset[dataKey(key)] = String(value);
    },
    getAttribute(key) {
      return key.startsWith('data-') ? this.dataset[dataKey(key)] ?? null : attributes.get(key) ?? null;
    },
    appendChild(child) { child.parent = this; this.children.push(child); },
    insertBefore(child, before) {
      child.parent = this;
      const index = this.children.indexOf(before);
      if (index < 0) this.children.push(child); else this.children.splice(index, 0, child);
    },
    replaceChildren(...children) { this.children = children; children.forEach(child => { child.parent = this; }); },
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); },
    addEventListener(name, listener) { (listeners.get(name) || listeners.set(name, []).get(name)).push(listener); },
    emit(name) { (listeners.get(name) || []).forEach(listener => listener.call(this, { target: this })); },
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; },
    querySelectorAll(selector) {
      const classes = selector.split(',').map(value => value.trim()).filter(value => value.startsWith('.'))
        .map(value => value.slice(1));
      if (!classes.length) return [];
      const result = [];
      const visit = node => node.children.forEach(child => {
        if (classes.some(name => child.className.split(' ').includes(name))) result.push(child);
        visit(child);
      });
      visit(this); return result;
    }
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
  const llmLog = element();
  const controls = { businessText: input, analyzeBtn: element(), analysisDurationDisplay: element(),
    llmCommLogContent: llmLog };
  controls.analysisDurationDisplay.hidden = true;
  const focused = element();
  let renders = 0;
  const handlers = new Map();
  let transport;
  const statuses = [];
  const state = { taxonomyData: [], currentScores: {}, currentReasons: {} };
  const copiedDiagnostics = [];
  const document = {
    documentElement: { lang: locale }, activeElement: focused, addEventListener() {},
    getElementById: id => controls[id] || null,
    createElement: () => element(), createTextNode: value => ({ textContent: value }),
    querySelector(selector) { return nodes.get(selector.match(/data-(?:code|taxonomy-container)="([^"]+)"/)?.[1]) || null; },
    querySelectorAll(selector) {
      if (selector === '#taxonomyTree [data-taxonomy-container]') {
        return [...nodes.values()].filter(node => node.getAttribute('data-taxonomy-container'));
      }
      assert.ok(['.tax-node[data-code]', '.tax-node[data-code], [data-taxonomy-container]'].includes(selector));
      return [...nodes.values()];
    }
  };
  const sharedUtils = {
    escapeHtml: value => String(value),
    async copyText(value) { copiedDiagnostics.push(value); }
  };
  const graphSelections = [];
  const window = { TaxonomyState: state, TaxonomyI18n: { getLocale: () => locale }, TaxonomyUtils: sharedUtils,
    TaxonomyGraph: {selectNode: code => graphSelections.push(code)},
    TaxonomyBrowse: {
      renderView() { renders++; }, ensureNodeRendered() {}, showStatus(...args) { statuses.push(args); }, clearStatus() {},
      updateExportGroupVisibility() {}
    }
  };
  const context = vm.createContext({
    window, document, console,
    TaxonomyI18n: { t: (key, ...values) => key === 'browse.container.relevance' ? `Area relevance: ${values[0]}%`
      : key === 'browse.container.area.clarification' ? locale === 'de' ? 'Klärung erforderlich' : 'Clarification needed'
      : key === 'browse.container.area.unassessed' ? locale === 'de' ? 'Bereichsrelevanz nicht bewertet' : 'Area relevance not assessed'
      : key === 'scoring.ui.stats' ? values.join('/') : key }, TaxonomyUtils: sharedUtils,
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
  // Keep the real source-summary renderer at this module boundary, not a stubbed area badge.
  browse.refreshContainerSummaries = window.TaxonomyBrowse.refreshContainerSummaries;
  window.TaxonomyBrowse = browse;
  vm.runInContext(source, context);
  vm.runInContext(viewsSource, context);
  window.TaxonomyScoring.runStreamingAnalysis();
  return {
    state, nodes, statuses, controls, document, focused, copiedDiagnostics, browse, graphSelections,
    renders: () => renders, api: window.TaxonomyScoring, views: window.TaxonomyViews,
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
    startPolling() {
      let receive;
      window.TaxonomyAnalysisSession = { state: () => ({ ready: true, workspaceId: 'workspace-a' }) };
      window.TaxonomyAnalysisProgress = { start(operationId, onSnapshot) {
        receive = onSnapshot;
        return { acceptsResult: () => true };
      } };
      controls.includeArchitectureView = { checked: false };
      context.crypto = { randomUUID: () => 'cb2a3d71-e849-4a50-9855-1f9cb8f81402' };
      context.fetch = url => {
        assert.equal(url, '/api/analyze');
        return new Promise(() => {});
      };
      window.TaxonomyScoring.runAnalysis();
      assert.equal(typeof receive, 'function');
      return receive;
    },
    score(scores, details = {}) { this.send('scores', { scores, rawScores: scores, scoreDetails: details }); },
    badge(code = product) { return nodes.get(code).header.querySelector('.tax-pct'); },
    aria(code = product) { return nodes.get(code).getAttribute('aria-label'); }
  };
}

function summaryHarness() {
  const h = harness();
  const container = element();
  let markup = '';
  Object.defineProperty(container, 'innerHTML', {
    get() { return markup; },
    set(html) {
      markup = html;
      container.replaceChildren();
      const summary = element('summary-view');
      container.appendChild(summary);
      for (const match of html.matchAll(/<button class="summary-layer-element"[^>]*>|<button type="button" class="summary-layer-element"[^>]*>/g)) {
        const button = element('summary-layer-element');
        for (const attribute of match[0].matchAll(/([\w-]+)="([^"]*)"/g)) button.setAttribute(attribute[1], attribute[2]);
        summary.appendChild(button);
      }
    }
  });
  h.controls.taxonomyTree = container;
  let mode = 'all';
  h.browse.isRelevanceFilterActive = () => mode !== 'all';
  const zero = 'IP-1018';
  const unknown = 'IP-1021';
  // An explicitly external legacy element is evidence, not a claimed catalogue identity.
  const external = 'legacy:external-evidence';
  const data = [{ code: 'CP', children: [] }, { code: 'IP', children: [
    { code: family, children: [{ code: product, children: [] }] },
    { code: zero, children: [] }, { code: unknown, children: [] }
  ] }];
  const scores = { CP: 60, [family]: 0, [product]: 80, [zero]: 0 };
  const view = {
    includedElements: ['CP', family, product, zero, unknown, external].map(nodeCode => ({
      nodeCode, title: nodeCode, taxonomySheet: nodeCode === 'CP' ? 'CP' : nodeCode === external ? 'Legacy' : 'IP',
      relevance: (scores[nodeCode] || 0) / 100, anchor: nodeCode !== family,
      hierarchyPath: nodeCode === product ? `IP > ${family} > ${product}` : nodeCode
    })),
    includedRelationships: [
      { sourceCode: 'CP', targetCode: product, relationType: 'REALIZES' },
      { sourceCode: family, targetCode: product, relationType: 'HIERARCHY' },
      { sourceCode: zero, targetCode: 'CP', relationType: 'EXCLUDED_ZERO_RELATION' },
      { sourceCode: unknown, targetCode: 'CP', relationType: 'EXCLUDED_UNKNOWN_RELATION' },
      { sourceCode: external, targetCode: 'CP', relationType: 'EXTERNAL_LINK' }
    ],
    anchors: ['CP', product, zero, unknown, external].map(nodeCode => ({ nodeCode }))
  };
  h.state.currentArchView = view;
  h.state.currentReasons = { [product]: 'The saved explanation' };
  const saved = JSON.stringify(view);
  return { ...h, container, view, saved, external,
    render(filter = 'all', tree = data) {
      mode = filter;
      h.api.renderSummaryView(h.views.filterRelevance(tree, scores, {}, mode), scores);
    },
    buttons() { return container.querySelectorAll('.summary-layer-element'); },
    codes() { return this.buttons().map(button => button.dataset.code).sort(); },
    select(code) { this.buttons().find(button => button.dataset.code === code).emit('click'); },
    details() { return container.querySelector('.summary-view').children.find(child => child.id === 'summarySelectionDetails'); }
  };
}

test('Summary intentional selection updates Graph Explorer without replaying selection on render', () => {
  const h = summaryHarness();
  h.render();
  assert.deepEqual(h.graphSelections, []);
  h.select(product);
  assert.deepEqual(h.graphSelections, [product]);
  h.render('positive');
  assert.deepEqual(h.graphSelections, [product]);
  h.select('CP');
  assert.deepEqual(h.graphSelections, [product, 'CP']);
});

test('Summary positive filter projects saved elements, relationships and anchor counts without losing a surviving selection', () => {
  const h = summaryHarness();
  h.render();
  h.select(product);
  h.render('positive');
  assert.deepEqual(h.codes(), ['CP', family, product].sort(), 'Retain the zero-scored context ancestor of the positive descendant');
  assert.match(h.container.innerHTML, /class="summary-stats">2\/3\/2\/2</);
  assert.match(h.container.innerHTML, /class="arrow-label">REALIZES</);
  assert.doesNotMatch(h.container.innerHTML, /EXCLUDED_ZERO_RELATION|EXCLUDED_UNKNOWN_RELATION|EXTERNAL_LINK/);
  assert.equal(h.buttons().find(button => button.dataset.code === product).getAttribute('aria-pressed'), 'true');
  assert.equal(h.details().hidden, false);
  assert.ok(h.details().children.some(child => child.textContent === 'The saved explanation'));
  assert.equal(h.state.currentArchView, h.view);
  assert.equal(JSON.stringify(h.state.currentArchView), h.saved, 'Filtering must not rewrite saved architecture/export evidence');
});

test('Summary all mode preserves external legacy evidence and a filtered-away selection leaves no stale details', () => {
  const h = summaryHarness();
  h.render();
  assert.equal(h.codes().length, 6);
  assert.ok(h.codes().includes(h.external));
  assert.match(h.container.innerHTML, /class="summary-stats">5\/6\/5\/3</);
  h.select(h.external);
  h.render('positive');
  assert.equal(h.details().hidden, true);
  assert.ok(h.buttons().every(button => button.getAttribute('aria-pressed') === 'false'));
  h.render();
  assert.ok(h.codes().includes(h.external));
  assert.equal(JSON.stringify(h.state.currentArchView), h.saved);
});

test('Summary displays the shared empty-filter state when retained taxonomy nodes have no saved elements', () => {
  const h = summaryHarness();
  h.render('hide-zero', [{ code: 'BR', children: [] }]);
  assert.deepEqual(h.codes(), []);
  assert.match(h.container.innerHTML, /view\.relevance\.empty/);
  assert.equal(JSON.stringify(h.state.currentArchView), h.saved);
});

for (const order of ['family-first', 'product-first']) {
  test(`${order}: shared filters follow resolved and pending product relevance during streaming`, () => {
    const h = harness();
    const data = [{ code: 'IP', children: [{ code: family, children: [{ code: product, children: [] }] }] }];
    const containsProduct = mode => {
      const projected = h.views.filterRelevance(data, h.state.currentScores, h.state.currentScoreDetails, mode);
      return projected.some(root => root.children.some(parent => parent.children.some(child => child.code === product)));
    };
    if (order === 'family-first') h.score({ [family]: 40 });
    h.score({ [product]: 80 }, { [product]: hint });
    if (order === 'product-first') {
      assert.equal(h.state.currentScores[product], 0);
      assert.equal(containsProduct('hide-zero'), true, 'Unresolved suitability is not measured zero relevance');
      assert.equal(containsProduct('positive'), false);
      h.score({ [family]: 40 });
    }
    assert.equal(h.state.currentScores[product], 32);
    assert.equal(containsProduct('positive'), true, 'The streamed family score resolves positive effective relevance');
    h.score({ [family]: 0 });
    assert.equal(h.state.currentScores[product], 0);
    assert.equal(containsProduct('hide-zero'), false, 'Resolved zero effective relevance must be filtered');
    assert.equal(containsProduct('positive'), false);
  });
}

test('streaming reason previews preserve a recorded explanation when a later batch has no reason', () => {
  const h = harness();
  h.send('scores', { scores: { IP: 80 }, reasons: { IP: 'Saved explanation\nFINAL LINE' } });
  for (const reason of [null, '', '   ']) {
    h.send('scores', { scores: { IP: 80 }, reasons: { IP: reason } });
    assert.equal(h.state.currentReasons.IP, 'Saved explanation\nFINAL LINE');
  }
});

for (const terminal of ['complete', 'error']) {
  test(`${terminal}: finalized stream rejects late score and reason callbacks`, () => {
    const h = harness();
    const finalScores = { IP: 60 };
    const terminalPayload = { totalScores: finalScores, partialScores: finalScores, rawScores: finalScores,
      status: terminal === 'complete' ? 'SUCCESS' : 'PARTIAL', reasons: { IP: 'Final saved explanation' },
      errorMessage: 'controlled partial result', analysisDurationMillis: 5000 };
    h.send(terminal, terminalPayload);
    const accepted = JSON.stringify({ scores: h.state.currentScores, rawScores: h.state.currentRawScores,
      reasons: h.state.currentReasons, status: h.state.lastAnalysisStatus,
      duration: h.state.lastAnalysisDurationMillis });
    h.send('scores', { scores: { IP: 5 }, reasons: { IP: 'Late incremental explanation' } });
    assert.equal(JSON.stringify({ scores: h.state.currentScores, rawScores: h.state.currentRawScores,
      reasons: h.state.currentReasons, status: h.state.lastAnalysisStatus,
      duration: h.state.lastAnalysisDurationMillis }), accepted);
    h.send(terminal === 'complete' ? 'error' : 'complete', { ...terminalPayload,
      status: 'ERROR', reasons: { IP: 'Duplicate terminal explanation' } });
    assert.equal(h.state.currentReasons.IP, 'Final saved explanation');
    assert.equal(h.state.lastAnalysisStatus, terminal === 'complete' ? 'SUCCESS' : 'PARTIAL');
  });
}

test('streaming keeps area relevance in the group heading without a catalogue-node score badge', () => {
  const h = harness();
  const group = h.nodes.get('BP');
  group.setAttribute('data-taxonomy-container', 'BP');
  group._taxonomyNode = { code: 'BP', taxonomyContainer: true, children: [] };
  h.state.taxonomyData = [group._taxonomyNode];
  group.header.className = 'tax-container-heading';
  group.querySelector = selector => selector === ':scope > .tax-container-heading' ? group.header : null;
  h.score({BP: 80});
  assert.equal(group.header.querySelector('.tax-container-score')?.textContent, 'Area relevance: 80%');
  assert.equal(group.header.querySelector('.tax-pct'), null);
  assert.equal(h.state.currentScores.BP, 80);
  assert.equal(h.state.currentRawScores.BP, 80);
  h.score({BP: 0});
  assert.equal(group.header.querySelector('.tax-container-score')?.textContent, 'Area relevance: 0%');
  assert.equal(h.state.currentRawScores.BP, 0);
});

test('legacy LLM log renderer exposes copy controls for prompt and response', async () => {
  const h = harness();
  h.api.appendLlmLogEntry('IP', { IP: 80 }, {
    prompt: 'legacy prompt', rawResponse: '{"IP":80}', provider: 'CUSTOM_OPENAI', durationMs: 25
  });
  const buttons = h.controls.llmCommLogContent.querySelectorAll('.llm-log-copy');
  assert.equal(buttons.length, 2, 'legacy prompt and response each need a copy action');
  buttons[0].emit('click'); buttons[1].emit('click');
  await Promise.resolve();
  assert.deepEqual(h.copiedDiagnostics, ['legacy prompt', '{"IP":80}']);
});

for (const locale of ['de', 'en']) {
  test(`${locale}: a streamed root clarification never becomes an error or a reason with a fabricated zero`, () => {
    const h = harness(locale);
    const question = 'Should booking work online or entirely locally without later synchronization?';
    const rootAssessments = { CO: { status: 'UNRESOLVED', score: null, reason: 'The recording channels are unspecified.', question } };
    h.score({ BP: 72 });
    h.send('scores', { scores: {}, rawScores: {}, reasons: { CO: 'The recording channels are unspecified. ' + question },
      rootAssessments,
      prompt: 'Recorded root request', rawResponse: JSON.stringify(rootAssessments),
      error: 'ROOT_UNRESOLVED: The recording channels are unspecified. ' + question });
    const entry = h.controls.llmCommLogContent.children[0];
    const markup = node => [node.innerHTML || '', node.textContent || '', ...(node.children || []).map(markup)].join(' ');
    const text = markup(entry);
    assert.match(text, locale === 'de' ? /Klärung erforderlich/ : /Clarification needed/);
    assert.ok(text.includes(question));
    assert.doesNotMatch(text, /ERROR|0%|0\/100/);
    assert.doesNotMatch(entry.className, /llm-log-error/);
    assert.equal(h.state.currentRawScores.CO, undefined);
    assert.equal(h.state.currentRawScores.BP, 72);
  });
}

test('an explanation without recorded numeric evidence is displayed as unassessed in legacy logs', () => {
  const h = harness();
  h.api.appendLlmLogEntry('CO', {}, { reasons: { CO: 'No accepted assessment is recorded.' } });
  const markup = node => [node.innerHTML || '', node.textContent || '', ...(node.children || []).map(markup)].join(' ');
  const text = markup(h.controls.llmCommLogContent.children[0]);
  assert.match(text, /Area relevance not assessed/);
  assert.doesNotMatch(text, /0%|0\/100/);
});

test('a product explanation without its raw score cannot fabricate suitability from a known parent', () => {
  const h = harness();
  h.score({ [family]: 40 });
  h.api.appendLlmLogEntry(product, {}, { reasons: { [product]: 'No accepted suitability is recorded.' },
    scoreDetails: { [product]: { ...hint, parentScore: 40 } } });
  const markup = node => [node.innerHTML || '', node.textContent || '', ...(node.children || []).map(markup)].join(' ');
  const text = markup(h.controls.llmCommLogContent.children[0]);
  assert.match(text, /not assessed/);
  assert.doesNotMatch(text, /0%|0\/100/);
  assert.equal(h.state.currentRawScores[product], undefined);
});

for (const language of ['en', 'de']) {
  test(`${language}: report browser assertion reads wrapped decision-map score labels`, () => {
    const h = harness(language);
    h.score({ [product]: 80 }, { [product]: hint });
    const assertionStart = reportBrowserSource.indexOf("require([...container.querySelectorAll('.dm-label')]");
    assert.notEqual(assertionStart, -1);
    const assertion = reportBrowserSource.slice(assertionStart,
      reportBrowserSource.indexOf('if (state.currentScores', assertionStart));
    const wrap = vm.runInNewContext('(' + viewsSource.slice(
      viewsSource.indexOf('function decisionLines('),
      viewsSource.indexOf('function decisionMeasure(')).trim() + ')');
    const check = (label, renderedLabel = label, code = product) => {
      const lines = wrap(`${code} Product · ${renderedLabel}`, text => text.length * 8, 226);
      assert.ok(lines.length > 1, 'exercise the wrapped SVG label');
      const node = {
        textContent: lines.join(''),
        querySelectorAll(selector) {
          assert.equal(selector, 'tspan');
          return lines.map(textContent => ({ textContent }));
        }
      };
      vm.runInNewContext(assertion, {
        container: { querySelectorAll: () => [node] },
        product: { code: product }, label, stage: 'wrapped label',
        require: (condition, message) => assert.ok(condition, message)
      });
    };
    for (const familyScore of [undefined, 0, 40]) {
      if (familyScore !== undefined) h.score({ [family]: familyScore });
      const label = h.api.describeScore(product, h.state.currentScores[product]).label;
      check(label);
      assert.throws(() => check(label, 'missing score'), /decision map retains product evidence/);
      assert.throws(() => check(label, label, family), /decision map retains product evidence/);
    }
    h.score({ [product]: 0 });
    check(h.api.describeScore(product, h.state.currentScores[product]).label);
  });

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
  test(`${terminal}: authoritative root clarification coverage survives streaming completion`, () => {
    const h = harness(); h.score({ BP: 72 });
    const coverage = { assessedNodes: 1, unknownNodes: 2, failedOrBlockedNodes: 2, nodes: {
      BP: { state: 'RELEVANT', score: 72, effectiveRelevance: 72, descendants: 'COMPLETE', reason: null },
      CO: { state: 'UNKNOWN', score: null, effectiveRelevance: null, descendants: 'UNASSESSED',
        reason: 'CLARIFICATION: Recording channels are unspecified. Is connectivity required?' },
      'CO-1000': { state: 'UNKNOWN', score: null, effectiveRelevance: null, descendants: 'UNASSESSED', reason: 'BLOCKED_BY:CO' }
    } };
    h.send(terminal, { status: 'PARTIAL', totalScores: { BP: 72 }, partialScores: { BP: 72 }, rawScores: { BP: 72 },
      reasons: { CO: 'Recording channels are unspecified. Is connectivity required?' }, analysisCoverage: coverage,
      warnings: [], errorMessage: 'Other work was interrupted.' });
    assert.ok(h.state.analysisCoverage, 'terminal coverage must be retained for the root renderer');
    assert.deepEqual(JSON.parse(JSON.stringify(h.state.analysisCoverage)), coverage);
    assert.equal(h.state.currentScores.CO, undefined);
    assert.equal(h.state.currentRawScores.BP, 72);
    assert.equal(h.state.lastAnalysisStatus, 'PARTIAL');
    assert.equal(h.renders(), 1, 'terminal metadata must not rebuild the focused tree');
  });
  test(`${terminal}: filtered rows receive root clarification coverage without rebuilding unchanged evidence`, () => {
    for (const view of ['list', 'tabs']) {
      const h = harness();
      h.state.currentView = view;
      h.browse.isRelevanceFilterActive = () => true;
      const group = h.nodes.get('BP');
      group.setAttribute('data-taxonomy-container', 'BP');
      group._taxonomyNode = { code: 'BP', taxonomyContainer: true, children: [] };
      group.header.className = 'tax-container-heading';
      group.querySelector = selector => selector === ':scope > .tax-container-heading' ? group.header
        : selector === ':scope > .tax-container-clarification'
          ? group.children.find(child => child.className === 'tax-container-clarification') || null : null;
      const question = 'Should booking work online or entirely locally?';
      h.send('scores', { scores: {}, reasons: { BP: question } });
      h.browse.refreshContainerSummaries(h.state.currentScores);
      assert.equal(group.header.querySelector('.tax-container-area-state').textContent, 'Area relevance not assessed');
      const renderedBefore = h.renders();
      h.send(terminal, { status: 'PARTIAL', totalScores: {}, partialScores: {}, rawScores: {},
        reasons: { BP: question }, errorMessage: 'Other work was interrupted.',
        analysisCoverage: { assessedNodes: 0, unknownNodes: 1, failedOrBlockedNodes: 1, nodes: {
          BP: { state: 'UNKNOWN', score: null, effectiveRelevance: null, descendants: 'UNASSESSED',
            reason: 'CLARIFICATION: ' + question }
        } } });
      assert.equal(group.header.querySelector('.tax-container-area-state').textContent, 'Clarification needed');
      assert.equal(group.querySelector(':scope > .tax-container-clarification').textContent, question);
      assert.equal(group.getAttribute('aria-describedby'), 'tax-container-clarification-BP');
      assert.equal(h.state.currentScores.BP, undefined);
      assert.equal(h.state.currentRawScores.BP, undefined);
      assert.equal(h.state.currentReasons.BP, question);
      assert.equal(h.renders(), renderedBefore);
      assert.equal(h.document.activeElement, h.focused);
    }
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

test('explicit zero product suitability and normal relevance remain measured visible evidence', () => {
  const h = harness(); h.score({ [family]: 40, [product]: 0, BP: 75 }, { [product]: hint });
  assert.match(h.badge().textContent, /0%.*0\/100/);
  assert.equal(h.badge('BP').textContent, '75%');
  h.score({ BP: 0 });
  assert.equal(h.badge('BP').textContent, '0%');
  assert.match(h.aria('BP'), /Score: 0 percent/);
  assert.equal(h.state.currentRawScores.BP, 0);
  assert.equal(h.state.currentEffectiveScores.BP, 0);
  assert.equal(h.nodes.get('BP').header.style.backgroundColor, '');
});

test('terminal missing relevance removes an old positive badge without fabricating measured zero', () => {
  const h = harness(); h.score({ BP: 75 });
  assert.equal(h.badge('BP').textContent, '75%');
  h.send('complete', { totalScores: {}, rawScores: {}, status: 'SUCCESS' });
  assert.equal(h.badge('BP'), null);
  assert.doesNotMatch(h.aria('BP'), /Score: 0 percent/);
  assert.equal(Object.prototype.hasOwnProperty.call(h.state.currentRawScores, 'BP'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(h.state.currentScores, 'BP'), false);
  assert.equal(h.nodes.get('BP').header.style.backgroundColor, '');
});

test('both Maven-owned entry points execute the streaming regression suite', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
  assert.equal(pkg.scripts['test:product-score-streaming'],
    '../mvnw -B -f ../pom.xml -pl taxonomy-tooling -am test -Dtest=JavascriptContractTest -Dsurefire.failIfNoSpecifiedTests=false -Dtaxonomy.javascript.sources=.github/scripts/product-score-streaming.test.mjs');
  assert.match(pkg.scripts['verify:ui-contracts'], /-Dtest=JavascriptContractTest/);
  assert.equal(pkg.scripts['verify:ui'], '../mvnw -B -f ui-verification-pom.xml verify -Pshard');
  const inventory = await readFile(new URL('../../.mvn/javascript-junit-sources.txt', import.meta.url), 'utf8');
  assert.ok(inventory.includes('product-score-streaming.test.mjs'));
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
    const score = { [family]: 60 };
    h.score(score);
    const renderedBefore = h.renders();
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

for (const terminal of ['complete', 'error', 'mapping-error']) {
  test(`${terminal}: terminal explanations and missed scores refresh the diagram evidence`, () => {
    const h = harness();
    h.state.currentView = 'decision';
    const renderedBefore = h.renders();
    const score = { [family]: 60 };
    h.send(terminal === 'complete' ? 'complete' : 'error', {
      status: terminal === 'complete' ? 'SUCCESS' : 'PARTIAL',
      totalScores: score, partialScores: score, rawScores: score,
      reasons: { [family]: 'Saved terminal explanation\nFINAL LINE' },
      errorMessage: 'controlled test failure', scoreSemanticsUnavailable: terminal === 'mapping-error'
    });
    assert.equal(h.renders(), renderedBefore + 1);
    assert.equal(h.state.currentScores[family], 60);
    assert.equal(h.state.currentReasons[family], 'Saved terminal explanation\nFINAL LINE');
    assert.equal(h.document.activeElement, h.focused);
  });
}
test('duration header distinguishes zero duration from an unknown replacement result', () => {
  const h = harness();
  h.state.lastAnalyzedText = 'A requirement';
  h.send('complete', { totalScores: {}, analysisDurationMillis: 0 });
  assert.match(h.controls.analysisDurationDisplay.textContent, /0 min 00 s/);
  h.api.runStreamingAnalysis();
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

// Execute the actual interactive closure; only expose its entry point in the test realm.
const browseSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js', import.meta.url), 'utf8');
function interactiveHarness(response) {
  const classes = new Set(['tax-has-unevaluated']);
  const children = { style: { display: 'none' }, dataset: { renderState: 'ready' } };
  const wrapper = {
    dataset: { taxonomyContainer: 'IP' }, isConnected: false,
    classList: { add: key => classes.add(key), remove: key => classes.delete(key) },
    querySelector: selector => selector === ':scope > .tax-children' ? children : null,
    setAttribute() {}
  };
  const status = { innerHTML: '', dataset: {} }; const logs = []; const errors = [];
  const state = { currentScores: { IP: 100 }, currentRawScores: { IP: 100 }, currentReasons: {},
    storedBusinessText: 'Read existing evidence', evaluatedNodes: new Set(['IP']) };
  const scoring = {
    applyLocalRawScores(scores) { Object.assign(state.currentRawScores, scores); Object.assign(state.currentScores, scores); },
    appendLlmLogEntry(...args) { logs.push(args); }, syncVisibleScoreNodes() {}
  };
  const window = { TaxonomyState: state, TaxonomyScoring: scoring };
  const context = vm.createContext({
    window,
    document: { documentElement: { lang: 'en' }, addEventListener() {}, getElementById: id => id === 'statusArea' ? status : null },
    console: { log() {}, warn() {}, error(...args) { errors.push(args); } },
    TaxonomyI18n: { t: (key, ...args) => [key, ...args].join(' ') },
    TaxonomyUtils: { escapeHtml: text => String(text) }, CSS: { escape: text => text },
    fetch: async () => ({ ok: true, json: async () => response })
  });
  vm.runInContext(browseSource.replace('renderView: renderView,',
    'evaluateNodeChildren: evaluateNodeChildren, renderView: renderView,'), context);
  return { state, classes, logs, status, errors, async run() {
    window.TaxonomyBrowse.evaluateNodeChildren('IP', wrapper, { textContent: '' });
    await new Promise(resolve => setImmediate(resolve));
  } };
}

test('failed interactive category assessment stays unevaluated and retryable', async () => {
  const h = interactiveHarness({ scores: {}, reasons: {}, error: 'Missing assessment for IP-1069', rawResponse: '{}' });
  await h.run();
  assert.equal(h.state.evaluatedNodes.has('IP'), false);
  assert.equal(h.classes.has('tax-has-unevaluated'), true);
  assert.equal(h.classes.has('tax-evaluating'), false);
  assert.deepEqual(h.state.currentRawScores, { IP: 100 });
  assert.equal(h.state.lastAnalysisStatus, 'PARTIAL');
  assert.match(h.status.innerHTML, /Missing assessment/);
  assert.equal(h.logs.length, 1);
  assert.equal(h.errors.length, 0);
});

test('a partial interactive batch keeps valid results but allows retry of missing children', async () => {
  const h = interactiveHarness({ scores: { 'IP-1065': 80 }, reasons: {}, error: 'Another assessment failed' });
  await h.run();
  assert.equal(h.state.currentRawScores['IP-1065'], 80);
  assert.equal(Object.prototype.hasOwnProperty.call(h.state.currentRawScores, 'IP-1069'), false);
  assert.equal(h.state.evaluatedNodes.has('IP'), false);
  assert.equal(h.classes.has('tax-has-unevaluated'), true);
  assert.equal(h.errors.length, 0);
});

test('a complete interactive zero is evaluated evidence, not an error or retry loop', async () => {
  const h = interactiveHarness({ scores: { 'IP-1065': 0 }, reasons: { 'IP-1065': 'Not required' } });
  await h.run();
  assert.equal(h.state.currentRawScores['IP-1065'], 0);
  assert.equal(h.state.evaluatedNodes.has('IP'), true);
  assert.equal(h.classes.has('tax-has-unevaluated'), false);
  assert.equal(h.errors.length, 0);
});

for (const locale of ['en', 'de']) {
  test(`${locale}: fractions below one percent remain positive in streaming badges and exports`, () => {
    const h = harness(locale);
    h.score({[family]: .5});
    assert.equal(h.state.currentScores[family], .5);
    assert.equal(h.badge(family).textContent, '0.5%');
    h.score({[product]: 25}, {[product]: {...hint, rawScore: 25}});
    assert.equal(h.state.currentRawScores[product], 25);
    assert.equal(h.state.currentScores[product], .125);
    assert.match(h.badge(product).textContent, /25%.*0\.125\/100/);
    const node = catalogue.nodePatches.find(n => n.code === product);
    assert.match(h.views.buildMermaidTreeExport([node], h.state.currentScores), /0\.125\/100/);
  });
}
test('local raw score editing keeps decimals and rejects invalid values atomically', () => {
  const h = harness();
  h.state.taxonomyData = [{code: family, analysisRole: 'PRODUCT_FAMILY', children: [
    {code: product, analysisRole: 'PRODUCT', parentCode: family, children: []}]}];
  h.api.applyLocalRawScores({[family]: 1, [product]: 50}, true);
  assert.equal(h.state.currentScores[product], .5);
  assert.equal(h.state.scoreSemanticsVersion, 2);
  h.api.applyLocalRawScores({[family]: .5, [product]: 25}, true);
  assert.equal(h.state.currentScores[product], .125);
  const before = JSON.stringify(h.state.currentRawScores);
  for (const invalid of [null, false, '', '0.5', -1, 100.1, NaN, Infinity]) {
    assert.throws(() => h.api.applyLocalRawScores({[family]: invalid}, true), /percentage/);
    assert.equal(JSON.stringify(h.state.currentRawScores), before);
  }
});
test('explicit local product previews remain pending until the family score or authoritative result arrives', () => {
  const h = harness();
  const data = [{code: family, analysisRole: 'PRODUCT_FAMILY', children: [
    {code: product, analysisRole: 'PRODUCT', parentCode: family, children: []}]}];
  h.state.taxonomyData = data;
  const containsProduct = mode => h.views.filterRelevance(data, h.state.currentScores,
    h.state.currentScoreDetails, mode).some(parent => parent.children.some(child => child.code === product));
  // A continuation can retain its previous status; the preview boundary is explicit.
  h.state.lastAnalysisStatus = 'SUCCESS';
  h.api.applyLocalRawScores({[product]: 80}, true, true, true);
  assert.equal(h.state.currentRawScores[product], 80);
  assert.equal(h.state.currentProductSuitabilityScores[product], 80);
  assert.equal(h.state.currentScores[product], 0, 'Retain the existing numeric preview placeholder');
  assert.equal(h.state.currentScoreDetails[product].parentScore, null);
  assert.equal(h.state.currentScoreDetails[product].effectiveRelevance, null,
    'A locally derived pending placeholder is not authoritative zero evidence');
  assert.match(h.api.describeScore(product, 0).label, /pending/);
  assert.ok(h.state.currentScoreSemanticsWarnings.some(warning => /pending/.test(warning)));
  assert.equal(containsProduct('hide-zero'), true);
  assert.equal(containsProduct('positive'), false);

  h.api.applyLocalRawScores({[product]: 80, [family]: 40}, true, true, true);
  assert.equal(h.state.currentScores[product], 32);
  assert.equal(h.state.currentScoreDetails[product].effectiveRelevance, 32);
  assert.equal(containsProduct('positive'), true);
  assert.doesNotMatch(h.api.describeScore(product, 32).label, /pending/);

  h.api.applyLocalRawScores({[product]: 80, [family]: 0}, true, true, true);
  assert.equal(h.state.currentScoreDetails[product].effectiveRelevance, 0);
  assert.equal(containsProduct('hide-zero'), false);
  assert.equal(containsProduct('positive'), false);

  h.api.applyLocalRawScores({[product]: 80}, true, true, true);
  assert.equal(containsProduct('hide-zero'), true);
  h.send('complete', {status: 'SUCCESS', totalScores: {[product]: 0}, rawScores: {[product]: 80},
    effectiveScores: {[product]: 0}, productSuitabilityScores: {[product]: 80},
    scoreDetails: {[product]: {...hint, parentScore: null, effectiveRelevance: 0}}});
  assert.equal(h.state.currentScoreDetails[product].effectiveRelevance, 0);
  assert.equal(containsProduct('hide-zero'), false);
  assert.doesNotMatch(h.api.describeScore(product, 0).label, /pending/);
});

test('local product projection without a live flag preserves final and manual-import semantics even with stale status', () => {
  for (const status of [null, 'IN_PROGRESS', 'SUCCESS', 'PARTIAL', 'IMPORTED']) {
    const h = harness();
    h.state.taxonomyData = [{code: family, analysisRole: 'PRODUCT_FAMILY', children: [
      {code: product, analysisRole: 'PRODUCT', parentCode: family, children: []}]}];
    h.state.lastAnalysisStatus = status;
    h.api.applyLocalRawScores({[product]: 80}, true);
    assert.equal(h.state.currentScores[product], 0);
    assert.equal(h.state.currentRawScores[product], 80);
    assert.equal(h.state.currentScoreDetails[product].effectiveRelevance, 0);
    assert.doesNotMatch(h.api.describeScore(product, 0).label, /pending/);
    assert.ok(h.state.currentScoreSemanticsWarnings.some(warning => /effective relevance is 0/.test(warning)));
  }
});

for (const view of ['list', 'tabs']) {
  test(`${view}: polling resolves a pending product badge when its family arrives as measured zero`, () => {
    const h = harness();
    h.state.taxonomyData = [{code: family, analysisRole: 'PRODUCT_FAMILY', children: [
      {code: product, analysisRole: 'PRODUCT', parentCode: family, children: []}]}];
    h.state.currentView = view;
    const receive = h.startPolling();
    receive({rawScores: {[product]: 80}});
    const badge = h.badge();
    assert.match(badge.textContent, /pending/);
    assert.match(h.aria(), /pending/);
    const renders = h.renders();
    receive({rawScores: {[product]: 80, [family]: 0}});
    assert.equal(h.state.currentScores[product], 0);
    assert.equal(h.state.currentRawScores[product], 80);
    assert.equal(h.badge(), badge, 'Update the existing badge without replacing the row');
    assert.match(h.badge().textContent, /80%.*0\/100/);
    assert.doesNotMatch(h.badge().textContent, /pending/);
    assert.doesNotMatch(h.aria(), /pending/);
    assert.equal(h.renders(), renders, 'Unfiltered list and tab updates remain incremental');
  });
}

test('invalid incoming numerical evidence is left open rather than turned into zero', () => {
  const h = harness(); h.score({[family]: .5});
  h.score({[product]: null}, {[product]: hint});
  assert.equal(h.state.currentScores[family], .5);
  assert.equal(Object.prototype.hasOwnProperty.call(h.state.currentRawScores, product), false);
  assert.equal(h.state.lastAnalysisStatus, 'PARTIAL');
  assert.equal(h.statuses.at(-1)[0], 'warning');
});
test('a tiny positive score remains visible without a false zero label', () => {
  const h = harness(); h.score({[family]: 1e-10});
  assert.equal(h.state.currentScores[family], 1e-10);
  assert.ok(h.badge(family));
  assert.notEqual(h.badge(family).textContent, '0%');
  assert.notEqual(h.api.describeScore(family, 1e-10).label, '0%');
});
