const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const resources = path.resolve(__dirname, '../../main/resources');
const template = readFileSync(path.join(resources, 'templates/index.html'), 'utf8');
const marker = '<!-- ── Preferences Tab Script';
const start = template.indexOf('<script>', template.indexOf(marker));
const end = template.indexOf('</script>', start);
assert.ok(start >= 0 && end > start, 'live Preferences inline script is available');
const preferencesScript = template.slice(start + '<script>'.length, end);
const draftScript = readFileSync(path.join(resources,
    'static/js/core/taxonomy-analysis-session-draft.js'), 'utf8');

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
}
function settled() { return new Promise(resolve => setImmediate(resolve)); }
function copy(value) { return JSON.parse(JSON.stringify(value)); }
function completedPayload() {
    return {schemaVersion: 1, draftState: 'ACTIVE', businessText: 'Resilient communications',
        lastAnalyzedText: 'Resilient communications', scores: {'CR-1047': 83},
        rawScores: {'CR-1047': 83}, effectiveScores: {'CR-1047': 83},
        reasons: {'CR-1047': 'Encrypted voice'}, architectureView: {id: 'graph-1',
            includedElements: [{nodeCode: 'CR-1047'}]},
        provisionalRelations: [{source: 'CP-1023', target: 'CR-1047', status: 'proposed'}],
        evaluatedNodes: ['CR-1047'], currentView: 'list'};
}
function fixture({deferDraftRead = false} = {}) {
    const listeners = new Map(), requests = [], timers = new Map(), controls = [];
    let timerId = 0, preferenceFailure = false, pendingDraftSave = null;
    const draftRead = deferDraftRead ? deferred() : null;
    const storedDraft = {version: 4, payload: completedPayload()};
    const preferences = {'limits.max-architecture-nodes': 50};
    function element(id, extra = {}) {
        const handlers = new Map(), attrs = new Map(), classes = new Set(id === 'tab-preferences' ? ['d-none'] : []);
        return {id, value: '', disabled: false, dataset: {}, placeholder: '', type: 'button',
            className: '', textContent: '', ...extra,
            classList: {add: x => classes.add(x), remove: x => classes.delete(x),
                contains: x => classes.has(x)},
            addEventListener: (name, fn) => handlers.set(name, fn),
            click() { const fn = handlers.get('click'); if (fn) fn({target: this}); },
            setAttribute: (key, value) => attrs.set(key, String(value)),
            getAttribute: key => attrs.get(key) || null,
            removeAttribute: key => attrs.delete(key),
            checkValidity: () => true};
    }
    const nodes = new Map();
    for (const id of ['prefSaveBtn', 'prefResetBtn', 'prefHistorySection', 'prefStatusMsg',
            'tab-preferences', 'businessText', 'statusArea', 'taxonomyTree', 'viewSummary'])
        nodes.set(id, element(id));
    const maxNodes = element('pref-max-arch-nodes', {type: 'number', value: '50'});
    maxNodes.setAttribute('data-pref-key', 'limits.max-architecture-nodes');
    controls.push(maxNodes);
    const state = {taxonomyData: [{code: 'CR-1047'}], currentScores: null,
        currentRawScores: {}, currentReasons: {}, currentArchView: null, evaluatedNodes: new Set()};
    const runtime = {workspaceId: 'ws-1', version: null, restoring: false,
        invalidating: false, resetting: false, conflict: false};
    const businessText = nodes.get('businessText');
    const document = {
        getElementById: id => nodes.get(id) || null,
        querySelectorAll: selector => selector === '[data-pref-key]' ? controls : [],
        addEventListener: (name, fn) => {
            if (!listeners.has(name)) listeners.set(name, []);
            listeners.get(name).push(fn);
        },
        dispatchEvent: event => {for (const fn of listeners.get(event.type) || []) fn(event);}
    };
    const window = {
        _currentProvisionalRelations: [], TaxonomyBrowse: {renderView() {}, updateExportGroupVisibility() {}},
        TaxonomyScoring: {renderArchitectureView() {}, renderSuggestedRelations() {}},
        clearTimeout: id => timers.delete(id), setTimeout: fn => {timers.set(++timerId, fn); return timerId;},
        confirm: () => true, console: {warn() {}}
    };
    function currentPayload() {
        return {schemaVersion: 1, draftState: 'ACTIVE', businessText: businessText.value,
            lastAnalyzedText: state.lastAnalyzedText, scores: state.currentScores,
            rawScores: state.currentRawScores, effectiveScores: state.currentEffectiveScores,
            reasons: state.currentReasons, architectureView: state.currentArchView,
            provisionalRelations: window._currentProvisionalRelations,
            evaluatedNodes: [...(state.evaluatedNodes || [])], currentView: state.currentView || 'list'};
    }
    const C = {S: state, runtime, AUTOSAVE_DELAY_MS: 900, MAX_RESTORE_ATTEMPTS: 0,
        text: key => key, businessTextElement: () => businessText,
        currentPayload, comparable: value => JSON.stringify(value), meaningful: value => Boolean(value.businessText),
        draftEndpoint: () => '/api/analysis-drafts/ws-1',
        hasDerivedAnalysis: () => Boolean(state.currentArchView), isStale: () => false,
        showActionAlert() {}, showStaleActions() {}, clearDerivedUi() {},
        jsonRequest: (url, options) => {
            requests.push({url, method: options.method || 'GET'});
            assert.equal(url, '/api/analysis-drafts/ws-1');
            if (options.method === 'GET') return draftRead ? draftRead.promise : Promise.resolve(copy(storedDraft));
            assert.equal(options.method, 'PUT');
            const body = JSON.parse(options.body);
            const accept = () => {
                assert.equal(body.expectedVersion, storedDraft.version);
                storedDraft.payload = copy(body.payload);
                storedDraft.version++;
                return copy(storedDraft);
            };
            if (pendingDraftSave) return pendingDraftSave.promise.then(accept);
            return Promise.resolve(accept());
        }};
    window.__TaxonomyAnalysisSessionContext = C;
    const fetch = (url, options = {}) => {
        requests.push({url, method: options.method || 'GET', body: options.body});
        if (url !== '/api/preferences') throw Error('Unexpected request: ' + url);
        if (options.method === 'PUT') {
            if (preferenceFailure) return Promise.resolve({ok: false, status: 500, json: async () => ({})});
            const changes = JSON.parse(options.body);
            assert.deepEqual(changes, {'limits.max-architecture-nodes': 150});
            Object.assign(preferences, changes);
        }
        return Promise.resolve({ok: true, json: async () => copy(preferences)});
    };
    const context = {window, document, fetch, TaxonomyI18n: {t: key => key},
        CustomEvent: class {constructor(type, options) {this.type = type; this.detail = options.detail;}},
        console: window.console};
    vm.runInNewContext(draftScript, context, {filename: 'taxonomy-analysis-session-draft.js'});
    vm.runInNewContext(preferencesScript, context, {filename: 'index.html Preferences script'});
    document.dispatchEvent({type: 'DOMContentLoaded'});
    return {C, state, runtime, nodes, maxNodes, requests, storedDraft, preferences,
        draftRead, activate(page) {
            if (page === 'preferences') nodes.get('tab-preferences').classList.remove('d-none');
            else nodes.get('tab-preferences').classList.add('d-none');
            document.dispatchEvent({type: 'taxonomy:page-activated', detail: {page}});
        },
        failPreferences: () => {preferenceFailure = true;},
        delayDraftSave: () => {pendingDraftSave = deferred(); return pendingDraftSave;}};
}
function assertWorkPreserved(f, expected) {
    assert.equal(f.nodes.get('businessText').value, expected.businessText);
    assert.deepEqual(copy(f.state.currentScores), expected.scores);
    assert.deepEqual(copy(f.state.currentRawScores), expected.rawScores);
    assert.deepEqual(copy(f.state.currentReasons), expected.reasons);
    assert.deepEqual(copy(f.state.currentArchView), expected.architectureView);
    assert.deepEqual([...f.state.evaluatedNodes], expected.evaluatedNodes);
    assert.deepEqual(copy(f.C.runtime.restoredPayload.provisionalRelations), expected.provisionalRelations);
    assert.deepEqual(copy(f.C.runtime.restoredPayload.architectureView), expected.architectureView);
    assert.deepEqual(copy(f.C.runtime.restoredPayload.reasons), expected.reasons);
}
async function openAndSave(f) {
    f.activate('preferences');
    await settled();
    assert.equal(f.maxNodes.value, '50');
    f.maxNodes.value = '150';
    f.nodes.get('prefSaveBtn').click();
    await settled();
}

