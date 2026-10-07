import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const source = path => readFileSync(new URL('static/js/' + path, resources), 'utf8');
const versionFields = ['versionText', 'changeReason', 'sourceSection', 'sourcePage', 'sourceOriginal'];
const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
};

function fixture(name, expose, locale = 'en') {
    const nodes = new Map();
    const downloads = [];
    let activeElement = null;
    function element(tagName = 'div') {
        const listeners = new Map();
        const attributes = new Map();
        let value = '', text = '';
        const node = {tagName, children: [], dataset: {}, className: '', innerHTML: '', valueWrites: 0,
            addEventListener(type, listener) { listeners.set(type, listener); },
            fire(type, event = {}) { return listeners.get(type)?.(event); },
            appendChild(child) { this.children.push(child); return child; },
            append(...children) { children.forEach(child => this.appendChild(child)); },
            setAttribute(name, value) { attributes.set(name, String(value)); },
            getAttribute(name) { return attributes.get(name) ?? null; },
            removeAttribute(name) { attributes.delete(name); },
            querySelector() { return null; },
            closest(selector) { return selector.startsWith('.') && this.classList.contains(selector.slice(1)) ? this : null; },
            querySelectorAll(selector) {
                return selector === '[data-version-id]' ? this.children.filter(child => child.dataset.versionId != null) : [];
            },
            focus() { activeElement = this; }, remove() {},
            click() { if (tagName === 'a' && downloads.length) downloads.at(-1).filename = this.download; }
        };
        node.classList = {
            contains(name) { return node.className.split(/\s+/).includes(name); },
            toggle(name, force) {
                const classes = new Set(node.className.split(/\s+/).filter(Boolean));
                if (force ?? !classes.has(name)) classes.add(name); else classes.delete(name);
                node.className = [...classes].join(' ');
            },
            add(...names) { names.forEach(name => this.toggle(name, true)); },
            remove(...names) { names.forEach(name => this.toggle(name, false)); }
        };
        Object.defineProperties(node, {
            value: {get: () => value, set(next) {
                value = String(next); this.valueWrites++;
                this.selectionStart = this.selectionEnd = value.length;
            }},
            textContent: {get: () => text, set(next) { text = String(next); this.children = []; this.innerHTML = ''; }}
        });
        return node;
    }
    const get = id => { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); };
    const document = {documentElement: {lang: locale}, readyState: 'loading',
        getElementById: get, createElement: element, body: element('body'), addEventListener() {},
        querySelector: selector => get(selector),
        querySelectorAll: selector => selector === '#newVersionForm input, #newVersionForm textarea'
            ? versionFields.map(get) : [],
        get activeElement() { return activeElement; }
    };
    class BrowserURL extends URL {
        static createObjectURL(blob) { downloads.push({blob}); return 'blob:review-' + downloads.length; }
        static revokeObjectURL() {}
    }
    const data = {project: {id: 1, projectKey: 'PROJECT', title: 'Control system'},
        requirement: {id: 2, requirementKey: 'REQ-2', title: 'Pump control',
            currentVersion: {id: 3, versionNumber: 1, text: 'Stored text', source: {
                sourceArtifactId: 'artifact-1', sourceVersionId: 'source-v1',
                sectionReference: 'Stored section', pageNumber: 4, originalText: 'Stored source'}}}};
    const api = {
        getProject: async () => data.project,
        getRequirement: async () => data.requirement,
        listRequirementVersions: async () => [],
        listRequirementSnapshots: async () => [],
        getProjectPortfolio: async () => ({requirements: [data.requirement], solutions: []}),
        getAccount: async () => ({architectureMutationAllowed: true}),
        getAnalysisJob: async () => ({id: 'job-1', status: 'SUCCESS', items: []})
    };
    const context = vm.createContext({document, URL: BrowserURL, URLSearchParams, Blob, console,
        location: new URL(`https://taxonomy.example.test/projects/1/${name === 'requirement-detail' ? 'requirements/2' : 'matrices'}?lang=${locale}`),
        TaxonomyI18n: {formatEnum: value => String(value || ''), ready: async () => {}},
        TaxonomyPortfolioApi: api,
        bootstrap: {
            Modal: {getOrCreateInstance: node => ({hide() { node.classList.remove('show'); }})},
            Offcanvas: {getOrCreateInstance: () => ({show() {}})}
        },
        setTimeout(callback) { callback(); }, clearTimeout() {}
    });
    context.window = context;
    for (const id of ['detailError', 'newVersionError']) get(id).classList.add('d-none');
    get('relationState').value = 'all';
    get('minimumValue').value = '0';
    vm.runInContext(source('shared/taxonomy-utils.js'), context);
    const script = source('portfolio/' + name + '.js').replace(/\}\)\(\);\s*$/, `window.review = {${expose}}; })();`);
    vm.runInContext(script, context, {filename: name + '.js'});
    return {get, document, context, api, data, downloads, ...context.review};
}

