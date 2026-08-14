package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalGitReviewUiContractTest {

    private static final Path RELATION_API = Path.of(
            "src/main/resources/static/js/api/relations-api.js");
    private static final Path PROPOSAL_API = Path.of(
            "src/main/resources/static/js/api/proposals-api.js");
    private static final Path PROPOSAL_ADAPTER = Path.of(
            "src/main/resources/static/js/relations/"
                    + "taxonomy-proposals-git-commands.js");

    @Test
    void loadedAdapterCapturesEveryProductiveProposalReviewButton()
            throws Exception {
        String loader = Files.readString(RELATION_API);
        String adapter = Files.readString(PROPOSAL_ADAPTER);

        assertThat(loader)
                .contains("window.TaxonomyProposalsApiReady")
                .contains("new URL('proposals-api.js', loader.src)")
                .contains("taxonomy-proposals-git-commands.js")
                .contains("data-taxonomy-proposal-command-adapter");
        assertThat(adapter)
                .contains("document.addEventListener('click', "
                        + "captureProposalCommand, true)")
                .contains("event.stopImmediatePropagation()")
                .contains("#bulkAcceptBtn")
                .contains("#bulkRejectBtn")
                .contains(".proposal-table .btn-accept")
                .contains(".proposal-table .btn-reject")
                .contains("window._proposalAccept = function")
                .contains("window._proposalReject = function");
    }

    @Test
    void transportLivesInNamedRawResponseApiBoundary() throws Exception {
        String api = Files.readString(PROPOSAL_API);
        String adapter = Files.readString(PROPOSAL_ADAPTER);

        assertThat(api)
                .contains("window.TaxonomyProposalsApi")
                .contains("fetch('/api/proposals/head'")
                .contains("cache: 'no-store'")
                .contains("function review(proposalId, action, headers)")
                .contains("method: 'POST'")
                .contains("headers: headers");
        assertThat(adapter)
                .contains("ProposalsApi().readHead()")
                .contains("ProposalsApi().review(")
                .doesNotContain("fetch(");
    }

    @Test
    void everyUserActionReadsAHeadThenAdvancesEtagInOrder()
            throws Exception {
        String adapter = Files.readString(PROPOSAL_ADAPTER);

        int actionStart = adapter.indexOf(
                "    function reviewMany(ids, action, offerUndo) {");
        int headRead = adapter.indexOf("readHead()", actionStart);
        int processing = adapter.indexOf(
                "return processNext(ids, action, operationKey, state, 0)",
                headRead);
        int command = adapter.indexOf(
                "return ProposalsApi().review(", processing);
        int etagAdvance = adapter.indexOf(
                "if (outcome.etag) state.etag = outcome.etag", command);
        int nextCommand = adapter.indexOf(
                "return processNext(ids, action, operationKey, state, index + 1)",
                etagAdvance);

        assertThat(actionStart).isGreaterThanOrEqualTo(0);
        assertThat(headRead).isGreaterThan(actionStart);
        assertThat(processing).isGreaterThan(headRead);
        assertThat(command).isGreaterThan(processing);
        assertThat(etagAdvance).isGreaterThan(command);
        assertThat(nextCommand).isGreaterThan(etagAdvance);
        assertThat(adapter)
                .contains("'If-Match': state.etag")
                .contains("'Idempotency-Key': operationKey")
                .contains("response.status === 202")
                .contains("response.status === 412")
                .contains("state.stopped = true");
    }

    @Test
    void bulkAndUndoUseTheSameOrderedGitFirstCommandPath()
            throws Exception {
        String adapter = Files.readString(PROPOSAL_ADAPTER);

        assertThat(adapter)
                .contains("reviewMany(selectedProposalIds(), 'ACCEPT', true)")
                .contains("reviewMany(selectedProposalIds(), 'REJECT', true)")
                .contains("reviewMany(ids, 'REVERT', false)")
                .contains("state.projectedIds")
                .contains("state.failedIds")
                .contains("PENDING_RECOVERY")
                .contains("showUndoToast(state.projectedIds, action)");
    }

    @Test
    void concurrentOrPendingOutcomesAreNotReportedAsOrdinarySuccess()
            throws Exception {
        String adapter = Files.readString(PROPOSAL_ADAPTER);

        int pending = adapter.indexOf("if (response.status === 202)");
        int conflict = adapter.indexOf("if (response.status === 412)");
        int success = adapter.indexOf("if (response.ok)", conflict);

        assertThat(pending).isGreaterThanOrEqualTo(0);
        assertThat(conflict).isGreaterThan(pending);
        assertThat(success).isGreaterThan(conflict);
        assertThat(adapter)
                .contains("showStatus(\n                'warning'")
                .contains("The proposal queue has been reloaded")
                .contains("the readable projection still requires recovery")
                .doesNotContain("Promise.all(promises)");
    }
}
