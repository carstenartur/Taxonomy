import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js', import.meta.url), 'utf8');
const escapeHtml = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
const window = { TaxonomyState: {} };
vm.runInNewContext(source, { window, TaxonomyI18n: { t: (key, ...args) => `${key} ${args.join(' ')}` }, TaxonomyUtils: { escapeHtml } });
const render = window.TaxonomyScoring.renderRelationSearchReport;
const base = () => ({ totalCalls: 5, maxCalls: 24, searchExhausted: false, sources: [], warnings: [], stopReason: '', result: { edges: [], unfinished: [], trace: [] } });
const edge = necessity => ({ type: 'CONSUMES', direction: 'OUTGOING', contribution: { source: { id: 'reader' }, text: 'Read only', quote: 'Read evidence.', condition: 'authorized role' }, target: { id: 'evidence' }, evidence: { necessity, contribution: 'existing record', quote: 'Read evidence.', rationale: 'Read, not write', condition: 'if requested', alternativeGroup: 'channel' } });
test('absent report leaves legacy view unchanged', () => assert.equal(render(null), ''));
test('evaluation budget is visible', () => { const html = render(base()); assert.match(html, /relation.search.budget 5 24/); assert.match(html, /<details/); assert.match(html, /<summary/); });
test('cumulative relation consumption is separate from new evaluations and completed search work', () => {
  const r = base();
  r.budget = { callsPerSlice: 256, maxTotalCalls: 4096, newCalls: 512, spentCalls: 4096,
    automatic: true, slices: 2, sourcesPerSlice: 128, workItemsPerSlice: 4096, sourceCohortSize: 256 };
  r.progress = { completedSearches: 28, totalSearches: 80, unresolvedSearches: 4, pendingSearches: 48 };
  r.stopReason = 'TOTAL_CALL_BUDGET';
  const html = render(r);
  assert.match(html, /relation.search.budget.total 4096 4096/);
  assert.match(html, /relation.search.budget.new 512/);
  assert.match(html, /relation.search.budget.initial 256/);
  assert.match(html, /relation.search.budget.automatic.enabled/);
  assert.match(html, /relation.search.progress 28 80 4 48/);
  assert.match(html, /relation.search.partial/);
  assert.doesNotMatch(html, /relation.search.budget 5 24|relation.search.exhausted|relation.search.progress 80 80/);
});
test('zero relation budgets remain explicit and malformed optional metadata uses the legacy summary', () => {
  const r = base(); r.budget = { callsPerSlice: 0, maxTotalCalls: 0, newCalls: 0, spentCalls: 0, automatic: false };
  assert.match(render(r), /relation.search.budget.total 0 0/);
  assert.match(render(r), /relation.search.budget.automatic.disabled/);
  for (const value of [undefined, -1, 2.5, '4096', '<script>bad</script>']) {
    r.budget.spentCalls = value;
    assert.match(render(r), /relation.search.budget 5 24/);
    assert.doesNotMatch(render(r), /relation.search.budget.total|<script/);
  }
});
test('final report retains the complete task denominator and makes open work inspectable', () => {
  const r = base(); r.progress = { completedSearches: 28, totalSearches: 80, unresolvedSearches: 4, pendingSearches: 48 };
  r.tasks = [{ sourceId: '<BP-1>', targetRoot: 'IP', state: 'PENDING' }, { sourceId: 'BP-2', targetRoot: 'CR', state: 'COMPLETED' }];
  const html = render(r);
  assert.match(html, /relation.search.progress 28 80 4 48/);
  assert.match(html, /&lt;BP-1&gt;/);
  assert.match(html, /relation.search.task.pending/);
  assert.doesNotMatch(html, /<BP-1>/);
});
test('partial is not presented as complete', () => { assert.match(render(base()), /relation.search.partial/); const r = base(); r.searchExhausted = true; assert.match(render(r), /relation.search.exhausted/); });
test('unresolved questions are shown with source and reason', () => { const r = base(); r.result.unfinished = [{ sourceId: 'reader', reason: 'UNRESOLVED', question: 'Which channel?' }]; assert.match(render(r), /reader/); assert.match(render(r), /Which channel\?/); });
test('optional and alternative evidence is visible without adoption controls', () => { const r = base(); r.result.edges = [edge('OPTIONAL'), edge('ALTERNATIVE')]; const html = render(r); assert.match(html, /OPTIONAL/); assert.match(html, /ALTERNATIVE/); assert.match(html, /channel/); assert.match(html, /authorized role/); assert.doesNotMatch(html, /<button|onclick=/); });
test('provider and requirement strings cannot inject markup', () => { const r = base(); r.warnings = ['<script>alert(1)</script>']; r.result.edges = [edge('REQUIRED')]; r.result.edges[0].evidence.rationale = '<img src=x onerror="alert(1)">'; const html = render(r); assert.doesNotMatch(html, /<script|<img/); assert.match(html, /&lt;script/); assert.match(html, /&lt;img/); });
test('bounded rendering states omissions explicitly', () => { const r = base(); r.result.unfinished = Array.from({ length: 81 }, (_, i) => ({ sourceId: `n${i}`, reason: 'CALL_BUDGET', question: 'Pending' })); const html = render(r); assert.match(html, /relation.search.omitted/); assert.doesNotMatch(html, /n80/); });
test('full requirement context and checked rationale are available', () => { const r = base(); r.result.edges = [edge('REQUIRED')]; const html = render(r); assert.match(html, /Read evidence\./); assert.match(html, /Read, not write/); assert.match(html, /existing record/); assert.match(html, /reader/); });
test('regular UI verification executes both relationship report suites', async () => {
  const { scripts } = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
  for (const name of ['relation-search-report.test.mjs', 'relation-search-confidence.test.mjs']) {
    assert.ok(scripts['test:requirement-relations'].includes(`scripts/${name}`), `missing regular suite: ${name}`);
  }
  assert.match(scripts['verify:ui-contracts'], /-Dtest=JavascriptContractTest/);
  assert.equal(scripts['verify:ui'], '../mvnw -B -f ui-verification-pom.xml verify -Pshard');
  const inventory=await readFile(new URL('../../.mvn/javascript-junit-sources.txt', import.meta.url),'utf8');
  for(const source of ['relation-search-report.test.mjs','relation-search-confidence.test.mjs']) assert.ok(inventory.includes(source));
});

