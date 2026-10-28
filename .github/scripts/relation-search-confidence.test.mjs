import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
const i18nSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js', import.meta.url), 'utf8');
const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js', import.meta.url), 'utf8');
function harness(scoped, language = 'en') {
  const elements = new Map();
  const window = { location: { pathname: '/projects/1/requirements/2', search: '' }, TaxonomyUtils: { escapeHtml: value => String(value ?? '') } };
  const document = { documentElement: { lang: language }, addEventListener() {}, getElementById(id) { if (!elements.has(id)) elements.set(id, {}); return elements.get(id); } };
  document.cookie = ''; document.dispatchEvent = () => {};
  // Load the real shared formatter required by the production page.
  vm.runInNewContext(i18nSource, { window, document, fetch: async () => ({ ok: true, json: async () => ({}) }), CustomEvent: class {} });
  // Expose private rendering functions in the test closure; execute their unchanged production bodies.
  const instrumented = source.replace(/\}\)\(\);\s*$/, 'window.probe = { state, renderMappings, renderRelations, renderAnalysisSummary }; })();');
  vm.runInNewContext(instrumented, { window, document, URLSearchParams });
  window.probe.state.snapshotDetail = { analysis: scoped ? { relationSearchReport: {} } : {} };
  return { ...window.probe, elements };
}
test('scoped element evidence does not display relevance-derived confidence', () => {
  const h = harness(true); h.renderMappings([{ nodeCode: 'source', directScore: 95, relevance: 0.2, confidence: 0.8 }]);
  const html = h.elements.get('mappingTable').innerHTML; assert.doesNotMatch(html, /80%/); assert.match(html, /<td>—<\/td>/);
});
test('scoped relation confidence is unavailable, not zero-percent certainty', () => {
  const h = harness(true); h.renderRelations([{ sourceCode: 'source', targetCode: 'target', relevance: 0.1, confidence: 0 }]);
  const html = h.elements.get('relationTable').innerHTML; assert.doesNotMatch(html, /<td>0%<\/td>/); assert.match(html, /<td>—<\/td>/);
});
test('existing snapshot confidence display remains unchanged', () => {
  const h = harness(false); h.renderMappings([{ nodeCode: 'source', directScore: 95, relevance: 0.2, confidence: 0.8 }]);
  assert.match(h.elements.get('mappingTable').innerHTML, /80%/);
});

for (const language of ['en', 'de']) {
  test(`${language} saved portfolio results separate total consumption from completed relationship work`, () => {
    const h = harness(true, language);
    const report = { budget: { callsPerSlice: 256, maxTotalCalls: 4096, newCalls: 512,
      spentCalls: 4096, automatic: true }, searchExhausted: false,
      progress: { completedSearches: 28, totalSearches: 80, unresolvedSearches: 4, pendingSearches: 48 } };
    const html = h.renderAnalysisSummary({ analysis: { relationSearchReport: report } });
    assert.match(html, language === 'de' ? /Gesamtbudget.*4096.*4096/ : /Total budget.*4096.*4096/);
    assert.match(html, language === 'de' ? /Neue Prüfungen.*512/ : /New evaluations.*512/);
    assert.match(html, language === 'de' ? /28 von 80/ : /28 of 80/);
    assert.match(html, language === 'de' ? /Sucharbeit.*offen/ : /search work.*open/i);
    assert.doesNotMatch(html, /80 of 80|80 von 80/);
    assert.doesNotMatch(h.renderAnalysisSummary({ analysis: {} }), /snapshotRelationSearch/);
  });
}

test('scoped mappings use raw and effective score evidence, not numeric index defaults', () => {
  const h = harness(true);
  h.state.snapshotDetail.analysis.scoreDetails = { source: { nodeCode: 'source', kind: 'PRODUCT_SUITABILITY', rawScore: 80, effectiveRelevance: 32 } };
  h.renderMappings([{ nodeCode: 'source', directScore: 32, relevance: 0.32, confidence: 0 }]);
  assert.match(h.elements.get('mappingTable').innerHTML, /<td>80%<\/td><td>32%<\/td>/);
});
test('scoped unassessed mappings never display synthetic zero scores', () => {
  const h = harness(true);
  h.state.snapshotDetail.analysis.scoreDetails = {};
  h.renderMappings([{ nodeCode: 'unassessed', directScore: 0, relevance: 0, confidence: 0 }]);
  assert.doesNotMatch(h.elements.get('mappingTable').innerHTML, /<td>0%<\/td>/);
});
test('scoped explicit zero remains a known negative assessment', () => {
  const h = harness(true);
  h.state.snapshotDetail.analysis.scoreDetails = { source: { nodeCode: 'source', kind: 'HIERARCHICAL_RELEVANCE', rawScore: 0, effectiveRelevance: 0 } };
  h.renderMappings([{ nodeCode: 'source', directScore: 85, relevance: 0.85, confidence: 0 }]);
  assert.match(h.elements.get('mappingTable').innerHTML, /<td>0%<\/td><td>0%<\/td>/);
});


for (const state of ['SKIPPED_PARTIAL', 'SKIPPED_SCOPE']) test(`portfolio ${state} reports remain unavailable instead of zero-valued successes`, () => {
  const h = harness(true);
  const html = h.renderAnalysisSummary({ analysis: { copilot: { state, notes: ['Coverage remains open.'] } },
    gapAnalysis: { totalGaps: 0 }, patternDetection: { patternCoverage: 0 }, recommendation: { confidence: 0 } }).split('<details id="technicalSnapshotData"')[0];
  assert.match(html, /Coverage remains open/);
  assert.match(html, />—</);
  assert.doesNotMatch(html, /0%|id="gapResultSection"|id="patternResultSection"|id="recommendationResultSection"/);
});
test('retained portfolio reports use neutral compatibility labels and retain evidence caveats', () => {
  const h = harness(true);
  const copilot = { state: 'COMPLETED', gaps: { totalGaps: 0, totalAnchors: 0 },
    patterns: { patternCoverage: 0 }, recommendation: { confidence: 85, confirmedElements: [{ nodeCode: 'fixture' }] },
    notes: ['Search policy does not prove completeness.'] };
  const html = h.renderAnalysisSummary({ analysis: { copilot } }).split('<details id="technicalSnapshotData"')[0];
  assert.match(html, /Open compatibility checks/); assert.match(html, /Mean retained relevance/);
  assert.match(html, /Assessment matches/); assert.match(html, /Search policy does not prove completeness/);
  assert.doesNotMatch(html, /Open gaps|Confirmed elements/);
});
test('portfolio patterns preserve concrete evidence, conditional applicability and incomplete chains', () => {
  const h = harness(true);
  const copilot = { state: 'PARTIAL', gaps: {}, recommendation: {}, notes: [], patterns: { patternCoverage: 0,
    incompletePatterns: [{ patternName: 'Retained chain', completeness: 75, expectedSteps: ['BP REALIZES CP'],
      presentSteps: ['BP-fixture REALIZES CP-fixture [when approved]'], missingSteps: ['Applicability needs review.'] }] } };
  const html = h.renderAnalysisSummary({ analysis: { copilot } }).split('<details id="technicalSnapshotData"')[0];
  assert.match(html, /BP-fixture REALIZES CP-fixture \[when approved\]/);
  assert.match(html, /Applicability needs review/);
  assert.doesNotMatch(html, /is-missing[^>]*>[^<]*<span[^>]*>○<\/span>BP REALIZES CP/);
});
