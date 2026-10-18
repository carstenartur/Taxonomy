package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisRecoveryView;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RelationSearchReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectAnalysisContinuationPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"CALL_BUDGET", "SOURCE_LIMIT", "WORK_LIMIT"})
    void executionLimitsPermitOneExplicitFurtherCycle(String limit) {
        assertThat(ProjectAnalysisContinuationService.budgetProblem(partial(limit))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "CANCELLED", "COMPLETED_WITH_GAPS", "COMPLETED", "RUNNING"})
    void anotherRecoveryStateIsNotAnExecutionBudgetDecision(String state) {
        var analysis = partial("CALL_BUDGET");
        analysis.setRecovery(new AnalysisRecoveryView("run", 12, state, 8, 9, null, List.of()));
        assertThat(ProjectAnalysisContinuationService.budgetProblem(analysis)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUTCOME_UNCERTAIN: timed out", "RELATION_UNRESOLVED:", "LEFT_OPEN", "FAILED"})
    void failedOrUnassessedQuestionsAreNeverRetriedOrSkippedByABudgetAction(String error) {
        var analysis = partial("CALL_BUDGET");
        analysis.setRecovery(new AnalysisRecoveryView("run", 12, "STOPPED", 8, 9, null,
                List.of(new AnalysisRecoveryView.OpenQuestion("question", List.of("BP-1"), error, 1,
                        error.equals("LEFT_OPEN"), error.startsWith("OUTCOME_UNCERTAIN")))));
        assertThat(ProjectAnalysisContinuationService.budgetProblem(analysis)).isNotNull();
    }

    @Test
    void acceptedRelationClarificationMayRemainOpenWhileIndependentBudgetWorkAdvances() {
        var analysis = partial("CALL_BUDGET");
        var question = new AnalysisRecoveryView.OpenQuestion("Rquestion", List.of("IP-1"),
                "RELATION_UNRESOLVED: IP-1: Which retention policy?", 1, false, false);
        analysis.setRecovery(new AnalysisRecoveryView("run", 12, "STOPPED", 8, 9, null, List.of(question)));
        var report = analysis.getRelationSearchReport();
        analysis.setRelationSearchReport(new RelationSearchReport(1, "0".repeat(64), "reference", List.of(),
                new Result(List.of(), List.of(new Unfinished("BP-1", "CONSUMES", Direction.OUTGOING,
                        List.of("IP-1"), "UNRESOLVED", "Which retention policy?")), List.of(), 3, 0, 0),
                3, 24, 0, report.warnings(), ""));
        assertThat(ProjectAnalysisContinuationService.budgetProblem(analysis)).isNull();

        // An open semantic question grants no additional calls when budget work is finished.
        var complete = analysis.getRelationSearchReport();
        analysis.setRelationSearchReport(new RelationSearchReport(1, "0".repeat(64), "reference", List.of(),
                complete.result(), 3, 24, 0, List.of(), ""));
        assertThat(ProjectAnalysisContinuationService.budgetProblem(analysis)).contains("No resumable execution budget");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "TIME_LIMIT", "MEMORY_PRESSURE", "AWAITING_DECISION"})
    void cooperativeStopCannotBeHiddenByAnEarlierSourceBudget(String stop) {
        var analysis = partial("SOURCE_LIMIT");
        analysis.setErrorMessage(stop + ": analysis stopped");
        assertThat(ProjectAnalysisContinuationService.budgetProblem(analysis)).isNotNull();
    }

    @Test
    void projectionFailureAlongsideABudgetStopRemainsASeparatePartialCause() {
        var analysis = partial("CALL_BUDGET");
        analysis.setWarnings(List.of("ARCHITECTURE_VIEW_INCOMPLETE: retained evidence"));
        assertThat(ProjectAnalysisContinuationService.budgetProblem(analysis)).isNotNull();
    }

    @Test
    void anUnjournaledSourceQuestionCannotBeHiddenByAnotherPendingSource() {
        var analysis = partial("SOURCE_LIMIT");
        var source = new Node("BP-1", "BP", "Process", "Existing evidence read", false);
        var report = analysis.getRelationSearchReport();
        analysis.setRelationSearchReport(new RelationSearchReport(1, "0".repeat(64), "reference",
                List.of(new SourceAssessment(source, List.of(), "The role is ambiguous", "Which archive?")),
                report.result(), 3, 24, 0, report.warnings(), ""));
        assertThat(ProjectAnalysisContinuationService.budgetProblem(analysis)).isNotNull();
    }

    private AnalysisResult partial(String limit) {
        var analysis = new AnalysisResult(Map.of("BP-1", 0.5), List.of()); analysis.setStatus("PARTIAL");
        analysis.setErrorMessage("RELATION_SEARCH_PARTIAL: pending tasks");
        analysis.setRecovery(new AnalysisRecoveryView("run", 12, "STOPPED", 8, 9, null, List.of()));
        analysis.setRelationSearchReport(new RelationSearchReport(1, "0".repeat(64), "reference", List.of(),
                new Result(List.of(), List.of(), List.of(), 3, 0, 0), 3, 24, 0,
                List.of(limit + ": independent work remains unassessed"), ""));
        return analysis;
    }
}
