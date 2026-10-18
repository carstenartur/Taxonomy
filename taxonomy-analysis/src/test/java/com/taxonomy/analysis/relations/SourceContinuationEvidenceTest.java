package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.analysis.relations.RequirementRelationSearchContract.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceContinuationEvidenceTest {
    private static final String QUESTION = "Which evidence record is in scope?";

    @Test void interruptionOfTheNextSourceCohortRetainsTheEarlierTargetClarification() {
        var next = new Node("process-next", "BP", "Next process", "Another process", false);
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return id.equals("process-next") ? next : SOURCE; }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node node) { return node.equals(ROOT) ? List.of(TARGET) : List.of(); }
        };
        var report = new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(), prompt -> {
            var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
            if (input.has("nodes") && input.get("nodes").get(0).path("id").asString().equals("process-next"))
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
            return clarification(prompt);
        }, () -> {}).searchWithSourceContinuation(READ, Map.of("process", 10d, "process-next", 1d),
                new RequirementRelationSearch.Options(new Limits(30, 8, 1, 128), 1));
        assertTrue(report.stopReason().startsWith("CANCELLED"));
        assertEquals(1, report.progress().assessedSources());
        assertTrue(report.result().trace().stream().anyMatch(t -> t.outcome() == Outcome.UNRESOLVED));
        assertTrue(report.result().unfinished().stream().anyMatch(q -> q.sourceId().equals("process")
                && q.targets().equals(List.of("IP")) && q.question().equals(QUESTION)),
                "The request-owned journal is discarded, so its clarification must survive in the returned report");
        assertFalse(report.isSearchExhausted());
        assertEquals(1, report.progress().unresolvedSearches());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aRevisitedTargetReplacesItsEarlierClarificationWithTheCurrentDecision(boolean legacyReport) {
        var previous = new RelationSearchReport[1];
        var journal = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                return new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null);
            }
            public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) { }
            public void checkActive() { }
            public RelationSearchReport previousRelations() { return previous[0]; }
        };
        try (var session = new AnalysisCheckpointSession(journal)) {
            previous[0] = session(SourceContinuationEvidenceTest::clarification).search(READ, Map.of("process", 10d), OPTIONS);
        }
        assertTrue(previous[0].result().unfinished().stream().anyMatch(q -> q.question().equals(QUESTION)));
        if (legacyReport) {
            var payload = JSON.valueToTree(previous[0]);
            for (String field : List.of("unfinished", "trace")) for (var entry : payload.path("result").path(field))
                ((tools.jackson.databind.node.ObjectNode) entry).remove("contributionKey");
            previous[0] = JSON.treeToValue(payload, RelationSearchReport.class);
        }
        try (var session = new AnalysisCheckpointSession(journal)) {
            var resolved = session(RequirementRelationSearchContract::answer).search(READ, Map.of("process", 10d), OPTIONS);
            assertTrue(resolved.result().unfinished().isEmpty());
            assertTrue(resolved.isSearchExhausted());
            assertEquals(0, resolved.progress().unresolvedSearches());
            assertEquals(1, resolved.progress().completedSearches());
            assertEquals(1, resolved.result().edges().size());
        }
    }

    @ParameterizedTest @EnumSource(Phase.class)
    void deferredTargetWorkCannotEraseItsEarlierSemanticQuestion(Phase clarificationPhase) {
        var previous = new RelationSearchReport[1];
        var journal = journal(previous);
        try (var session = new AnalysisCheckpointSession(journal)) {
            previous[0] = session(prompt -> {
                var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
                if (input.has("nodes")) return answer(prompt);
                Query q = JSON.treeToValue(input, Query.class);
                if (q.type().equals("CONSUMES") && q.phase() == clarificationPhase)
                    return unresolved(q);
                return answer(prompt);
            }).search(READ, Map.of("process", 10d), OPTIONS);
        }
        assertTrue(previous[0].result().unfinished().stream().anyMatch(q -> q.question().equals(QUESTION)));
        try (var session = new AnalysisCheckpointSession(journal)) {
            var stopped = session(prompt -> {
                var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
                if (!input.has("nodes") && JSON.treeToValue(input, Query.class).phase() == clarificationPhase)
                    throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                return answer(prompt);
            }).search(READ, Map.of("process", 10d), OPTIONS);
            assertTrue(stopped.stopReason().startsWith("CANCELLED"));
            assertTrue(stopped.result().unfinished().stream().anyMatch(q -> q.question().equals(QUESTION)),
                    "A deferred query or navigation match cannot resolve a prior semantic clarification");
        }
    }

    @Test void aDecisionForAnotherContributionCannotResolveTheUnreplayedContributionQuestion() {
        var previous = new RelationSearchReport[1];
        var journal = journal(previous);
        var resumed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Function<String, String> complete = prompt -> {
            var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
            if (input.has("nodes")) {
                var contributions = new ArrayList<>(List.of(
                        Map.of("text", "first requested contribution", "quote", READ, "condition", ""),
                        Map.of("text", "second requested contribution", "quote", READ, "condition", "")));
                if (resumed.get()) Collections.reverse(contributions);
                return JSON.writeValueAsString(Map.of("selections", List.of(Map.of("nodeId", "process", "outcome", "EXPLICIT",
                        "contributions", contributions, "rationale", "Two distinct requested contributions", "question", ""))));
            }
            Query q = JSON.treeToValue(input, Query.class);
            if (q.type().equals("CONSUMES") && q.contribution().text().equals("first requested contribution")) {
                if (resumed.get()) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                return unresolved(q);
            }
            return JSON.writeValueAsString(Map.of("decisions", q.candidates().stream().map(n ->
                    new Decision(n.id(), Outcome.REJECT, "unneeded counterpart", READ, Necessity.REQUIRED,
                            "", "", "This contribution does not need that counterpart", "")).toList()));
        };
        try (var session = new AnalysisCheckpointSession(journal)) {
            previous[0] = session(complete).search(READ, Map.of("process", 10d), OPTIONS);
        }
        assertTrue(previous[0].result().unfinished().stream().anyMatch(q -> q.question().equals(QUESTION)));
        resumed.set(true);
        try (var session = new AnalysisCheckpointSession(journal)) {
            var stopped = session(complete).search(READ, Map.of("process", 10d), OPTIONS);
            assertTrue(stopped.result().trace().stream().anyMatch(t -> t.outcome() == Outcome.REJECT));
            assertTrue(stopped.result().unfinished().stream().anyMatch(q -> q.question().equals(QUESTION)),
                    "A different contribution's rejection does not answer this contribution's question");
        }
    }

    @Test void failedCorrectiveAssessmentCannotReplaceAPreviousSemanticClarification() {
        var previous = new RelationSearchReport[1];
        var journal = journal(previous);
        try (var session = new AnalysisCheckpointSession(journal)) {
            previous[0] = session(SourceContinuationEvidenceTest::clarification).search(READ, Map.of("process", 10d), OPTIONS);
        }
        try (var session = new AnalysisCheckpointSession(journal)) {
            var stopped = session(prompt -> {
                var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
                return input.has("nodes") ? answer(prompt) : "No valid structured assessment";
            }).search(READ, Map.of("process", 10d), OPTIONS);
            assertTrue(stopped.result().unfinished().stream().anyMatch(q -> q.question().startsWith("TECHNICAL_ASSESSMENT_FAILURE:")));
            assertTrue(stopped.result().unfinished().stream().anyMatch(q -> q.question().equals(QUESTION)),
                    "A technical UNRESOLVED fallback is not a new semantic assessment of the old question");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void retainedQuestionsAndTaskCountsAgreeWhenAnotherRootRemainsOpen(boolean legacyReport) {
        var serviceRoot = new Node("CR", "CR", "Services", "Service category", true);
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return Map.of("process", SOURCE, "IP", ROOT, "evidence", TARGET, "CR", serviceRoot).get(id); }
            public List<Node> roots() { return List.of(ROOT, serviceRoot); }
            public List<Node> children(Node node) { return node.equals(ROOT) ? List.of(TARGET) : List.of(); }
        };
        var previous = new RelationSearchReport[1]; var journal = journal(previous);
        var resumed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Function<String, String> complete = prompt -> {
            var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
            if (input.has("nodes")) return answer(prompt);
            Query q = JSON.treeToValue(input, Query.class);
            if (q.type().equals("SUPPORTS")) return unresolved(q);
            if (q.type().equals("CONSUMES") && !resumed.get()) {
                if (q.candidates().getFirst().container()) return answer(prompt);
                return unresolved(q);
            }
            return JSON.writeValueAsString(Map.of("decisions", q.candidates().stream().map(n ->
                    new Decision(n.id(), Outcome.REJECT, "unneeded counterpart", READ, Necessity.REQUIRED,
                            "", "", "This source needs no counterpart in this branch", "")).toList()));
        };
        java.util.function.Supplier<RequirementRelationSearch> search = () -> new RequirementRelationSearch(
                catalogue, new RelationCompatibilityMatrix(), complete, () -> {});
        try (var session = new AnalysisCheckpointSession(journal)) {
            previous[0] = search.get().search(READ, Map.of("process", 10d), OPTIONS);
        }
        assertTrue(previous[0].result().unfinished().stream().anyMatch(q -> q.targets().contains("evidence")));
        if (legacyReport) {
            var payload = JSON.valueToTree(previous[0]);
            for (String field : List.of("unfinished", "trace")) for (var entry : payload.path("result").path(field)) {
                ((tools.jackson.databind.node.ObjectNode) entry).remove("contributionKey");
                ((tools.jackson.databind.node.ObjectNode) entry).remove("targetRoot");
            }
            previous[0] = JSON.treeToValue(payload, RelationSearchReport.class);
        }
        resumed.set(true);
        try (var session = new AnalysisCheckpointSession(journal)) {
            var current = search.get().search(READ, Map.of("process", 10d), OPTIONS);
            boolean retained = current.result().unfinished().stream().anyMatch(q -> q.targets().contains("evidence"));
            var information = current.progress().taxonomies().stream().filter(t -> t.root().equals("IP")).findFirst().orElseThrow();
            if (legacyReport) { assertTrue(retained); assertEquals(1, information.unresolved()); }
            else { assertFalse(retained, "Completing its actual target root retires the old child question");
                assertEquals(0, information.unresolved()); assertEquals(1, information.completed()); }
        }
    }

    private static AnalysisCheckpointSession.Store journal(RelationSearchReport[] previous) {
        return new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                return new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null);
            }
            public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) { }
            public void checkActive() { }
            public RelationSearchReport previousRelations() { return previous[0]; }
        };
    }

    private static String unresolved(Query q) {
        return JSON.writeValueAsString(Map.of("decisions", q.candidates().stream().map(n ->
                new Decision(n.id(), Outcome.UNRESOLVED, "evidence access", READ, Necessity.REQUIRED,
                        "", "", "The record identity requires clarification", QUESTION)).toList()));
    }

    private static String clarification(String prompt) {
        var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
        if (input.has("nodes")) return answer(prompt);
        Query q = JSON.treeToValue(input, Query.class);
        if (!q.type().equals("CONSUMES")) return answer(prompt);
        return JSON.writeValueAsString(Map.of("decisions", q.candidates().stream().map(n ->
                new Decision(n.id(), Outcome.UNRESOLVED, "evidence access", READ, Necessity.REQUIRED,
                        "", "", "The record identity requires clarification", QUESTION)).toList()));
    }
}