test('source contributions remain inspectable without any verified edge', () => {
  const r = base();
  r.sources = [{ node: { id: 'reader' }, rationale: 'Needed by the requirement', question: '',
    contributions: [{ text: 'Read published evidence', quote: 'Read evidence.', condition: '' }] }];
  const html = render(r);
  assert.match(html, /relation.search.sources/);
  assert.match(html, /reader/);
  assert.match(html, /Read published evidence/);
  assert.match(html, /<q>Read evidence\.<\/q>/);
  assert.doesNotMatch(html, /VERIFIED/);
});
test('source questions, conditions and quotations are escaped and bounded', () => {
  const r = base();
  r.sources = [{ node: { id: '<reader>' }, rationale: '<script>bad</script>', question: 'Which <channel>?',
    contributions: Array.from({ length: 5 }, (_, i) => ({ text: `part-${i}`, quote: '<quoted>', condition: '<optional>' })) }];
  const html = render(r);
  assert.match(html, /&lt;reader&gt;/);
  assert.match(html, /Which &lt;channel&gt;\?/);
  assert.match(html, /&lt;quoted&gt;/);
  assert.match(html, /&lt;optional&gt;/);
  assert.match(html, /relation.search.omitted 4 5/);
  assert.doesNotMatch(html, /<script|part-4/);
});
function elementTable(element) {
  const local = { TaxonomyState: {} };
  vm.runInNewContext(source.replace('window.TaxonomyScoring = {',
    'window.elementProbe = renderElementsTable; window.TaxonomyScoring = {'),
    { window: local, TaxonomyI18n: { t: key => key }, TaxonomyUtils: { escapeHtml } });
  return { html: local.elementProbe([element], new Set()),
    formatPercentage: local.TaxonomyScoring.formatPercentage };
}
test('scoped source and target proposals never display absent scores as assessed zeros', () => {
  for (const origin of ['REQUIREMENT_EVIDENCE', 'RELATION_EVIDENCE']) {
    const { html } = elementTable({ nodeCode: 'reader', origin, relevance: 0, directLlmScore: 0 });
    assert.doesNotMatch(html, /<td>0\.0%<\/td>|<td>0<\/td>/);
    assert.match(html, /<td>—<\/td><td>—<\/td>/);
  }
});
test('scoped raw and effective assessment values remain distinct, including explicit zero', () => {
  for (const [rawScore, effectiveRelevance] of [[80, 32], [0, 0]]) {
    const { html, formatPercentage } = elementTable({ nodeCode: 'reader', origin: 'REQUIREMENT_EVIDENCE', relevance: 0.95,
      directLlmScore: 95, scoreDetail: { rawScore, effectiveRelevance } });
    assert.ok(html.includes(`<td>${formatPercentage(effectiveRelevance)}</td><td>${rawScore}</td>`));
  }
});
