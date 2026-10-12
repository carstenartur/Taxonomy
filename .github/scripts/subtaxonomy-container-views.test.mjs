import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const script = name => readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/' + name, import.meta.url), 'utf8');
const window = {};
const context = {window, document: {readyState: 'loading', addEventListener() {}}, TaxonomyI18n: {t: (key, value) => key === 'browse.container.relevance' ? `Area relevance: ${value}%` : key}};
vm.runInNewContext(script('shared/taxonomy-utils.js'), context);
// Inspect the private selection helper in this VM; no test hook is shipped to browsers.
vm.runInNewContext(script('core/taxonomy-views.js').replace('window.TaxonomyViews = {',
    'window.decisionMatchesForTest = typeof decisionMatches === "function" ? decisionMatches : null; window.TaxonomyViews = {'), context);

// Existing source identities; values are deterministic presentation fixtures, not model evidence.
const original = Object.freeze({code: 'BP-1000', taxonomyRoot: 'BP', level: 1, parentCode: 'BP',
    name: 'Business Processes', description: 'Original description', children: Object.freeze([])});
const catalogue = Object.freeze([
    Object.freeze({code: 'BP', taxonomyRoot: 'BP', level: 0, parentCode: null, taxonomyContainer: true,
        name: 'Business Processes', children: Object.freeze([original])}),
    Object.freeze({code: 'BR', taxonomyRoot: 'BR', level: 0, parentCode: null, taxonomyContainer: true,
        name: 'Business Roles', children: Object.freeze([])})
]);
const scores = Object.freeze({BP: 95, BR: 90, 'BP-1000': 70, 'unknown-snapshot-entry': 100});

test('DOT represents subtaxonomies as groups while retaining the original first node', () => {
    const output = window.TaxonomyViews.buildDotExport(catalogue, scores);
    assert.match(output, /subgraph "cluster_BP"/);
    assert.match(output, /label="BP.*Business Processes/);
    assert.match(output, /"BP-1000" \[label=/);
    assert.doesNotMatch(output, /^\s*"(?:BP|BR)" (?:\[|->)/m);
    assert.equal(catalogue[0].children[0], original);
    assert.equal(original.parentCode, 'BP');
});

test('Mermaid represents empty and populated areas as labelled groups, not duplicate nodes', () => {
    const output = window.TaxonomyViews.buildMermaidTreeExport(catalogue, scores);
    assert.match(output, /subgraph cluster_BP\["BP.*Business Processes/);
    assert.match(output, /subgraph cluster_BR\["BR.*Business Roles/);
    assert.match(output, /subgraph cluster_BP\["[^\n]*Area relevance: 95%/);
    assert.match(output, /BP_1000\["BP-1000 Business Processes/);
    assert.doesNotMatch(output, /^\s*(?:BP|BR)\[/m);
    assert.doesNotMatch(output, /(?:BP|BR)\[.*-->/);
});

test('ranking excludes containers and unmatched score keys but preserves original root entries', () => {
    assert.equal(typeof window.decisionMatchesForTest, 'function', 'The decision selection must use the catalogue roles');
    const result = window.decisionMatchesForTest(catalogue, scores);
    assert.deepEqual(JSON.parse(JSON.stringify(result)), [['BP-1000', 70]]);
    assert.deepEqual(scores, {BP: 95, BR: 90, 'BP-1000': 70, 'unknown-snapshot-entry': 100});
});

test('an original top-level entry is retained even when its name and code look like a container', () => {
    const entries = [{code: 'BP', taxonomyContainer: false, taxonomyRoot: 'BP', level: 0,
        name: 'Business Processes', children: []}];
    assert.match(window.TaxonomyViews.buildDotExport(entries, {}), /"BP" \[label=/);
    assert.match(window.TaxonomyViews.buildMermaidTreeExport(entries, {}), /^\s*BP\[/m);
});

test('CSV keeps area evidence with explicit metadata and ranks only known catalogue entries', async () => {
    const downloads = [];
    const exportWindow = {TaxonomyUtils: window.TaxonomyUtils, setTimeout() {}};
    vm.runInNewContext(script('shared/taxonomy-export.js'), {
        window: exportWindow, TaxonomyI18n: context.TaxonomyI18n, Blob,
        document: {readyState: 'loading', addEventListener() {}, body: {appendChild() {}},
            createElement: () => ({click() {}, remove() {}})},
        URL: {createObjectURL: blob => {downloads.push(blob); return 'blob:fixture';}, revokeObjectURL() {}}
    });
    exportWindow.TaxonomyExport.exportCsv(scores, catalogue);
    const lines = (await downloads[0].text()).replace(/^\uFEFF/, '').split('\r\n').map(line => line.split(','));
    const column = name => lines[0].indexOf(name);
    assert.ok(column('TaxonomyContainer') >= 0, 'Evidence must distinguish area relevance from catalogue relevance');
    const row = code => lines.slice(1).find(values => values[column('Code')] === code);
    assert.equal(row('BP')[column('Rank')], '');
    assert.equal(row('BP')[column('TaxonomyContainer')], 'true');
    assert.equal(row('BP')[column('Score')], '95');
    assert.equal(row('BP-1000')[column('Rank')], '1');
    assert.equal(row('BP-1000')[column('TaxonomyContainer')], 'false');
    assert.equal(row('unknown-snapshot-entry')[column('Rank')], '');
    assert.equal(row('unknown-snapshot-entry')[column('TaxonomyContainer')], '');
    assert.equal(original.parentCode, 'BP');
    assert.equal(scores.BP, 95);
});
