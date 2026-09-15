import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js', import.meta.url), 'utf8');
function fixture(options = {}) {
    const statuses = [], calls = [], observations = [], renders = [], order = [];
    let rejectRequest;
    const sessionState = { ready: true, workspaceId: 'workspace-a' };
    const state = { currentDiscrepancies: ['old discrepancy'], currentProductCoverageGaps: ['old gap'],
        currentArchView: { title: 'old architecture' }, evaluatedNodes: new Set(['CP']),
        storedBusinessText: 'old requirement', lastAnalyzedText: 'old requirement',
        pendingProposalNodeCode: 'CP', lastAnalysisProvider: 'old provider', currentReasons: { CP: 'retained reason' }, currentRawScores: { CP: 80 },
        lastAnalysisStatus: 'SUCCESS', taxonomyData: [], currentScoreDetails: {}, currentScores: { CP: 80 } };
    const window = { _currentProvisionalRelations: ['old relation'], TaxonomyState: state, TaxonomyBrowse: {
        showStatus: (...args) => statuses.push(args), clearStatus() {}, ensureNodeRendered() {},
        renderView: (tree, scores) => { renders.push({ tree, scores: { ...scores } }); order.push('render'); }
    } };
    const textField = { value: 'communications', classList: { remove() {} } };
    const document = { querySelector: () => null, getElementById: id => id === 'businessText' ? textField
        : id === 'includeArchitectureView' ? { checked: true } : null };
    const sandbox = { window, document, CSS: { escape: value => value }, console: { log() {}, error() {} },
        TaxonomyI18n: { t: key => key }, TaxonomyUtils: { escapeHtml: text => text },
        crypto: { randomUUID: () => 'cb2a3d71-e849-4a50-9855-1f9cb8f81402' },
        fetch: (...args) => { calls.push(args); order.push('post'); return new Promise((resolve, reject) => { rejectRequest = reject; }); }
    };
    if (Object.hasOwn(options, 'crypto')) sandbox.crypto = options.crypto;
    vm.runInNewContext(source, sandbox);
    function progress() { window.TaxonomyAnalysisProgress = { start: (...args) => {
        observations.push(args); order.push('monitor'); return { finish() {}, stop() {}, cancel() {} };
    } }; }
    return { window, state, sessionState, statuses, calls, observations, progress, renders, order,
        rejectRequest: error => rejectRequest(error) };
}

for (const phase of ['no lifecycle', 'progress loaded before lifecycle', 'loader still running']) {
    test(`full analysis does not start or clear results with ${phase}`, () => {
        const f = fixture();
        if (phase !== 'no lifecycle') f.progress();
        if (phase === 'loader still running') {
            f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
            f.window.__taxonomyAnalysisSessionLoading = true;
        }
        f.window.TaxonomyScoring.runAnalysis();
        assert.equal(f.calls.length, 0);
        assert.equal(f.observations.length, 0);
        assert.equal(f.state.currentReasons.CP, 'retained reason');
        assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
        assert.deepEqual(f.statuses, [['warning', 'scoring.lifecycle.not.ready']]);
    });
}

test('once lifecycle is ready full analysis always starts observation before its POST', () => {
    const f = fixture();
    f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.__taxonomyAnalysisSessionLoading = false;
    f.window.TaxonomyScoring.runAnalysis();
    assert.equal(f.observations.length, 1);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0][0], '/api/analyze');
    assert.equal(f.calls[0][1].headers['X-Taxonomy-Workspace-Id'], 'workspace-a');
    assert.equal(f.calls[0][1].headers['X-Analysis-Operation-Id'], f.observations[0][0]);
    assert.equal(JSON.parse(f.calls[0][1].body).includeArchitectureView, true);
});


