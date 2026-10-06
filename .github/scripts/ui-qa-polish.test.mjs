import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const source = await readFile(new URL('static/js/architecture-workbench.js', resources), 'utf8');
const start = source.indexOf('    function updateKpis() {');
const end = source.indexOf('    function updateModeButtons()', start);
assert.ok(start >= 0 && end > start, 'exercise the production KPI update handler');
const updateKpis = source.slice(start, end);

for (const [locale, suffix, expected] of [
    ['de', '_de', { singular: ['Element', 'Beziehung', 'Ebene'], plural: ['Elemente', 'Beziehungen', 'Ebenen'] }],
    ['en', '', { singular: ['Element', 'Relationship', 'Layer'], plural: ['elements', 'relationships', 'layers'] }]
]) {
    const bundle = await readFile(new URL(`i18n/messages${suffix}.properties`, resources), 'utf8');
    const messages = Object.fromEntries(bundle.split(/\r?\n/)
        .filter(line => line.startsWith('workbench.ui.'))
        .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
    for (const count of [0, 1, 2, 11]) {
        test(`${locale}: Workbench quantities use the correct form for ${count}`, () => {
            const elements = new Map(['directKpi', 'elementKpi', 'relationKpi', 'layerKpi']
                .map(id => [id, { textContent: '' }]));
            const state = {
                visibleNodes: Array.from({ length: count }, (_, index) => ({ anchor: index === 0 })),
                visibleEdges: Array.from({ length: count }, () => ({})),
                layerGroups: Array.from({ length: count }, () => ({}))
            };
            const context = vm.createContext({
                state,
                document: { getElementById: id => elements.get(id) },
                t: key => { assert.ok(key in messages, `missing ${locale} translation: ${key}`); return messages[key]; }
            });
            vm.runInContext(updateKpis + '\nupdateKpis();', context);
            const forms = count === 1 ? expected.singular : expected.plural;
            ['elementKpi', 'relationKpi', 'layerKpi'].forEach((id, index) => {
                assert.equal(elements.get(id).textContent, `${count} ${forms[index]}`);
            });
            assert.equal(elements.get('directKpi').textContent, `${count > 0 ? 1 : 0} ${messages['workbench.ui.direct']}`);
            // A later empty view must not retain the previous count or singular form.
            state.visibleNodes = []; state.visibleEdges = []; state.layerGroups = [];
            vm.runInContext('updateKpis();', context);
            ['elementKpi', 'relationKpi', 'layerKpi'].forEach((id, index) => {
                assert.equal(elements.get(id).textContent, `0 ${expected.plural[index]}`);
            });
        });
    }
}

const workflowCss = await readFile(new URL('static/css/taxonomy-analysis-workflow.css', resources), 'utf8');
test('short viewports let workflow stages reflow instead of forcing four tiny columns', () => {
    const compact = workflowCss.slice(workflowCss.indexOf('@media (max-height: 32rem)'),
        workflowCss.indexOf('@media (max-height: 18rem)'));
    assert.match(compact, /grid-template-columns:\s*repeat\(auto-fit,\s*minmax\(min\(100%,\s*8rem\),\s*1fr\)\)/);
    assert.doesNotMatch(compact, /grid-template-columns:\s*repeat\(4,/);
});
test('wide viewports also respect the actual width of the analysis column', () => {
    assert.match(workflowCss, /@media\s*\(min-width:\s*641px\)[\s\S]*?\.analysis-task-stages\s*\{\s*grid-template-columns:\s*repeat\(auto-fit,\s*minmax\(min\(100%,\s*9rem\),\s*1fr\)\)/);
});
