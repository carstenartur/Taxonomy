package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HypothesisGitReviewUiContractTest {

    private static final Path RELATION_API = Path.of(
            "src/main/resources/static/js/api/relations-api.js");
    private static final Path HYPOTHESIS_API = Path.of(
            "src/main/resources/static/js/api/hypotheses-api.js");
    private static final Path ADAPTER = Path.of(
            "src/main/resources/static/js/relations/"
                    + "taxonomy-hypotheses-git-commands.js");
    private static final Path DTO = Path.of(
            "../taxonomy-domain/src/main/java/com/taxonomy/dto/"
                    + "RelationHypothesisDto.java");

    @Test
    void relationApiLoaderInstallsDirectHypothesisReviewAdapter()
            throws Exception {
        String loader = Files.readString(RELATION_API);

        assertThat(loader)
                .contains("window.TaxonomyHypothesesApiReady")
                .contains("new URL('hypotheses-api.js', loader.src)")
                .contains("taxonomy-hypotheses-git-commands.js")
                .contains("data-taxonomy-hypothesis-command-adapter");
    }

    @Test
    void persistedAnalysisDtoExposesExactReviewIdentity() throws Exception {
        String dto = Files.readString(DTO);

        assertThat(dto)
                .contains("private Long hypothesisId")
                .contains("Long getHypothesisId()")
                .contains("void setHypothesisId(Long hypothesisId)");
    }

    @Test
    void transportIsIsolatedInRawResponseApiBoundary() throws Exception {
        String api = Files.readString(HYPOTHESIS_API);
        String adapter = Files.readString(ADAPTER);

        assertThat(api)
                .contains("window.TaxonomyHypothesesApi")
                .contains("fetch('/api/dsl/hypotheses/head'")
                .contains("function review(hypothesisId, action, headers)")
                .contains("method: 'POST'")
                .contains("headers: headers");
        assertThat(adapter)
                .contains("Api().readHead()")
                .contains("Api().review(command.id, action, headers)")
                .contains("Api().applyForSession(id)")
                .doesNotContain("fetch(");
    }

    @Test
    void compatibilityFunctionsBypassProposalConversionAndUiOnlyDismiss()
            throws Exception {
        String adapter = Files.readString(ADAPTER);

        assertThat(adapter)
                .contains("window._acceptHypothesis = function")
                .contains("window._rejectHypothesis = function")
                .contains("window._acceptAllHighConfidence = function")
                .contains("reviewIndices([index], 'ACCEPT', true)")
                .contains("reviewIndices([index], 'REJECT', true)")
                .doesNotContain("/api/proposals/from-hypothesis")
                .doesNotContain("just hides the row")
                .doesNotContain("Promise.all(");
    }

    @Test
    void everyActionUsesStrongHeadIdempotencyAndSequentialEtagProgression()
            throws Exception {
        String adapter = Files.readString(ADAPTER);

        int actionStart = adapter.indexOf(
                "    function reviewIndices(indices, action, offerUndo) {");
        int headRead = adapter.indexOf("readHead()", actionStart);
        int process = adapter.indexOf("return processNext(", headRead);
        int command = adapter.indexOf("return Api().review(", process);
        int etagAdvance = adapter.indexOf(
                "if (outcome.etag) state.etag = outcome.etag", command);
        int next = adapter.indexOf("return processNext(", etagAdvance);

        assertThat(actionStart).isGreaterThanOrEqualTo(0);
        assertThat(headRead).isGreaterThan(actionStart);
        assertThat(process).isGreaterThan(headRead);
        assertThat(command).isGreaterThan(process);
        assertThat(etagAdvance).isGreaterThan(command);
        assertThat(next).isGreaterThan(etagAdvance);
        assertThat(adapter)
                .contains("'If-Match': state.etag")
                .contains("'Idempotency-Key': operationKey")
                .contains("response.status === 202")
                .contains("response.status === 412")
                .contains("state.stopped = true");
    }

    @Test
    void successfulReviewOffersGitFirstRevertWhilePendingDoesNot()
            throws Exception {
        String adapter = Files.readString(ADAPTER);

        assertThat(adapter)
                .contains("reviewIndices([command.index], 'REVERT', false)")
                .contains("class=\"btn btn-sm btn-link")
                .contains("Recovery pending")
                .contains("Run the analysis again to refresh the review queue")
                .contains("setBusy(state.commands, false)");
    }
}
