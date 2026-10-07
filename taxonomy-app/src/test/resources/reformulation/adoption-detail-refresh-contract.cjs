'use strict';
const assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm');
const text = fs.readFileSync(process.argv[2], 'utf8');

function section(name, followingName) {
    const start = new RegExp(`^    (?:async )?function ${name}\\(`, 'm').exec(text);
    assert.ok(start, `Production function ${name} must exist`);
    const end = text.indexOf(`\n    function ${followingName}(`, start.index);
    assert.ok(end > start.index, `Production boundary ${followingName} must follow ${name}`);
    return text.slice(start.index, end);
}

const fields = new Map([
    ['versionText', {value: 'unsaved text'}],
    ['changeReason', {value: 'unsaved reason'}],
    ['sourceSection', {value: 'unsaved section'}],
    ['sourcePage', {value: '9'}],
    ['sourceOriginal', {value: 'unsaved source'}]
]);
let renders = 0, renderedCurrentText = '';
const elements = new Map([
    ...fields,
    ['currentText', {
        get textContent() { return renderedCurrentText; },
        set textContent(value) { renderedCurrentText = value; renders++; }
    }],
    ['sourceMetadata', {textContent: ''}],
    ['sourceText', {textContent: ''}],
    ['newVersionModal', {classList: {contains: () => false}}],
    ...['portfolioBack', 'matrixLink', 'requirementKey', 'requirementStatus',
        'requirementReview', 'requirementHeading', 'requirementMeta'].map(id => [id, {}])
]);
const pending = [];
const state = {busy: 0, readGeneration: 0, versionFormDirty: false};
const api = () => ({
    getProject: async () => ({id: 1}),
    getRequirement: () => new Promise(resolve => pending.push(resolve)),
    listRequirementVersions: async () => [], listRequirementSnapshots: async () => [],
    getProjectPortfolio: async () => ({}), getAccount: async () => ({})
});
const context = vm.createContext({
    state, projectId: 1, requirementId: 2, locale: 'en', api,
    document: {
        getElementById: id => {
            assert.ok(elements.has(id), `Expected detail element ${id}`);
            return elements.get(id);
        }
    },
    setBusy: flag => { state.busy += flag ? 1 : -1; },
    showError: error => { throw error; },
    t: key => key,
    addDefinition: (target, term, value) => { target[term] = value; },
    applicationUrl: path => path,
    humanize: value => value,
    renderVersions: () => {}, renderSnapshots: () => {}, renderTasks: () => {},
    renderSolutions: () => {}, applyCapabilities: () => {}
});

// Exercise the real loadAll -> renderAll -> renderCurrentText chain. Only
// independent panels are stubbed, so losing the preserve flag at either
// production boundary must fail this existing JUnit-owned unit contract.
vm.runInContext(section('loadAll', 'renderAll') + '\n'
    + section('renderAll', 'renderCurrentText') + '\n'
    + section('renderCurrentText', 'renderVersions'), context);

function requirement(id, value) {
    return {currentVersion: {id, text: value, source: {
        sectionReference: 'persisted section', pageNumber: 4, originalText: 'persisted source'
    }}};
}

(async () => {
    const first = vm.runInContext('loadAll(true)', context);
    fields.get('versionText').value = 'newer unsaved edit while loading';
    pending.shift()(requirement(7, 'adopted')); await first;
    assert.equal(fields.get('versionText').value, 'newer unsaved edit while loading',
        'Read-only adoption refresh erased unsaved form input');
    assert.equal(fields.get('changeReason').value, 'unsaved reason');
    assert.equal(fields.get('sourceSection').value, 'unsaved section');
    assert.equal(fields.get('sourcePage').value, '9');
    assert.equal(fields.get('sourceOriginal').value, 'unsaved source');
    assert.equal(elements.get('currentText').textContent, 'adopted');
    assert.equal(state.requirement.currentVersion.id, 7);

    const old = vm.runInContext('loadAll(true)', context), oldResolve = pending.shift();
    const next = vm.runInContext('loadAll(true)', context), nextResolve = pending.shift();
    nextResolve(requirement(9, 'newer response')); await next;
    oldResolve(requirement(8, 'late older response')); await old;
    assert.equal(state.requirement.currentVersion.id, 9, 'Older refresh overwrote newer version');
    assert.equal(elements.get('currentText').textContent, 'newer response');
    assert.equal(renders, 2);
    assert.equal(state.busy, 0);

    const clean = vm.runInContext('loadAll(false)', context);
    pending.shift()(requirement(10, 'fresh persisted baseline')); await clean;
    assert.equal(fields.get('versionText').value, 'fresh persisted baseline',
        'An ordinary clean load must still initialize the form from its persisted version');
    assert.equal(fields.get('sourceSection').value, 'persisted section');
    assert.equal(fields.get('sourcePage').value, 4);
    assert.equal(fields.get('sourceOriginal').value, 'persisted source');
    assert.equal(renders, 3);
    assert.equal(state.busy, 0);
    assert.match(text, /refreshAfterAdoption:\s*\(\)\s*=>\s*loadAll\(true\)/,
        'Read-only refresh must be exposed to adoption');
    console.log('REFORMULATION_ADOPTION_DETAIL_REFRESH_OK');
})().catch(error => { console.error(error); process.exitCode = 1; });
