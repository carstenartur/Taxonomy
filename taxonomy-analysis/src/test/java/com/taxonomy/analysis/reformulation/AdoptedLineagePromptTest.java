package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdoptedLineagePromptTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String inherited = """
            [{"historicalEvidenceHash":"0123456789abcdef","adoptionActor":"architect",
              "adoptionRationale":"Terminal-only decision","statements":[{"wording":"No browser input",
              "provenance":"MODEL_ADDITION","reviewState":"REJECTED"}],
              "questions":[{"wording":"Which terminal?","state":"ANSWERED"}],
              "humanAnswers":[{"values":["Card terminal"],"state":"ANSWERED",
              "author":"architect","rationale":"Selected by operator","supersedes":[]}]}]
            """;

    private ReformulationBaseline adopted() {
        var b = WalkUpReformulationTest.baseline();
        var context = new HashMap<>(b.frozenContext());
        context.put("adoptedLineage", "RAW_OLD_ARCHIVE_DO_NOT_PROMPT");
        context.put("inheritedDecisionContext", inherited);
        context.put("reconcilePrompt", ReconcilePromptBuilder.template());
        return new ReformulationBaseline(b.scope(), b.sourceVersionId(), b.originalText(),
                b.originalTextHash(), b.snapshotId(), b.snapshotPayload(), context,
                b.language(), b.algorithmVersion());
    }

    static ReformulationBaseline scopedAdopted() {
        var b = WalkUpReformulationTest.baseline();
        var context = new HashMap<>(b.frozenContext());
        context.put("adoptedLineage", "frozen archive");
        context.put("catalogue", "[{\"code\":\"A\",\"children\":[]},{\"code\":\"B\",\"children\":[]}] ");
        context.put("relationMappings", "[{\"id\":\"1\",\"sourceCode\":\"A\",\"targetCode\":\"B\"}]");
        context.put("inheritedDecisionContext", """
                [{"historicalEvidenceHash":"history-1","statements":[
                  {"id":"a","wording":"Rejected A-only wording","reviewState":"REJECTED","architectureLinks":["A"],"questionDependencies":[]},
                  {"id":"b","wording":"Rejected B-only wording","reviewState":"REJECTED","architectureLinks":["B"],"questionDependencies":[]},
                  {"id":"retired","wording":"Obsolete mapping remains visible","reviewState":"REJECTED","architectureLinks":["RETIRED"],"questionDependencies":[]},
                  {"id":"unknown","wording":"Unmapped historical wording","reviewState":"REJECTED","architectureLinks":[],"questionDependencies":[]}],
                  "questions":[{"id":"q-global","key":{"scope":"global"},"wording":"Shared policy?","affectedStatementIds":[],"prerequisites":[],"discoveries":[]},
                    {"id":"q-boundary","key":{"scope":"edge-1"},"wording":"Boundary decision?","affectedStatementIds":[],"prerequisites":[],"discoveries":[]},
                    {"id":"edit-b","key":{"scope":"local"},"wording":"B-only statement edit?","affectedStatementIds":["b"],"prerequisites":[],"discoveries":[]},
                    {"id":"edit-unmapped","key":{"scope":"local"},"wording":"Retired statement edit?","affectedStatementIds":["retired"],"prerequisites":[],"discoveries":[]}],
                  "humanAnswers":[{"questionId":"q-global","values":["retain"],"rationale":"Globally decided"},
                    {"questionId":"edit-b","values":["B only"],"rationale":"B edit rationale"}],
                  "historicalReview":{"findings":[]}},
                 {"historicalEvidenceHash":"history-2","statements":[
                   {"id":"dependent","wording":"Dependent historical wording","reviewState":"REJECTED","architectureLinks":[],"questionDependencies":["q-boundary"]}],
                   "questions":[],"humanAnswers":[],"historicalReview":{"findings":[]}}]
                """);
        context.put("reconcilePrompt", ReconcilePromptBuilder.template());
        return new ReformulationBaseline(b.scope(), b.sourceVersionId(), b.originalText(),
                b.originalTextHash(), b.snapshotId(), b.snapshotPayload(), context,
                b.language(), b.algorithmVersion());
    }

    @Test void branchCallsExcludeUnrelatedInheritedDecisionsButKeepGlobalAndUnmapped() {
        var baseline = scopedAdopted();
        var builder = new ReformulationPromptBuilder(json);
        for (String step : List.of("A", "B")) {
            var input = new NodeSynthesisInput(baseline, step, null, step, List.of(), List.of(),
                    List.of(), Map.of(), List.of(), List.of(), "Preserve source");
            String prompt = builder.build(input, null);
            assertThat(prompt).contains("Shared policy?", "Globally decided", "Unmapped historical wording",
                    "Obsolete mapping remains visible", "Retired statement edit?");
            assertThat(prompt).contains("Rejected " + step + "-only wording");
            assertThat(prompt).doesNotContain("Rejected " + (step.equals("A") ? "B" : "A") + "-only wording");
            assertThat(prompt).doesNotContain("Boundary decision?", "Dependent historical wording");
            if (step.equals("A")) assertThat(prompt).doesNotContain("B-only statement edit?", "B edit rationale");
            else assertThat(prompt).contains("B-only statement edit?", "B edit rationale");
        }
        var boundary = new NodeSynthesisInput(baseline, "A", null, "A", List.of(), List.of(),
                List.of(), Map.of("edge-1", "{\"sourceCode\":\"A\",\"targetCode\":\"B\"}"),
                List.of(), List.of(), "Preserve source");
        assertThat(builder.build(boundary, null)).contains("Boundary decision?", "Dependent historical wording");
        var input = new ReconciliationInput(baseline, 1, List.of(), List.of(), List.of(),
                List.of(), Map.of(), Map.of(), List.of());
        assertThat(new ReconcilePromptBuilder(json).build(input, null))
                .contains("Rejected A-only wording", "Rejected B-only wording", "Shared policy?");
    }

    @Test void nodeGroupAggregateAndRewordPayloadsCarryConcreteInheritedEvidenceWithoutRawArchive() {
        var base = adopted();
        for (String node : List.of("NODE", "NODE_GROUP", "NODE_AGGREGATE", "REWORD")) {
            var input = new NodeSynthesisInput(base, node, null, "Node", List.of(), List.of(),
                    List.of(), Map.of(), List.of(), List.of(), "Preserve source");
            String prompt = new ReformulationPromptBuilder(json).build(input, null);
            assertThat(prompt).contains(base.originalText(), "Card terminal", "Selected by operator",
                    "MODEL_ADDITION", "REJECTED", "Which terminal?", "Terminal-only decision",
                    "0123456789abcdef");
            assertThat(prompt).doesNotContain("RAW_OLD_ARCHIVE_DO_NOT_PROMPT");
        }
    }

    @Test void reconcilePayloadCarriesConcreteInheritedEvidenceWithoutRawArchive() {
        var base = adopted();
        var input = new ReconciliationInput(base, 1, List.of(), List.of(), List.of(),
                List.of(), Map.of(), Map.of(), List.of());
        String prompt = new ReconcilePromptBuilder(json).build(input, null);
        assertThat(prompt).contains(base.originalText(), "Card terminal", "Selected by operator",
                "MODEL_ADDITION", "REJECTED", "Which terminal?", "Terminal-only decision",
                "0123456789abcdef");
        assertThat(prompt).doesNotContain("RAW_OLD_ARCHIVE_DO_NOT_PROMPT");
    }

    @Test void inheritedContextBeyondSmallBudgetFailsRatherThanDroppingHistoricalDecisions() {
        var base = adopted();
        var plainContext = new HashMap<>(base.frozenContext());
        plainContext.remove("adoptedLineage");
        plainContext.remove("inheritedDecisionContext");
        var plain = new ReformulationBaseline(base.scope(), base.sourceVersionId(), base.originalText(),
                base.originalTextHash(), base.snapshotId(), base.snapshotPayload(), plainContext,
                base.language(), base.algorithmVersion());
        var input = new NodeSynthesisInput(base, "NODE", null, "Node", List.of(), List.of(),
                List.of(), Map.of(), List.of(), List.of(), "Preserve source");
        var without = new NodeSynthesisInput(plain, "NODE", null, "Node", List.of(), List.of(),
                List.of(), Map.of(), List.of(), List.of(), "Preserve source");
        var builder = new ReformulationPromptBuilder(json);
        int maxCharacters = builder.build(without, null).length() + inherited.length() / 2;
        assertThat(builder.build(without, null).length()).isLessThan(maxCharacters);
        assertThat(builder.build(input, null).length()).isGreaterThan(maxCharacters);
        assertThatThrownBy(() -> new BoundedNodeSynthesis(json).partition(input,
                candidate -> builder.build(candidate, null).length() <= maxCharacters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INPUT_TOO_LARGE_FOR_PROVIDER");
    }

    @Test void adoptedOriginalIsProtectedSourceThroughEngineAndReconciliation() {
        var base = adopted();
        var service = mock(NodeReformulationService.class);
        when(service.synthesize(any(NodeSynthesisInput.class), any(ReformulationStepExecutor.class)))
                .thenAnswer(call -> {
                    NodeSynthesisInput input = call.getArgument(0);
                    return new NodeSynthesisResult(input.nodeId(), "Summary", List.of(),
                            input.directContributions().stream().map(Statement::id).toList(),
                            List.of(), List.of(), List.of(), List.of());
                });
        when(service.reconcile(any(ReconciliationInput.class)))
                .thenReturn(new ReconciliationResult(List.of(), Map.of(), List.of()));
        var phaseA = new FrozenReformulationEngine(service, json).synthesize(base, List.of(), List.of());
        assertThat(phaseA.statements()).filteredOn(s -> s.editingOrigin() == Statement.EditingOrigin.SOURCE)
                .singleElement().satisfies(s -> {
                    assertThat(s.provenance().name()).isEqualTo("ADOPTED_SOURCE");
                    assertThat(s.sourceSpans().getFirst().matches(base.originalText())).isTrue();
                });
        var phaseB = new CrossTaxonomyReconciler(service, json).reconcile(base, phaseA,
                List.of(), List.of(), ReformulationStepExecutor.direct());
        assertThat(phaseB.statements()).filteredOn(s -> s.editingOrigin() == Statement.EditingOrigin.SOURCE)
                .singleElement().satisfies(s -> assertThat(s.provenance().name())
                        .isEqualTo("ADOPTED_SOURCE"));
        assertThat(phaseB.text()).contains(base.originalText());
    }

    @Test void historicalRejectionCannotBecomeActiveViaEngineOrReconciledLayout() {
        var frozen = scopedAdopted();
        var base = new ReformulationBaseline(frozen.scope(), frozen.sourceVersionId(),
                frozen.originalText(), frozen.originalTextHash(), frozen.snapshotId(),
                "{\"rawScores\":{\"A\":45}}", frozen.frozenContext(), frozen.language(), frozen.algorithmVersion());
        var service = mock(NodeReformulationService.class);
        when(service.synthesize(any(NodeSynthesisInput.class), any(ReformulationStepExecutor.class)))
                .thenAnswer(call -> {
                    NodeSynthesisInput input = call.getArgument(0);
                    var repeated = new Statement("repeated", "Rejected A-only wording", List.of(),
                            Statement.Provenance.MODEL_ADDITION, List.of(input.nodeId()), List.of(), null,
                            Statement.EditingOrigin.MODEL, "UNREVIEWED");
                    return new NodeSynthesisResult(input.nodeId(), "Rejected A-only wording",
                            List.of(repeated), input.directContributions().stream().map(Statement::id).toList(),
                            List.of(), List.of(), List.of(), List.of());
                });
        when(service.reconcile(any(ReconciliationInput.class)))
                .thenReturn(new ReconciliationResult(List.of(), Map.of(), List.of()));
        var phaseA = new FrozenReformulationEngine(service, json).synthesize(base, List.of(), List.of());
        assertThat(phaseA.statements()).filteredOn(s -> s.id().equals("repeated"))
                .singleElement().satisfies(s -> assertThat(s.reviewState()).isEqualTo("REJECTED"));
        assertThat(phaseA.text()).doesNotContain("Rejected A-only wording");
        var phaseB = new CrossTaxonomyReconciler(service, json).reconcile(base, phaseA,
                List.of(), List.of(), ReformulationStepExecutor.direct());
        assertThat(phaseB.text()).doesNotContain("Rejected A-only wording");
    }
}
