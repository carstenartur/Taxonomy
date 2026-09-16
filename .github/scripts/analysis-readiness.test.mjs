import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js', import.meta.url), 'utf8');
function fixture(options = {}) {
    const statuses = [], calls = [], observations = [], renders = [], order = [], progressActions = [];
    let rejectRequest, activeMonitor;
    const requests = [];
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
        TaxonomyI18n: { t: (key, ...values) => [key, ...values].join(': ') }, TaxonomyUtils: { escapeHtml: text => text },
        crypto: { randomUUID: () => 'cb2a3d71-e849-4a50-9855-1f9cb8f81402' },
        fetch: (...args) => { calls.push(args); order.push('post'); return new Promise((resolve, reject) => { rejectRequest = reject; requests.push({ resolve, reject }); }); }
    };
    if (Object.hasOwn(options, 'crypto')) sandbox.crypto = options.crypto;
    vm.runInNewContext(source, sandbox);
    function progress() { window.TaxonomyAnalysisProgress = { start: (...args) => {
        observations.push(args); order.push('monitor');
        const workspace = sessionState.workspaceId;
        const monitor = { acceptsResult: () => activeMonitor === monitor && sessionState.workspaceId === workspace,
            finish() { progressActions.push('finish'); }, stop() { progressActions.push('stop'); },
            cancel() { progressActions.push('cancel'); }, transportFailed() { progressActions.push('transportFailed'); } };
        activeMonitor = monitor;
        return monitor;
    } }; }
    return { window, state, sessionState, statuses, calls, observations, progress, renders, order,
        requests, progressActions, rejectRequest: error => rejectRequest(error) };
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


test('lost POST uses bounded original-operation cleanup instead of premature terminal finish', async () => {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.TaxonomyScoring.runAnalysis();
    f.rejectRequest(new TypeError('network connection lost'));
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(f.progressActions, ['transportFailed']);
    assert.equal(f.calls.length, 1);
});

test('rejected HTTP admission finishes normally without a cleanup cancellation', async () => {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.TaxonomyScoring.runAnalysis();
    f.rejectRequest(Object.assign(new Error('HTTP 503'), { httpStatus: 503 }));
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(f.progressActions, ['finish']);
});


function readyFixture() {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    return f;
}
const flushResults = () => new Promise(resolve => setImmediate(resolve));
const completedResult = (scores = {}) => ({ status: 'SUCCESS', scores, reasons: {}, warnings: [] });

for (const ending of ['success', 'network failure', 'HTTP rejection']) {
    test(`superseded full-analysis ${ending} cannot overwrite a newer run`, async () => {
        const f = readyFixture();
        f.window.TaxonomyScoring.runAnalysis();
        f.window.TaxonomyScoring.runAnalysis();
        const count = f.renders.length;
        if (ending === 'success') f.requests[0].resolve({ ok: true, json: async () => completedResult({ CP: 90 }) });
        else f.requests[0].reject(Object.assign(new Error('obsolete failure'),
            ending === 'HTTP rejection' ? { httpStatus: 503 } : {}));
        await flushResults();
        assert.equal(f.state.lastAnalysisStatus, 'IN_PROGRESS');
        assert.equal(f.state.currentRawScores.CP, undefined);
        assert.equal(f.renders.length, count);
        assert.equal(f.statuses.length, 0);
        assert.equal(f.progressActions.length, 0);
        f.requests[1].resolve({ ok: true, json: async () => completedResult({ CP: 40 }) });
        await flushResults();
        assert.equal(f.state.currentRawScores.CP, 40);
        assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    });
}

for (const ending of ['success', 'failure']) {
    test(`full-analysis ${ending} after workspace invalidation does not change the new context`, async () => {
        const f = readyFixture(); f.window.TaxonomyScoring.runAnalysis();
        f.sessionState.workspaceId = 'workspace-b';
        const count = f.renders.length;
        if (ending === 'success') f.requests[0].resolve({ ok: true, json: async () => completedResult({ CP: 90 }) });
        else f.requests[0].reject(new Error('obsolete error'));
        await flushResults();
        assert.equal(f.renders.length, count);
        assert.equal(f.state.currentRawScores.CP, undefined);
        assert.equal(f.statuses.length, 0);
        assert.equal(f.progressActions.length, 0);
    });
}