for (const view of ['list', 'tabs', 'tree', 'sunburst', 'graph']) {
    test(`a new ${view} analysis visibly clears stale scores before its monitor and POST`, async () => {
        const f = fixture();
        f.progress();
        f.state.currentView = view;
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        f.window.TaxonomyScoring.runAnalysis();
        assert.equal(f.renders.length, 1);
        assert.deepEqual(f.renders[0].scores, {});
        assert.deepEqual(f.order, ['render', 'monitor', 'post']);
        const failure = new Error('HTTP 503');
        failure.httpStatus = 503;
        f.rejectRequest(failure);
        await new Promise(resolve => setImmediate(resolve));
        assert.deepEqual(f.renders.at(-1).scores, {});
        assert.equal(f.statuses.at(-1)[0], 'danger');
    });
}


for (const reason of ['restoring', 'conflict', 'invalidating', 'unresolved']) {
    test(`full analysis cannot enter while workspace state is ${reason}`, () => {
        const f = fixture(); f.progress();
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        f.sessionState.ready = false;
        f.sessionState[reason] = true;
        f.window.TaxonomyScoring.runAnalysis();
        assert.equal(f.calls.length, 0);
        assert.equal(f.observations.length, 0);
        assert.equal(f.renders.length, 0);
        assert.equal(f.state.currentArchView.title, 'old architecture');
        assert.equal(f.state.currentReasons.CP, 'retained reason');
    });
}

test('new requirement cannot inherit old architecture, gaps, relations or export evidence after rejection', async () => {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.TaxonomyScoring.runAnalysis();
    function assertClean() {
        assert.equal(f.state.currentDiscrepancies.length, 0);
        assert.equal(f.state.currentProductCoverageGaps.length, 0);
        assert.equal(f.state.currentArchView, null);
        assert.equal(f.state.evaluatedNodes.size, 0);
        assert.equal(f.window._currentProvisionalRelations.length, 0);
        assert.equal(f.state.lastAnalyzedText, null);
        assert.equal(f.state.storedBusinessText, null);
        assert.equal(f.state.lastAnalysisProvider, null);
    }
    assertClean();
    const error = Object.assign(new Error('HTTP 503'), { httpStatus: 503 });
    f.rejectRequest(error);
    await new Promise(resolve => setImmediate(resolve));
    assertClean();
});

for (const workspaceId of [null, '', ' \t', undefined]) {
    test(`read-only or missing workspace (${JSON.stringify(workspaceId)}) cannot admit a workspace-scoped analysis`, () => {
        const f = fixture(); f.progress();
        f.sessionState.workspaceId = workspaceId;
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        assert.doesNotThrow(() => f.window.TaxonomyScoring.runAnalysis());
        assert.deepEqual(f.statuses, [['warning', 'guard.blocked.readonly']]);
        assert.equal(f.calls.length, 0);
        assert.equal(f.observations.length, 0);
        assert.equal(f.renders.length, 0);
        assert.equal(f.state.currentArchView.title, 'old architecture');
        assert.equal(f.state.currentReasons.CP, 'retained reason');
        assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    });
}


for (const [name, crypto] of [
    ['absent', undefined],
    ['null', null],
    ['missing both methods', {}],
    ['non-callable methods', { randomUUID: true, getRandomValues: true }],
    ['disabled random UUID', { randomUUID() { throw new Error('disabled'); } }],
    ['disabled random values', { getRandomValues() { throw new Error('disabled'); } }]
]) {
    test(`unavailable secure operation IDs (${name}) report a failure without starting or clearing anything`, () => {
        const f = fixture({ crypto }); f.progress();
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        assert.doesNotThrow(() => f.window.TaxonomyScoring.runAnalysis());
        assert.deepEqual(f.statuses, [['danger', 'scoring.secure.id.unavailable']]);
        assert.equal(f.calls.length, 0);
        assert.equal(f.observations.length, 0);
        assert.equal(f.renders.length, 0);
        assert.equal(f.state.currentArchView.title, 'old architecture');
        assert.equal(f.state.currentReasons.CP, 'retained reason');
        assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    });
}

