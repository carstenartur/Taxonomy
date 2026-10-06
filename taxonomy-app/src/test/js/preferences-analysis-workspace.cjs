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
        lastAnalyzedText: 'Resilient communications', scores: {'CR-1047': 77},
        rawScores: {'CR-1047': 91}, effectiveScores: {'CR-1047': 77},
        reasons: {'CR-1047': 'Encrypted voice'}, architectureView: {id: 'graph-1',
            includedElements: [{nodeCode: 'CR-1047'}]},
        provisionalRelations: [{source: 'CP-1023', target: 'CR-1047', status: 'proposed'}],
        evaluatedNodes: ['CR-1047'], currentView: 'list'};
}
function fixture({deferDraftRead = false, checkboxPreference = false} = {}) {
    const listeners = new Map(), requests = [], timers = new Map(), controls = [];
    const preferenceGates = new Map();
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
            fire(name) { const fn = handlers.get(name); if (fn) fn({target: this}); },
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
    const checkbox = checkboxPreference ? element('pref-fixture-checkbox', {type: 'checkbox', checked: false}) : null;
    if (checkbox) {
        checkbox.setAttribute('data-pref-key', 'fixture.checkbox');
        controls.push(checkbox);
        preferences['fixture.checkbox'] = false;
    }
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
        if (url !== '/api/preferences' && url !== '/api/preferences/reset') throw Error('Unexpected request: ' + url);
        if (options.method === 'PUT') {
            if (preferenceFailure) return Promise.resolve({ok: false, status: 500, json: async () => ({})});
            const changes = JSON.parse(options.body);
            Object.assign(preferences, changes);
        }
        if (url === '/api/preferences/reset') {
            preferences['limits.max-architecture-nodes'] = 50;
            if (checkbox) preferences['fixture.checkbox'] = false;
        }
        const snapshot = copy(preferences);
        const response = {ok: true, json: async () => copy(snapshot)};
        const gate = preferenceGates.get(options.method || 'GET');
        if (gate) {
            preferenceGates.delete(options.method || 'GET');
            return gate.promise.then(() => response);
        }
        return Promise.resolve(response);
    };
    const context = {window, document, fetch, TaxonomyI18n: {t: key => key},
        CustomEvent: class {constructor(type, options) {this.type = type; this.detail = options.detail;}},
        console: window.console};
    vm.runInNewContext(draftScript, context, {filename: 'taxonomy-analysis-session-draft.js'});
    vm.runInNewContext(preferencesScript, context, {filename: 'index.html Preferences script'});
    document.dispatchEvent({type: 'DOMContentLoaded'});
    return {C, state, runtime, nodes, maxNodes, checkbox, requests, storedDraft, preferences,
        draftRead, activate(page) {
            if (page === 'preferences') nodes.get('tab-preferences').classList.remove('d-none');
            else nodes.get('tab-preferences').classList.add('d-none');
            document.dispatchEvent({type: 'taxonomy:page-activated', detail: {page}});
        },
        failPreferences: () => {preferenceFailure = true;},
        delayPreferences(method) {
            const gate = deferred();
            preferenceGates.set(method, gate);
            return gate;
        },
        delayDraftSave: () => {pendingDraftSave = deferred(); return pendingDraftSave;}};
}
function assertWorkPreserved(f, expected) {
    assert.equal(f.nodes.get('businessText').value, expected.businessText);
    assert.deepEqual(copy(f.state.currentScores), expected.scores);
    assert.deepEqual(copy(f.state.currentRawScores), expected.rawScores);
    assert.deepEqual(copy(f.state.currentEffectiveScores), expected.effectiveScores);
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

for (const method of ['GET', 'PUT', 'POST']) {
    test('Preferences ' + method + ' response preserves edits typed after the request began', async () => {
        const f = fixture(), expected = completedPayload();
        await f.C.loadDraft();
        if (method !== 'GET') {
            f.activate('preferences');
            await settled();
        }
        const pending = f.delayPreferences(method);
        if (method === 'GET') f.activate('preferences');
        else if (method === 'PUT') {
            f.maxNodes.value = '150';
            f.nodes.get('prefSaveBtn').click();
        } else f.nodes.get('prefResetBtn').click();
        // Fields remain editable while the request runs. This later value is
        // unsaved local work, distinct from the server's acknowledged response.
        f.maxNodes.value = '175';
        pending.resolve();
        await settled();
        assert.equal(f.maxNodes.value, '175');
        assert.equal(f.preferences['limits.max-architecture-nodes'], method === 'PUT' ? 150 : 50);
        assert.equal(f.nodes.get('prefSaveBtn').disabled, false, 'The newer edit remains saveable');
        assertWorkPreserved(f, expected);
        assert.equal(f.storedDraft.version, 4);
        assert.equal(f.runtime.workspaceId, 'ws-1');
        assert.equal(f.requests.filter(r => r.url.includes('/api/analyze')).length, 0);
        // Save again: confirms that currentPrefs tracks the returned authority
        // while the form independently retains the newer edit.
        f.nodes.get('prefSaveBtn').click();
        await settled();
        assert.equal(f.preferences['limits.max-architecture-nodes'], 175);
        assert.equal(f.nodes.get('prefSaveBtn').disabled, true);
    });
}

for (const method of ['GET', 'PUT', 'POST']) {
    test('Preferences ' + method + ' preserves input edited away and back while pending', async () => {
        const f = fixture();
        await f.C.loadDraft();
        if (method !== 'GET') {
            f.activate('preferences');
            await settled();
        }
        // PUT acknowledges numeric 150; retaining its typed spelling also
        // proves hydration did not run over an intervening input event.
        const startValue = method === 'PUT' ? '150.0' : '150';
        f.maxNodes.value = startValue;
        if (method === 'POST') {
            f.nodes.get('prefSaveBtn').click();
            await settled();
            assert.equal(f.preferences['limits.max-architecture-nodes'], 150);
        }
        const pending = f.delayPreferences(method);
        if (method === 'GET') f.activate('preferences');
        else f.nodes.get(method === 'PUT' ? 'prefSaveBtn' : 'prefResetBtn').click();
        f.maxNodes.value = '175';
        f.maxNodes.fire('input');
        f.maxNodes.value = startValue;
        f.maxNodes.fire('input');
        pending.resolve();
        await settled();
        assert.equal(f.maxNodes.value, startValue);
        assert.equal(f.nodes.get('prefSaveBtn').disabled, method === 'PUT');
        assertWorkPreserved(f, completedPayload());
    });

    test('Preferences ' + method + ' preserves a checkbox toggled away and back while pending', async () => {
        const f = fixture({checkboxPreference: true});
        if (method !== 'GET') {
            f.activate('preferences');
            await settled();
        }
        if (method === 'POST') {
            f.checkbox.checked = true;
            f.preferences['fixture.checkbox'] = true;
        } else {
            // A concurrent global preference update is reflected in getAll(),
            // including the response to PUT of the independent node limit.
            f.preferences['fixture.checkbox'] = true;
        }
        const startValue = f.checkbox.checked;
        const pending = f.delayPreferences(method);
        if (method === 'GET') f.activate('preferences');
        else if (method === 'PUT') {
            f.maxNodes.value = '150';
            f.nodes.get('prefSaveBtn').click();
        } else f.nodes.get('prefResetBtn').click();
        f.checkbox.checked = !startValue;
        f.checkbox.fire('change');
        f.checkbox.checked = startValue;
        f.checkbox.fire('change');
        pending.resolve();
        await settled();
        assert.equal(f.checkbox.checked, startValue);
        assert.equal(f.nodes.get('prefSaveBtn').disabled, false);
    });
}

test('Reducing the node limit preserves the complete draft without another analysis', async () => {
    const f = fixture(), expected = completedPayload();
    expected.architectureView.includedElements.push({nodeCode: 'CP-1023'});
    expected.provisionalRelations[0].status = 'REJECTED';
    expected.provisionalRelations[0].hypothesisId = 5;
    f.storedDraft.payload = copy(expected);
    await f.C.loadDraft();
    f.activate('preferences');
    await settled();
    f.maxNodes.value = '1';
    f.nodes.get('prefSaveBtn').click();
    await settled();
    f.activate('architecture');
    assert.equal(f.preferences['limits.max-architecture-nodes'], 1);
    assertWorkPreserved(f, expected);
    assert.deepEqual(copy(f.storedDraft.payload), expected);
    assert.equal(f.requests.filter(r => r.url.includes('/api/analyze')).length, 0);
    assert.equal(f.requests.filter(r => r.url.includes('/api/analysis-drafts') && r.method !== 'GET').length, 0);
});

// Keep these behavioral regressions in the Maven-owned preferences-workspace
// selection: restore must preserve the presentation of actual manual decisions.
function hypothesisFixture({reviewStatus = 200, reviewStatuses = [], applyStatus = 200, lateAdapter = false} = {}) {
    const rows = new Map(), calls = [];
    const input = {value: 'Authored hypothesis restore fixture', classList: {add() {}}};
    const panel = {style: {}}, badge = {};
    function actionsElement(html) {
        const actions = {innerHTML: html, undo: null};
        actions.querySelector = selector => selector === '.hypothesis-undo'
            && /hypothesis-undo/.test(actions.innerHTML) ? {
                addEventListener(name, handler) {actions.undo = handler;},
                click() {actions.undo?.();}
            } : null;
        return actions;
    }
    const bulkActions = {innerHTML: ''};
    const content = {
        set innerHTML(html) {
            this.html = html;
            bulkActions.innerHTML = html.match(/<span id="suggestedRelationsBulkActions">([\s\S]*?)<\/span>/)?.[1] || '';
            rows.clear();
            for (const match of html.matchAll(/<tr id="suggested-row-(\d+)">([\s\S]*?)<\/tr>/g)) {
                const actions = actionsElement(match[2].match(/<td class="text-nowrap">([\s\S]*)<\/td>$/)[1]);
                rows.set(Number(match[1]), {style: {}, classList: {add() {}, remove() {}},
                    setAttribute() {}, querySelector: () => actions,
                    querySelectorAll: () => []});
            }
        },
        get innerHTML() {return this.html.replace(
            /(<span id="suggestedRelationsBulkActions">)[\s\S]*?(<\/span>)/,
            (_, start, end) => start + bulkActions.innerHTML + end);}
    };
    const nodes = {businessText: input, suggestedRelationsPanel: panel,
        suggestedRelationsContent: content, suggestedRelationsBadge: badge, suggestedRelationsBulkActions: bulkActions};
    const document = {readyState: 'complete', documentElement: {lang: 'en'},
        getElementById: id => id.startsWith('suggested-row-')
            ? rows.get(Number(id.slice('suggested-row-'.length))) : nodes[id] || null,
        querySelector: () => null, querySelectorAll: () => [],
        addEventListener() {}, dispatchEvent() {}};
    const state = {taxonomyData: [{code: 'BP'}], currentScores: {BP: 77},
        currentRawScores: {BP: 91}, currentEffectiveScores: {BP: 77},
        lastAnalyzedText: input.value, evaluatedNodes: new Set(['BP'])};
    let stored = null;
    const response = (status, body = {}) => ({ok: status >= 200 && status < 300, status,
        headers: {get: key => key === 'ETag' ? '"head-1"' : 'application/json'},
        json: async () => copy(body)});
    const window = {TaxonomyState: state, console: {warn() {}, error() {}},
        clearTimeout() {}, setTimeout: () => 1,
        TaxonomyBrowse: {renderView() {}, updateExportGroupVisibility() {}, showStatus() {}},
        TaxonomyAnalysisSessionApi: {request: async (url, request) => {
            assert.equal(url, '/api/analysis-drafts/ws-hypotheses');
            assert.equal(request.method, 'PUT');
            assert.equal(request.headers['X-Taxonomy-Workspace-Id'], 'ws-hypotheses');
            const body = JSON.parse(request.body);
            assert.equal(body.expectedVersion, stored?.version ?? null);
            stored = {workspaceId: 'ws-hypotheses', version: (stored?.version ?? 0) + 1,
                payload: body.payload};
            return response(200, stored);
        }},
        TaxonomyHypothesesApi: {readHead: async () => response(200),
            review: async (id, action, headers) => {
                assert.ok([5, 6, 7].includes(id));
                assert.equal(headers['If-Match'], '"head-1"');
                assert.ok(headers['Idempotency-Key']);
                calls.push(action);
                return response(reviewStatuses.length ? reviewStatuses.shift() : reviewStatus);
            },
            applyForSession: async id => {assert.ok([5, 6, 7].includes(id)); calls.push('APPLY'); return response(applyStatus);}}
    };
    const context = vm.createContext({window, document, console: window.console,
        TaxonomyI18n: {t: key => key}, TaxonomyUtils: {escapeHtml: value => String(value ?? '')},
        CustomEvent: class {constructor(type) {this.type = type;}}});
    const run = name => vm.runInContext(readFileSync(path.join(resources, 'static/js', name), 'utf8'),
        context, {filename: name});
    run('core/taxonomy-analysis-session-core.js');
    run('core/taxonomy-scoring.js');
    run('core/taxonomy-analysis-session-draft.js');
    const C = window.__TaxonomyAnalysisSessionContext;
    C.runtime.workspaceId = 'ws-hypotheses';
    const installAdapter = () => run('relations/taxonomy-hypotheses-git-commands.js');
    if (!lateAdapter) installAdapter();
    return {window, C, calls, installAdapter,
        render(fields = {}) {window.TaxonomyScoring.renderSuggestedRelations((Array.isArray(fields) ? fields : [fields])
            .map((value, index) => ({hypothesisId: 5 + index,
            sourceCode: 'BP', targetCode: 'BR', relationType: 'SUPPORTS', confidence: 0.82,
            reasoning: 'Authored fixture', status: 'PROVISIONAL', ...value})));},
        actions: (index = 0) => rows.get(index).querySelector('td:last-child'),
        html: () => content.innerHTML,
        async roundTrip() {
            assert.equal(await C.saveDraft(), true);
            C.applyDraft(copy(stored));
            return copy(stored.payload.provisionalRelations[0]);
        }};
}

test('live reject and session apply remove bulk acceptance; Git Undo restores eligibility without rerendering rows', async () => {
    const f = hypothesisFixture(); f.render([{}, {}]);
    assert.match(f.html(), /_acceptAllHighConfidence/);
    f.window._rejectHypothesis(0); await settled();
    assert.match(f.html(), /_acceptAllHighConfidence/, 'Other provisional hypothesis keeps bulk eligibility');
    f.window._applyForSession(1); await settled();
    assert.doesNotMatch(f.html(), /_acceptAllHighConfidence/, 'Completed manual decisions must remove the live bulk control');
    assert.match(f.actions().innerHTML, /Rejected/);
    assert.match(f.actions(1).innerHTML, /Session only/);
    f.actions().querySelector('.hypothesis-undo').click(); await settled();
    assert.match(f.html(), /_acceptAllHighConfidence/, 'Undo restores high-confidence provisional eligibility');
    assert.match(f.actions().innerHTML, /Provisional/);
    assert.match(f.actions(1).innerHTML, /Session only/, 'Header refresh must preserve the other row decision');
    assert.deepEqual(f.calls, ['REJECT', 'APPLY', 'REVERT']);
});

test('live bulk completion removes bulk control and preserves each actual Undo', async () => {
    const f = hypothesisFixture(); f.render([{}, {}]);
    f.window._acceptAllHighConfidence(); await settled();
    assert.doesNotMatch(f.html(), /_acceptAllHighConfidence/);
    assert.match(f.actions().innerHTML, /Accepted/);
    assert.match(f.actions(1).innerHTML, /Accepted/);
    assert.ok(f.actions().querySelector('.hypothesis-undo'));
    assert.ok(f.actions(1).querySelector('.hypothesis-undo'));
    assert.deepEqual(f.calls, ['ACCEPT', 'ACCEPT']);
});

test('bulk eligibility refresh preserves partial pending and failed row outcomes', async () => {
    for (const [status, label] of [[202, /Recovery pending/], [503, /Rejected \(HTTP 503\)/]]) {
        const f = hypothesisFixture({reviewStatuses: [200, status]}); f.render([{}, {}]);
        f.window._acceptAllHighConfidence(); await settled();
        assert.match(f.actions().innerHTML, /Accepted/);
        assert.ok(f.actions().querySelector('.hypothesis-undo'));
        assert.match(f.actions(1).innerHTML, label);
        assert.match(f.html(), /_acceptAllHighConfidence/, 'Uncompleted hypothesis retains existing bulk eligibility');
        assert.equal(f.window._currentProvisionalRelations[0].status, 'ACCEPTED');
        assert.equal(f.window._currentProvisionalRelations[1].status, 'PROVISIONAL');
    }
});

for (const [action, status, label] of [['REJECT', 'REJECTED', 'Rejected'], ['ACCEPT', 'ACCEPTED', 'Accepted']]) {
    test('restored ' + status + ' hypothesis retains its badge and Git Undo without repeat review controls', async () => {
        const f = hypothesisFixture(); f.render();
        f.window[action === 'REJECT' ? '_rejectHypothesis' : '_acceptHypothesis'](0);
        await settled();
        assert.equal((await f.roundTrip()).status, status);
        assert.match(f.actions().innerHTML, new RegExp(label));
        assert.doesNotMatch(f.actions().innerHTML, /_acceptHypothesis|_rejectHypothesis|_applyForSession/);
        assert.doesNotMatch(f.html(), /_acceptAllHighConfidence/);
        f.window._acceptHypothesis(0); f.window._rejectHypothesis(0);
        f.window._applyForSession(0); f.window._acceptAllHighConfidence();
        await settled();
        assert.deepEqual(f.calls, [action]);
        f.actions().querySelector('.hypothesis-undo').click();
        await settled();
        assert.deepEqual(f.calls, [action, 'REVERT']);
        assert.equal((await f.roundTrip()).status, 'PROVISIONAL');
        assert.match(f.actions().innerHTML, /_acceptHypothesis/);
    });
}

test('restored session-applied hypothesis keeps Session only and prevents another application or review', async () => {
    const f = hypothesisFixture(); f.render(); f.window._applyForSession(0); await settled();
    assert.equal((await f.roundTrip()).appliedInCurrentAnalysis, true);
    assert.match(f.actions().innerHTML, /Session only|scoring.badge.session.only/);
    assert.doesNotMatch(f.actions().innerHTML, /_acceptHypothesis|_rejectHypothesis|_applyForSession/);
    assert.doesNotMatch(f.html(), /_acceptAllHighConfidence/);
    f.window._applyForSession(0); f.window._acceptHypothesis(0); f.window._acceptAllHighConfidence();
    await settled(); assert.deepEqual(f.calls, ['APPLY']);
});

test('unreviewed hypotheses remain actionable after draft restore; false apply flags stay false', async () => {
    for (const fields of [{}, {status: undefined}, {status: 'PROPOSED'}, {appliedInCurrentAnalysis: false},
            {appliedInCurrentAnalysis: 'false'}]) {
        const f = hypothesisFixture(); f.render(fields); await f.roundTrip();
        assert.match(f.actions().innerHTML, /_acceptHypothesis/);
        assert.match(f.actions().innerHTML, /_rejectHypothesis/);
        assert.match(f.actions().innerHTML, /_applyForSession/);
        assert.match(f.html(), /_acceptAllHighConfidence/);
    }
});

test('review status takes priority over a session flag and unsupported APPROVED state is not actionable', async () => {
    for (const [status, label] of [['REJECTED', 'Rejected'], ['ACCEPTED', 'Accepted'], ['APPROVED', 'APPROVED']]) {
        const f = hypothesisFixture(); f.render({status, appliedInCurrentAnalysis: true}); await f.roundTrip();
        assert.match(f.actions().innerHTML, new RegExp(label));
        assert.doesNotMatch(f.actions().innerHTML, /_acceptHypothesis|_rejectHypothesis|_applyForSession/);
        f.window._acceptHypothesis(0); f.window._rejectHypothesis(0); f.window._applyForSession(0);
        f.window._acceptAllHighConfidence(); await settled(); assert.deepEqual(f.calls, []);
        if (status === 'APPROVED') assert.equal(f.actions().querySelector('.hypothesis-undo'), null);
    }
});

test('failed server review and application do not become completed decisions after restore', async () => {
    for (const action of ['REJECT', 'APPLY']) {
        const f = hypothesisFixture({reviewStatus: 503, applyStatus: 503}); f.render();
        f.window[action === 'REJECT' ? '_rejectHypothesis' : '_applyForSession'](0); await settled();
        const restored = await f.roundTrip();
        assert.equal(restored.status, 'PROVISIONAL');
        assert.notEqual(restored.appliedInCurrentAnalysis, true);
        assert.match(f.actions().innerHTML, /_applyForSession/);
    }
});

test('late adapter initialization restores the rejected badge and Undo without changing draft evidence', async () => {
    const f = hypothesisFixture({lateAdapter: true}); f.render({status: 'REJECTED'});
    const payload = await f.roundTrip(); f.installAdapter();
    assert.match(f.actions().innerHTML, /Rejected/);
    assert.ok(f.actions().querySelector('.hypothesis-undo'));
    assert.deepEqual(copy(f.C.currentPayload().provisionalRelations[0]), payload);
});

test('restored completed evidence without a persisted identity does not invent an Undo command', async () => {
    const f = hypothesisFixture(); f.render({hypothesisId: undefined, status: 'REJECTED'});
    await f.roundTrip();
    assert.match(f.actions().innerHTML, /scoring.badge.dismissed/);
    assert.equal(f.actions().querySelector('.hypothesis-undo'), null);
    f.window._rejectHypothesis(0); f.window._applyForSession(0); await settled();
    assert.deepEqual(f.calls, []);
});
