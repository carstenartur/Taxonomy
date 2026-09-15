import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js', import.meta.url), 'utf8');
function fixture() {
    const statuses = [], calls = [], observations = [];
    const state = { currentReasons: { CP: 'retained reason' }, currentRawScores: { CP: 80 },
        lastAnalysisStatus: 'SUCCESS', taxonomyData: [], currentScoreDetails: {}, currentScores: { CP: 80 } };
    const window = { TaxonomyState: state, TaxonomyBrowse: {
        showStatus: (...args) => statuses.push(args), clearStatus() {}
    } };
    const textField = { value: 'communications', classList: { remove() {} } };
    const document = { getElementById: id => id === 'businessText' ? textField
        : id === 'includeArchitectureView' ? { checked: true } : null };
    const sandbox = { window, document, console: { log() {}, error() {} },
        TaxonomyI18n: { t: key => key }, TaxonomyUtils: { escapeHtml: text => text },
        crypto: { randomUUID: () => 'cb2a3d71-e849-4a50-9855-1f9cb8f81402' },
        fetch: (...args) => { calls.push(args); return new Promise(() => {}); }
    };
    vm.runInNewContext(source, sandbox);
    function progress() { window.TaxonomyAnalysisProgress = { start: (...args) => {
        observations.push(args); return { finish() {}, stop() {}, cancel() {} };
    } }; }
    return { window, state, statuses, calls, observations, progress };
}

for (const phase of ['no lifecycle', 'progress loaded before lifecycle', 'loader still running']) {
    test(`full analysis does not start or clear results with ${phase}`, () => {
        const f = fixture();
        if (phase !== 'no lifecycle') f.progress();
        if (phase === 'loader still running') {
            f.window.TaxonomyAnalysisSession = {};
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
    f.window.TaxonomyAnalysisSession = {};
    f.window.__taxonomyAnalysisSessionLoading = false;
    f.window.TaxonomyScoring.runAnalysis();
    assert.equal(f.observations.length, 1);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0][0], '/api/analyze');
    assert.equal(f.calls[0][1].headers['X-Analysis-Operation-Id'], f.observations[0][0]);
    assert.equal(JSON.parse(f.calls[0][1].body).includeArchitectureView, true);
});
