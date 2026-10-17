package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.analysis.recovery.RecoveryCheckpointPersistenceProbe;
import com.taxonomy.dto.RelationSearchModel.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A successful parent retry must not strand a selected corrective question in READY. */
class RelationCorrectiveCheckpointTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "arbeitszeiterfassungen";
    private static final Node FIRST = new Node("UA-first", "UA", "Time recording", "Record working time", false);
    private static final Node SECOND = new Node("UA-second", "UA", "Time sheet", "Enter contracted hours worked", false);

    @Test void aSuccessfulSourceParentRetryRetiresTheSelectedCorrectiveCheckpoint() {
        assertParentRetry(true);
    }

    @Test void aSuccessfulNavigationParentRetryRetiresTheSelectedCorrectiveCheckpoint() {
        assertParentRetry(false);
    }

    @Test void aSuccessfulParentRetryAlsoCoversPreviouslySkippedCorrections() {
        for (boolean sourcePhase : List.of(true, false)) assertParentRetry(sourcePhase, true);
    }

    @Test void aLocalParentRetryRetiresAnUnambiguousDistributedCorrection() {
        for (boolean sourcePhase : List.of(true, false)) assertParentRetry(sourcePhase, false, "RELATION_ANALYSIS:all");
    }

    @Test void aCorrectiveSemanticQuestionKeepsItsOwnOpenCheckpoint() {
        for (boolean sourcePhase : List.of(true, false)) {
            try (var journal = new RecoveryCheckpointPersistenceProbe.Journal();
                 var session = new AnalysisCheckpointSession(journal)) {
                var calls = new AtomicInteger();
                assess(sourcePhase, new RelationSearchProtocol(prompt -> calls.incrementAndGet() == 1
                        ? response(sourcePhase, List.of(selection(sourcePhase, FIRST.id(), ORIGINAL),
                        selection(sourcePhase, SECOND.id(), SECOND.description())))
                        : response(sourcePhase, List.of(unresolved(sourcePhase, SECOND.id())))));
                assertEquals(2, calls.get());
                assertEquals("COMPLETED_WITH_GAPS", journal.complete());
                assertEquals(1, journal.checkpoints().values().stream().filter(q -> q.state().equals("CLARIFICATION")
                        && q.question().nodes().equals(List.of(SECOND.id()))).count());
            }
        }
    }

    @Test void aValidParentPreservesCorrectionsForOtherRequirementsAndPhases() {
        try (var journal = new RecoveryCheckpointPersistenceProbe.Journal();
             var session = new AnalysisCheckpointSession(journal)) {
            assess(true, new RelationSearchProtocol(prompt -> offeredResponse(true, prompt, FIRST.description())), "urlaubstage");
            assess(false, new RelationSearchProtocol(prompt -> offeredResponse(false, prompt, FIRST.description())));
            var failed = journal.checkpoints().values().stream().filter(q -> q.state().equals("UNASSESSED")).toList();
            assertEquals(4, failed.size());
            assess(true, new RelationSearchProtocol(prompt -> offeredResponse(true, prompt, ORIGINAL)));
            var after = journal.checkpoints();
            failed.forEach(q -> assertEquals("UNASSESSED", after.get(q.question().key()).state(),
                    "same-node corrections in another immutable requirement or phase remain independent"));
        }
    }

    @Test void aLaterParentRetryCanReassessASupersededCorrectionIfItIsNeededAgain() {
        try (var journal = new RecoveryCheckpointPersistenceProbe.Journal()) {
            var calls = new AtomicInteger();
            try (var session = new AnalysisCheckpointSession(journal)) {
                assess(true, new RelationSearchProtocol(prompt -> calls.incrementAndGet() == 1
                        ? response(true, List.of(unresolved(true), selection(true, SECOND.id(), SECOND.description())))
                        : response(true, List.of(selection(true, SECOND.id(), SECOND.description())))));
            }
            assertEquals("COMPLETED_WITH_GAPS", journal.complete());
            String child = journal.checkpoints().values().stream().filter(q -> q.state().equals("UNASSESSED"))
                    .findFirst().orElseThrow().question().key();
            journal.retryOpen();
            try (var session = new AnalysisCheckpointSession(journal)) {
                assess(true, new RelationSearchProtocol(prompt -> {
                    calls.incrementAndGet();
                    return response(true, List.of(unresolved(true), selection(true, SECOND.id(), ORIGINAL)));
                }));
            }
            assertEquals("COMPLETED_WITH_GAPS", journal.complete());
            assertEquals("SUPERSEDED", journal.checkpoints().get(child).state());
            journal.retryOpen();
            try (var session = new AnalysisCheckpointSession(journal)) {
                assess(true, new RelationSearchProtocol(prompt -> calls.incrementAndGet() == 4
                        ? response(true, List.of(selection(true, FIRST.id(), ORIGINAL), selection(true, SECOND.id(), SECOND.description())))
                        : response(true, List.of(selection(true, SECOND.id(), ORIGINAL)))));
            }
            assertEquals(5, calls.get());
            assertEquals("COMPLETED", journal.complete());
            assertEquals("SUCCESS", journal.checkpoints().get(child).state());
        }
    }

    private static void assertParentRetry(boolean sourcePhase) {
        assertParentRetry(sourcePhase, false);
    }

    private static void assertParentRetry(boolean sourcePhase, boolean skipped) {
        assertParentRetry(sourcePhase, skipped, null);
    }

    private static void assertParentRetry(boolean sourcePhase, boolean skipped, String namespace) {
        try (var journal = namespace == null ? new RecoveryCheckpointPersistenceProbe.Journal()
                : new RecoveryCheckpointPersistenceProbe.Journal(namespace)) {
            var calls = new AtomicInteger();
            try (var session = new AnalysisCheckpointSession(journal)) {
                assess(sourcePhase, new RelationSearchProtocol(prompt -> {
                    if (calls.incrementAndGet() == 1) return response(sourcePhase, List.of(
                            unresolved(sourcePhase), selection(sourcePhase, SECOND.id(), SECOND.description())));
                    return response(sourcePhase, List.of(selection(sourcePhase, SECOND.id(), SECOND.description())));
                }));
            }
            assertEquals(2, calls.get());
            assertEquals("COMPLETED_WITH_GAPS", journal.complete());
            var before = journal.checkpoints();
            var child = before.values().stream().filter(q -> q.state().equals("UNASSESSED")).findFirst().orElseThrow();
            assertEquals(1, before.values().stream().filter(q -> q.state().equals("CLARIFICATION")).count());
            if (skipped) {
                journal.skipCorrections();
                assertEquals("COMPLETED_WITH_GAPS", journal.complete());
                assertEquals("SKIPPED", journal.checkpoints().get(child.question().key()).state());
            }
            journal.retryOpen(); // Retry the parent and every remaining open corrective question.
            if (namespace != null) journal.useLocalScope();
            try (var session = new AnalysisCheckpointSession(journal)) {
                assess(sourcePhase, new RelationSearchProtocol(prompt -> {
                    calls.incrementAndGet();
                    return response(sourcePhase, List.of(selection(sourcePhase, FIRST.id(), ORIGINAL),
                            selection(sourcePhase, SECOND.id(), ORIGINAL)));
                }));
            }
            assertEquals(3, calls.get(), "the valid parent covers both entries without another corrective provider call");
            assertEquals("COMPLETED", journal.complete(), "a covered corrective question must not keep the operation STOPPED");
            var after = journal.checkpoints();
            assertEquals("SUPERSEDED", after.get(child.question().key()).state());
            assertEquals(child.detail().getRawResponse(), after.get(child.question().key()).detail().getRawResponse(),
                    "retiring redundant work must preserve the original failed response");
            assertTrue(after.values().stream().noneMatch(q -> q.state().equals("READY")));
        }
    }

    private static void assess(boolean sourcePhase, RelationSearchProtocol protocol) {
        assess(sourcePhase, protocol, ORIGINAL);
    }

    private static void assess(boolean sourcePhase, RelationSearchProtocol protocol, String original) {
        if (sourcePhase) {
            protocol.contributionsRecovering(original, List.of(FIRST, SECOND));
        } else {
            var contribution = new Contribution(new Node("BP-source", "BP", "Process", "Time recording", false),
                    "Capture working time", original, "");
            protocol.evaluateRecovering(new Query(original, contribution, "PRODUCES", Direction.OUTGOING,
                    Phase.NAVIGATE, List.of(FIRST, SECOND), null));
        }
    }

    private static Map<String, Object> unresolved(boolean sourcePhase) {
        return unresolved(sourcePhase, FIRST.id());
    }

    private static Map<String, Object> unresolved(boolean sourcePhase, String id) {
        return sourcePhase ? Map.of("nodeId", id, "outcome", "UNRESOLVED", "contributions", List.of(),
                "rationale", "Clarify the required time recording scope", "question", "Which records are required?")
                : Map.of("targetId", id, "outcome", "UNRESOLVED", "contribution", "", "requirementQuote", "",
                "necessity", "REQUIRED", "condition", "", "alternativeGroup", "", "rationale", "Clarify the required scope",
                "question", "Which records are required?");
    }

    private static Map<String, Object> selection(boolean sourcePhase, String id, String quote) {
        return sourcePhase ? Map.of("nodeId", id, "outcome", "EXPLICIT", "contributions", List.of(Map.of(
                "text", "Capture working time", "requirementQuote", quote, "condition", "")),
                "rationale", "Time recording is required", "question", "")
                : Map.of("targetId", id, "outcome", "MATCH", "contribution", "Working time data", "requirementQuote", quote,
                "necessity", "REQUIRED", "condition", "", "alternativeGroup", "", "rationale", "The requested function needs these data", "question", "");
    }

    private static String response(boolean sourcePhase, List<?> entries) {
        return JSON.writeValueAsString(Map.of(sourcePhase ? "selections" : "decisions", entries));
    }

    private static String offeredResponse(boolean sourcePhase, String prompt, String quote) {
        var input = JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6));
        var entries = new java.util.ArrayList<Map<String, Object>>();
        input.get(sourcePhase ? "nodes" : "candidates").forEach(node ->
                entries.add(selection(sourcePhase, node.get("id").asString(), quote)));
        return response(sourcePhase, entries);
    }
}
