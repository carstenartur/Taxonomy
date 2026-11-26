const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

const [contract, templatePath, ...scriptPaths] = process.argv.slice(2);
const template = readFileSync(templatePath, 'utf8');
const paths = ['api/portfolio-api.js', 'portfolio/taxonomy-portfolio-async.js', 'portfolio/taxonomy-portfolio.js', 'portfolio/inventory-comparison.js'];
const scripts = scriptPaths.map(path => readFileSync(path, 'utf8'));
const contracts = new Map();
const flush = () => new Promise(resolve => setImmediate(resolve));
const voidTags = new Set(['br', 'hr', 'img', 'input', 'link', 'meta']);

// A small DOM adapter for the template and dynamic markup used by these handlers. The
// Maven Node test runner has no browser dependency. Bootstrap and HTTP are boundary doubles.
class Element {
  constructor(tag, owner = null) {
    this.tagName = tag.toUpperCase();
    this.owner = owner;
    this.children = [];
    this.attributes = new Map();
    this.dataset = {};
    this.listeners = new Map();
    this.parentElement = null;
    this.text = '';
    this.classList = {
      contains: name => this.className.split(/\s+/).includes(name),
      add: name => this.classList.toggle(name, true),
      remove: name => this.classList.toggle(name, false),
      toggle: (name, on = !this.classList.contains(name)) => {
        const classes = new Set(this.className.split(/\s+/).filter(Boolean));
        if (on) classes.add(name);
        else classes.delete(name);
        this.className = [...classes].join(' ');
      }
    };
  }
  get id() { return this.getAttribute('id') || ''; }
  set id(value) { this.setAttribute('id', value); }
  get type() { return this.getAttribute('type') || ''; }
  set type(value) { this.setAttribute('type', value); }
  get lang() { return this.getAttribute('lang') || ''; }
  set lang(value) { this.setAttribute('lang', value); }
  get className() { return this.getAttribute('class') || ''; }
  set className(value) { this.setAttribute('class', value); }
  get disabled() { return this.attributes.has('disabled'); }
  set disabled(value) { if (value) this.setAttribute('disabled', ''); else this.removeAttribute('disabled'); }
  get value() { return this.inputValue ?? this.getAttribute('value') ?? ''; }
  set value(value) { this.inputValue = String(value); }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this.replaceChildren(); this.text = String(value); }
  get isConnected() { return this === this.owner || this.parentElement?.isConnected === true; }
  set innerHTML(html) { this.replaceChildren(); this.owner.parse(html, this); }
  getAttribute(name) {
    const dataKey=name.startsWith('data-')?name.slice(5).replace(/-([a-z])/g,(_,letter)=>letter.toUpperCase()):null;
    return dataKey && this.dataset[dataKey] !== undefined ? String(this.dataset[dataKey]) : this.attributes.get(name) ?? null;
  }
  setAttribute(name, value) {
    this.attributes.set(name, String(value));
    if (name.startsWith('data-')) {
      const key = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      this.dataset[key] = String(value);
    }
  }
  removeAttribute(name) { this.attributes.delete(name); }
  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    if (this.isConnected) this.owner.changed();
    return child;
  }
  append(...children) { children.forEach(child => this.appendChild(child)); }
  insertAdjacentElement(position, child) {
    assert.equal(position, 'afterend');
    this.parentElement.appendChild(child);
  }
  replaceChildren(...children) {
    for (const child of this.children) child.parentElement = null;
    this.text = '';
    this.children = [];
    this.append(...children);
    if (this.isConnected) this.owner.changed();
  }
  focus() { this.owner.activeElement = this; }
  reset() { this.querySelectorAll('input, select, textarea').forEach(field => { delete field.inputValue; }); }
  scrollIntoView() {}
  matches(selector) {
    if (selector.includes(',')) return selector.split(',').some(part => this.matches(part));
    const parts = selector.trim().split(/\s+(?![^\[]*\])/);
    const match = parts.pop().match(/^([\w-]+)?((?:[.#][\w-]+)*)(?:\[([\w-]+)(?:="([^"]*)")?\])?$/);
    if (!match) return false;
    const [, tag, qualifiers, attribute, value] = match;
    if (tag && this.tagName !== tag.toUpperCase()) return false;
    for (const [, prefix, name] of qualifiers.matchAll(/([.#])([\w-]+)/g)) {
      if (prefix === '.' ? !this.classList.contains(name) : this.id !== name) return false;
    }
    if (attribute && (this.getAttribute(attribute) === null || value !== undefined && this.getAttribute(attribute) !== value)) return false;
    return !parts.length || Boolean(this.parentElement?.closest(parts.join(' ')));
  }
  closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector) ?? null; }
  querySelectorAll(selectors) {
    return this.children.flatMap(child => [...(child.matches(selectors) ? [child] : []), ...child.querySelectorAll(selectors)]);
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  addEventListener(type, handler, capture = false) {
    this.listeners.set(type, [...this.listeners.get(type) || [], {handler, capture}]);
  }
  removeEventListener(type, handler) {
    this.listeners.set(type, (this.listeners.get(type) || []).filter(item => item.handler !== handler));
  }
  dispatchEvent(event) {
    event.target = this;
    const lineage = [];
    for (let target = this; target; target = target.parentElement) lineage.push(target);
    const pending = [];
    const deliver = (target, capture) => {
      event.currentTarget = target;
      for (const listener of target.listeners.get(event.type) || []) {
        if (event.stopped) break;
        if (listener.capture === capture) pending.push(listener.handler(event));
      }
    };
    for (const target of lineage.toReversed()) deliver(target, true);
    for (const target of lineage) deliver(target, false);
    return Promise.all(pending);
  }
  async click() {
    const click = new Event('click');
    await this.dispatchEvent(click);
    if (this.type === 'submit' && !this.disabled && !click.defaultPrevented) {
      return this.closest('form').dispatchEvent(new Event('submit', {submitter: this}));
    }
    return click;
  }
}

class Event {
  constructor(type, options = {}) { Object.assign(this, options, {type}); }
  preventDefault() { this.defaultPrevented = true; }
  stopImmediatePropagation() { this.stopped = true; }
}

class Document extends Element {
  constructor() {
    super('document');
    this.owner = this;
    this.parse(template, this);
    this.documentElement = this.querySelector('html');
    this.body = this.querySelector('body');
  }
  getElementById(id) { return this.querySelector('#' + id); }
  createElement(tag) { return new Element(tag, this); }
  createTreeWalker() { return {nextNode: () => false}; }
  changed() {
    if (!this.observer || this.changeScheduled) return;
    this.changeScheduled = true;
    queueMicrotask(() => { this.changeScheduled = false; this.observer(); });
  }
  parse(html, root) {
    const stack = [root];
    for (const token of html.matchAll(/<!--[\s\S]*?-->|<![^>]*>|<\/?[\w:-]+(?:[^>"']|"[^"]*"|'[^']*')*>|[^<]+/g)) {
      const markup = token[0];
      if (markup.startsWith('<!')) continue;
      const tag = markup.match(/^<(\/?)([\w:-]+)/);
      if (!tag) { stack.at(-1).text += markup; continue; }
      if (tag[1]) { stack.pop(); continue; }
      const child = this.createElement(tag[2]);
      for (const attribute of markup.slice(tag[0].length, -1).matchAll(/([^\s=/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'))?/g)) {
        child.setAttribute(attribute[1], attribute[2] ?? attribute[3] ?? '');
      }
      stack.at(-1).appendChild(child);
      if (!voidTags.has(tag[2]) && !markup.endsWith('/>')) stack.push(child);
    }
  }
}

const project = {id: 7, projectKey: 'P-7', title: 'Project seven', status: 'PLANNING', requirementCount: 1, solutionCount: 1};
const requirement = {id: 9, requirementKey: 'R-9', title: 'Retain records', reviewStatus: 'PROPOSED', currentVersion: {id: 101, versionNumber: 1, text: 'Keep archived records.'}};
const product = {id: 19, productKey: 'PROD-19', manufacturer: 'Example', productName: 'Records', productStatus: 'ACTIVE', operatingModel: 'ON_PREMISES', sourceReference: 'Catalogue sheet', taxonomyCoverage: []};
const solution = {id: 12, solutionKey: 'SOL-12', title: 'Record retention', taxonomyCoverage: [{id: 30, nodeCode: 'CP-ORIGINAL', coveragePercent: 45, reviewStatus: 'PROPOSED'}]};
const projectSolution = {id: 11, projectId: 7, solution, status: 'PROPOSED', actionStatus: 'UNDECIDED', requirements: [], productCandidates: []};
const productCandidate = {id: 31, projectSolutionId: 11, product, coveragePercent: 65, confidence: 0,
  reviewStatus: 'PROPOSED', selectionStatus: 'CANDIDATE', openEvidence: 'Pending supplier confirmation'};
const otherProject = {id: 8, projectKey: 'P-8', title: 'Project eight', status: 'PLANNING', requirementCount: 0, solutionCount: 0};
const summary = {id: 'snapshot-a', projectId: 7, requirementId: 9, requirementKey: 'R-9', requirementVersionId: 101, requirementVersionNumber: 1, status: 'SUCCESS', provider: 'fixture', createdAt: '2026-11-26T00:00:00Z', durationMs: 400, warningCount: 3, commitSha: 'a'.repeat(40)};
const mapping = {id: 23, snapshotId: 'snapshot-a', nodeCode: 'CP-1', nodeTitle: 'Retention', taxonomyRoot: 'CP', directScore: 80, relevance: 0.8, confidence: 0.9, mappingOrigin: 'DIRECT_SCORE', selectedForImpact: true, reviewStatus: 'PROPOSED', actionStatus: 'CHANGE', actionEvidence: null};
const olderSummary = {...summary, id: 'snapshot-b', createdAt: '2026-11-25T00:00:00Z'};
const olderMapping = {...mapping, id: 24, snapshotId: 'snapshot-b'};
const snapshotDiff = {olderSnapshotId: 'snapshot-b', newerSnapshotId: 'snapshot-a',
  scoreChanges: {'CP-1': {oldScore: 50, newScore: 80}, 'CP-2': {oldScore: 60, newScore: 70}},
  addedElements: ['CP-3'], removedElements: ['CP-4', 'CP-5'], addedRelations: [], removedRelations: [],
  taxonomyFingerprintChanged: true, promptFingerprintChanged: false, providerChanged: true};
const kinds = {
  solution: {form: '.add-solution-coverage', id: 'solutionId', route: '/api/solutions/12/taxonomy-coverage'},
  product: {form: '.add-product-coverage', id: 'productId', route: '/api/products/19/taxonomy-coverage'}
};
const comparison = {id:'retained-comparison',createdAt:'2026-11-26T12:00:00Z',fingerprint:'retained-fingerprint',sources:[],
  demand:{snapshotId:'snapshot-a',requirementVersionId:101,taxonomyFingerprint:'original-taxonomy',selectedNodes:['CP-1'],requirementText:'Original source text',
    question:{mode:'START_WINDOW',start:'2026-11-01',endExclusive:'2026-12-01',organization:'Original organization',environment:'production'}},
  candidates:[{id:'original-product:19',name:'Product record',kind:'TECHNOLOGY_PRODUCT',metadata:{citation:'Keep the original'},assertions:[]}],
  findings:[
    {candidateId:'original-product:19',criterion:'AVAILABILITY',outcome:'UNKNOWN',limitations:['NO_ADMISSIBLE_ASSERTION'],evidenceIds:[],
      nextClarification:'Obtain explicit dated evidence for this criterion, organization and environment'},
    {candidateId:'original-product:19',criterion:'CAPABILITY',outcome:'SUPPORTED',limitations:[],evidenceIds:['original-evidence'],
      nextClarification:'Review the cited assertion and its planned/actual status'},
    {candidateId:'original-product:19',criterion:'RELATIONSHIPS',outcome:'UNEXAMINED',limitations:['NOT_EXAMINED'],evidenceIds:[],
      nextClarification:'Examine the required relationship evidence'},
    {candidateId:'original-product:19',criterion:'RELATIONSHIPS',outcome:'UNKNOWN',limitations:['DIRECTED_REQUIREMENT_REQUIRED'],evidenceIds:[],
      nextClarification:'Specify exact directed relationship requirements'},
    {candidateId:'original-product:19',criterion:'ORIGINAL_CRITERION',outcome:'ORIGINAL_OUTCOME',limitations:['ORIGINAL_LIMIT','constructor'],evidenceIds:[],
      nextClarification:'An original reviewer sentence.'}
  ]};

async function boot({readOnly = false, empty = false, holdMutation, products = [product], account,
  compare = async () => snapshotDiff, holdOtherProject, locale = 'en', inventoryComparisons = []} = {}) {
  const document = new Document();
  document.documentElement.lang = locale;
  const writes = [], unexpected = [], dialogs = [], downloads = [];
  const candidate = {...productCandidate};
  const portfolio = {project, metrics: {totalRequirements: empty ? 0 : 1}, requirements: empty ? [] : [requirement],
    solutions: [{...projectSolution, productCandidates: [candidate]}], conflicts: [], taxonomyNodes: []};
  const snapshot = {...mapping};
  const reads = new Map([
    ['/api/projects', [project, otherProject]],
    ['/api/projects/7/portfolio', portfolio], ['/api/products', products],
    ['/api/projects/7/requirements/9/snapshots', [summary, olderSummary]],
    ['/api/projects/7/snapshots/snapshot-a', {summary, analysis: {warnings: []}, elementMappings: [snapshot], relationMappings: []}],
    ['/api/projects/7/snapshots/snapshot-b', {summary: olderSummary, analysis: {warnings: []}, elementMappings: [olderMapping], relationMappings: []}]
  ]);
  reads.set('/api/projects/7/snapshots/snapshot-a/inventory-comparisons', inventoryComparisons);
  reads.set('/api/projects/7/snapshots/snapshot-b/inventory-comparisons', []);
  reads.set('/api/inventory-comparisons/retained-comparison', comparison);
  reads.set('/api/inventory-comparisons/retained-comparison/reviews', []);
  const stored = new Map();
  const window = {
    location: new URL('https://taxonomy.example/projects'),
    localStorage: {getItem: key => stored.get(key) ?? null, setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key)},
    async fetch(path, init) {
      if (init.method === 'GET' && path === '/api/account/me') {
        return Response.json(account ? await account() : {architectureMutationAllowed: !readOnly});
      }
      if (init.method === 'GET' && path === '/api/projects/7/snapshots/diff?older=snapshot-b&newer=snapshot-a') {
        return Response.json(await compare());
      }
      if (init.method === 'GET' && path === '/api/projects/8/portfolio') {
        await holdOtherProject;
        return Response.json({...portfolio, project: otherProject, requirements: [], solutions: []});
      }
      if (init.method === 'GET' && reads.has(path)) return Response.json(reads.get(path));
      const body = init.body ? JSON.parse(init.body) : null;
      if (init.method !== 'GET') writes.push({method: init.method, path, body});
      if (path === '/api/projects/7/analysis-mappings/elements/23' && init.method === 'PATCH') {
        assert.ok(['CONFIRMED', 'REJECTED'].includes(body.reviewStatus));
        assert.ok(Object.keys(body).every(key => ['reviewStatus', 'actionEvidence', 'comment'].includes(key)));
        await holdMutation;
        Object.assign(snapshot, body);
        return Response.json(snapshot);
      }
      if (Object.values(kinds).some(kind => kind.route === path) && init.method === 'POST') {
        assert.deepEqual(Object.keys(body).sort(), ['coveragePercent', 'evidence', 'nodeCode', 'reviewStatus']);
        assert.equal(typeof body.nodeCode, 'string');
        assert.ok(Number.isInteger(body.coveragePercent) && body.coveragePercent >= 0 && body.coveragePercent <= 100);
        await holdMutation;
        return Response.json(path.startsWith('/api/solutions/') ? solution : product);
      }
      if (path === '/api/projects/7/solutions/11/products' && init.method === 'POST') {
        assert.deepEqual(Object.keys(body).sort(), ['confidence', 'coveragePercent', 'productId', 'reviewStatus', 'selectionStatus']);
        assert.equal(body.productId, 19);
        assert.ok(Number.isInteger(body.coveragePercent) && body.coveragePercent >= 0 && body.coveragePercent <= 100);
        assert.equal(body.confidence, 0);
        await holdMutation;
        return Response.json({id: 31, projectSolutionId: 11, product, ...body});
      }
      if (path === '/api/projects/7/product-candidates/31' && init.method === 'PATCH') {
        assert.deepEqual(Object.keys(body).sort(), ['reviewStatus', 'selectionStatus']);
        await holdMutation;
        Object.assign(candidate, body);
        return Response.json(candidate);
      }
      if (path === '/api/projects/7/analyses' && init.method === 'POST') return Response.json({successfulItems: 1});
      if (path === '/api/projects/7/solutions/propose-from-taxonomy' && init.method === 'POST') return Response.json([]);
      if (path === '/api/projects/7/solutions/11' && init.method === 'PATCH') {
        assert.deepEqual(body, {actionStatus: 'CREATE'});
        return Response.json({...projectSolution, actionStatus: 'CREATE'});
      }
      if (path === '/api/products' && init.method === 'POST') return Response.json({...product, id: 20, productName: 'New product'});
      unexpected.push(`${init.method} ${path}`);
      throw new Error(`Unexpected request ${init.method} ${path}`);
    },
    addEventListener() {}, removeEventListener() {}, setTimeout() {}, clearTimeout() {},
    TaxonomyI18n: {ready: async () => {}, t: key => key, formatEnum: value => String(value ?? '')},
    TaxonomyUtils: {hideModal() {}},
    TaxonomyApiClient: {
      captureContext: () => () => true,
      requestJson: async (path, init) => (await window.fetch(path, init)).json()
    },
    bootstrap: {
      Tab: {getOrCreateInstance: () => ({show() {}})},
      Modal: {getOrCreateInstance: target => ({
        show() { dialogs.push(target); },
        hide() { target.dispatchEvent(new Event('hidden.bs.modal')); }
      })}
    }
  };
  class DownloadURL extends URL { static createObjectURL(blob) { downloads.push(blob); return 'blob:retained'; } static revokeObjectURL() {} }
  const context = vm.createContext({window, document, bootstrap: window.bootstrap, console, URL:DownloadURL, URLSearchParams, Request, Response, Headers, Blob,
    crypto: require('node:crypto').webcrypto,
    FormData: class { constructor(form) { this.form = form; } forEach(consumer) { this.form.querySelectorAll('[name]').forEach(field => consumer(field.value, field.getAttribute('name'))); } },
    CustomEvent: Event, NodeFilter: {SHOW_TEXT: 4}, MutationObserver: class {constructor(callback) { document.observer = callback; } observe() {} }});
  paths.forEach((path, index) => vm.runInContext(scripts[index], context, {filename: path}));
  await document.dispatchEvent(new Event('DOMContentLoaded'));
  await flush();
  return {document, writes, unexpected, dialogs, downloads, sharedStorage: window.localStorage,
    async selectSnapshot() {
      await document.querySelector('.requirement-snapshots').click();
      await flush();
    }
  };
}

for (const [status, evidence] of [['CONFIRMED', 'Checked the retention requirement.'], ['REJECTED', '']]) {
  contracts.set('review-' + status.toLowerCase(), async () => {
    const {document, writes, unexpected, dialogs, selectSnapshot} = await boot();
    await selectSnapshot();
    const button = document.querySelector(`.mapping-review[data-status="${status}"]`);
    assert.ok(button, 'The actual saved-snapshot response must render a mapping review button');
    await button.click();
    await button.click();
    assert.equal(dialogs.length, 1);
    assert.deepEqual(writes, []);
    const form = document.getElementById('portfolioDecisionForm');
    if (status === 'CONFIRMED') {
      await form.dispatchEvent(new Event('submit'));
      assert.deepEqual(writes, [], 'Confirmation requires evidence entered by the reviewer');
    }
    document.getElementById('portfolioDecisionEvidence').value = evidence;
    document.getElementById('portfolioDecisionComment').value = 'A human review comment';
    await form.dispatchEvent(new Event('submit'));
    await form.dispatchEvent(new Event('submit'));
    await flush();
    assert.deepEqual(unexpected, []);
    assert.deepEqual(writes, [{method: 'PATCH', path: '/api/projects/7/analysis-mappings/elements/23', body: {
      reviewStatus: status, actionEvidence: evidence || null, comment: 'A human review comment'
    }}]);
    assert.match(document.getElementById('portfolioInfo').textContent, /saved/i);
    assert.match(document.getElementById('snapshotDetail').querySelector('tbody .badge').textContent, new RegExp(status));
  });
}

contracts.set('obsolete-mapping-decision', async () => {
  const {document, writes, dialogs, selectSnapshot} = await boot();
  await selectSnapshot();
  assert.ok(document.querySelector('.mapping-review'), 'A saved snapshot must expose its mapping review');
  await document.querySelector('.mapping-review[data-status="REJECTED"]').click();
  assert.equal(dialogs.length, 1);
  await document.querySelectorAll('.snapshot-select')[1].click();
  assert.equal(document.querySelector('.mapping-review').dataset.mappingId, '24');
  await document.getElementById('portfolioDecisionForm').dispatchEvent(new Event('submit'));
  await flush();
  assert.deepEqual(writes, []);
});

contracts.set('other-tab-project-selection', async () => {
  const {document, writes, dialogs, sharedStorage, selectSnapshot} = await boot();
  await selectSnapshot();
  sharedStorage.setItem('taxonomy.portfolio.projectId', '8');
  const filter = document.getElementById('projectFilter');
  filter.value = 'Only another project';
  await filter.dispatchEvent(new Event('input'));
  assert.equal(document.querySelector('.project-select.active'), null);
  const button = document.querySelector('.mapping-review[data-status="REJECTED"]');
  await button.click();
  assert.equal(dialogs.length, 1, 'The displayed project still owns its mapping action');
  sharedStorage.setItem('taxonomy.portfolio.projectId', '10');
  await document.getElementById('portfolioDecisionForm').dispatchEvent(new Event('submit'));
  await flush();
  assert.deepEqual(writes, [{method: 'PATCH', path: '/api/projects/7/analysis-mappings/elements/23', body: {
    reviewStatus: 'REJECTED', actionEvidence: null, comment: null
  }}]);
  assert.match(document.getElementById('snapshotDetail').querySelector('tbody .badge').textContent, /REJECTED/);
});

contracts.set('verbatim-human-review', async () => {
  const {document, writes, selectSnapshot} = await boot();
  await selectSnapshot();
  await document.querySelector('.mapping-review[data-status="CONFIRMED"]').click();
  document.getElementById('portfolioDecisionEvidence').value = 'Reviewed in the project portfolio workspace';
  document.getElementById('portfolioDecisionComment').value = 'Human review completed';
  await document.getElementById('portfolioDecisionForm').dispatchEvent(new Event('submit'));
  await flush();
  assert.deepEqual(writes, [{method: 'PATCH', path: '/api/projects/7/analysis-mappings/elements/23', body: {
    reviewStatus: 'CONFIRMED', actionEvidence: 'Reviewed in the project portfolio workspace', comment: 'Human review completed'
  }}]);
});

contracts.set('compare-snapshots', async () => {
  const {document, unexpected, selectSnapshot} = await boot();
  await selectSnapshot();
  await document.querySelector('.snapshot-compare').click();
  const diff = document.getElementById('snapshotDiff');
  assert.deepEqual(diff.querySelectorAll('strong').map(value => value.textContent), ['1', '2', '2']);
  assert.match(diff.textContent, /Taxonomy changed: Yes/);
  assert.match(diff.textContent, /Prompts changed: No/);
  assert.match(diff.textContent, /Provider changed: Yes/);
  assert.deepEqual(unexpected, []);
});

contracts.set('compare-snapshots-de', async () => {
  const {document, selectSnapshot} = await boot({locale: 'de'});
  await selectSnapshot();
  await document.querySelector('.snapshot-compare').click();
  const text = document.getElementById('snapshotDiff').textContent;
  for (const label of ['Snapshot-Vergleich', 'Hinzugefügte Elemente', 'Entfernte Elemente', 'Score-Änderungen',
    'Taxonomie geändert: Ja', 'Prompts geändert: Nein', 'Anbieter geändert: Ja']) assert.ok(text.includes(label), label);
});

contracts.set('compare-oldest-snapshot', async () => {
  let comparisons = 0;
  const {document, selectSnapshot} = await boot({compare: async () => { comparisons++; return snapshotDiff; }});
  await selectSnapshot();
  await document.querySelectorAll('.snapshot-select')[1].click();
  await document.querySelector('.snapshot-compare').click();
  assert.match(document.getElementById('snapshotDiff').textContent, /no earlier snapshot/i);
  assert.equal(comparisons, 0);
});

contracts.set('propose-solutions', async () => {
  const {document, writes, unexpected} = await boot();
  await document.getElementById('proposeSolutionsBtn').click();
  assert.deepEqual(writes, [{method: 'POST', path: '/api/projects/7/solutions/propose-from-taxonomy', body: {}}]);
  assert.deepEqual(unexpected, []);
  assert.match(document.getElementById('portfolioInfo').textContent, /0/);
});

contracts.set('save-solution-action', async () => {
  const {document, writes, unexpected} = await boot();
  document.querySelector('.solution-action-select').value = 'CREATE';
  await document.querySelector('.save-solution-action').click();
  assert.deepEqual(writes, [{method: 'PATCH', path: '/api/projects/7/solutions/11', body: {actionStatus: 'CREATE'}}]);
  assert.deepEqual(unexpected, []);
  assert.match(document.getElementById('portfolioInfo').textContent, /saved/i);
});

for (const initiallyEmpty of [false, true]) contracts.set(initiallyEmpty ? 'first-product-enables-candidates' : 'product-created-preserves-draft', async () => {
  const {document, writes, unexpected} = await boot({products: initiallyEmpty ? [] : [product]});
  const form = document.querySelector('.add-product-candidate');
  const productField = form.querySelector('.candidate-product-id');
  const coverage = form.querySelector('.candidate-coverage-percent');
  const submit = form.querySelector('button[type="submit"]');
  productField.value = initiallyEmpty ? '' : '19';
  coverage.value = '42';
  if (initiallyEmpty) {
    assert.equal(submit.disabled, true);
    assert.match(submit.getAttribute('title'), /create a sourced product/i);
  }
  await document.getElementById('productForm').dispatchEvent(new Event('submit'));
  await flush();
  assert.deepEqual(unexpected, []);
  assert.equal(writes.length, 1);
  assert.equal(document.querySelector('.add-product-candidate'), form, 'Product creation must not replace an unsaved solution form');
  assert.equal(productField.value, initiallyEmpty ? '' : '19');
  assert.equal(coverage.value, '42');
  assert.ok(productField.querySelector('option[value="20"]'), 'New products are immediately available');
  assert.equal(submit.disabled, false);
  assert.equal(submit.getAttribute('title'), null);
});

contracts.set('labeled-coverage-fields', async () => {
  const {document} = await boot();
  for (const selector of ['.candidate-product-id', '.candidate-coverage-percent', '.solution-node-code',
    '.solution-coverage-percent', '.product-node-code', '.product-coverage-percent', '.solution-action-select']) {
    const field = document.querySelector(selector);
    assert.ok(field.closest('label') || field.id && document.querySelector('label[for="' + field.id + '"]'), selector);
  }
});

contracts.set('inventory-setup', async () => {
  const {document, selectSnapshot, writes} = await boot();
  await selectSnapshot();
  const panel=document.querySelector('[data-inventory-comparison]');
  const options=panel.querySelector('[data-inventory-options]');
  assert.ok(options);assert.equal(Boolean(options.open),false);
  const nodes=panel.querySelector('[data-inventory-nodes]');assert.ok(nodes);
  assert.ok(nodes.querySelectorAll('input').every(input=>!input.checked));
  const reopen=panel.querySelector('[data-inventory-reopen]');
  assert.equal(reopen.disabled,true);
  assert.match(reopen.getAttribute('title'),/select a retained comparison/i);
  await reopen.click();assert.deepEqual(writes,[]);
});

contracts.set('snapshot-summary-dto',async()=>{
  const {document,selectSnapshot,unexpected}=await boot();await selectSnapshot();
  assert.match(document.getElementById('snapshotList').textContent,/Warnings: 3/);
  assert.ok(document.getElementById('snapshotDetail').textContent.includes(mapping.mappingOrigin));
  assert.ok(document.getElementById('snapshotDetail').textContent.includes(summary.commitSha));
  assert.deepEqual(unexpected,[]);
});

for(const locale of ['de','en']) contracts.set('inventory-labels-'+locale,async()=>{
  const {document, selectSnapshot, downloads, unexpected} = await boot({locale,inventoryComparisons:[comparison]});
  await selectSnapshot();
  const panel=document.querySelector('[data-inventory-comparison]');
  const select=panel.querySelectorAll('label').find(label=>label.textContent.startsWith(locale==='de'?'Gespeicherte Abgleiche':'Retained comparisons')).querySelector('select');
  const reopen=panel.querySelectorAll('button').find(button=>button.textContent===(locale==='de'?'Gespeicherten Abgleich öffnen':'Open retained comparison'));
  assert.ok(select && reopen);
  select.value='retained-comparison';await select.dispatchEvent(new Event('change'));
  assert.equal(reopen.disabled,false);
  await reopen.click();await flush();
  const output=panel.querySelector('[data-inventory-result]');
  const display=output.querySelector('table').textContent;
  for(const original of ['Original organization','An original reviewer sentence.','ORIGINAL_CRITERION','ORIGINAL_OUTCOME','ORIGINAL_LIMIT','original-product:19'])
    assert.ok(output.textContent.includes(original),original);
  assert.ok(display.includes('constructor'),'Unknown limitation values must remain exact text');
  if(locale==='de') {
    for(const translated of ['Ungeklärt','Fähigkeit','Verfügbarkeit','Nicht geprüft','Technologieprodukt',
      'Explizite datierte Nachweise','Zitierte Aussage','Erforderliche Beziehungsnachweise','Exakte gerichtete Beziehungsanforderungen'])
      assert.ok(display.includes(translated),translated);
    assert.ok(output.textContent.includes('Startfenster'));
  } else {
    for(const literal of comparison.findings.map(f=>f.nextClarification))assert.ok(display.includes(literal),literal);
    assert.ok(output.textContent.includes('Start window'));
  }
  const download=output.querySelectorAll('button').find(button=>button.textContent.includes('JSON'));
  await download.click();await flush();
  assert.deepEqual(JSON.parse(await downloads[0].text()),comparison);
  assert.equal(select.value,'retained-comparison');
  assert.deepEqual(unexpected,[]);
});

contracts.set('compare-snapshot-read-failure', async () => {
  const {document, selectSnapshot} = await boot({compare: async () => { throw new Error('Snapshot comparison unavailable'); }});
  await selectSnapshot();
  await document.querySelector('.snapshot-compare').click();
  assert.match(document.getElementById('portfolioAlert').textContent, /snapshot comparison unavailable/i);
});

contracts.set('obsolete-snapshot-comparison', async () => {
  let rejectComparison;
  const {document, selectSnapshot} = await boot({compare: () => new Promise((resolve, reject) => { rejectComparison = reject; })});
  await selectSnapshot();
  const pending = document.querySelector('.snapshot-compare').click();
  await document.querySelectorAll('.snapshot-select')[1].click();
  assert.equal(typeof rejectComparison, 'function', 'A comparison request must be in flight before leaving the snapshot');
  rejectComparison(new Error('Obsolete comparison error'));
  await pending;
  assert.equal(document.querySelector('.mapping-review').dataset.mappingId, '24');
  assert.equal(document.getElementById('portfolioAlert').textContent, '');
});

for (const [name, kind] of Object.entries(kinds)) {
  contracts.set(name + '-coverage-submit', async () => {
    const {document, writes, unexpected} = await boot();
    let form = document.querySelector(kind.form);
    const code = form.querySelector(`.${name}-node-code`);
    code.value = '  CP-NEW  ';
    form.querySelector(`.${name}-coverage-percent`).value = '0';
    await form.dispatchEvent(new Event('submit'));
    await flush();
    assert.deepEqual(writes, [{method: 'POST', path: kind.route, body: {
      nodeCode: 'CP-NEW', coveragePercent: 0, evidence: null, reviewStatus: 'PROPOSED'
    }}]);
    assert.deepEqual(unexpected, []);
    form = document.querySelector(kind.form);
    const nameField = form.querySelector(`.${name}-node-code`);
    const coverageField = form.querySelector(`.${name}-coverage-percent`);
    nameField.value = 'CP-BUTTON';
    coverageField.value = '100';
    assert.equal((await nameField.click()).defaultPrevented, undefined);
    assert.equal((await coverageField.click()).defaultPrevented, undefined);
    assert.equal(writes.length, 1);
    await form.querySelector('button[type="submit"]').click();
    await flush();
    assert.deepEqual(writes.at(-1), {method: 'POST', path: kind.route, body: {
      nodeCode: 'CP-BUTTON', coveragePercent: 100, evidence: null, reviewStatus: 'PROPOSED'
    }});
    assert.equal(writes.length, 2);
    if (name === 'solution') assert.match(document.getElementById('solutionsList').textContent, /CP-ORIGINAL/);
  });
}

contracts.set('candidate-submit', async () => {
  const {document, writes, unexpected} = await boot();
  const form = document.querySelector('.add-product-candidate');
  const productField = form.querySelector('.candidate-product-id');
  const coverageField = form.querySelector('.candidate-coverage-percent');
  await productField.click();
  productField.value = '19';
  coverageField.value = '65';
  await coverageField.click();
  assert.deepEqual(writes, [], 'Both an empty and a chosen product can be edited without saving');
  await form.dispatchEvent(new Event('submit'));
  await flush();
  assert.deepEqual(unexpected, []);
  assert.deepEqual(writes, [{method: 'POST', path: '/api/projects/7/solutions/11/products', body: {
    productId: 19, coveragePercent: 65, confidence: 0, reviewStatus: 'PROPOSED', selectionStatus: 'CANDIDATE'
  }}]);
  const refreshedForm = document.querySelector('.add-product-candidate');
  refreshedForm.querySelector('.candidate-product-id').value = '19';
  refreshedForm.querySelector('.candidate-coverage-percent').value = '100';
  await refreshedForm.querySelector('button[type="submit"]').click();
  assert.equal(writes.length, 2);
  assert.equal(writes[1].body.coveragePercent, 100);
});

contracts.set('invalid-forms', async () => {
  const {document, writes} = await boot();
  for (const [name, kind] of Object.entries(kinds)) {
    const form = document.querySelector(kind.form);
    const code = form.querySelector(`.${name}-node-code`);
    const coverage = form.querySelector(`.${name}-coverage-percent`);
    code.value = 'CP-VALID';
    for (const invalid of ['-1', '101', '', '1.5']) {
      coverage.value = invalid;
      await form.dispatchEvent(new Event('submit'));
      assert.match(document.getElementById('portfolioError').textContent, /0.*100/);
    }
    coverage.value = '50';
    code.value = '  ';
    await form.dispatchEvent(new Event('submit'));
    assert.match(document.getElementById('portfolioError').textContent, /taxonomy node/i);
    code.value = 'CP-VALID';
    form.dataset[kind.id] = '0';
    await form.dispatchEvent(new Event('submit'));
  }
  const form = document.querySelector('.add-product-candidate');
  const product = form.querySelector('.candidate-product-id');
  for (const invalid of ['', '0', '999']) {
    product.value = invalid;
    await form.dispatchEvent(new Event('submit'));
    assert.match(document.getElementById('portfolioError').textContent, /product/i);
  }
  product.value = '19';
  form.querySelector('.candidate-coverage-percent').value = '120';
  await form.dispatchEvent(new Event('submit'));
  assert.match(document.getElementById('portfolioError').textContent, /0.*100/);
  assert.deepEqual(writes, []);
});

contracts.set('busy-form', async () => {
  let release;
  const holdMutation = new Promise(resolve => { release = resolve; });
  const {document, writes} = await boot({holdMutation});
  const form = document.querySelector('.add-product-candidate');
  form.querySelector('.candidate-product-id').value = '19';
  const first = form.dispatchEvent(new Event('submit'));
  await flush();
  assert.equal(writes.length, 1);
  assert.equal(form.querySelector('button[type="submit"]').disabled, true);
  try {
    await form.dispatchEvent(new Event('submit'));
    assert.equal(writes.length, 1);
  } finally {
    release();
    await first;
  }
  assert.equal(document.querySelector('.add-product-candidate').querySelector('button[type="submit"]').disabled, false);
});

for (const [reviewStatus, selectionStatus] of [['CONFIRMED', 'SHORTLISTED'], ['REJECTED', 'REJECTED']]) {
  contracts.set('candidate-review-' + reviewStatus.toLowerCase(), async () => {
    const {document, writes, unexpected} = await boot();
    const button = document.querySelector(`.product-candidate-review[data-status="${reviewStatus}"]`);
    assert.ok(button);
    assert.equal(button.disabled, false);
    await button.click();
    assert.deepEqual(writes, [{method: 'PATCH', path: '/api/projects/7/product-candidates/31', body: {reviewStatus, selectionStatus}}]);
    assert.deepEqual(unexpected, []);
  });
}

contracts.set('busy-candidate-review', async () => {
  let release;
  const holdMutation = new Promise(resolve => { release = resolve; });
  const {document, writes} = await boot({holdMutation});
  const [confirm, reject] = document.querySelectorAll('.product-candidate-review');
  const first = confirm.dispatchEvent(new Event('click'));
  const second = reject.dispatchEvent(new Event('click'));
  await flush();
  try {
    assert.equal(writes.length, 1, 'An in-flight review excludes the opposite decision');
    assert.equal(confirm.disabled, true);
    assert.equal(reject.disabled, true);
  } finally {
    release();
    await Promise.all([first, second]);
  }
  assert.ok(document.querySelectorAll('.product-candidate-review').every(button => !button.disabled));
  assert.deepEqual(writes[0], {method: 'PATCH', path: '/api/projects/7/product-candidates/31', body: {
    reviewStatus: 'CONFIRMED', selectionStatus: 'SHORTLISTED'
  }});
});

contracts.set('stale-candidate-review', async () => {
  const {document, writes} = await boot();
  const [confirm, reject] = document.querySelectorAll('.product-candidate-review');
  reject.dataset.candidateId = '999';
  await reject.dispatchEvent(new Event('click'));
  const staleWrites = writes.length;
  confirm.disabled = true;
  await confirm.dispatchEvent(new Event('click'));
  assert.equal(staleWrites, 0, 'An identity absent from the rendered solution cannot submit');
  assert.deepEqual(writes, [], 'A disabled review cannot submit');
});

contracts.set('stale-candidate-project', async () => {
  let release;
  const holdOtherProject = new Promise(resolve => { release = resolve; });
  const {document, writes} = await boot({holdOtherProject});
  const button = document.querySelector('.product-candidate-review');
  await document.querySelectorAll('.project-select')[1].click();
  assert.equal(button.isConnected, true, 'The previous card is still visible while the new project loads');
  try {
    await button.dispatchEvent(new Event('click'));
    assert.deepEqual(writes, [], 'An earlier project card cannot submit while another project is selected');
  } finally {
    release();
    await flush();
  }
  assert.equal(button.isConnected, false);
});

async function assertEditingUnavailable({document, writes, dialogs, selectSnapshot}) {
  const controls = [];
  for (const [name, kind] of Object.entries(kinds)) {
    const form = document.querySelector(kind.form);
    form.querySelector(`.${name}-node-code`).value = 'CP-VALID';
    controls.push(form.querySelector('button[type="submit"]'));
    await form.dispatchEvent(new Event('submit'));
  }
  const candidate = document.querySelector('.add-product-candidate');
  candidate.querySelector('.candidate-product-id').value = '19';
  controls.push(candidate.querySelector('button[type="submit"]'));
  await candidate.dispatchEvent(new Event('submit'));
  const candidateReviews = document.querySelectorAll('.product-candidate-review');
  assert.equal(candidateReviews.length, 2, 'The existing product candidate remains readable');
  controls.push(...candidateReviews);
  for (const button of candidateReviews) await button.dispatchEvent(new Event('click'));
  await selectSnapshot();
  const button = document.querySelector('.mapping-review');
  assert.ok(button);
  controls.push(button);
  await button.dispatchEvent(new Event('click'));
  assert.deepEqual(writes, []);
  assert.deepEqual(dialogs, []);
  assert.ok(controls.every(control => control.disabled));
}

contracts.set('readonly-forms-and-review', async () => {
  await assertEditingUnavailable(await boot({readOnly: true}));
});

contracts.set('pending-edit-permission', async () => {
  let resolveAccount;
  const pending = new Promise(resolve => { resolveAccount = resolve; });
  const app = await boot({account: () => pending});
  await assertEditingUnavailable(app);
  const reason = app.document.getElementById('portfolioMutationAccessMessage');
  assert.equal(reason.hidden, false);
  assert.match(reason.textContent, /checking.*permission/i);
  resolveAccount({architectureMutationAllowed: true});
  await flush();
  assert.equal(reason.hidden, true);
  const {document, writes, dialogs} = app;
  assert.ok(document.querySelectorAll('.product-candidate-review').every(button => !button.disabled));
  await document.querySelector('.mapping-review[data-status="REJECTED"]').click();
  assert.equal(dialogs.length, 1);
  document.getElementById('portfolioDecisionDialog').dispatchEvent(new Event('hidden.bs.modal'));
  const form = document.querySelector('.add-product-candidate');
  assert.equal(form.querySelector('button[type="submit"]').disabled, false);
  await form.querySelector('button[type="submit"]').click();
  assert.equal(writes.length, 1);
});

contracts.set('unavailable-edit-permission', async () => {
  const app = await boot({account: async () => { throw new Error('Account endpoint unavailable'); }});
  await assertEditingUnavailable(app);
  const reason = app.document.getElementById('portfolioMutationAccessMessage');
  assert.equal(reason.hidden, false);
  assert.match(reason.textContent, /permissions.*could not be checked/i);
});

contracts.set('empty-project-analysis', async () => {
  const {document, writes} = await boot({empty: true});
  const button = document.getElementById('analyzeAllBtn');
  assert.equal(button.disabled, true);
  const description = document.getElementById(button.getAttribute('aria-describedby'));
  assert.ok(description, 'The reason for disabling the action must have an accessible description');
  assert.match(description.textContent, /add.*requirement/i);
  await button.dispatchEvent(new Event('click'));
  assert.deepEqual(writes, []);
  assert.match(document.getElementById('portfolioAlert').textContent, /add.*requirement/i);
});

contracts.set('populated-project-analysis', async () => {
  const {document, writes} = await boot();
  const button = document.getElementById('analyzeAllBtn');
  assert.equal(button.disabled, false);
  await button.click();
  assert.equal(writes.length, 1);
  assert.equal(writes[0].path, '/api/projects/7/analyses');
  assert.deepEqual(writes[0].body.requirementIds, [9]);
});

assert.ok(contracts.has(contract), `Unknown form actions contract: ${contract}`);
contracts.get(contract)().then(() => {
  console.log(`PORTFOLIO_FORM_ACTIONS_OK ${contract}`);
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
