import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/versioning/taxonomy-context-compare.js', import.meta.url), 'utf8');
function harness() {
  const elements = new Map();
  const context = { window: {}, document: {
    readyState: 'loading', addEventListener() {}, getElementById: id => elements.get(id) || null
  }, TaxonomyI18n: { t: (key, ...args) => key + (args.length ? ': ' + args.join(', ') : ''), formatBranch: name => name } };
  vm.runInNewContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-utils.js', import.meta.url), 'utf8'), context);
  context.TaxonomyUtils = context.window.TaxonomyUtils;
  vm.runInNewContext(source, context);
  return { api: context.window.TaxonomyContextCompare, elements, context };
}
// Same structure and real catalogue relationship as a recorded /api/dsl/diff response.
function fixture() {
  return { totalChanges: 1, isEmpty: false,
    addedElements: 0, removedElements: 0, changedElements: 0,
    addedRelations: 0, removedRelations: 0, changedRelations: 1,
    details: { addedElements: [], removedElements: [], changedElements: [], addedRelations: [], removedRelations: [],
      changedRelations: [{ before: { status: 'accepted' }, after: { status: 'proposed' } }] },
    semanticChanges: [{ changeType: 'RELATION_STATUS_CHANGED', entityKind: 'relation', entityId: 'BP|CONSUMES|IP',
      description: "Status changed from 'accepted' to 'proposed' for BP|CONSUMES|IP", beforeValue: 'accepted', afterValue: 'proposed' }] };
}
function convert(diff, left, right) {
  const { api } = harness();
  assert.equal(typeof api.fromDocumentDiff, 'function', 'Shared comparison must accept the actual document-diff response');
  return api.fromDocumentDiff(diff, left, right);
}
test('relation-only change is rendered with both commit identities and before/after values', () => {
  const { api, elements } = harness();
  const result = convert(fixture(), { branch: 'draft', commitId: '0123456789' }, { branch: 'draft', commitId: 'abcdef0123' });
  assert.equal(result.summary.relationsChanged, 1);
  assert.equal(result.summary.elementsChanged, 0);
  assert.equal(result.changes[0].category, 'RELATION');
  assert.equal(result.changes[0].changeType, 'MODIFY');
  elements.set('results', { innerHTML: '', querySelectorAll: () => [] });
  api.renderComparison('results', result);
  const html = elements.get('results').innerHTML;
  for (const expected of ['0123456', 'abcdef0', 'data-compare-section="relations"', 'accepted', 'proposed']) assert.ok(html.includes(expected), expected);
  assert.ok(!html.slice(0, html.indexOf('data-compare-section')).includes('compare.no.differences'), 'The overall summary must not claim an empty comparison');
});
test('zero changes require a consistent explicitly empty response', () => {
  const diff = fixture(); diff.changedRelations = 0; diff.details.changedRelations = [];
  diff.totalChanges = 0; diff.isEmpty = true; diff.semanticChanges = [];
  assert.equal(convert(diff).changes.length, 0);
});
for (const [name, corrupt] of Object.entries({
  'missing details': diff => delete diff.details,
  'missing count': diff => delete diff.addedElements,
  'negative count': diff => diff.changedRelations = -1,
  'wrong total': diff => diff.totalChanges = 0,
  'wrong empty flag': diff => diff.isEmpty = true,
  'missing detail entries': diff => diff.details.changedRelations = [],
  'semantic changes in an empty response': diff => { diff.changedRelations = 0; diff.details.changedRelations = []; diff.totalChanges = 0; diff.isEmpty = true; },
  'missing semantic entries': diff => diff.semanticChanges = [],
  'unknown category': diff => diff.semanticChanges[0].entityKind = 'made-up',
  'missing description': diff => delete diff.semanticChanges[0].description
})) test(`reject ${name}, never display an empty success`, () => {
  const { api } = harness();
  assert.equal(typeof api.fromDocumentDiff, 'function');
  const diff = fixture(); corrupt(diff); assert.throws(() => api.fromDocumentDiff(diff));
});
for (const [type, key, expected] of [['RELATION_ADDED', 'addedRelations', 'ADD'], ['RELATION_REMOVED', 'removedRelations', 'REMOVE'], ['RELATION_CONFIDENCE_CHANGED', 'changedRelations', 'MODIFY']]) {
  test(`map ${type} without reversing the direction`, () => {
    const diff = fixture(); diff.changedRelations = 0; diff.details.changedRelations = [];
    diff[key] = 1; diff.details[key] = [{}]; diff.semanticChanges[0].changeType = type;
    assert.equal(convert(diff).changes[0].changeType, expected);
  });
}
test('one changed relation may describe several changed properties', () => {
  const diff = fixture(); diff.semanticChanges.push({ ...diff.semanticChanges[0], changeType: 'RELATION_CONFIDENCE_CHANGED', beforeValue: '0', afterValue: '0.5' });
  const result = convert(diff); assert.equal(result.summary.relationsChanged, 1); assert.equal(result.changes.length, 2);
});
test('zero and empty old values remain visible and unsafe markup is escaped', () => {
  const { api, elements } = harness();
  elements.set('results', { innerHTML: '', querySelectorAll: () => [] });
  const result = convert(fixture());
  result.changes[0].beforeValue = 0; result.changes[0].afterValue = '<img src=x onerror=alert(1)>';
  api.renderComparison('results', result);
  const html = elements.get('results').innerHTML;
  assert.ok(html.includes('0 → &lt;img')); assert.ok(!html.includes('<img'));
});