for (const [status, body, expected] of [
    [400, { error: 'Unknown provider: unavailable' }, 'Unknown provider: unavailable'],
    [403, { detail: 'Full analysis requires an isolated workspace' }, 'Full analysis requires an isolated workspace'],
    [503, { message: 'Taxonomy data is still loading' }, 'Taxonomy data is still loading'],
    [503, { detail: '', error: 'Analysis capacity exhausted' }, 'Analysis capacity exhausted'],
    [503, { detail: 'x'.repeat(5000) }, 'x'.repeat(1024)]
]) {
    test(`full-analysis HTTP ${status} retains bounded server diagnostic ${JSON.stringify(body).slice(0, 70)}`, async () => {
        const f = readyFixture(); f.window.TaxonomyScoring.runAnalysis();
        f.requests[0].resolve({ ok: false, status, json: async () => body });
        await flushResults();
        assert.equal(f.state.lastAnalysisStatus, 'ERROR');
        assert.equal(f.statuses.at(-1)[1], 'scoring.analysis.error: ' + expected);
        assert.deepEqual(f.progressActions, ['finish']);
    });
}

for (const body of [null, {}, { detail: { internal: 'not a user-facing string' } }, 'invalid-json']) {
    test(`HTTP failure keeps numeric fallback for absent or malformed diagnostics ${JSON.stringify(body)}`, async () => {
        const f = readyFixture(); f.window.TaxonomyScoring.runAnalysis();
        f.requests[0].resolve({ ok: false, status: 502, json: async () => {
            if (body === 'invalid-json') throw new SyntaxError('invalid JSON');
            return body;
        } });
        await flushResults();
        assert.equal(f.statuses.at(-1)[1], 'scoring.analysis.error: HTTP 502');
        assert.deepEqual(f.progressActions, ['finish']);
    });
}

for (const replace of [false, true]) {
    test(`valid manual score edits remain possible after unresolved provider evidence (replace=${replace})`, () => {
        const f = fixture(); f.state.taxonomyData = [liveFamily, liveProduct];
        const api = f.window.TaxonomyScoring;
        api.applyLocalRawScores({ [liveFamily.code]: 40, [liveProduct.code]: 80, [unresolvedProviderKey]: 90 }, true, true);
        assert.doesNotThrow(() => api.applyLocalRawScores({ [liveFamily.code]: 50 }, replace));
        assert.equal(f.state.currentRawScores[liveFamily.code], 50);
        if (!replace) {
            assert.equal(f.state.currentRawScores[unresolvedProviderKey], 90);
            assert.equal(f.state.currentProductSuitabilityScores[liveProduct.code], 80);
            assert.equal(f.state.currentScores[liveProduct.code], 40);
            assert.ok(f.state.currentScoreSemanticsWarnings.some(w => w.includes(unresolvedProviderKey)));
        } else assert.equal(f.state.currentRawScores[unresolvedProviderKey], undefined);
    });
}

test('retained provider evidence does not authorize newly entered unknown keys', () => {
    const f = fixture(); f.state.taxonomyData = [liveFamily, liveProduct];
    const api = f.window.TaxonomyScoring;
    api.applyLocalRawScores({ [liveFamily.code]: 40, [unresolvedProviderKey]: 90 }, true, true);
    for (const key of [unresolvedProviderKey, '__new_unknown_key__']) {
        assert.throws(() => api.applyLocalRawScores({ [key]: 10 }, false), /Cannot resolve taxonomy score/);
        assert.equal(f.state.currentRawScores[unresolvedProviderKey], 90);
        assert.equal(f.state.currentRawScores.__new_unknown_key__, undefined);
    }
});
