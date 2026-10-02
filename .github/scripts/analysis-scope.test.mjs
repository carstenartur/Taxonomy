import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync, readFileSync} from 'node:fs';
import vm from 'node:vm';

const path = new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-scope.js', import.meta.url);
function harness() {
    const nodes = new Map(), listeners = new Map();
    function node(tag = 'div') {
        return {tagName: tag, children: [], checked: false, value: '', dataset: {},
            append(...children) {this.children.push(...children);},
            replaceChildren(...children) {this.children = children;},
            querySelectorAll() {return this.children.flatMap(child => child.tagName === 'input' ? [child] : child.querySelectorAll());},
            setAttribute() {}, addEventListener() {}, classList: {toggle() {}}};
    }
    nodes.set('analysisTaxonomyRoots', node());
    nodes.set('analysisMode', Object.assign(node('select'), {value: 'FULL'}));
    nodes.set('includeArchitectureView', Object.assign(node('input'), {checked: true}));
    nodes.set('analysisScopeEvidence', node());
    const document = {getElementById: id => nodes.get(id), createElement: node,
        documentElement: {lang: 'en'}, addEventListener: (name, fn) => listeners.set(name, fn)};
    const S = {taxonomyData: [{code: 'BP', nameEn: 'Processes'}, {code: 'CP', nameEn: 'Capabilities'}]};
    const window = {TaxonomyState: S};
    if (existsSync(path)) vm.runInNewContext(readFileSync(path, 'utf8'), {window, document, TaxonomyI18n: {t: key => key}});
    const scope = window.TaxonomyAnalysisScope;
    assert.ok(scope, 'scope control module must be available');
    scope.render(S.taxonomyData);
    return {scope, S, nodes, window, document, change(target) {listeners.get('change')?.({target});},
        inputs: () => nodes.get('analysisTaxonomyRoots').querySelectorAll()};
}
const copy = value => JSON.parse(JSON.stringify(value));

test('all roots default to legacy full analysis and selection produces a canonical scope', () => {
    const h = harness();
    assert.deepEqual(copy(h.scope.read()), {taxonomyRoots: [], mode: 'FULL'});
    h.inputs()[1].checked = false;
    h.change(h.inputs()[1]);
    h.nodes.get('analysisMode').value = 'TAXONOMIES_ONLY';
    h.change(h.nodes.get('analysisMode'));
    assert.deepEqual(copy(h.scope.read()), {taxonomyRoots: ['BP'], mode: 'TAXONOMIES_ONLY'});
    assert.equal(h.nodes.get('includeArchitectureView').disabled, true);
});

test('empty selection survives draft option restore without becoming all roots', () => {
    const h = harness();
    h.inputs().forEach(input => {input.checked = false; h.change(input);});
    const saved = copy(h.scope.options());
    const restored = harness();
    restored.scope.restoreOptions(saved);
    assert.throws(() => restored.scope.read(), /analysis.scope.select.one/);
});

test('restored selection survives catalogue rendering and stays separate from evidence scope', () => {
    const h = harness();
    h.scope.restoreOptions({taxonomySelection: ['CP'], analysisMode: 'TAXONOMIES_ONLY'});
    h.scope.render(h.S.taxonomyData);
    h.scope.acceptResult({taxonomyRoots: ['BP'], mode: 'FULL'});
    assert.deepEqual(copy(h.scope.read()), {taxonomyRoots: ['CP'], mode: 'TAXONOMIES_ONLY'});
    assert.deepEqual(copy(h.S.lastAnalysisScope), {taxonomyRoots: ['BP'], mode: 'FULL'});
    assert.equal(h.scope.restrictsGlobalAnalysis(h.S.lastAnalysisScope), true);
    assert.equal(h.scope.restrictsGlobalAnalysis({taxonomyRoots: ['CP', 'BP'], mode: 'FULL'}), false);
});

test('a missing previously selected root is not silently replaced with all taxonomies', () => {
    const h = harness();
    h.scope.restoreOptions({taxonomySelection: ['REMOVED'], analysisMode: 'FULL'});
    assert.throws(() => h.scope.read(), /analysis.scope.select.one/);
});

for (const action of ['enterManualScoringMode', 'applyManualScores']) {
    test(`${action} replaces frozen scope while retaining next-run selection`, () => {
        const h = harness();
        h.scope.restoreOptions({taxonomySelection: ['CP'], analysisMode: 'TAXONOMIES_ONLY'});
        h.scope.acceptResult({taxonomyRoots: ['BP'], mode: 'TAXONOMIES_ONLY'});
        const nextOptions = copy(h.scope.options());
        h.S.taxonomyData = [];
        h.nodes.set('businessText', {value: 'New manual requirement'});
        h.nodes.set('statusArea', {dataset: {}, parentNode: {insertBefore() {}}});
        h.document.querySelectorAll = selector => selector === '.manual-score-input'
            ? [{value: '80', dataset: {code: 'CP'}, remove() {}}] : [];
        h.window.TaxonomyScoring = {applyLocalRawScores: scores => {h.S.currentScores = scores;}};
        const browse = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js', import.meta.url), 'utf8');
        // Expose the existing event handlers inside this VM; production code receives no test hooks.
        vm.runInNewContext(browse.replace('renderView: renderView,',
            'renderView: renderView, enterManualScoringMode, applyManualScores,'),
        {window: h.window, document: h.document, TaxonomyI18n: {t: key => key},
            TaxonomyUtils: {escapeHtml: value => String(value)}});
        h.window.TaxonomyBrowse[action]();
        assert.equal(h.S.lastAnalysisScope, null);
        assert.equal(h.nodes.get('analysisScopeEvidence').hidden, true);
        assert.deepEqual(copy(h.scope.options()), nextOptions);
        assert.equal(h.scope.restrictsGlobalAnalysis(h.S.lastAnalysisScope), false);
    });
}
