import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
const i18nSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js', import.meta.url), 'utf8');
const utilsSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-utils.js', import.meta.url), 'utf8');
const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js', import.meta.url), 'utf8');
function harness(scoped, language = 'en') {
  const elements = new Map();
  const window = { location: { pathname: '/projects/1/requirements/2', search: '' } };
  const document = { readyState: 'loading', documentElement: { lang: language }, addEventListener() {}, getElementById(id) { if (!elements.has(id)) elements.set(id, {}); return elements.get(id); } };
  document.cookie = ''; document.dispatchEvent = () => {};
  vm.runInNewContext(utilsSource, { window, document });
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
  const html = h.renderAnalysisSummary({ analysis: { copilot: { schemaVersion: 2, state, notes: ['Coverage remains open.'] } },
    gapAnalysis: { totalGaps: 0 }, patternDetection: { patternCoverage: 0 }, recommendation: { meanRelevance: null } }).split('<details id="technicalSnapshotData"')[0];
  assert.match(html, /Coverage remains open/);
  assert.match(html, />—</);
  assert.doesNotMatch(html, /0%|id="gapResultSection"|id="patternResultSection"|id="recommendationResultSection"/);
});
test('retained portfolio reports use neutral compatibility labels and retain evidence caveats', () => {
  const h = harness(true);
  const copilot = { schemaVersion: 2, state: 'COMPLETED', gaps: { profileId: 'compatibility', profileVersion: '1.0.0', judgments: [], evidenceCompleteness: 'INCOMPLETE' },
    patterns: { patternCoverage: 0 }, recommendation: { meanRelevance: 85, matchedElements: [{ nodeCode: 'fixture' }], acceptedElements: [] },
    notes: ['Search policy does not prove completeness.'] };
  const html = h.renderAnalysisSummary({ analysis: { copilot } }).split('<details id="technicalSnapshotData"')[0];
  assert.match(html, /Obligation assessment/); assert.match(html, /Mean relevance/);
  assert.match(html, /Assessment matches/); assert.match(html, /Search policy does not prove completeness/);
  assert.doesNotMatch(html, /Open gaps|Confirmed elements/);
});
test('retained element evidence stays available in closed details without invented relation proposals', () => {
  const h = harness(true);
  const caveat = 'Candidate ranking and mean relevance use only retained effective relevance scores.';
  const copilot = { schemaVersion: 2, state: 'COMPLETED',
    gaps: { profileId: 'compatibility', profileVersion: '1.0.0', judgments: [],
      evidenceAuthority: 'RETAINED_ANALYSIS', evidenceCompleteness: 'INCOMPLETE' },
    patterns: {}, recommendation: { meanRelevance: 72.5,
      matchedElements: [{ nodeCode: 'CP-RETAINED', title: 'Retained capability', taxonomyRoot: 'CP', score: 85, reasoning: 'Retained assessment reason.' }],
      acceptedElements: [],
      proposedElements: [{ nodeCode: 'CR-RETAINED', title: 'Retained service candidate', taxonomyRoot: 'CR', score: 60, reasoning: 'Retained assessment reason.' }],
      suggestedRelations: [], reasoning: [caveat] },
    notes: ['Search policy does not prove completeness.'] };
  const html = h.renderAnalysisSummary({ analysis: { copilot } }).split('<details id="technicalSnapshotData"')[0];
  const details = [...html.matchAll(/<details\b([^>]*)>([\s\S]*?)<\/details>/g)]
    .filter(match => /\bportfolio-finding-details\b/.test(match[1]));
  assert.equal(details.length, 2, 'Both nonempty retained element groups must expose closed evidence details');
  for (const [label, code, title] of [
    ['Assessment matches', 'CP-RETAINED', 'Retained capability'],
    ['Proposed elements', 'CR-RETAINED', 'Retained service candidate']
  ]) {
    const detail = details.find(match => match[2].includes(label));
    assert.ok(detail, `Missing evidence details for ${label}`);
    assert.doesNotMatch(detail[1], /(?:^|\s)open(?:\s|=|$)/);
    assert.match(detail[2], new RegExp(`<summary[^>]*>${label}(?:\\s|<)`));
    assert.ok(detail[2].includes(`<td>${code}</td>`));
    assert.ok(detail[2].includes(title));
  }
  assert.match(html, /<h4[^>]*>Elements in the selected model<\/h4><p[^>]*>—<\/p>/);
  assert.match(html, /Recorded evidence scope: RETAINED_ANALYSIS · INCOMPLETE/);
  assert.ok(html.includes(caveat));
  assert.match(html, /No obligations were evaluated; this is not a completeness claim\./);
  assert.match(html, /Search policy does not prove completeness\./);
  assert.doesNotMatch(html, /Open compatibility checks|Confirmed elements/);
});
test('empty retained element groups remain unavailable without fabricated evidence details', () => {
  const h = harness(true);
  const copilot = { schemaVersion: 2, state: 'COMPLETED', gaps: {}, patterns: {},
    recommendation: { matchedElements: [], acceptedElements: [], proposedElements: [], suggestedRelations: [] } };
  const html = h.renderAnalysisSummary({ analysis: { copilot } }).split('<details id="technicalSnapshotData"')[0];
  assert.doesNotMatch(html, /portfolio-finding-details/);
  for (const label of ['Assessment matches', 'Elements in the selected model', 'Proposed elements']) {
    assert.match(html, new RegExp(`<h4[^>]*>${label}</h4><p[^>]*>—</p>`));
  }
});
test('portfolio patterns preserve concrete evidence, conditional applicability and incomplete chains', () => {
  const h = harness(true);
  const copilot = { schemaVersion: 2, state: 'PARTIAL', gaps: {}, recommendation: {}, notes: [], patterns: { patternCoverage: 0,
    incompletePatterns: [{ patternName: 'Retained chain', completeness: 75, expectedSteps: ['BP REALIZES CP'],
      presentSteps: ['BP-fixture REALIZES CP-fixture [when approved]'], missingSteps: ['Applicability needs review.'] }] } };
  const html = h.renderAnalysisSummary({ analysis: { copilot } }).split('<details id="technicalSnapshotData"')[0];
  assert.match(html, /BP-fixture REALIZES CP-fixture \[when approved\]/);
  assert.match(html, /Applicability needs review/);
  assert.doesNotMatch(html, /is-missing[^>]*>[^<]*<span[^>]*>○<\/span>BP REALIZES CP/);
});