function dialogHarness() {
  const h = harness();
  const pending = [];
  const listeners = new Map();
  const button = { disabled: false };
  const modal = { querySelector: () => button, addEventListener: (name, fn) => listeners.set(name, fn) };
  h.elements.set('contextCompareModal', modal);
  h.elements.set('contextCompareResults', { textContent: '', innerHTML: '', querySelectorAll: () => [] });
  for (const id of ['compareLeftBranch', 'compareRightBranch', 'compareLeftCommit', 'compareRightCommit']) {
    const events = {};
    h.elements.set(id, { value: '', tagName: id.endsWith('Branch') ? 'SELECT' : 'INPUT', disabled: false,
      replaceChildren() {}, appendChild() {}, addEventListener: (name, fn) => { events[name] = fn; }, events });
  }
  h.context.document.createElement = () => ({});
  h.context.bootstrap = { Modal: { getOrCreateInstance: () => ({ show() {} }) } };
  h.context.window.TaxonomyApiClient = { getJson: url => new Promise((resolve, reject) => pending.push({ url, resolve, reject })) };
  return { ...h, pending, listeners, button };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
async function open(h, context = { branch: 'draft' }) {
  const work = h.api.showDialog(context);
  h.pending.shift().resolve({ branches: ['draft', 'review'] });
  await work;
}
test('closing a comparison invalidates its outstanding response', async () => {
  const h = dialogHarness(); await open(h);
  h.api.doCompare(); const pending = h.pending.shift();
  const container = h.elements.get('contextCompareResults'); const loading = container.innerHTML;
  h.listeners.get('hide.bs.modal')(); pending.resolve(convert(fixture())); await flush();
  assert.equal(container.innerHTML, loading);
});
test('changing a side invalidates the outstanding comparison', async () => {
  const h = dialogHarness(); await open(h); h.api.doCompare();
  const pending = h.pending.shift(); h.elements.get('compareRightCommit').events.input();
  const container = h.elements.get('contextCompareResults'); const current = container.innerHTML;
  pending.resolve(convert(fixture())); await flush(); assert.equal(container.innerHTML, current);
});
test('a newer dialog prevents an old branch response from selecting its sides', async () => {
  const h = dialogHarness();
  const old = h.api.showDialog({ branch: 'draft' }); const first = h.pending.shift();
  const next = h.api.showDialog({ branch: 'review', commitId: 'review-head' }); const second = h.pending.shift();
  second.resolve({ branches: ['draft', 'review'] }); await next;
  first.resolve({ branches: ['draft'] }); await old;
  assert.equal(h.elements.get('compareLeftBranch').value, 'review');
  assert.equal(h.elements.get('compareLeftCommit').value, 'review-head');
});
test('failed branches disable Compare and cannot run with a stale selection', async () => {
  const h = dialogHarness(); const work = h.api.showDialog({ branch: 'draft' });
  h.pending.shift().reject(new Error('HTTP 503')); await work;
  h.api.doCompare(); assert.equal(h.pending.length, 0); assert.equal(h.button.disabled, true);
  assert.match(h.elements.get('contextCompareResults').innerHTML, /compare.failed/);
});
test('branch loading disables editable sides until the new choices are authoritative', async () => {
  const h = dialogHarness(); const work = h.api.showDialog({ branch: 'draft' });
  for (const id of ['compareLeftBranch', 'compareRightBranch', 'compareLeftCommit', 'compareRightCommit']) {
    assert.equal(h.elements.get(id).disabled, true, id);
  }
  h.pending.shift().resolve({ branches: ['draft'] }); await work;
  assert.equal(h.elements.get('compareLeftCommit').disabled, false);
});

test('relation-only comparison does not bury its changes under three empty element cards', () => {
  const { api, elements } = harness();
  elements.set('results', { innerHTML: '', querySelectorAll: () => [] });
  api.renderComparison('results', convert(fixture()));
  const html = elements.get('results').innerHTML;
  assert.ok(!html.includes('compare.column.added'), 'Empty element columns must not precede the actual relationship change');
  assert.ok(html.includes('compare.filter.elements'), 'Keep an explicit compact no-element-changes message');
});

for (const type of ['UNKNOWN_ADDED', 'RELATION_WHATEVER', '', 'ELEMENT_ADDED']) test(`reject unknown or wrong-kind semantic change ${type}`, () => {
  const { api } = harness(); const diff = fixture(); diff.semanticChanges[0].changeType = type;
  assert.throws(() => api.fromDocumentDiff(diff));
});
test('all Java SemanticChangeType values are accepted for their actual entity kind', () => {
  const java = readFileSync(new URL('../../taxonomy-dsl/src/main/java/com/taxonomy/dsl/diff/SemanticChangeType.java', import.meta.url), 'utf8');
  const types = [...java.matchAll(/\b((?:ELEMENT|RELATION)_[A-Z_]+)\(/g)].map(match => match[1]);
  assert.equal(types.length, 13);
  for (const type of types) {
    const diff = fixture(), kind = type.startsWith('ELEMENT_') ? 'Elements' : 'Relations';
    const action = type.endsWith('_ADDED') ? 'added' : type.endsWith('_REMOVED') ? 'removed' : 'changed';
    diff.changedRelations = 0; diff.details.changedRelations = [];
    diff[action + kind] = 1; diff.details[action + kind] = [{}];
    Object.assign(diff.semanticChanges[0], { changeType: type, entityKind: kind.toLowerCase().slice(0, -1) });
    assert.equal(convert(diff).changes.length, 1, type);
  }
});
test('actual shared escaping retains element numeric zero and boolean false evidence', () => {
  const { api, elements, context } = harness();
  assert.equal(context.TaxonomyUtils.escapeHtml(0), '', 'Exercise actual production escaping, not a friendlier test double');
  elements.set('results', { innerHTML: '' });
  const diff = convert(fixture()); diff.changes[0].category = 'ELEMENT';
  diff.changes[0].beforeValue = 0; diff.changes[0].afterValue = false;
  api.renderComparison('results', diff);
  assert.match(elements.get('results').innerHTML, /0 → false/);
});
for (const failAt of ['success', 'partial setup', 'UI assertion']) test(`QA comparison branch cleanup after ${failAt}`, async () => {
  const workflow = await import('./ui-primary-version-workflow.mjs');
  assert.equal(typeof workflow.usingComparisonBranch, 'function');
  let allocated, cleaned;
  const run = branch => { allocated = branch; if (failAt !== 'success') throw new Error(failAt); return 'done'; };
  const cleanup = branch => { cleaned = branch; };
  if (failAt === 'success') assert.equal(await workflow.usingComparisonBranch(run, cleanup), 'done');
  else await assert.rejects(workflow.usingComparisonBranch(run, cleanup), new RegExp(failAt));
  assert.match(allocated, /^qa-compare-[0-9a-f-]{36}$/);
  assert.equal(cleaned, allocated, 'Cleanup must run even before setup returned version IDs');
});
test('QA cleanup errors preserve the original assertion failure', async () => {
  const workflow = await import('./ui-primary-version-workflow.mjs');
  assert.equal(typeof workflow.usingComparisonBranch, 'function');
  await assert.rejects(workflow.usingComparisonBranch(() => { throw new Error('original assertion'); },
    () => { throw new Error('cleanup unavailable'); }), error => {
      assert.equal(error.errors.length, 2);
      assert.match(error.errors[0].message, /original assertion/);
      assert.match(error.errors[1].message, /cleanup unavailable/);
      return true;
    });
});
