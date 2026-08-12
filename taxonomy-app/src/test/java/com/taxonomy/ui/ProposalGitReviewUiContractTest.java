package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalGitReviewUiContractTest {

    private static final Path PROPOSAL_ADAPTER = Path.of(
            "src/main/resources/static/js/relations/"
                    + "taxonomy-proposals-git-commands.js");
    private static final Path QUALITY_MODULE = Path.of(
            "src/main/resources/static/js/relations/taxonomy-quality.js");

    @Test
    void siblingAdapterReplacesEveryLegacyProposalDecisionRoute()
            throws Exception {
        String adapter = Files.readString(PROPOSAL_ADAPTER);
        String loader = Files.readString(QUALITY_MODULE);

        assertThat(loader)
                .contains("'taxonomy-proposals-git-commands.js', loader.src")
                .doesNotContain("script.src = '/js/");
        assertThat(adapter)
                .contains("window._proposalAccept = function")
                .contains("window._proposalReject = function")
                .contains("target.closest('#bulkAcceptBtn')")
                .contains("target.closest('#bulkRejectBtn')")
                .contains("data-git-authoritative=\"true\"")
                .contains("/api/architecture/proposals/")
                .contains("'If-Match'")
                .contains("'If-None-Match'")
                .contains("'Idempotency-Key'")
                .contains("response.status === 202")
                .contains("response.status === 412")
                .doesNotContain("'/api/proposals/' + id + '/accept'")
                .doesNotContain("'/api/proposals/' + id + '/reject'")
                .doesNotContain("'/api/proposals/' + id + '/revert'")
                .doesNotContain("fetch('/api/proposals/bulk'");
    }

    @Test
    void readsExactAuthorityBeforeSequencingAndAdvancesEveryResponseEtag()
            throws Exception {
        String adapter = Files.readString(PROPOSAL_ADAPTER);

        int reviewStart = adapter.indexOf(
                "    function reviewProposals(ids, action) {");
        int authorityRead = adapter.indexOf(
                "refreshAuthority()", reviewStart);
        int sequenceStart = adapter.indexOf(
                "runSequentially(", authorityRead);
        int sequenceFunction = adapter.indexOf(
                "    function runSequentially(ids, action, authority, completed) {");
        int promiseSeed = adapter.indexOf(
                "var sequence = Promise.resolve();", sequenceFunction);
        int command = adapter.indexOf(
                "sendReviewCommand(id, action, authority)", promiseSeed);
        int etagAdvance = adapter.indexOf(
                "authority.etag = nextEtag;", command);

        assertThat(reviewStart).isGreaterThanOrEqualTo(0);
        assertThat(authorityRead).isGreaterThan(reviewStart);
        assertThat(sequenceStart).isGreaterThan(authorityRead);
        assertThat(sequenceFunction).isGreaterThan(sequenceStart);
        assertThat(promiseSeed).isGreaterThan(sequenceFunction);
        assertThat(command).isGreaterThan(promiseSeed);
        assertThat(etagAdvance).isGreaterThan(command);
        assertThat(adapter)
                .contains("fetch('/api/relations/count', { cache: 'no-store' })")
                .contains("(response.ok || response.status === 409) && etag")
                .doesNotContain("Promise.all(");
    }

    @Test
    void bulkReviewIsBusyAwareProgressiveAndUndoUsesGitRevert()
            throws Exception {
        String adapter = Files.readString(PROPOSAL_ADAPTER);

        assertThat(adapter)
                .contains("setBusy(true)")
                .contains("setBusy(false)")
                .contains("container.setAttribute('aria-busy'")
                .contains("updateProgress(index + 1, ids.length, action)")
                .contains("count.textContent = completed + '/' + total")
                .contains("reviewProposals(ids, 'REVERT')")
                .contains("showUndoToast(completed, action)")
                .contains("refreshProposalBrowser()")
                .contains("active.click()");
    }
}