function retainedRecommendation(recommendation, language = 'en') {
  const h = harness(true, language);
  return h.renderAnalysisSummary({ analysis: { copilot: {
    schemaVersion: 2, state: 'COMPLETED',
    gaps: { profileId: 'compatibility', profileVersion: '1.0.0', judgments: [] },
    patterns: {}, recommendation, notes: ['Only retained evidence is available.']
  } }, recommendation: { matchedElements: [{ nodeCode: 'unrelated-current-candidate' }] } })
    .split('<details id="technicalSnapshotData"')[0];
}

function recommendationDetails(html) {
  return [...html.matchAll(/<details\b([^>]*)class="portfolio-finding-details"([^>]*)>([\s\S]*?)<\/details>/g)]
    .map(match => ({ attributes: match[1] + match[2], html: match[3] }));
}

for (const language of ['en', 'de']) test(`${language} retained recommendation categories expose their actual evidence in closed details`, () => {
  const categories = [
    { nodeCode: 'CR-assessed', title: 'Retained service', taxonomyRoot: 'CR', score: 90,
      reasoning: 'Retained assessment reason: recorded relevance.' },
    { nodeCode: 'model-system', title: 'Selected system', taxonomyRoot: 'SY', score: 0,
      reasoning: 'Member of canonical model at ' + 'a'.repeat(40) },
    { nodeCode: 'BP-candidate', title: 'Retained process', taxonomyRoot: 'BP', score: 60,
      reasoning: 'Retained assessment reason: review this candidate.' }
  ];
  const recommendation = { matchedElements: [categories[0]], acceptedElements: [categories[1]],
    proposedElements: [categories[2]], suggestedRelations: [], meanRelevance: 75 };
  const original = JSON.stringify(recommendation);
  const html = retainedRecommendation(recommendation, language);
  const details = recommendationDetails(html);
  assert.equal(details.length, 3, 'Every populated retained category needs its own bounded disclosure');
  const labels = language === 'de'
    ? ['Bewertungstreffer', 'Elemente im gewählten Modell', 'Vorgeschlagene Elemente']
    : ['Assessment matches', 'Elements in the selected model', 'Proposed elements'];
  for (let index = 0; index < categories.length; index++) {
    const detail = details[index], element = categories[index];
    assert.doesNotMatch(detail.attributes, /\bopen\b/);
    assert.ok(detail.html.includes('<summary>' + labels[index] + ' <span class="badge text-bg-secondary">1</span></summary>'));
    assert.ok(detail.html.includes('<caption class="visually-hidden">' + labels[index] + '</caption>'));
    const cells = [...detail.html.matchAll(/<td>([\s\S]*?)<\/td>/g)].map(match => match[1]);
    assert.deepEqual(cells, [element.nodeCode, element.title, element.taxonomyRoot, element.reasoning]);
    assert.deepEqual([...detail.html.matchAll(/<th scope="col">([^<]*)<\/th>/g)].map(match => match[1]),
      language === 'de' ? ['Knotencode', 'Titel', 'Taxonomiewurzel', 'Begründung']
        : ['Node code', 'Title', 'Taxonomy root', 'Reasoning']);
  }
  assert.doesNotMatch(html, /unrelated-current-candidate/);
  assert.doesNotMatch(details[1].html, /0%|confidence|Konfidenz/);
  assert.equal(JSON.stringify(recommendation), original, 'Rendering must not change retained evidence or membership');
});