async function requirementFixture() {
    const app = fixture('requirement-detail', 'state, loadAll, wireEvents, createVersion, pollAnalysis');
    app.wireEvents();
    await app.loadAll();
    return app;
}

function editVersion(app) {
    const values = ['Draft text\nKeep this edit', 'Reviewed interlock sequence', 'Draft section', '9', 'Draft source'];
    versionFields.forEach((id, index) => { app.get(id).value = values[index]; });
    app.get('versionText').selectionStart = 3;
    app.get('versionText').selectionEnd = 8;
    app.get('newVersionForm').fire('input', {target: app.get('versionText')});
    return values;
}

test('new-version feedback belongs inside the open modal form and can receive focus', () => {
    const template = readFileSync(new URL('templates/requirement-detail.html', resources), 'utf8');
    const form = template.slice(template.indexOf('<form id="newVersionForm"'), template.indexOf('</form>', template.indexOf('<form id="newVersionForm"')));
    assert.ok(/id="newVersionError"[^>]*role="alert"[^>]*tabindex="-1"/.test(form), 'error alert must be inside the modal focus trap');
});

for (const status of [400, 409]) {
    test(`new-version HTTP ${status} keeps the draft and exposes its error in the modal`, async () => {
        const app = await requirementFixture();
        app.get('newVersionModal').classList.add('show');
        const draft = editVersion(app);
        const message = status === 409 ? 'This requirement changed. Review the new version.' : 'Change reason is required.';
        app.api.createRequirementVersion = async () => { throw Object.assign(new Error(message), {status}); };
        await app.createVersion({preventDefault() {}});
        assert.equal(app.get('newVersionError').textContent, message);
        assert.equal(app.get('newVersionError').classList.contains('d-none'), false);
        assert.equal(app.document.activeElement, app.get('newVersionError'));
        assert.equal(app.get('newVersionModal').classList.contains('show'), true);
        assert.equal(app.get('detailError').textContent, '', 'the error must not be hidden behind the modal');
        assert.deepEqual(versionFields.map(id => app.get(id).value), draft);
        assert.equal(app.get('detailBusy').classList.contains('d-none'), true);
    });
}

test('a new-version retry clears the old modal error while retaining the submitted fields', async () => {
    const app = await requirementFixture();
    app.get('newVersionModal').classList.add('show');
    const draft = editVersion(app);
    app.api.createRequirementVersion = async () => { throw new Error('First attempt failed'); };
    await app.createVersion({preventDefault() {}});
    // Represent a previously visible error independently of how the first attempt reported it.
    app.get('newVersionError').textContent = 'First attempt failed';
    app.get('newVersionError').classList.remove('d-none');
    const pending = deferred();
    app.api.createRequirementVersion = () => pending.promise;
    const retry = app.createVersion({preventDefault() {}});
    assert.equal(app.get('newVersionError').textContent, '');
    assert.equal(app.get('newVersionError').classList.contains('d-none'), true);
    assert.deepEqual(versionFields.map(id => app.get(id).value), draft);
    pending.reject(new Error('Retry failed'));
    await retry;
    assert.equal(app.get('newVersionError').textContent, 'Retry failed');
});

