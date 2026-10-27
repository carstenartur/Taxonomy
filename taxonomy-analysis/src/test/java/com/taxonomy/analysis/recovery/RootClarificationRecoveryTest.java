package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.taxonomy.analysis.recovery.PartialAnalysisResilienceChecks.*;
import static org.junit.jupiter.api.Assertions.*;

/** Root uncertainty is accepted semantic evidence, not a failed request or a numeric exclusion. */
class RootClarificationRecoveryTest {
    private static final String REASON = "The requested approval responsibility is unspecified.";
    private static final String QUESTION = "Must a supervisor approve the recorded time?";
    private static final String DIAGNOSTIC = "ROOT_UNRESOLVED: " + REASON + " " + QUESTION;

    @Test
    void validRootClarificationReplaysWithoutZeroOrAnotherProviderCall() {
        var journal = new Journal();
        var calls = new AtomicInteger();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var first = AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "TEST", List.of("BP"), "root prompt",
                    () -> { calls.incrementAndGet(); return unresolved(); });
            assertEquals("CLARIFICATION", journal.entries.values().iterator().next().state());
            assertTrue(first.getScores().isEmpty());
            assertEquals(REASON, first.getReasons().get("BP"));
            var replay = AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "TEST", List.of("BP"), "root prompt",
                    () -> { calls.incrementAndGet(); return unresolved(); });
            assertEquals(1, calls.get(), "A clarification is not permission for another provider call");
            assertEquals(first.getRawResponse(), replay.getRawResponse());
            assertEquals(REASON, replay.getReasons().get("BP"));
            var root = new JsonMapper().valueToTree(replay).path("rootAssessments").path("BP");
            assertEquals("UNRESOLVED", root.path("status").asString());
            assertTrue(root.path("score").isNull());
            assertEquals(QUESTION, root.path("question").asString());
        }
    }

    @Test
    void clarificationCoverageCountsTheRootAndBlocksOnlyItsDescendants() {
        var coverage = AnalysisCoverage.derive(tree(), Map.of(), Map.of(), Map.of("BP", "CLARIFICATION:" + REASON + " " + QUESTION));
        assertEquals(AnalysisCoverage.State.UNKNOWN, coverage.nodes().get("BP").state());
        assertNull(coverage.nodes().get("BP").score());
        assertNull(coverage.nodes().get("BP").effectiveRelevance());
        assertEquals("BLOCKED_BY:BP", coverage.nodes().get("BP-1").reason());
        assertEquals(3, coverage.failedOrBlockedNodes(), "The root itself remains an open assessment");
    }

    @Test
    void cancellationAfterTheRootResponsePreservesItsClarification() {
        var journal = new Journal();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var stopped = assertThrows(AnalysisStoppedException.class, () ->
                    AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "TEST", List.of("BP"), "root prompt",
                            () -> { throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED)
                                    .withCompletedCall(unresolved()); }));
            assertEquals(AnalysisStoppedException.Reason.CANCELLED, stopped.reason());
            assertEquals(REASON, stopped.partialReasons().get("BP"));
            assertTrue(stopped.partialScores().isEmpty());
            assertEquals("CLARIFICATION", journal.entries.values().iterator().next().state());
        }
    }

    @Test
    void aDiagnosticPrefixAloneDoesNotTurnABrokenResponseIntoAClarification() {
        var journal = new Journal();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var result = AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "TEST", List.of("BP"), "root prompt",
                    () -> answer(Map.of("BP", 0d), DIAGNOSTIC, "invalid or missing typed evidence"));
            assertEquals("UNASSESSED", journal.entries.values().iterator().next().state());
            assertTrue(result.getScores().isEmpty());
        }
    }

    @Test
    void anInconsistentSavedClarificationCannotReplayANumericExclusion() {
        var journal = new Journal();
        try (var session = new AnalysisCheckpointSession(journal)) {
            AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "TEST", List.of("BP"), "root prompt", RootClarificationRecoveryTest::unresolved);
            journal.entries.replaceAll((key, saved) -> {
                var inconsistent = unresolved(); inconsistent.setScores(Map.of("BP", 0d));
                return new AnalysisCheckpointSession.Checkpoint(saved.question(), "CLARIFICATION", inconsistent);
            });
            var replay = AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "TEST", List.of("BP"), "root prompt",
                    () -> { throw new AssertionError("An inconsistent saved answer cannot authorize a provider call"); });
            assertTrue(replay.getScores().isEmpty(), "A saved clarification cannot be replayed as a zero score");
        }
    }

    @Test
    void persistedClarificationKeepsItsQuestionUntilAnExplicitRetry() {
        try (var db = new Database()) {
            var request = request();
            var claim = db.begin(request);
            try (var session = db.session(claim)) {
                AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "TEST", List.of("BP"), "root prompt", RootClarificationRecoveryTest::unresolved);
            }
            var partial = new AnalysisResult(Map.of(), tree()); partial.setStatus("PARTIAL");
            var completed = db.call(store -> store.complete(claim, partial, tree()));
            var saved = db.call(store -> store.read(request.getContinuationId(), scope()));
            assertEquals("COMPLETED_WITH_GAPS", saved.recovery().state());
            assertEquals(1, saved.recovery().openQuestions().size());
            assertEquals(DIAGNOSTIC, saved.recovery().openQuestions().getFirst().error());
            var root = saved.result().getAnalysisCoverage().nodes().get("BP");
            assertEquals(AnalysisCoverage.State.UNKNOWN, root.state());
            assertNull(root.score());
            assertEquals("CLARIFICATION:" + REASON + " " + QUESTION, root.reason());
            assertEquals(3, saved.result().getAnalysisCoverage().failedOrBlockedNodes());
            var question = saved.recovery().openQuestions().getFirst().key();
            var detail = db.call(store -> store.detail(request.getContinuationId(), question, scope()));
            assertEquals(QUESTION, new JsonMapper().valueToTree(detail).path("rootAssessments").path("BP").path("question").asString());

            request.setContinuationAction("RETRY");
            request.setContinuationVersion(completed.getRecovery().version());
            request.setContinuationQuestions(List.of(question));
            var retry = db.begin(request);
            var calls = new AtomicInteger();
            try (var session = db.session(retry)) {
                var accepted = AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "TEST", List.of("BP"), "root prompt",
                        () -> { calls.incrementAndGet(); return answer(Map.of("BP", 20d), null, "{\"BP\":20}"); });
                assertEquals(20d, accepted.getScores().get("BP"));
            }
            assertEquals(1, calls.get());
            var result = new AnalysisResult(Map.of("BP", 20d), tree()); result.setStatus("SUCCESS");
            var settled = db.call(store -> store.complete(retry, result, tree()));
            assertTrue(settled.getRecovery().openQuestions().isEmpty());
            assertEquals(AnalysisCoverage.State.RELEVANT, settled.getAnalysisCoverage().nodes().get("BP").state());
        }
    }

    private static LlmCallDetail unresolved() {
        // Reading the actual wire shape also covers additive-field compatibility with older details.
        return JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build().readValue("""
                {"scores":{},"reasons":{"BP":"The requested approval responsibility is unspecified."},
                 "rootAssessments":{"BP":{"status":"UNRESOLVED","score":null,
                   "reason":"The requested approval responsibility is unspecified.",
                   "question":"Must a supervisor approve the recorded time?"}},
                 "provider":"TEST","rawResponse":"valid root clarification",
                 "error":"ROOT_UNRESOLVED: The requested approval responsibility is unspecified. Must a supervisor approve the recorded time?"}
                """, LlmCallDetail.class);
    }
}