test('secure random-values fallback supplies the same canonical version-four ID to monitor and POST', () => {
    const f = fixture({ crypto: { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) } }); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    assert.doesNotThrow(() => f.window.TaxonomyScoring.runAnalysis());
    assert.equal(f.calls.length, 1);
    const id = f.calls[0][1].headers['X-Analysis-Operation-Id'];
    assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.equal(id, f.observations[0][0]);
});


// Use actual catalogue identities and roles, never a fabricated taxonomy.
const liveCatalogue = JSON.parse(readFileSync(new URL(
    '../../taxonomy-app/src/main/resources/data/nato-taxonomy.json', import.meta.url), 'utf8'));
const liveProduct = liveCatalogue.nodePatches.find(node => node.analysisRole === 'PRODUCT'
    && liveCatalogue.nodePatches.some(parent => parent.code === node.parentCode));
assert.ok(liveProduct, 'Expected a real product with its real family in the catalogue');
const liveFamily = liveCatalogue.nodePatches.find(node => node.code === liveProduct.parentCode);
const unresolvedProviderKey = '__unresolved_provider_key__';

for (const view of ['list', 'tabs', 'tree', 'sunburst', 'graph']) {
    test(`live ${view} scores preserve unresolved provider evidence without interrupting known scores`, () => {
        const f = fixture(); f.progress();
        f.state.taxonomyData = [liveFamily, liveProduct];
        f.state.currentView = view;
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        f.window.TaxonomyScoring.runAnalysis();
        const receive = f.observations[0][1];
        assert.doesNotThrow(() => receive({ rawScores: {
            [unresolvedProviderKey]: 90, [liveFamily.code]: 40, [liveProduct.code]: 80
        } }));
        assert.equal(f.state.currentRawScores[unresolvedProviderKey], 90);
        assert.equal(f.state.currentScores[liveProduct.code], 32);
        const detail = f.state.currentScoreDetails[unresolvedProviderKey];
        assert.equal(detail.kind, 'HIERARCHICAL_RELEVANCE');
        assert.equal(detail.rawScore, 90);
        assert.equal(detail.effectiveRelevance, 90);
        assert.equal(detail.parentCode, null);
        assert.equal(detail.parentScore, null);
        assert.ok(f.state.currentScoreSemanticsWarnings.some(w => w.includes(unresolvedProviderKey)));
        assert.doesNotThrow(() => receive({ rawScores: {
            [liveFamily.code]: 50, [liveProduct.code]: 70, [unresolvedProviderKey]: 5
        } }));
        assert.equal(f.state.currentScores[liveProduct.code], 35);
        assert.equal(f.state.currentRawScores[unresolvedProviderKey], 5);
        assert.equal(f.calls.length, 1, 'Status updates must not restart analysis');
    });
}

test('unresolved-provider diagnostics are bounded without dropping typed score evidence', () => {
    const f = fixture(); f.progress();
    f.state.taxonomyData = [liveFamily, liveProduct];
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.TaxonomyScoring.runAnalysis();
    const scores = Object.fromEntries(Array.from({ length: 200 }, (_, i) => ['unresolved-provider-' + i, 50]));
    assert.doesNotThrow(() => f.observations[0][1]({ rawScores: scores }));
    assert.equal(Object.keys(f.state.currentRawScores).length, 200);
    assert.equal(Object.keys(f.state.currentScoreDetails).length, 200);
    assert.equal(f.state.currentScoreSemanticsWarnings.length, 101);
    assert.equal(f.state.currentScoreSemanticsWarnings.at(-1), 'Additional score-semantics warnings were suppressed.');
});

test('expert-entered raw scores still reject unknown catalogue identities', () => {
    const f = fixture(); f.state.taxonomyData = [liveFamily, liveProduct];
    assert.throws(() => f.window.TaxonomyScoring.applyLocalRawScores(
        { [unresolvedProviderKey]: 90 }, true), /Cannot resolve taxonomy score/);
});
