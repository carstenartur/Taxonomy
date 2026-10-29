import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const portfolioSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/taxonomy-portfolio.js', import.meta.url), 'utf8');
const guidedSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/portfolio-guided-decisions.js', import.meta.url), 'utf8');

function harness(locale) {
  const elements = new Map();
  const element = () => ({ textContent: '', innerHTML: '', dataset: {}, children: [],
    classList: { remove() {} }, appendChild(child) { this.children.push(child); } });
  const document = { documentElement: { lang: locale }, addEventListener() {}, createElement: element,
    getElementById(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); } };
  const window = { location: { search: '' }, TaxonomyI18n: { formatEnum: value => value },
    TaxonomyUtils: { escapeHtml: value => String(value ?? '') } };
  const bootstrap = { Modal: { getOrCreateInstance: () => ({ show() {} }) } };
  vm.runInNewContext(portfolioSource.replace(/\}\)\(\);\s*$/, 'window.renderConflicts = renderConflicts; })();'),
    { document, window, URLSearchParams, Intl });
  vm.runInNewContext(guidedSource.replace(/\}\(\)\);\s*$/, 'window.interceptConflictReview = interceptConflictReview; }());'),
    { document, window, URLSearchParams, bootstrap });
  return { window, elements, document };
}

function render(h, confidence = 0.88, confidenceBasis = 'UNCALIBRATED_RULE_WEIGHT') {
  h.window.renderConflicts([{ id: 1, confidence, confidenceBasis, status: 'PROPOSED',
    conflictType: 'HOSTING', title: 'Hosting constraints', requirementAKey: 'CLOUD',
    requirementBKey: 'LOCAL', evidence: 'Read the original requirements.' }]);
  return h.elements.get('conflictsList').children[0].innerHTML;
}

for (const locale of ['en', 'de']) {
  test(`${locale} conflict review shows a rule weight and its limitation instead of a probability`, () => {
    const html = render(harness(locale));
    assert.match(html, locale === 'de' ? /Regelgewicht/ : /Rule weight/);
    assert.match(html, /88\s*\/\s*100/);
    assert.match(html, locale === 'de' ? /keine gemessene Wahrscheinlichkeit/ : /not a measured probability/);
    assert.doesNotMatch(html, /88\s*%|Konfidenz|Confidence/);
    assert.match(html, /PROPOSED/);
  });
}

test('explicit zero is retained as a rule weight', () => {
  assert.match(render(harness('en'), 0), />0\s*\/\s*100</);
});

for (const invalid of [null, undefined, NaN, Infinity, -0.1, 1.1, '0.88']) {
  test(`invalid or missing conflict weight (${String(invalid)}) is not synthesized as zero`, () => {
    const h = harness('en');
    // Pass explicitly: an absent field is distinct from render helper's default.
    h.window.renderConflicts([{ id: 1, confidence: invalid, confidenceBasis: 'UNCALIBRATED_RULE_WEIGHT' }]);
    const html = h.elements.get('conflictsList').children[0].innerHTML;
    assert.match(html, /—/);
    assert.doesNotMatch(html, /[\d.]\s*\/\s*100|NaN|Infinity/);
  });
}

test('a score without known provenance remains unavailable', () => {
  const html = render(harness('en'), 0.88, 'UNRECORDED');
  assert.doesNotMatch(html, /88\s*\/\s*100|88\s*%/);
  assert.match(html, /—/);
});

test('review dialog retains the exact assessment text independently of translated labels', () => {
  const h = harness('de');
  const assessment = 'Regelgewicht 88 / 100 — keine gemessene Wahrscheinlichkeit';
  const card = { querySelectorAll: selector => selector === 'code' ? [{ textContent: 'CLOUD' }, { textContent: 'LOCAL' }] : [],
    querySelector: selector => selector === '.conflict-assessment' ? { textContent: assessment }
      : selector === '.portfolio-evidence' ? { textContent: 'Original source evidence' }
      : { textContent: 'Hosting constraints' } };
  const button = { dataset: { status: 'CONFIRMED' }, closest: () => card };
  h.window.interceptConflictReview({ target: { closest: () => button }, preventDefault() {}, stopImmediatePropagation() {} });
  assert.equal(h.elements.get('guidedConflictConfidence').textContent, assessment);
  assert.equal(h.elements.get('guidedConflictEvidence').textContent, 'Original source evidence');
  assert.equal(h.elements.get('guidedConflictDecision').value, 'CONFIRMED');
});
