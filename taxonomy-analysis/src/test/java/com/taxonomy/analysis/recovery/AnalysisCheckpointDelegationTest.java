package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisCheckpointDelegationTest {
    @Test void laterSlicesRetainTheOriginalJournalLedgerOwnershipAndBothSupersessionHooks() {
        var original = RelationCallLedgerTest.report(1); var later = RelationCallLedgerTest.report(2);
        var journal = new Journal(original); var report = new AtomicReference<>(later);
        try (var owner = new AnalysisCheckpointSession(journal)) {
            evaluate(); assertEquals(1, journal.answers.size());
            try (var scope = withPrevious(report::get)) {
                assertSame(later, AnalysisCheckpointSession.previousRelations());
                evaluate(); assertEquals(1, journal.providerCalls);
                AnalysisCheckpointSession.supersedeEvidence("NAVIGATE", "MOCK", question -> true);
                AnalysisCheckpointSession.supersedeEvidence("VERIFY", "MOCK", Set.of("CP"), question -> true);
                assertEquals(List.of("plain:NAVIGATE", "codes:VERIFY:[CP]"), journal.superseded);
                report.set(original); assertSame(original, AnalysisCheckpointSession.previousRelations());
                journal.active = false;
                assertThrows(IllegalStateException.class, AnalysisCheckpointSession::checkpoint);
                journal.active = true;
                assertEquals(1, AnalysisCheckpointSession.relationCallsSpent());
                assertEquals(32, AnalysisCheckpointSession.sourceCohortSize(),
                        "A later budget slice must retain its original prompt cohort width");
                assertSame(journal.budget, AnalysisCheckpointSession.relationBudget());
            }
            assertSame(original, AnalysisCheckpointSession.previousRelations());
            evaluate(); assertEquals(1, journal.providerCalls);
        }
        assertFalse(AnalysisCheckpointSession.active());
    }

    @Test void aReportOverrideCannotInventAStandaloneJournal() {
        assertFalse(AnalysisCheckpointSession.active());
        assertThrows(IllegalStateException.class, () -> withPrevious(() -> null));
    }

    private static void evaluate() {
        AnalysisCheckpointSession.evaluateEvidence("NAVIGATE", "MOCK", List.of("CP"), "original input", () -> {
            AnalysisCheckpointSession.beforeRelationCall(3);
            var detail = new LlmCallDetail(); detail.setProvider("MOCK"); detail.setScores(Map.of());
            detail.setRawResponse("validated evidence"); return detail;
        });
    }
    private static AnalysisCheckpointSession withPrevious(Supplier<RelationSearchReport> previous) {
        return AnalysisCheckpointSession.withPreviousRelations(previous);
    }
    private static final class Journal implements AnalysisCheckpointSession.Store {
        final RelationSearchReport previous;
        final com.taxonomy.analysis.relations.RequirementRelationSearch.BudgetPolicy budget =
                new com.taxonomy.analysis.relations.RequirementRelationSearch.BudgetPolicy(3, 10, 0, true, 32, 512, 32);
        final Map<String, AnalysisCheckpointSession.Checkpoint> answers = new HashMap<>();
        final List<String> superseded = new ArrayList<>();
        int providerCalls; boolean active = true;
        Journal(RelationSearchReport previous) { this.previous = previous; }
        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
            return answers.getOrDefault(question.key(), new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null));
        }
        public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
            answers.put(question.key(), new AnalysisCheckpointSession.Checkpoint(question, state, detail));
        }
        public void checkActive() { if (!active) throw new IllegalStateException("original claim stopped"); }
        public RelationSearchReport previousRelations() { return previous; }
        public int relationCallsSpent() { return providerCalls; }
        public int sourceCohortSize() { return 32; }
        public com.taxonomy.analysis.relations.RequirementRelationSearch.BudgetPolicy relationBudget() { return budget; }
        public void beforeRelationCall(int maximum) { checkActive(); if (providerCalls >= maximum) throw new AssertionError("limit"); providerCalls++; }
        public void supersedeEvidence(String kind, String provider, Predicate<AnalysisCheckpointSession.Question> covered) { superseded.add("plain:" + kind); }
        public void supersedeEvidence(String kind, String provider, Set<String> codes, Predicate<AnalysisCheckpointSession.Question> covered) { superseded.add("codes:" + kind + ":" + codes); }
    }
}
