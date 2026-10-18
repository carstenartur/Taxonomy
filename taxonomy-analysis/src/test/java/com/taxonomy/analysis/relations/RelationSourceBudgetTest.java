package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.analysis.relations.RequirementRelationSearchContract.*;
import static org.junit.jupiter.api.Assertions.*;

class RelationSourceBudgetTest {
    @Test
    void sourceCorrectionsCannotSpendTheEntireBudgetBeforeTheFirstVerifiedRelation() {
        var fixture = new Fixture();
        var calls = new AtomicInteger();
        var search = new RequirementRelationSearch(fixture.catalogue(), new RelationCompatibilityMatrix(), prompt -> {
            calls.incrementAndGet();
            return fixture.answerWithCorrections(prompt);
        }, () -> {});
        var report = search.searchWithSourceContinuation(READ, fixture.scores, fixture.options);
        assertFalse(report.result().edges().isEmpty(),
                "Even with malformed source batches, accepted contributions must reach navigation and separate verification");
        assertEquals("CONSUMES", report.result().edges().getFirst().type());
        assertEquals("evidence", report.result().edges().getFirst().targetId());
        assertTrue(report.result().trace().stream().anyMatch(t -> t.phase() == Phase.VERIFY && t.outcome() == Outcome.VERIFIED));
        assertEquals(calls.get(), report.totalCalls());
        assertTrue(report.totalCalls() <= 24, "No increase in the operator's total request budget");
        assertEquals(30, report.progress().totalSources());
        assertTrue(report.progress().assessedSources() < 30);
        assertFalse(report.isSearchExhausted(), "Unassessed sources stay visible in the complete work plan");
    }

    @Test
    void distributedPreparationAlsoRetainsAnAllowanceForTargetWork() {
        var fixture = new Fixture();
        var search = new RequirementRelationSearch(fixture.catalogue(), new RelationCompatibilityMatrix(), fixture::answerWithCorrections, () -> {});
        var plan = search.prepare("preparation", READ, fixture.scores, List.of("frozen-source-result"), fixture.options, true);
        assertTrue(plan.sourceCalls() < 24, "Preparation cannot consume the complete operation grant");
        var grants = RelationSearchDistribution.ready(plan, List.of());
        assertFalse(grants.isEmpty(), "An accepted contribution must be able to dispatch target work");
        var target = new RequirementRelationSearch(catalogue(), new RelationCompatibilityMatrix(), RequirementRelationSearchContract::answer, () -> {});
        var result = target.evaluate(READ, plan, grants.getFirst());
        assertFalse(result.result().edges().isEmpty());
        assertTrue(plan.sourceCalls() + result.calls() <= 24);
    }