test('terminal background analysis preserves the open draft, caret and source fields', async () => {
    const app = await requirementFixture();
    app.get('newVersionModal').classList.add('show');
    const draft = editVersion(app);
    const writes = app.get('versionText').valueWrites;
    app.data.requirement = {...app.data.requirement, title: 'Refreshed requirement',
        currentVersion: {...app.data.requirement.currentVersion, text: 'New persisted baseline'}};
    await app.pollAnalysis('job-1');
    assert.deepEqual(versionFields.map(id => app.get(id).value), draft);
    assert.equal(app.get('versionText').valueWrites, writes, 'background rendering must not rewrite an actively edited textarea');
    assert.equal(app.get('versionText').selectionStart, 3);
    assert.equal(app.get('versionText').selectionEnd, 8);
    assert.equal(app.get('currentText').textContent, 'New persisted baseline', 'the displayed authoritative version still refreshes');
    assert.equal(app.get('requirementHeading').textContent, 'Refreshed requirement');
});

test('a draft opened and edited during the background read survives the late response', async () => {
    const app = await requirementFixture();
    const pending = deferred(), started = deferred();
    app.api.getRequirement = () => { started.resolve(); return pending.promise; };
    const polling = app.pollAnalysis('job-1');
    await started.promise;
    app.get('newVersionModal').classList.add('show');
    const draft = editVersion(app);
    pending.resolve(app.data.requirement);
    await polling;
    assert.deepEqual(versionFields.map(id => app.get(id).value), draft);
});

test('closing an edited version modal retains the existing draft through a background refresh', async () => {
    const app = await requirementFixture();
    app.get('newVersionModal').classList.add('show');
    const draft = editVersion(app);
    app.get('newVersionModal').classList.remove('show');
    await app.pollAnalysis('job-1');
    assert.deepEqual(versionFields.map(id => app.get(id).value), draft);
});

test('an untouched closed version form follows the refreshed baseline', async () => {
    const app = await requirementFixture();
    app.data.requirement.currentVersion = {...app.data.requirement.currentVersion, text: 'Updated baseline'};
    await app.pollAnalysis('job-1');
    assert.equal(app.get('versionText').value, 'Updated baseline');
});

test('successful version creation closes the editor and starts the next form from the saved baseline', async () => {
    const app = await requirementFixture();
    app.get('newVersionModal').classList.add('show');
    editVersion(app);
    app.api.createRequirementVersion = async (projectId, requirementId, body) => {
        assert.equal(projectId, 1); assert.equal(requirementId, 2);
        app.data.requirement.currentVersion = {...app.data.requirement.currentVersion, text: body.text};
    };
    await app.createVersion({preventDefault() {}});
    assert.equal(app.get('newVersionModal').classList.contains('show'), false);
    assert.equal(app.get('currentText').textContent, 'Draft text\nKeep this edit');
    app.data.requirement.currentVersion = {...app.data.requirement.currentVersion, text: 'Subsequently refreshed baseline'};
    await app.pollAnalysis('job-1');
    assert.equal(app.get('versionText').value, 'Subsequently refreshed baseline', 'a saved draft must no longer block normal form refresh');
});

function descendants(node) { return node.children.flatMap(child => [child, ...descendants(child)]); }
function content(node) { return [node.textContent, node.innerHTML, ...node.children.map(content)].join(' '); }
function matrixFixture(locale = 'en') {
    const app = fixture('portfolio-matrices', 'state, renderAll, filteredMatrix, openCellDetail, exportCurrent, wireEvents', locale);
    app.state.project = app.data.project;
    app.state.activeType = 'solution';
    app.state.portfolio = {
        requirements: ['ZERO', 'POSITIVE', 'MISSING', 'UNKNOWN'].map((name, index) => ({id: index + 2, requirementKey: 'REQ-' + name})),
        solutions: [{solution: {solutionKey: 'SOL-1'}, requirements: [{requirementId: 2, coveragePercent: 0, reviewStatus: 'CONFIRMED', evidence: 'Explicitly reviewed as no coverage'}]}],
        requirementSolutionMatrix: {rows: ['SOL-1'], columns: ['REQ-ZERO', 'REQ-POSITIVE', 'REQ-MISSING', 'REQ-UNKNOWN'],
            values: {'SOL-1': {'REQ-ZERO': 0, 'REQ-POSITIVE': 65, 'REQ-UNKNOWN': null}}}
    };
    return app;
}
function matrixButtons(app) {
    const table = app.get('solutionMatrix').children.find(node => node.tagName === 'table');
    return table ? descendants(table).filter(node => node.tagName === 'button') : [];
}