test('an empty retained recommendation stays empty without adding findings or current candidates', () => {
  const html = retainedRecommendation({ matchedElements: [], acceptedElements: [], proposedElements: [], suggestedRelations: [] });
  assert.equal(recommendationDetails(html).length, 0);
  assert.match(html, /No obligations were evaluated; this is not a completeness claim/);
  assert.match(html, /Only retained evidence is available/);
  assert.doesNotMatch(html, /unrelated-current-candidate|<td>|0%/);
});

test('unavailable retained element fields stay unavailable in the disclosure', () => {
  const details = recommendationDetails(retainedRecommendation({ matchedElements: [{ nodeCode: 'CR-retained' }] }));
  assert.equal(details.length, 1);
  assert.deepEqual([...details[0].html.matchAll(/<td>([\s\S]*?)<\/td>/g)].map(match => match[1]),
    ['CR-retained', '—', '—', '—']);
  assert.doesNotMatch(details[0].html, /undefined|null|0%/);
});

test('retained recommendation identity, title, root and reasoning are escaped in real renderer output', () => {
  const details = recommendationDetails(retainedRecommendation({ matchedElements: [{
    nodeCode: '<img src=x onerror=alert(1)>', title: 'Title "quoted" & <script>bad()</script>',
    taxonomyRoot: '<svg onload=alert(1)>', reasoning: "Recorded <b>reason</b> & 'quotation'"
  }] }));
  assert.equal(details.length, 1);
  const html = details[0].html;
  for (const rawOpening of ['<img', '<script', '<svg', '<b>']) {
    assert.ok(!html.toLowerCase().includes(rawOpening), `Raw markup must not reach the rendered fragment: ${rawOpening}`);
  }
  assert.deepEqual([...html.matchAll(/<td>([\s\S]*?)<\/td>/g)].map(match => match[1]), [
    '&lt;img src=x onerror=alert(1)&gt;', 'Title &quot;quoted&quot; &amp; &lt;script&gt;bad()&lt;/script&gt;',
    '&lt;svg onload=alert(1)&gt;', 'Recorded &lt;b&gt;reason&lt;/b&gt; &amp; &#39;quotation&#39;'
  ]);
});
