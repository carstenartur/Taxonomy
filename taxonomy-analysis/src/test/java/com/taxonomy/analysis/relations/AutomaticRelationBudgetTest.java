package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.taxonomy.analysis.relations.RequirementRelationSearchContract.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticRelationBudgetTest {
    @Test void aDurableSearchAdvancesSourceSectionsWithoutReplacingItsJournal() {
        var journal = new Journal();
        var prompts = new HashSet<String>();
        var catalogue = twoSources();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var report = new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(), prompt -> {
                assertTrue(prompts.add(prompt), "Accepted answers must be replayed from the original journal");
                return answer(prompt);
            }, () -> {}).searchWithSourceContinuation(READ, Map.of("process", 10d, "process-next", 1d),
                    new RequirementRelationSearch.Options(new Limits(30, 8, 1, 128), 1));
            assertEquals(2, report.progress().assessedSources(), "The durable journal must not disable automatic source sections");
            assertEquals(2, report.progress().completedSearches());
            assertTrue(report.isSearchExhausted());
            assertEquals(prompts.size(), journal.accepted(), "Every actual answer remains in the durable journal");
            assertEquals(prompts.size(), report.totalCalls());
        }
        assertFalse(AnalysisCheckpointSession.active(), "The delegating section scope must close with its parent");
    }

    @Test void automaticSourceSectionsKeepTheOriginalCallCeiling() {
        var journal = new Journal();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var report = new RequirementRelationSearch(twoSources(), new RelationCompatibilityMatrix(), prompt -> {
                calls.incrementAndGet(); return answer(prompt);
            }, () -> {}).searchWithSourceContinuation(READ, Map.of("process", 10d, "process-next", 1d),
                    new RequirementRelationSearch.Options(new Limits(6, 8, 1, 128), 1));
            assertEquals(6, calls.get());
            assertEquals(6, report.totalCalls());
            assertFalse(report.isSearchExhausted(), "Spending the final credit cannot mean that all work completed");
            assertEquals(2, report.progress().totalSearches());
        }
    }

    @Test void manualContinuationKeepsItsTotalAndAnIncreaseReusesEveryAcceptedAnswer() {
        var journal = new Journal();
        var sent = new HashSet<String>();
        var search = session(prompt -> {
            assertTrue(sent.add(prompt), "A budget change must not resubmit an accepted provider prompt");
            return answer(prompt);
        });
        try (var scope = new AnalysisCheckpointSession(journal)) {
            journal.previous = search.search(READ, Map.of("process", 10d), bounded(2, 4, journal.spent, 16, 16));
            assertEquals(2, journal.spent);
            journal.previous = search.search(READ, Map.of("process", 10d), bounded(2, 4, journal.spent, 32, 16));
            assertEquals(4, journal.spent);
            var exhausted = search.search(READ, Map.of("process", 10d), bounded(2, 4, journal.spent, 32, 16));
            assertEquals(0, exhausted.totalCalls());
            assertEquals(4, exhausted.budget().spentCalls());
            assertFalse(exhausted.isSearchExhausted());
            assertTrue(exhausted.result().unfinished().stream().anyMatch(q -> q.reason().equals("TOTAL_CALL_BUDGET")));
            journal.previous = exhausted;
            var finished = search.search(READ, Map.of("process", 10d), bounded(2, 6, journal.spent, 32, 16));
            assertTrue(finished.isSearchExhausted());
            assertEquals(1, finished.budget().newCalls());
            assertEquals(5, finished.budget().spentCalls());
            assertEquals(5, sent.size());
            assertEquals(finished, JSON.readValue(JSON.writeValueAsString(finished), RelationSearchReport.class));
        }
    }

    @Test void increasingTheSourceLimitRetainsTheOriginalPromptCohorts() {
        var journal = new Journal();
        var sent = new HashSet<String>();
        var search = new RequirementRelationSearch(twoSources(), new RelationCompatibilityMatrix(), prompt -> {
            assertTrue(sent.add(prompt), "Raised source admission must preserve the partial original prompt group");
            return answer(prompt);
        }, () -> {});
        try (var scope = new AnalysisCheckpointSession(journal)) {
            journal.previous = search.search(READ, Map.of("process", 10d, "process-next", 1d), bounded(5, 20, 0, 1, 1));
            assertEquals(1, journal.previous.progress().completedSearches());
            var finished = search.search(READ, Map.of("process", 10d, "process-next", 1d), bounded(10, 20, journal.spent, 2, 1));
            assertTrue(finished.isSearchExhausted());
            assertEquals(2, finished.progress().completedSearches());
            assertEquals(10, sent.size());
            assertEquals(1, finished.budget().sourceCohortSize());
        }
    }

    @Test void automaticContinuationCanAdmitAFrozenBatchAcrossSmallerSourceSections() {
        var journal = new Journal();
        var sent = new HashSet<String>();
        var search = new RequirementRelationSearch(twoSources(), new RelationCompatibilityMatrix(), prompt -> {
            assertTrue(sent.add(prompt), "Frozen prompt identities must remain reusable");
            return answer(prompt);
        }, () -> {});
        try (var scope = new AnalysisCheckpointSession(journal)) {
            journal.previous = search.search(READ, Map.of("process", 10d, "process-next", 1d), bounded(0, 20, 0, 2, 2));
            var options = new RequirementRelationSearch.Options(new Limits(20, 8, 10, 128), 1, 16_000,
                    new RequirementRelationSearch.BudgetPolicy(2, 20, 0, true, 1, 128, 2));
            var resumed = search.searchWithSourceContinuation(READ, Map.of("process", 10d, "process-next", 1d), options);
            assertTrue(resumed.isSearchExhausted(), "A smaller section must not permanently strand an unanswered frozen batch");
            assertEquals(2, resumed.progress().assessedSources());
            assertEquals(2, resumed.budget().sourceCohortSize());
            assertEquals(9, sent.size());
        }
    }

    private static RequirementRelationSearch.Options bounded(int initial, int total, int spent, int sources, int cohort) {
        return new RequirementRelationSearch.Options(new Limits(Math.min(initial, Math.max(0, total - spent)), 8, 10, 128),
                sources, 16_000, new RequirementRelationSearch.BudgetPolicy(initial, total, spent, false, sources, 128, cohort));
    }

    private static RequirementRelationSearch.InputCatalogue twoSources() {
        var next = new Node("process-next", "BP", "Next process", "Another process", false);
        return new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return id.equals("process-next") ? next : SOURCE; }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node node) { return node.equals(ROOT) ? List.of(TARGET) : List.of(); }
        };
    }

    private static class Journal implements AnalysisCheckpointSession.Store {
        final Map<String, AnalysisCheckpointSession.Checkpoint> entries = new LinkedHashMap<>();
        RelationSearchReport previous;
        int spent;
        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
            var saved = entries.get(question.key());
            return saved != null && !"READY".equals(saved.state()) ? saved
                    : new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null);
        }
        public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
            entries.put(question.key(), new AnalysisCheckpointSession.Checkpoint(question, state, detail));
        }
        public void checkActive() { }
        public RelationSearchReport previousRelations() { return previous; }
        public int relationCallsSpent() { return spent; }
        public void beforeRelationCall(int maximum) {
            if (spent >= maximum) throw new AnalysisCheckpointSession.DeferredException("TOTAL_CALL_BUDGET");
            spent++;
        }
        int accepted() { return (int) entries.values().stream().filter(q -> "SUCCESS".equals(q.state())).count(); }
    }
}
