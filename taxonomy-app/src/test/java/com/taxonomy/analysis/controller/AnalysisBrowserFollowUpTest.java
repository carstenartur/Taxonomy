package com.taxonomy.analysis.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** JUnit owns browser contracts; the pinned JS runtime only executes the real browser modules. */
class AnalysisBrowserFollowUpTest {
    @TempDir Path temporary;
    @Test void zeroOnlyEvidenceKeepsTheExportControlsAvailable() throws Exception {
        run("""
            S.currentScores = {BP: 0};
            window.TaxonomyBrowse.updateExportGroupVisibility();
            assert.equal(elements.exportGroup.style.display, '');
            S.currentScores = {};
            window.TaxonomyBrowse.updateExportGroupVisibility();
            assert.equal(elements.exportGroup.style.display, 'none');
            """);
    }
    @Test void leafRequestRetainsProviderIdAndAuthorizedOperation() throws Exception {
        run("""
            S.storedBusinessText = 'requirement';
            S.analysisProviderId = 'DEMO.TIME-TRACKING-V1.R0';
            S.analysisOperationId = '10000000-0000-4000-8000-000000000001';
            window.TaxonomyScoring.requestLeafJustification('BP', {textContent: 'Explain'});
            assert.equal(requests.length, 1);
            const payload = JSON.parse(requests[0].options.body);
            assert.equal(payload.provider, S.analysisProviderId);
            assert.equal(payload.operationId, S.analysisOperationId);
            """);
    }
    @Test void legacyImportedReplayWithoutOperationIdentityCannotReachADefaultProvider() throws Exception {
        run("""
            S.storedBusinessText = 'requirement';
            S.lastAnalysisProvider = 'Scenario replay time-tracking-v1 R0';
            window.TaxonomyBrowse.showStatus = () => {};
            window.TaxonomyScoring.requestLeafJustification('BP', {textContent: 'Explain'});
            assert.equal(requests.length, 0);
            """);
    }
    @Test void replayedServerOperationIdentityOverridesTheNewTransportRequestId() throws Exception {
        run("""
            window.TaxonomyBrowse = {renderView() {}, showStatus() {}};
            window.TaxonomyScoring.applyAnalysisResult({scores: {BP: 0}, status: 'SUCCESS',
                analysisOperationId: 'original-admitted-operation', analysisProviderId: 'DEMO.TIME-TRACKING-V1.R0'},
                {text: 'requirement', operationId: 'new-unadmitted-request', provider: 'OPENAI', options: {recoveryManaged: true}});
            assert.equal(S.analysisOperationId, 'original-admitted-operation');
            assert.equal(S.analysisProviderId, 'DEMO.TIME-TRACKING-V1.R0');
            """);
    }
    private void run(String assertions) throws Exception {
        var script = temporary.resolve("browser.cjs");
        Files.writeString(script, """
            const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
            const S = {}, requests = [];
            const elements = {exportGroup: {style: {}}, exportHint: {classList: {toggle() {}}}};
            const context = {window: {TaxonomyState: S}, console: {log() {}, error() {}},
                TaxonomyI18n: {t: v => v}, TaxonomyUtils: {escapeHtml: v => v},
                document: {addEventListener() {}, getElementById: id => elements[id] || null},
                fetch: (url, options) => {requests.push({url, options}); return new Promise(() => {});}};
            vm.createContext(context);
            vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), context);
            vm.runInContext(fs.readFileSync(process.argv[3], 'utf8').replace('runAnalysis: runAnalysis,',
                'applyAnalysisResult: applyAnalysisResult, runAnalysis: runAnalysis,'), context);
            const window = context.window;
            """ + assertions);
        Path base = Path.of("..").toAbsolutePath().normalize().resolve("taxonomy-app/src/main/resources/static/js/core");
        var log = temporary.resolve("node.log");
        var process = new ProcessBuilder(List.of(System.getProperty("taxonomy.node.executable"), script.toString(),
                base.resolve("taxonomy-browse.js").toString(), base.resolve("taxonomy-scoring.js").toString()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("browser checks completed").isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
