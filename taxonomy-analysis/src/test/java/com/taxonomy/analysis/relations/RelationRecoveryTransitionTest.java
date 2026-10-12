package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchReport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real use case, relation service, protocol and architecture projection; provider and journal are controlled. */
class RelationRecoveryTransitionTest {
    @Test
    void invalidSourceExtractionCanBeRetriedThenReachArchitecture() throws Exception {
        assertRetry(true, 1, 5);
    }

    @Test
    void invalidNavigationCanBeRetriedWithoutRepeatingValidatedSourceExtraction() throws Exception {
        assertRetry(false, 2, 4);
    }

    private static void assertRetry(boolean failSource, int firstCalls, int continuationCalls) throws Exception {
        var journal = new Journal();
        var fixture = fixture(failSource);
        AnalysisResult paused = analyze(fixture, journal);
        assertPause(paused, journal, firstCalls, fixture);
        String failedKey = journal.decide("READY");

        AnalysisResult resumed = analyze(fixture, journal);
        assertNotNull(resumed.getArchitectureView(), "successful retry must reach architecture projection");
        assertEquals("SUCCESS", resumed.getStatus());
        assertTrue(resumed.getRelationSearchReport().isSearchExhausted());
        assertEquals(1, resumed.getRelationSearchReport().result().edges().size());
        assertEquals(firstCalls + continuationCalls, fixture.raw().get(), "accepted exchanges must replay locally");
        assertEquals(continuationCalls, resumed.getRelationSearchReport().totalCalls());
        assertEquals("SUCCESS", journal.entries.get(failedKey).state());
        assertEquals(Map.of("process", 1), resumed.getScores(), "relation recovery cannot rewrite node assessments");
        assertEquals(0, fixture.legacy().get(), "recovery cannot fall back to score-inferred relations");
    }

    @Test
    void leavingFailedNavigationOpenContinuesIndependentRelationWorkAndRendersPartialArchitecture() throws Exception {
        var journal = new Journal();
        var fixture = fixture(false);
        AnalysisResult paused = analyze(fixture, journal);
        assertPause(paused, journal, 2, fixture);
        String failedKey = journal.decide("SKIPPED");

        AnalysisResult resumed = analyze(fixture, journal);
        assertNotNull(resumed.getArchitectureView(), "leaving one relation area open must allow projection to proceed");
        assertEquals("PARTIAL", resumed.getStatus());
        assertFalse(resumed.getRelationSearchReport().isSearchExhausted());
        assertEquals("SKIPPED", journal.entries.get(failedKey).state());
        assertEquals(3, fixture.raw().get(), "only the remaining independent PRODUCES assessment is sent");
        assertTrue(resumed.getRelationSearchReport().result().trace().stream().anyMatch(trace -> trace.type().equals("PRODUCES")));
        assertTrue(resumed.getRelationSearchReport().result().trace().stream().noneMatch(trace -> trace.type().equals("CONSUMES")),
                "the failed CONSUMES assessment must remain unknown, without a fabricated negative finding");
        assertTrue(resumed.getRelationSearchReport().result().unfinished().stream().anyMatch(item -> item.type().equals("CONSUMES")));
        assertTrue(resumed.getRelationSearchReport().result().edges().isEmpty());
        assertTrue(resumed.getArchitectureView().getIncludedRelationships().isEmpty());
        assertEquals(Map.of("process", 1), resumed.getScores());
        assertEquals(0, fixture.legacy().get());
    }

    private static RelationSearchUseCaseContract.Fixture fixture(boolean failSource) throws Exception {
        var failed = new AtomicBoolean();
        return RelationSearchUseCaseContract.fixture(true, 24, LlmProvider.CUSTOM_OPENAI, prompt -> {
            boolean source = prompt.contains("Extract ONLY each source");
            if (source == failSource && failed.compareAndSet(false, true)) return "invalid JSON";
            return RequirementRelationSearchContract.answer(prompt);
        });
    }

    private static AnalysisResult analyze(RelationSearchUseCaseContract.Fixture fixture, Journal journal) {
        try (var session = new AnalysisCheckpointSession(journal)) {
            AnalysisResult result = fixture.analyze(true);
            journal.previous = result.getRelationSearchReport();
            return result;
        }
    }

    private static void assertPause(AnalysisResult result, Journal journal, int calls,
                                    RelationSearchUseCaseContract.Fixture fixture) {
        assertEquals("PARTIAL", result.getStatus());
        assertNull(result.getArchitectureView(), "an unresolved provider failure pauses before architecture projection");
        assertTrue(result.getRelationSearchReport().stopReason().startsWith("AWAITING_DECISION:"));
        assertTrue(journal.paused);
        assertEquals(calls, fixture.raw().get());
        var failure = journal.entries.values().stream().filter(checkpoint -> checkpoint.state().equals("FAILED")).findFirst().orElseThrow();
        assertEquals("invalid JSON", failure.detail().getRawResponse(), "raw failure evidence remains available");
        assertTrue(failure.detail().getError().startsWith("INVALID_RELATION_RESPONSE:"));
        assertTrue(failure.detail().getScores().isEmpty(), "invalid relations cannot become node-score evidence");
    }

    private static final class Journal implements AnalysisCheckpointSession.Store {
        private final Map<String, AnalysisCheckpointSession.Checkpoint> entries = new LinkedHashMap<>();
        private RelationSearchReport previous;
        private boolean paused;

        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
            var saved = entries.get(question.key());
            return saved == null || saved.state().equals("READY")
                    ? new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null) : saved;
        }

        public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
            entries.put(question.key(), new AnalysisCheckpointSession.Checkpoint(question, state, detail));
            paused = state.equals("FAILED");
        }

        public void checkActive() {
            if (paused) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION);
        }

        public RelationSearchReport previousRelations() { return previous; }

        String decide(String state) {
            var failed = entries.values().stream().filter(checkpoint -> checkpoint.state().equals("FAILED")).findFirst().orElseThrow();
            entries.put(failed.question().key(), new AnalysisCheckpointSession.Checkpoint(failed.question(), state, failed.detail()));
            paused = false;
            return failed.question().key();
        }
    }
}