for (const locale of ['en', 'de']) {
    test(`matrix ${locale} distinguishes explicit 0%, absent and unknown cells in table and detail`, () => {
        const app = matrixFixture(locale);
        app.wireEvents();
        app.renderAll();
        const buttons = matrixButtons(app);
        const zero = buttons.find(node => node.dataset.column === 'REQ-ZERO');
        assert.equal(zero?.textContent, '0%');
        assert.match(zero.getAttribute('aria-label'), /0%/);
        const missing = buttons.find(node => node.dataset.column === 'REQ-MISSING');
        const unknown = buttons.find(node => node.dataset.column === 'REQ-UNKNOWN');
        assert.notEqual(missing.textContent, zero.textContent);
        assert.notEqual(unknown.textContent, zero.textContent);
        assert.notEqual(unknown.getAttribute('aria-label'), missing.getAttribute('aria-label'));
        assert.match(unknown.getAttribute('aria-label'), locale === 'de' ? /unbekannt/i : /unknown/i);
        app.get('matrixMain').fire('click', {target: zero});
        const detail = content(app.get('cellDetailBody'));
        assert.match(detail, /0%/);
        assert.match(detail, /Explicitly reviewed as no coverage/);
        assert.doesNotMatch(detail, /No stored relationship exists|keine Beziehung gespeichert/);
        app.openCellDetail('solution', 'SOL-1', 'REQ-MISSING', 0);
        assert.match(content(app.get('cellDetailBody')), /No stored relationship exists|keine Beziehung gespeichert/);
        app.openCellDetail('solution', 'SOL-1', 'REQ-UNKNOWN', 0);
        assert.doesNotMatch(content(app.get('cellDetailBody')), /No stored relationship exists|keine Beziehung gespeichert|0%/);
        assert.match(content(app.get('cellDetailBody')), locale === 'de' ? /unbekannt/i : /unknown/i);
    });
}

test('a zero-valued taxonomy mapping retains its current-snapshot provenance', () => {
    const app = matrixFixture();
    app.state.portfolio.requirementTaxonomyMatrix = {rows: ['REQ-ZERO'], columns: ['TAX-1'], values: {'REQ-ZERO': {'TAX-1': 0}}};
    app.state.portfolio.taxonomyNodes = [{nodeCode: 'TAX-1', title: 'Access control'}];
    app.openCellDetail('taxonomy', 'REQ-ZERO', 'TAX-1');
    const detail = content(app.get('cellDetailBody'));
    assert.match(detail, /0%/);
    assert.match(detail, /From current snapshot/);
    assert.doesNotMatch(detail, /No stored relationship exists/);
});

test('a zero-valued product candidate retains its recorded source and review status', () => {
    const app = matrixFixture();
    app.state.portfolio.solutionProductMatrix = {rows: ['SOL-1'], columns: ['PROD-1'], values: {'SOL-1': {'PROD-1': 0}}};
    app.state.portfolio.solutions[0].productCandidates = [{product: {productKey: 'PROD-1', sourceReference: 'Reviewed datasheet'},
        coveragePercent: 0, reviewStatus: 'CONFIRMED', selectionStatus: 'SHORTLISTED'}];
    app.openCellDetail('product', 'SOL-1', 'PROD-1');
    const detail = content(app.get('cellDetailBody'));
    assert.match(detail, /0%/);
    assert.match(detail, /Reviewed datasheet/);
    assert.match(detail, /Confirmed/);
    assert.doesNotMatch(detail, /No stored relationship exists/);
});

