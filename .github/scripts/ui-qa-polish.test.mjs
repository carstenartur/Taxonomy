import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const [source, sharedSource] = await Promise.all([
    readFile(new URL('static/js/architecture-workbench.js', resources), 'utf8'),
    readFile(new URL('static/js/shared/architecture-diagram.js', resources), 'utf8')
]);

// Mount the production adapter and observe its real shared-controller callback.
// Drawing is outside this localization contract; preparation and layout stay real.
async function workbench(messages, count) {
    const elements = new Map();
    function element() {
        return { textContent: '', value: '', dataset: {}, style: {},
            classList: { add() {}, remove() {}, toggle() {} },
            addEventListener() {}, setAttribute() {}, replaceChildren() {}, append() {} };
    }
    const root = Object.assign(element(), { querySelector(selector) {
        if (selector === '#architectureExportOptions') return null;
        const id = selector.slice(1);
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
    } });
    const nodes = Array.from({ length: count }, (_, index) => ({
        id: 'N' + index, label: 'Original source ' + index, type: 'Layer ' + index, layer: index,
        anchor: index === 0, relevance: 0.9, x: 100 + index * 300, y: 100, width: 230, height: 86
    }));
    const edges = Array.from({ length: count }, (_, index) => ({
        id: 'E' + index, sourceId: 'N' + index, targetId: 'N' + ((index + 1) % count),
        relationType: 'SUPPORTS', sourceX: 330 + index * 300, sourceY: 143,
        targetX: 100 + ((index + 1) % count) * 300, targetY: 143
    }));
    const context = vm.createContext({
        document: { getElementById: () => null, addEventListener() {}, dispatchEvent() {}, createElement: element },
        window: { TaxonomyI18n: {
            t: key => { assert.ok(key in messages, `missing translation: ${key}`); return messages[key]; },
            ready: () => Promise.resolve(), getLocale: () => 'en', formatEnum: value => value || ''
        } },
        ArchitectureWorkbenchApi: { load: async () => ({
            projectId: '1', snapshotId: 'quantity-test', elements: {}, relations: {}, warnings: [],
            scene: { title: 'workbench.ui.elements', width: 1000, height: 420, nodes, edges }
        }) },
        AbortController, CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options?.detail; } }
    });
    vm.runInContext(sharedSource, context);
    let publish;
    context.window.TaxonomyArchitectureDiagram.create = (_svg, options) => {
        let state;
        const common = context.window.TaxonomyArchitectureDiagram;
        publish = scene => {
            const visible = common.visibleModel(scene, state);
            const model = Object.assign({}, visible, options.layout(visible, state));
            options.onChange({ state, model, matches: [] });
        };
        return { render(scene, initialState) { state = initialState; publish(scene); },
            getState: () => ({ ...state }), fit() {}, captureViewport: () => ({ x: 0, y: 0, k: 1 }) };
    };
    vm.runInContext(source, context);
    const adapter = context.window.TaxonomyArchitectureSnapshot.mount(root);
    assert.equal(await adapter.activate({ projectId: '1', snapshotId: 'quantity-test' }), true,
        elements.get('architectureAlert')?.textContent);
    return { elements, empty() { publish({ nodes: [], edges: [] }); } };
}

for (const [locale, suffix, expected] of [
    ['de', '_de', { singular: ['Element', 'Beziehung', 'Ebene'], plural: ['Elemente', 'Beziehungen', 'Ebenen'] }],
    ['en', '', { singular: ['Element', 'Relationship', 'Layer'], plural: ['elements', 'relationships', 'layers'] }]
]) {
    const bundle = await readFile(new URL(`i18n/messages${suffix}.properties`, resources), 'utf8');
    const messages = Object.fromEntries(bundle.split(/\r?\n/)
        .filter(line => line.startsWith('workbench.ui.'))
        .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
    for (const count of [0, 1, 2, 11]) {
        test(`${locale}: Workbench quantities use the correct form for ${count}`, async () => {
            const mounted = await workbench(messages, count);
            const { elements } = mounted;
            const forms = count === 1 ? expected.singular : expected.plural;
            ['elementKpi', 'relationKpi', 'layerKpi'].forEach((id, index) => {
                assert.equal(elements.get(id).textContent, `${count} ${forms[index]}`);
            });
            assert.equal(elements.get('directKpi').textContent, `${count > 0 ? 1 : 0} ${messages['workbench.ui.direct']}`);
            // A later empty view must not retain the previous count or singular form.
            mounted.empty();
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
