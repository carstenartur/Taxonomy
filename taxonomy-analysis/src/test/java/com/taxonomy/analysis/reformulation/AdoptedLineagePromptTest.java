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
        context.put("inheritedDecisionContext", """
                [{"historicalEvidenceHash":"history-1","statements":[
                  {"id":"a","wording":"Rejected A-only wording","reviewState":"REJECTED","architectureLinks":["A"],"questionDependencies":[]},
                  {"id":"b","wording":"Rejected B-only wording","reviewState":"REJECTED","architectureLinks":["B"],"questionDependencies":[]},
                  {"id":"unknown","wording":"Unmapped historical wording","reviewState":"REJECTED","architectureLinks":[],"questionDependencies":[]}],
                  "questions":[{"id":"q-global","key":{"scope":"global"},"wording":"Shared policy?","affectedStatementIds":[],"prerequisites":[],"discoveries":[]}],
                  "humanAnswers":[{"questionId":"q-global","values":["retain"],"rationale":"Globally decided"}],
                  "historicalReview":{"findings":[]}}]
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
            assertThat(prompt).contains("Shared policy?", "Globally decided", "Unmapped historical wording");
            assertThat(prompt).contains("Rejected " + step + "-only wording");
            assertThat(prompt).doesNotContain("Rejected " + (step.equals("A") ? "B" : "A") + "-only wording");
        }
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
}
