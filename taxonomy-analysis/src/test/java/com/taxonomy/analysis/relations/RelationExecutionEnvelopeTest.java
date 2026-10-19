package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.taxonomy.analysis.relations.RequirementRelationSearchContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationExecutionEnvelopeTest {
    @Test void distributedWorkIdentitiesSupportTheConfigured4096ItemDefault() {
        var root = com.taxonomy.analysis.dag.TaxonomyShardRoot.of("CP");
        var id = assertDoesNotThrow(() -> com.taxonomy.analysis.dag.AnalysisTaskId.relationWork("budget", root, 4095));
        assertEquals(4095, id.relationWorkOrdinal().orElseThrow());
        assertTrue(id.matchesRelation("budget", List.of(root), 2));
        assertThrows(IllegalArgumentException.class, () ->
                com.taxonomy.analysis.dag.AnalysisTaskId.relationWork("budget", root, 100_000));
    }

    @Test void automaticPreparationAdmitsWorkBeyondTheInitialAllowanceInTheSameOperation() {
        var service = service(true, 2, 20);
        var plan = service.prepare("one-operation:relations", READ, Map.of("process", 10d),
                List.of("BP-result"), catalogue(), true);
        assertEquals(20, plan.options().limits().maxCalls());
        var completed = new ArrayList<RelationSearchDistribution.WorkResult>();
        for (int wave = 0; wave < 10; wave++) {
            var ready = RelationSearchDistribution.ready(plan, completed);
            if (ready.isEmpty()) break;
            ready.forEach(work -> completed.add(service.evaluate(READ, plan, work, catalogue())));
        }
        var report = RelationSearchDistribution.combine(plan, completed);
        assertTrue(report.isSearchExhausted());
        assertEquals(5, report.totalCalls());
        assertEquals(1, report.result().edges().size());
        assertTrue(completed.stream().allMatch(work -> work.work().preparationId().equals("one-operation:relations")));
    }

    @Test void theHigherDefaultsCompleteARealDownwalkBeyondTheOld24CallBoundary() {
        var sources = new LinkedHashMap<String, com.taxonomy.dto.RelationSearchModel.Node>();
        var scores = new LinkedHashMap<String, Double>();
        for (int i = 0; i < 7; i++) {
            String id = "process-" + i;
            sources.put(id, new com.taxonomy.dto.RelationSearchModel.Node(id, "BP", id, "Required process", false));
            scores.put(id, (double) (10 - i));
        }
        var inputs = new RequirementRelationSearch.InputCatalogue() {
            public com.taxonomy.dto.RelationSearchModel.Node find(String id) { return sources.get(id); }
            public List<com.taxonomy.dto.RelationSearchModel.Node> roots() { return List.of(ROOT); }
            public List<com.taxonomy.dto.RelationSearchModel.Node> children(com.taxonomy.dto.RelationSearchModel.Node node) {
                return node.equals(ROOT) ? List.of(TARGET) : List.of();
            }
        };
        var service = service(true, 256, 4096);
        var plan = service.prepare("default-budget", READ, scores, List.of("BP-result"), inputs, true);
        var report = RelationSearchDistribution.combine(plan, execute(service, plan, inputs));
        assertTrue(report.isSearchExhausted());
        assertEquals(29, report.totalCalls());
        assertEquals(7, report.progress().completedSearches());
        assertEquals(7, report.result().edges().size());
        assertEquals(256, report.budget().callsPerSlice());
        assertEquals(4096, report.budget().maxTotalCalls());
    }

    @Test void switchingOffAutomaticContinuationKeepsTheInitialAllowance() {
        var service = service(false, 2, 20);
        var plan = service.prepare("manual:relations", READ, Map.of("process", 10d),
                List.of("BP-result"), catalogue(), true);
        assertEquals(2, plan.options().limits().maxCalls());
        var completed = RelationSearchDistribution.ready(plan, List.of()).stream()
                .map(work -> service.evaluate(READ, plan, work, catalogue())).toList();
        var report = RelationSearchDistribution.combine(plan, completed);
        assertFalse(report.isSearchExhausted());
        assertEquals(2, report.totalCalls());
    }

    @Test void theOverallAllowanceCanBeSmallerThanTheInitialAllowance() {
        var service = service(false, 256, 3);
        var plan = service.prepare("overall:relations", READ, Map.of("process", 10d),
                List.of("BP-result"), catalogue(), true);
        assertEquals(3, plan.options().limits().maxCalls());
    }

    @Test void aZeroInitialAllowanceDoesNotStartAutomaticProviderWork() {
        var service = service(true, 0, 4096);
        var plan = service.prepare("disabled:relations", READ, Map.of("process", 10d),
                List.of("BP-result"), catalogue(), true);
        assertEquals(0, plan.options().limits().maxCalls());
        assertEquals(0, plan.sourceCalls());
    }

    @Test void aContinuationAtTheOverallCeilingReconstructsCommittedAnswersAfterWorkerLoss() {
        var journal = new ReplayJournal();
        try (var ignored = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(journal)) {
            var first = service(true, 2, 5);
            var admitted = first.prepare("lost-result", READ, Map.of("process", 10d),
                    List.of("BP-result"), catalogue(), true);
            execute(first, admitted, catalogue()); // Provider answers commit; the worker result is lost.
            assertEquals(5, journal.spent);
            var resumed = service(false, 0, 5, prompt -> { throw new AssertionError("No new call is funded"); });
            var plan = resumed.prepare("recovered-result", READ, Map.of("process", 10d),
                    List.of("BP-result"), catalogue(), true);
            var report = RelationSearchDistribution.combine(plan, execute(resumed, plan, catalogue()));
            assertTrue(report.isSearchExhausted(), "Accepted target answers must be replayable with zero new-call allowance");
            assertEquals(1, report.result().edges().size());
            assertEquals(0, report.totalCalls());
            assertEquals(5, report.budget().spentCalls());
        }
    }

    @Test void freeReplayedIntentsDoNotConsumeTheNewWorkItemAllowance() {
        var journal = new ReplayJournal();
        try (var ignored = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(journal)) {
            var first = service(true, 20, 100);
            var admitted = first.prepare("old-work", READ, Map.of("process", 10d),
                    List.of("BP-result"), catalogue(), true);
            execute(first, admitted, catalogue());
            var resumed = service(false, 20, 100, prompt -> { throw new AssertionError("Every answer is already accepted"); });
            ReflectionTestUtils.setField(resumed, "maxWorkItems", 1);
            var plan = resumed.prepare("new-work", READ, Map.of("process", 10d),
                    List.of("BP-result"), catalogue(), true);
            assertTrue(plan.items().size() > 1, "The fixture must exercise more than one work ordinal");
            var results = execute(resumed, plan, catalogue());
            var report = RelationSearchDistribution.combine(plan, results);
            assertTrue(report.isSearchExhausted(), "Cached work before a later ordinal must not strand that ordinal");
            assertEquals(0, report.totalCalls());
            assertEquals(plan.items().size(), results.size());
        }
    }

    @Test void automaticPreparationSpendsItsReservedRemainderOnLaterSources() {
        var nodes = new LinkedHashMap<String, com.taxonomy.dto.RelationSearchModel.Node>();
        var scores = new LinkedHashMap<String, Double>();
        for (int i = 0; i < 11; i++) {
            String id = i == 0 ? "process" : "process-" + i;
            nodes.put(id, new com.taxonomy.dto.RelationSearchModel.Node(id, "BP", id, "process", false));
            scores.put(id, (double) (20 - i));
        }
        var inputs = new RequirementRelationSearch.InputCatalogue() {
            public com.taxonomy.dto.RelationSearchModel.Node find(String id) { return nodes.get(id); }
            public List<com.taxonomy.dto.RelationSearchModel.Node> roots() { return List.of(ROOT); }
            public List<com.taxonomy.dto.RelationSearchModel.Node> children(com.taxonomy.dto.RelationSearchModel.Node n) {
                return n.equals(ROOT) ? List.of(TARGET) : List.of();
            }
        };
        var prompts = new HashSet<String>();
        var service = service(true, 2, 20, prompt -> {
            assertTrue(prompts.add(prompt), "The preparation fallback must reuse accepted source responses");
            var input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
            if (input.has("nodes") && !input.path("nodes").get(0).path("id").asString().equals("process"))
                return JSON.writeValueAsString(Map.of("selections", input.path("nodes").valueStream().map(n ->
                        Map.of("nodeId", n.path("id").asString(), "outcome", "REJECT", "contributions", List.of(),
                                "rationale", "This process is outside the requirement", "question", "")).toList()));
            return answer(prompt);
        });
        ReflectionTestUtils.setField(service, "batchSize", 1);
        var plan = service.prepare("source-overflow:relations", READ, scores, List.of("BP-result"), inputs, true);
        var completed = new ArrayList<RelationSearchDistribution.WorkResult>();
        for (int wave = 0; wave < 20; wave++) {
            var ready = RelationSearchDistribution.ready(plan, completed);
            if (ready.isEmpty()) break;
            ready.forEach(work -> completed.add(service.evaluate(READ, plan, work, inputs)));
        }
        var report = RelationSearchDistribution.combine(plan, completed);
        assertEquals(11, report.progress().assessedSources(), "Unused target reservation must not strand source eleven");
        assertTrue(report.isSearchExhausted());
        assertEquals(15, report.totalCalls());
        assertEquals(15, prompts.size());
        assertEquals(1, report.result().edges().size());
    }

    private static RequirementRelationSearchService service(boolean automatic, int initial, int maximum) {
        return service(automatic, initial, maximum, RequirementRelationSearchContract::answer);
    }

    private static RequirementRelationSearchService service(boolean automatic, int initial, int maximum,
            java.util.function.Function<String, String> completion) {
        var llm = mock(LlmService.class);
        when(llm.supportsGenerativeCompletion()).thenReturn(true);
        when(llm.getActiveProviderId()).thenReturn(new ProviderId("MOCK"));
        when(llm.getActiveProviderName()).thenReturn("MOCK");
        when(llm.isMockMode()).thenReturn(true);
        when(llm.callLlmRaw(anyString(), any())).thenAnswer(call -> completion.apply(call.getArgument(0)));
        var service = new RequirementRelationSearchService(mock(TaxonomyService.class),
                new RelationCompatibilityMatrix(), llm, mock(AiPromptBudgetPolicy.class));
        ReflectionTestUtils.setField(service, "maxCalls", initial);
        ReflectionTestUtils.setField(service, "maxTotalCalls", maximum);
        ReflectionTestUtils.setField(service, "autoContinue", automatic);
        return service;
    }

    private static List<RelationSearchDistribution.WorkResult> execute(RequirementRelationSearchService service,
            RelationSearchDistribution.Plan plan, RequirementRelationSearch.InputCatalogue catalogue) {
        var results = new ArrayList<RelationSearchDistribution.WorkResult>();
        for (int wave = 0; wave < 100; wave++) {
            var ready = RelationSearchDistribution.ready(plan, results);
            if (ready.isEmpty()) return results;
            ready.forEach(work -> results.add(service.evaluate(READ, plan, work, catalogue)));
        }
        throw new AssertionError("Distributed work must settle within its finite plan");
    }

    private static final class ReplayJournal implements com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Store {
        private final Map<String, com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint> answers = new HashMap<>();
        int spent;
        public com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint prepare(
                com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Question question) {
            var cached = answers.get(question.key());
            return cached == null || "READY".equals(cached.state())
                    ? new com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null)
                    : new com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint(cached.question(), cached.state(), cached.detail(), false);
        }
        public void finish(com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Question question, String state,
                com.taxonomy.dto.LlmCallDetail detail) {
            answers.put(question.key(), new com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint(question, state, detail));
        }
        public void checkActive() { }
        public int relationCallsSpent() { return spent; }
        public void beforeRelationCall(int maximum) {
            if (spent >= maximum) throw new com.taxonomy.analysis.recovery.AnalysisCheckpointSession.DeferredException("TOTAL_CALL_BUDGET");
            spent++;
        }
    }
}
