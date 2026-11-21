package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetHealth;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetMode;
import com.taxonomy.analysis.dto.AiTargetDtos.PromptBudget;
import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.LlmGateway;
import com.taxonomy.analysis.service.LlmGatewayRegistry;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.ReconciliationInput;
import com.taxonomy.reformulation.ReconciliationResult;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.reformulation.ReformulationDocument;
import com.taxonomy.reformulation.ReformulationStepExecutor;
import com.taxonomy.reformulation.Section;
import com.taxonomy.reformulation.Statement;
import com.taxonomy.reformulation.ValidationReport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundedReconciliationTest {
    private static final String ORIGINAL = "The capture channel is still open. Consider a terminal and a browser.";
    private static final String QUESTION = "capture-channel";
    private static final String DISCOVERY = "Archived discovery context and rejected alternatives. ".repeat(180);
    private static final String EMPTY = "{\"affectedSectionIds\":[],\"sourceResolutions\":[],\"findings\":[]}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void admitsBoundedViewsAndPreservesDisagreeingResolutionsAsAConflict() {
        var registry = mock(LlmGatewayRegistry.class);
        var config = mock(LlmProviderConfig.class);
        var replies = new ArrayList<JsonNode>();
        var success = new HashSet<String>();
        var budget = new AiPromptBudgetPolicy(null);
        var target = new AiTargetDescriptor("test", "test", "CUSTOM_OPENAI", "test", AiTargetMode.REMOTE,
                AiTargetHealth.UNAVAILABLE, false, false, false, new PromptBudget(30_000, 48_000, 7_000), "test", "test");
        when(config.getActiveProviderId()).thenReturn(LlmProvider.CUSTOM_OPENAI.id());
        when(config.isProviderConfigured(LlmProvider.CUSTOM_OPENAI.id())).thenReturn(true);
        when(registry.getGatewayById(LlmProvider.CUSTOM_OPENAI.id())).thenReturn(new LlmGateway() {
            @Override public void validatePromptBudget(String prompt) { budget.requireWithinBudget(prompt, target); }
            @Override public String providerName() { return "test"; }
            @Override public String extractResponseText(String raw) { return raw; }
            @Override public String sendHttpRequest(String prompt, String key) {
                validatePromptBudget(prompt);
                String marker = "\nRECONCILIATION_DATA_JSON\n";
                var payload = JSON.readTree(prompt.substring(prompt.indexOf(marker) + marker.length()));
                assertThat(payload.at("/baseline/originalText").asText()).isEqualTo(ORIGINAL);
                replies.add(payload);
                if (!payload.path("questions").findValuesAsString("id").contains(QUESTION)) return EMPTY;
                String channel = replies.size() == 1 ? "terminal" : "browser";
                int start = ORIGINAL.indexOf(channel);
                return JSON.writeValueAsString(Map.of("affectedSectionIds", List.of(), "findings", List.of(),
                        "sourceResolutions", List.of(Map.of("questionId", QUESTION, "values", List.of(channel),
                                "rationale", "Synthetic conflicting model suggestion", "sourceSpans",
                                List.of(Map.of("start", start, "end", start + channel.length(), "exactText", channel))))));
            }
        });
        var steps = ReformulationStepExecutor.of((kind, input, type, work) -> {
            var result = work.get();
            assertThat(type).isEqualTo(ReconciliationResult.class);
            assertThat(success.add(kind + JSON.writeValueAsString(input))).isTrue();
            return result;
        });
        var source = phaseA();
        String frozen = JSON.writeValueAsString(source);

        var result = new CrossTaxonomyReconciler(new NodeReformulationService(registry, config, JSON), JSON)
                .reconcile(baseline(source), source, List.of(), List.of(), steps);

        assertThat(replies.size()).isBetween(2, 17);
        assertThat(success).hasSize(replies.size());
        assertThat(replies.getFirst().path("sections").size()).isEqualTo(source.sections().size());
        assertThat(replies.getFirst().at("/questions/0/answerSchema/options").toString()).isEqualTo("[\"terminal\",\"browser\"]");
        assertThat(replies.stream().anyMatch(reply -> reply.toString().contains(DISCOVERY))).isTrue();
        assertThat(replies.subList(1, replies.size())).anySatisfy(reply -> {
            String wireEdge = reply.at("/boundaryEdges/edge-1").asText();
            assertThat(wireEdge).isNotBlank();
            var edge = JSON.readTree(wireEdge);
            assertThat(edge.path("sourceCode").asText()).isEqualTo("CI");
            assertThat(edge.path("targetCode").asText()).isEqualTo("CO");
            assertThat(reply.path("sections").findValuesAsString("id")).contains("CI", "CO");
        });
        assertThat(replies.subList(1, replies.size())).anySatisfy(reply ->
                assertThat(reply.path("inheritedDecisionContext")).isEqualTo(JSON.readTree(inheritedHistory())));
        assertThat(replies.stream().flatMap(reply -> reply.path("statements").findValuesAsString("id").stream()))
                .contains(source.statements().stream().map(Statement::id).toArray(String[]::new));
        assertThat(result.statements()).containsAll(source.statements());
        assertThat(JSON.writeValueAsString(source)).isEqualTo(frozen);
        assertThat(result.questions()).singleElement().satisfies(question -> {
            assertThat(question.id()).isEqualTo(QUESTION);
            assertThat(question.state()).isEqualTo(DecisionQuestion.State.CONFLICT);
            assertThat(question.sourceResolutions()).extracting(DecisionQuestion.SourceResolution::values)
                    .containsExactlyInAnyOrder(List.of("terminal"), List.of("browser"));
        });
        assertThat(result.validation().findings()).extracting(ValidationReport.Finding::code)
                .contains("CONFLICTING_ANSWERS", "BOUNDED_RECONCILIATION");
    }

    @Test
    void recordsTheMissingJointComparisonWhenOnlyDetailViewsFit() {
        var source = phaseA();
        var input = new ReconciliationInput(baseline(source, false), 1, source.sections(), source.statements(), source.questions(),
                List.of(), Map.of(), Map.of(), List.of());

        var plan = new BoundedReconciliation(JSON).plan(input, view ->
                view.scope() == BoundedReconciliation.Scope.DETAIL && view.input().sections().size() <= 2);

        assertThat(plan.hasOverview()).isFalse();
        assertThat(plan.views()).hasSizeGreaterThan(1).allSatisfy(view -> assertThat(view.input().sections()).hasSizeLessThanOrEqualTo(2));
        assertThat(plan.views().stream().flatMap(view -> view.input().sections().stream()).distinct())
                .containsExactlyInAnyOrderElementsOf(source.sections());
        assertThat(plan.coverageFinding().kind()).isEqualTo(ValidationReport.Kind.SEMANTIC_REVIEW);
        assertThat(plan.coverageFinding().message()).contains("comparisons across views still need review");
    }

    @Test
    void declinesThePlanWhenAnIndependentArchivedRecordCannotFit() {
        var source = phaseA();
        var input = new ReconciliationInput(baseline(source), 1, source.sections(), source.statements(), source.questions(),
                List.of(), Map.of(), Map.of(), List.of());
        var builder = new ReconcilePromptBuilder(JSON);

        assertThatThrownBy(() -> new BoundedReconciliation(JSON).plan(input, view ->
                !builder.build(view, null).contains("q-archived")))
                .hasMessageContaining("INPUT_TOO_LARGE_FOR_PROVIDER");
    }

    @Test
    void identicalArchiveEntriesKeepTheirDistinctAdmittedCheckpointInputs() {
        var source = phaseA();
        var baseline = baseline(source);
        var frozen = new java.util.HashMap<>(baseline.frozenContext());
        var archive = JSON.readTree(inheritedHistory());
        frozen.put("inheritedDecisionContext", JSON.createArrayNode().add(archive.get(0)).add(archive.get(0)).toString());
        var repeated = new ReformulationBaseline(baseline.scope(), baseline.sourceVersionId(), baseline.originalText(),
                baseline.originalTextHash(), baseline.snapshotId(), baseline.snapshotPayload(), frozen, baseline.language(), baseline.algorithmVersion());
        var input = new ReconciliationInput(repeated, 1, source.sections(), source.statements(), source.questions(),
                List.of(), Map.of(), Map.of(), List.of());

        var plan = new BoundedReconciliation(JSON).plan(input, view -> view.scope() != BoundedReconciliation.Scope.ARCHIVE
                || JSON.readTree(view.input().baseline().frozenContext().get("inheritedDecisionContext")).size() == 1);

        assertThat(plan.views().stream().filter(view -> view.scope() == BoundedReconciliation.Scope.ARCHIVE))
                .hasSize(2).doesNotHaveDuplicates();
    }

    private static ReformulationDocument phaseA() {
        var question = new DecisionQuestion(QUESTION, new DecisionQuestion.Key("capture", "channel", "global"),
                "How will users capture time?", List.of(new DecisionQuestion.Discovery("BP", DISCOVERY,
                        "The channel is unresolved", List.of(), List.of("BP"), List.of())), List.of("s-BP"),
                new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE,
                        List.of("terminal", "browser"), null, null, null), List.of(), List.of(), "Defines capture method",
                DecisionQuestion.State.OPEN);
        var sections = new ArrayList<Section>();
        var statements = new ArrayList<Statement>();
        for (String code : List.of("BP", "BR", "CI", "CO", "CR", "IP", "UA")) {
            String id = "s-" + code;
            var decisions = code.equals("BP") ? List.of(QUESTION) : List.<String>of();
            sections.add(new Section(code, code, "Section " + code, "Summary " + code, List.of(), List.of(id), decisions));
            statements.add(new Statement(id, "Requirement detail for " + code, List.of(), Statement.Provenance.MODEL_ADDITION,
                    List.of(code), decisions, null, Statement.EditingOrigin.MODEL, "UNREVIEWED"));
        }
        return new ReformulationDocument("Draft", sections, statements, List.of(question), new ValidationReport(List.of()), List.of());
    }

    private static ReformulationBaseline baseline(ReformulationDocument source) {
        return baseline(source, true);
    }

    private static ReformulationBaseline baseline(ReformulationDocument source, boolean includeHistory) {
        var scope = new ReformulationBaseline.Scope("repo", "ws", "main", 1, 1);
        var catalogue = new ArrayList<>(source.sections().stream().map(section -> Map.of("code", section.id(),
                "description", ("Frozen description with unique taxonomy detail for " + section.id() + " ½ 🤖. ").repeat(180))).toList());
        catalogue.add(Map.of("code", "CP", "description", "Known, independent historical architecture node"));
        var frozen = new java.util.HashMap<>(ReconcilePromptBuilder.freeze(Map.of()));
        frozen.put("catalogue", JSON.writeValueAsString(catalogue));
        frozen.put("relationMappings", JSON.writeValueAsString(List.of(
                Map.of("id", 1, "sourceCode", "CI", "targetCode", "CO", "relationType", "SUPPLIES", "reviewStatus", "PROPOSED"))));
        if (includeHistory) frozen.put("inheritedDecisionContext", inheritedHistory());
        return ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope, 1, ORIGINAL),
                new ReformulationBaseline.Snapshot(scope, "snapshot", 1, "{\"rawScores\":{}}"),
                frozen, "en", "v1");
    }

    private static String inheritedHistory() {
        return """
                [{"historicalEvidenceHash":"archive-hash","statements":[{"id":"s-archived","architectureLinks":["CP"],
                    "questionDependencies":["q-archived"],"wording":"Withdrawn delegation","reviewState":"REJECTED"}],
                  "questions":[{"id":"q-archived","key":{"scope":"CP"},"wording":"Which delegation?",
                    "affectedStatementIds":["s-archived"],"state":"ANSWERED"}],
                  "humanAnswers":[{"id":"a-archived","questionId":"q-archived","values":["Exact archival choice"],"state":"ANSWERED"}]}]
                """;
    }
}