    @Test
    void deliberatelyOpenSourcesAreNotReportedAsInvalidProviderAnswers() {
        var store = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                return new AnalysisCheckpointSession.Checkpoint(question, "SKIPPED", new LlmCallDetail());
            }
            public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) { fail("No new assessment"); }
            public void checkActive() { }
        };
        try (var session = new AnalysisCheckpointSession(store)) {
            RelationSearchReport report = session(prompt -> { fail("A deliberately open source is not retried"); return null; })
                    .search(READ, Map.of(SOURCE.id(), 100d), OPTIONS);
            assertTrue(report.warnings().stream().anyMatch(w -> w.startsWith("SOURCE_LEFT_OPEN")));
            assertTrue(report.warnings().stream().noneMatch(w -> w.startsWith("INVALID_SOURCE_RESPONSE")));
            assertFalse(report.isSearchExhausted());
        }
    }

    @Test
    void deliberatelyOpenTargetWorkIsNotReportedAsATechnicalAssessmentFailure() {
        var store = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                return new AnalysisCheckpointSession.Checkpoint(question, "SKIPPED", new LlmCallDetail());
            }
            public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) { fail("No new assessment"); }
            public void checkActive() { }
        };
        try (var session = new AnalysisCheckpointSession(store)) {
            var protocol = new RelationSearchProtocol(prompt -> { fail("Open target work cannot be retried automatically"); return null; });
            var engine = new RelationSearchEngine(node -> List.of(), protocol::evaluateRecovering, () -> {});
            var contribution = new Contribution(SOURCE, "Read evidence", READ, "");
            var report = engine.search(READ, List.of(new Intent(contribution, "CONSUMES", Direction.OUTGOING, List.of(ROOT))),
                    new Limits(24, 8, 3, 512));
            assertEquals("LEFT_OPEN", report.unfinished().getFirst().reason());
            assertFalse(report.unfinished().getFirst().question().startsWith("TECHNICAL_ASSESSMENT_FAILURE"));
            assertTrue(report.edges().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aLaterDeferredCorrectionCannotReclassifyAnEarlierDeliberateSkip(boolean sourcePhase) {
        String first = sourcePhase ? SOURCE.id() : TARGET.id();
        var nodes = List.of(sourcePhase ? SOURCE : TARGET, new Node("next", "IP", "Next", "Evidence", false));
        var store = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                var input = JSON.readTree(question.prompt().substring(question.prompt().indexOf("INPUT\n") + 6));
                boolean skipped = question.nodes().equals(List.of(first)) && input.has("validationErrors");
                return new AnalysisCheckpointSession.Checkpoint(question, skipped ? "SKIPPED" : "ATTEMPT", new LlmCallDetail());
            }
            public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) { }
            public void checkActive() { }
        };
        try (var session = new AnalysisCheckpointSession(store)) {
            var protocol = new RelationSearchProtocol(prompt -> {
                var input = JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6));
                if (input.has("validationErrors")) throw new AnalysisCheckpointSession.DeferredException("SOURCE_LIMIT");
                return sourcePhase ? "{\"selections\":[]}" : "{\"decisions\":[]}";
            });
            if (sourcePhase) {
                var result = protocol.contributionsRecovering(READ, nodes);
                assertEquals("RELATION_LEFT_OPEN", result.failures().get(first));
                assertTrue(result.failures().get("next").startsWith("SOURCE_LIMIT:"));
            } else {
                var contribution = new Contribution(SOURCE, "Read evidence", READ, "");
                var result = protocol.evaluateRecovering(new Query(READ, contribution, "CONSUMES", Direction.OUTGOING,
                        Phase.NAVIGATE, nodes, null));
                assertEquals("RELATION_LEFT_OPEN", result.getFirst().question());
                assertTrue(result.getLast().question().startsWith("TECHNICAL_ASSESSMENT_FAILURE: SOURCE_LIMIT:"));
            }
        }
    }

    private static final class Fixture {
        final Map<String, Node> sources = new LinkedHashMap<>();
        final Map<String, Double> scores = new LinkedHashMap<>();
        final RequirementRelationSearch.Options options = new RequirementRelationSearch.Options(new Limits(24, 8, 3, 512), 32);
        Fixture() {
            for (int i = 0; i < 30; i++) {
                String id = "process-" + i;
                sources.put(id, new Node(id, "BP", "Evidence review " + i, "Reads the required evidence", false));
                scores.put(id, 100d - i);
            }
        }
        RequirementRelationSearch.InputCatalogue catalogue() {
            var targets = RequirementRelationSearchContract.catalogue();
            return new RequirementRelationSearch.InputCatalogue() {
                public Node find(String id) { return sources.get(id); }
                public List<Node> roots() { return targets.roots(); }
                public List<Node> children(Node node) { return targets.children(node); }
            };
        }
        String answerWithCorrections(String prompt) {
            var input = JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6));
            if (input.has("nodes") && !input.has("validationErrors")) {
                // The full model response has a schema error; bounded singleton corrections succeed.
                return "{\"selections\":[]}";
            }
            return answer(prompt);
        }
    }
}