test('Related includes recorded zero and unknown coverage, while Empty includes only absent relationships', () => {
    const app = matrixFixture();
    app.get('relationState').value = 'related';
    assert.deepEqual(Array.from(app.filteredMatrix('solution').columns), ['REQ-ZERO', 'REQ-POSITIVE', 'REQ-UNKNOWN']);
    app.get('relationState').value = 'empty';
    assert.deepEqual(Array.from(app.filteredMatrix('solution').columns), ['REQ-MISSING']);
    app.get('relationState').value = 'all';
    app.get('minimumValue').value = '1';
    assert.deepEqual(Array.from(app.filteredMatrix('solution').columns), ['REQ-POSITIVE']);
});

test('unknown or invalid coverage is never coerced into an explicit numeric zero', () => {
    const app = matrixFixture();
    for (const value of [null, undefined, '', '0', 'unknown', false, NaN, Infinity, -1, 101]) {
        app.state.portfolio.requirementSolutionMatrix.values['SOL-1']['REQ-UNKNOWN'] = value;
        app.renderAll();
        const unknown = matrixButtons(app).find(node => node.dataset.column === 'REQ-UNKNOWN');
        assert.ok(unknown, 'an unknown stored relationship stays reviewable');
        assert.match(unknown.getAttribute('aria-label'), /unknown/i, String(value));
        assert.doesNotMatch(unknown.getAttribute('aria-label'), /0%/, String(value));
    }
});

test('the alternative matrix list preserves zero, missing and unknown semantics', () => {
    const app = matrixFixture();
    app.renderAll();
    const list = app.get('solutionMatrix').children.find(node => node.tagName === 'details');
    const buttons = descendants(list).filter(node => node.tagName === 'button');
    assert.match(content(buttons.find(node => node.dataset.column === 'REQ-ZERO')), /0%/);
    assert.match(content(buttons.find(node => node.dataset.column === 'REQ-UNKNOWN')), /unknown/i);
    assert.doesNotMatch(content(buttons.find(node => node.dataset.column === 'REQ-MISSING')), /0%/);
});

test('filtered-out intersections cannot masquerade as absent relationships', () => {
    const app = matrixFixture();
    app.state.portfolio.requirementSolutionMatrix = {rows: ['SOL-1', 'SOL-2'], columns: ['REQ-ZERO', 'REQ-POSITIVE'],
        values: {'SOL-1': {'REQ-ZERO': 0, 'REQ-POSITIVE': 70}, 'SOL-2': {'REQ-ZERO': 80, 'REQ-POSITIVE': 10}}};
    app.get('minimumValue').value = '50';
    app.renderAll();
    assert.equal(matrixButtons(app).length, 2, 'only matching cells remain actionable');
    assert.ok(matrixButtons(app).every(node => Number(node.dataset.value) >= 50));
    const list = app.get('solutionMatrix').children.find(node => node.tagName === 'details');
    assert.equal(descendants(list).filter(node => node.tagName === 'button').length, 2);
});

test('JSON matrix export preserves explicit zero, null unknown coverage and absent keys', async () => {
    const app = matrixFixture();
    app.exportCurrent('json');
    const exported = JSON.parse(await app.downloads[0].blob.text());
    assert.equal(exported.values['SOL-1']['REQ-ZERO'], 0);
    assert.equal(exported.values['SOL-1']['REQ-UNKNOWN'], null);
    assert.equal(Object.hasOwn(exported.values['SOL-1'], 'REQ-MISSING'), false);
});

test('CSV matrix export leaves absent cells blank and labels unknown coverage instead of inventing zeros', async () => {
    const app = matrixFixture();
    app.exportCurrent('csv');
    const exported = await app.downloads[0].blob.text();
    assert.equal(exported, 'row,REQ-ZERO,REQ-POSITIVE,REQ-MISSING,REQ-UNKNOWN\nSOL-1,0,65,,Unknown');
});