test('Preferences 50 to 150 leaves completed draft, analysis and manual evidence intact through return and reload', async () => {
    const f = fixture(), expected = completedPayload();
    await f.C.loadDraft();
    assertWorkPreserved(f, expected);
    await openAndSave(f);
    f.activate('architecture');
    assert.equal(f.preferences['limits.max-architecture-nodes'], 150);
    assertWorkPreserved(f, expected);
    assert.equal(f.requests.filter(r => r.url === '/api/analysis-drafts/ws-1' && r.method !== 'GET').length, 0);
    assert.equal(f.requests.filter(r => r.url.includes('/api/analyze')).length, 0);
    f.nodes.get('businessText').value = '';
    f.state.currentScores = null;
    f.state.currentArchView = null;
    await f.C.loadDraft({force: true});
    assertWorkPreserved(f, expected);
});

test('Preferences save during pending draft autosave does not replace newer local work', async () => {
    const f = fixture();
    await f.C.loadDraft();
    f.state.currentReasons['CR-1047'] = 'Reviewed manually';
    f.state.currentArchView.manualDecision = 'ACCEPTED';
    const pending = f.delayDraftSave();
    const save = f.C.saveDraft();
    await openAndSave(f);
    assert.equal(f.state.currentReasons['CR-1047'], 'Reviewed manually');
    pending.resolve();
    await save;
    assert.equal(f.storedDraft.payload.reasons['CR-1047'], 'Reviewed manually');
    assert.equal(f.storedDraft.payload.architectureView.manualDecision, 'ACCEPTED');
    assert.equal(f.preferences['limits.max-architecture-nodes'], 150);
});

test('Preferences save failure leaves draft, local state and preference value unchanged', async () => {
    const f = fixture(), expected = completedPayload();
    await f.C.loadDraft();
    f.failPreferences();
    await openAndSave(f);
    assert.equal(f.preferences['limits.max-architecture-nodes'], 50);
    assert.match(f.nodes.get('prefStatusMsg').textContent, /preferences.save.failed/);
    assertWorkPreserved(f, expected);
    assert.equal(f.storedDraft.version, 4);
});

test('Preferences change while initial draft restore is pending does not turn saved work into an empty draft', async () => {
    const f = fixture({deferDraftRead: true}), expected = completedPayload();
    const restoring = f.C.loadDraft();
    await openAndSave(f);
    f.draftRead.resolve(copy(f.storedDraft));
    await restoring;
    assert.equal(f.preferences['limits.max-architecture-nodes'], 150);
    assertWorkPreserved(f, expected);
    assert.equal(f.runtime.version, 4);
});
