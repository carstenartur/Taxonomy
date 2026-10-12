import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const sourceRoot = '../../taxonomy-app/src/main/resources/static/js/';
const utilsSource = readFileSync(new URL(sourceRoot + 'shared/taxonomy-utils.js', import.meta.url), 'utf8');
const onboardingSource = readFileSync(new URL(sourceRoot + 'shared/taxonomy-onboarding.js', import.meta.url), 'utf8');

function freeze(value) {
    if (value && typeof value === 'object') {
        Object.values(value).forEach(freeze);
        Object.freeze(value);
    }
    return value;
}

function catalogue() {
    return [{ code: 'BP', name: 'Business Processes', taxonomyContainer: true,
        taxonomyRoot: 'BP', level: 0, children: [
            { code: 'BP-1000', name: 'Business Processes', taxonomyRoot: 'BP', level: 1,
                parentCode: 'BP', children: [] }
        ] }];
}

// Use the real classifier and onboarding workflow. Browse/scoring are bounded
// navigation interfaces here; their rendering, lazy expansion and score updates
// are exercised with the real modules in subtaxonomy-container-browse.test.mjs.
function harness({ data = catalogue(), scores = {} } = {}) {
    const document = { readyState: 'loading', activeElement: null, addEventListener() {} };
    function element(tag = 'div') {
        const attributes = new Map();
        const node = { tagName: tag.toUpperCase(), dataset: {}, className: '', value: '',
            textContent: '', disabled: false, scrolled: false,
            setAttribute(name, value) { attributes.set(name, String(value)); },
            getAttribute(name) { return attributes.get(name) ?? null; },
            removeAttribute(name) { attributes.delete(name); },
            focus() { document.activeElement = this; },
            scrollIntoView() { this.scrolled = true; },
            querySelector() { return null; }
        };
        Object.defineProperty(node, 'tabIndex', {
            get() { return attributes.has('tabindex') ? Number(attributes.get('tabindex')) : (tag === 'button' ? 0 : -1); },
            set(value) { attributes.set('tabindex', String(value)); }
        });
        node.classList = {
            contains: name => node.className.split(/\s+/).includes(name),
            toggle(name, force) {
                const classes = new Set(node.className.split(/\s+/).filter(Boolean));
                if (force === undefined ? !classes.has(name) : force) classes.add(name);
                else classes.delete(name);
                node.className = [...classes].join(' ');
            }
        };
        return node;
    }
    const elements = {};
    for (const id of ['businessText', 'analyzeBtn', 'analyzeSpinner', 'statusArea', 'taskNextAction',
        'taskGuidance', 'taskStageDescribe', 'taskStageAnalyze', 'taskStageReview', 'taskStageContinue']) {
        elements[id] = element(id === 'taskNextAction' ? 'button' : 'div');
    }
    elements.businessText.value = 'A deterministic requirement';
    elements.analyzeSpinner.className = 'd-none';
    document.getElementById = id => elements[id] || null;

    const rows = new Map(), groups = new Map();
    document.querySelector = selector => {
        const code = selector.match(/^#taxonomyTree \.tax-node\[data-code="([^"]+)"\]$/)?.[1];
        return rows.get(code) || null;
    };
    document.querySelectorAll = selector => selector === '#taxonomyTree .tax-node[tabindex="0"]'
        ? [...rows.values()].filter(node => node.tabIndex === 0) : [];
    const state = { taxonomyData: freeze(data), currentScores: freeze(scores),
        currentRawScores: freeze({ ...scores }) };
    const i18n = { t: (key, score) => key === 'browse.container.relevance'
        ? 'Area relevance: ' + score + '%' : key };
    const window = { TaxonomyState: state };
    const context = vm.createContext({ window, document, TaxonomyI18n: i18n,
        CSS: { escape: value => value } });
    vm.runInContext(utilsSource, context, { filename: 'taxonomy-utils.js' });

    function render(nodes) {
        for (const node of nodes) {
            if (window.TaxonomyUtils.isTaxonomyContainer(node)) {
                const group = element('section'), heading = element('h3');
                group.dataset.taxonomyContainer = node.code;
                heading.textContent = node.code + ' ' + node.name;
                const toggle = node.children?.length ? element('button') : null;
                if (toggle) toggle.setAttribute('aria-expanded', 'false');
                group.querySelector = selector => selector === '.tax-container-toggle' ? toggle
                    : selector === '.tax-container-heading' ? heading : null;
                groups.set(node.code, group);
            } else {
                const row = element('div');
                row.className = 'tax-node'; row.dataset.code = node.code; row.tabIndex = 0;
                rows.set(node.code, row);
            }
            render(node.children || []);
        }
    }
    render(data);
    const viewChanges = [], expanded = [];
    window.TaxonomyBrowse = {
        switchView(view) { viewChanges.push(view); },
        findRenderedNode(code) { return rows.get(code) || groups.get(code) || null; }
    };
    window.TaxonomyScoring = {
        expandNodeByCode(code) {
            expanded.push(code);
            groups.get(code)?.querySelector('.tax-container-toggle')?.setAttribute('aria-expanded', 'true');
        }
    };
    // Expose the real private selection/action only in the test realm. No
    // production test hook or replacement selection logic is used.
    vm.runInContext(onboardingSource.replace('window.TaxonomyOnboarding = {',
        'window.TaxonomyOnboarding = { strongestForTest: strongestKnownMatch, actForTest: performNextAction,'),
    context, { filename: 'taxonomy-onboarding.js' });
    const api = window.TaxonomyOnboarding;
    return { document, state, elements, rows, groups, viewChanges, expanded,
        strongest: () => api.strongestForTest(state.currentScores),
        sync: () => api.syncTaskProgress(),
        review() { api.syncTaskProgress(); api.actForTest(); }
    };
}

test('Review results selects and focuses the strongest original entry below a higher-scored area', () => {
    const h = harness({ scores: { BP: 91, 'BP-1000': 64 } });
    const before = JSON.stringify(h.state);
    h.review();
    assert.deepEqual(h.expanded, ['BP-1000']);
    assert.equal(h.strongest()?.code, 'BP-1000');
    assert.ok(h.document.activeElement === h.rows.get('BP-1000'));
    assert.equal(h.rows.get('BP-1000').scrolled, true);
    assert.equal(h.elements.taskNextAction.dataset.action, 'open-architecture');
    assert.deepEqual(h.viewChanges, ['list']);
    assert.equal(JSON.stringify(h.state), before, 'navigation preserves source, scores and raw evidence');
});

test('explicit original top-level nodes remain eligible even when legacy metadata resembles a container', () => {
    const data = catalogue(); data[0].taxonomyContainer = false;
    const h = harness({ data, scores: { BP: 91, 'BP-1000': 64 } });
    h.review();
    assert.equal(h.strongest()?.code, 'BP');
    assert.deepEqual(h.expanded, ['BP']);
    assert.ok(h.document.activeElement === h.rows.get('BP'));
    assert.equal(h.rows.get('BP-1000').tabIndex, -1, 'the actual result remains the sole roving tree target');
    assert.equal(h.elements.taskNextAction.dataset.action, 'open-architecture');
});

test('a zero original assessment stays reviewable without inheriting positive area relevance', () => {
    const h = harness({ scores: { BP: 91, 'BP-1000': 0 } });
    h.sync();
    assert.equal(h.elements.taskNextAction.textContent, 'analysis.task.next.assessments');
    assert.equal(h.elements.taskGuidance.textContent, 'analysis.task.review.noMatch');
    h.review();
    assert.ok(h.document.activeElement === h.rows.get('BP-1000'));
    assert.deepEqual(h.expanded, ['BP-1000']);
    assert.equal(h.strongest()?.score, 0);
});

test('area-only assessments open the existing heading without claiming a catalogue match', () => {
    const h = harness({ scores: { BP: 91 } });
    const before = JSON.stringify(h.state);
    h.sync();
    assert.equal(h.strongest(), null);
    assert.equal(h.elements.taskNextAction.textContent, 'analysis.task.next.assessments');
    assert.notEqual(h.elements.taskGuidance.textContent, 'analysis.task.stage.review.description');
    h.review();
    const group = h.groups.get('BP'), toggle = group.querySelector('.tax-container-toggle');
    assert.ok(h.document.activeElement === toggle, 'review focuses the existing native area control');
    assert.equal(toggle.getAttribute('aria-expanded'), 'true');
    assert.equal(toggle.getAttribute('tabindex'), null, 'native button keeps its ordinary keyboard behavior');
    assert.equal(group.getAttribute('tabindex'), null);
    assert.equal(group.getAttribute('aria-expanded'), null);
    assert.equal(h.elements.taskNextAction.dataset.action, 'open-architecture');
    assert.equal(JSON.stringify(h.state), before);
});

test('a scored empty legacy container remains reviewable through its existing heading', () => {
    const data = catalogue(); delete data[0].taxonomyContainer; data[0].children = [];
    const h = harness({ data, scores: { BP: 0 } });
    h.review();
    const heading = h.groups.get('BP').querySelector('.tax-container-heading');
    assert.ok(h.document.activeElement === heading);
    assert.equal(heading.getAttribute('tabindex'), '-1', 'empty-area heading is a programmatic focus target');
    assert.equal(heading.scrolled, true);
    assert.equal(h.strongest(), null);
    assert.equal(h.elements.taskNextAction.dataset.action, 'open-architecture');
});

test('unknown score codes still show the existing unavailable state without a fabricated destination', () => {
    const h = harness({ scores: { UNKNOWN: 100 } });
    h.review();
    assert.equal(h.strongest(), null);
    assert.deepEqual(h.expanded, []);
    assert.equal(h.document.activeElement, null);
    assert.equal(h.elements.taskGuidance.textContent, 'analysis.task.review.unavailable');
    assert.equal(h.elements.taskGuidance.getAttribute('role'), 'status');
    assert.equal(h.elements.taskNextAction.dataset.action, 'review-results');
});
