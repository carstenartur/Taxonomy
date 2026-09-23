import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js', import.meta.url), 'utf8');
function harness(scoped) {
  const elements = new Map();
  const window = { location: { pathname: '/projects/1/requirements/2', search: '' }, TaxonomyUtils: { escapeHtml: value => String(value ?? '') } };
  const document = { documentElement: { lang: 'en' }, addEventListener() {}, getElementById(id) { if (!elements.has(id)) elements.set(id, {}); return elements.get(id); } };
  // Expose private rendering functions in the test closure; execute their unchanged production bodies.
  const instrumented = source.replace(/\}\)\(\);\s*$/, 'window.probe = { state, renderMappings, renderRelations }; })();');
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
