package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.service.*;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Regression coverage for evidence carried from an architecture into a revised requirement. */
class FrozenReformulationEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "Arbeitszeiterfassung ohne Standortüberwachung. Frist: 2 Sekunden.";
    private static final String CATALOGUE = """
            [{"code":"P","children":[{"code":"Q","children":[{"code":"L"}]}]},
             {"code":"X"}]
            """;

    @ParameterizedTest
    @ValueSource(strings = {"0.125", "0.000001", "1e-8"})
    void fractionalScoresRemainInBottomUpPlanAndPrompt(String percentage) {
        for (String field : List.of("scores", "rawScores")) {
            var service = new RecordingNodes(FrozenReformulationEvidenceTest::emptyReply);
            var baseline = baseline("{\"" + field + "\":{\"L\":" + percentage + "}}", "[]");
            var result = new FrozenReformulationEngine(service, JSON).synthesize(baseline, List.of(), List.of());
            assertEquals(List.of("Q", "P"), result.sections().stream().map(Section::id).toList());
            var terminal = JSON.readTree(service.inputs.getFirst().nodeDescription()).path("terminalContributions").get(0);
            assertEquals(Double.parseDouble(percentage), terminal.path("score").asDouble());
            assertEquals("L", terminal.path("id").asText());
            assertEquals(ORIGINAL, result.text());
            assertTrue(service.inputs.stream().allMatch(input -> input.baseline().originalText().equals(ORIGINAL)));
        }
    }

    @Test
    void frozenDecimalMappingOverridesSnapshotWithoutRoundingOrInventedMinimum() {
        var service = new RecordingNodes(FrozenReformulationEvidenceTest::emptyReply);
        var baseline = baseline("{\"rawScores\":{\"L\":50}}", "[{\"nodeCode\":\"L\",\"directScore\":0.03125}]");
        new FrozenReformulationEngine(service, JSON).synthesize(baseline, List.of(), List.of());
        var terminal = JSON.readTree(service.inputs.getFirst().nodeDescription()).path("terminalContributions").get(0);
        assertEquals(0.03125, terminal.path("score").asDouble());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "100.001", "\"0.5\"", "true", "{}", "[]", "1e-1000"})
    void invalidScoreEvidenceFailsBeforeAnySynthesis(String invalid) {
        var service = new RecordingNodes(FrozenReformulationEvidenceTest::emptyReply);
        var engine = new FrozenReformulationEngine(service, JSON);
        for (String field : List.of("scores", "rawScores")) {
            assertThrows(IllegalArgumentException.class,
                    () -> engine.synthesize(baseline("{\"" + field + "\":{\"L\":" + invalid + "}}", "[]"), List.of(), List.of()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> engine.synthesize(baseline("{}", "[{\"nodeCode\":\"L\",\"directScore\":" + invalid + "}]"), List.of(), List.of()));
        assertTrue(service.inputs.isEmpty());
    }

    @Test
    void nullUnknownAndExplicitZeroDoNotCreateArchitectureContributions() {
        for (String payload : List.of("{}", "{\"scores\":{\"L\":null}}", "{\"scores\":{\"L\":0}}",
                "{\"scores\":{\"L\":80},\"rawScores\":{\"L\":0}}")) {
            var service = new RecordingNodes(FrozenReformulationEvidenceTest::emptyReply);
            var result = new FrozenReformulationEngine(service, JSON).synthesize(baseline(payload, "[]"), List.of(), List.of());
            assertEquals(List.of(WalkUpPlanner.DOCUMENT_ROOT), result.sections().stream().map(Section::id).toList());
            assertEquals(ORIGINAL, result.text());
        }
    }

    @Test
    void unknownMappingDoesNotEraseObservedEvidenceAndLegacyIntegerPromptStaysStable() {
        var service = new RecordingNodes(FrozenReformulationEvidenceTest::emptyReply);
        new FrozenReformulationEngine(service, JSON).synthesize(
                baseline("{\"scores\":{\"L\":40}}", "[{\"nodeCode\":\"L\",\"directScore\":null}]"), List.of(), List.of());
        String description = service.inputs.getFirst().nodeDescription();
        assertTrue(description.contains("\"score\":40,"), description);
        assertFalse(description.contains("40.0"), description);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"scores\":[]}", "{\"rawScores\":true}", "null", "[]"})
    void malformedSnapshotIsNotReportedAsUnmatchedSource(String payload) {
        var service = new RecordingNodes(FrozenReformulationEvidenceTest::emptyReply);
        assertThrows(IllegalArgumentException.class,
                () -> new FrozenReformulationEngine(service, JSON).synthesize(baseline(payload, "[]"), List.of(), List.of()));
        assertTrue(service.inputs.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = DecisionQuestion.State.class, names = {"ANSWERED", "DEFERRED", "NOT_APPLICABLE", "CONFLICT"})
    void fullSynthesisCarriesExactRetainedEvidenceIntoParentInputs(DecisionQuestion.State state) {
        var retained = statement("s-retained", "Manually reviewed correction process.", Statement.EditingOrigin.HUMAN);
        var decided = question("q-retained", state);
        var fresh = question("q-new", DecisionQuestion.State.OPEN);
        var replay = statement(retained.id(), "A new model wording must not replace the retained statement.", Statement.EditingOrigin.MODEL);
        var service = new RecordingNodes(input -> new NodeSynthesisResult(input.nodeId(), "Safe summary", List.of(replay),
                List.of(retained.id()), List.of(question(decided.id(), DecisionQuestion.State.OPEN), fresh), List.of(decided.id()), List.of(), List.of()));
        var result = new FrozenReformulationEngine(service, JSON).synthesize(
                baseline("{\"scores\":{\"L\":1}}", "[]"), List.of(retained), List.of(), List.of(decided));
        assertSame(retained, findStatement(result.statements(), retained.id()));
        assertSame(decided, findQuestion(result.questions(), decided.id()));
        assertTrue(result.questions().contains(fresh));
        for (var node : result.nodeResults()) {
            assertSame(retained, findStatement(node.statementProposals(), retained.id()));
            assertSame(decided, findQuestion(node.questionProposals(), decided.id()));
        }
        var parent = service.inputs.stream().filter(input -> input.nodeId().equals("P")).findFirst().orElseThrow();
        assertSame(retained, findStatement(parent.children().getFirst().statementProposals(), retained.id()));
        assertSame(decided, findQuestion(parent.children().getFirst().questionProposals(), decided.id()));
    }

    @ParameterizedTest
    @EnumSource(value = DecisionQuestion.State.class, names = {"ANSWERED", "DEFERRED", "NOT_APPLICABLE", "CONFLICT"})
    void affectedSynthesisRetainsDecisionAndIndependentSection(DecisionQuestion.State state) {
        var decided = question("q-retained", state);
        var retained = statement("s-retained", "Manually reviewed correction process.", Statement.EditingOrigin.HUMAN);
        var independent = new Section("X", "X", "Independent", "Unchanged summary", List.of(), List.of(), List.of());
        var before = new ReformulationDocument(ORIGINAL,
                List.of(new Section("Q", "P", "Q", "Prior Q", List.of(), List.of(retained.id()), List.of(decided.id())),
                        new Section("P", "P", "P", "Prior P", List.of("Q"), List.of(retained.id()), List.of(decided.id())), independent),
                List.of(retained), List.of(decided), new ValidationReport(List.of()), List.of());
        var fresh = question("q-new", DecisionQuestion.State.OPEN);
        var service = new RecordingNodes(input -> new NodeSynthesisResult(input.nodeId(), "Safe summary", List.of(), List.of(retained.id()),
                List.of(question(decided.id(), DecisionQuestion.State.OPEN), fresh), List.of(decided.id()), List.of(), List.of()));
        var result = new FrozenReformulationEngine(service, JSON).synthesizeAffected(baseline("{}", "[]"), before, List.of(),
                new ReformulationImpact(List.of(retained.id()), List.of("Q", "P"), List.of(), List.of(), false));
        assertSame(decided, findQuestion(result.questions(), decided.id()));
        assertTrue(result.questions().contains(fresh));
        assertSame(independent, result.sections().stream().filter(section -> section.id().equals("X")).findFirst().orElseThrow());
        assertFalse(service.inputs.stream().anyMatch(input -> input.nodeId().equals("X")));
        assertSame(retained, findStatement(result.statements(), retained.id()));
        var parent = service.inputs.stream().filter(input -> input.nodeId().equals("P")).findFirst().orElseThrow();
        assertSame(decided, findQuestion(parent.children().getFirst().questionProposals(), decided.id()));
        assertSame(decided, findQuestion(result.nodeResults().stream().filter(node -> node.nodeId().equals("Q")).findFirst().orElseThrow().questionProposals(), decided.id()));
    }

    @Test
    void realPromptAndResponseParserKeepAnAnsweredQuestionAcrossRewording() {
        var config = mock(LlmProviderConfig.class);
        when(config.getActiveProviderId()).thenReturn(LlmProvider.OPENAI.id());
        when(config.isProviderConfigured(LlmProvider.OPENAI.id())).thenReturn(true);
        when(config.getApiKey(LlmProvider.OPENAI.id())).thenReturn("synthetic-test-key");
        var registry = mock(LlmGatewayRegistry.class);
        var inputs = new ArrayList<tools.jackson.databind.JsonNode>();
        var questionJson = JSON.readTree("""
                {"subject":"capture","dimension":"offline","scope":"Q","wording":"Offline required?",
                 "rationale":"Clarify capture availability","affectedStatementIds":[],"sourceSpans":[],
                 "nodeIds":["Q"],"edgeIds":[],
                 "answerSchema":{"kind":"TEXT","options":[],"unit":null,"minimum":null,"maximum":null},
                 "prerequisites":[],"consequences":"Changes capture scope"}
                """);
        var gateway = new LlmGateway() {
            @Override public String providerName() { return "controlled-reformulation-test"; }
            @Override public String extractResponseText(String raw) { return raw; }
            @Override public String sendHttpRequest(String prompt, String key) {
                var input = JSON.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n") + 16));
                inputs.add(input);
                var statementIds = new TreeSet<String>();
                var questionIds = new TreeSet<String>();
                input.path("directContributions").forEach(value -> statementIds.add(value.path("id").stringValue()));
                input.path("openDecisions").forEach(value -> questionIds.add(value.path("id").stringValue()));
                input.path("children").forEach(child -> {
                    child.path("statementProposals").forEach(value -> statementIds.add(value.path("id").stringValue()));
                    child.path("questionProposals").forEach(value -> questionIds.add(value.path("id").stringValue()));
                });
                return JSON.writeValueAsString(Map.of("summary", "Safe summary", "statementProposals", List.of(),
                        "preservedStatementIds", statementIds, "questionProposals",
                        "Q".equals(input.path("nodeId").stringValue()) ? List.of(questionJson) : List.of(),
                        "preservedQuestionIds", questionIds, "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of()));
            }
        };
        when(registry.getGatewayById(LlmProvider.OPENAI.id())).thenReturn(gateway);
        var engine = new FrozenReformulationEngine(new NodeReformulationService(registry, config, JSON), JSON);
        var baseline = baseline("{\"scores\":{\"L\":1}}", "[]");
        var first = engine.synthesize(baseline, List.of(), List.of());
        assertEquals(1, first.questions().size());
        var original = first.questions().getFirst();
        var answered = new DecisionQuestion(original.id(), original.key(), original.wording(), original.discoveries(),
                original.affectedStatementIds(), original.answerSchema(), original.prerequisites(), original.dependentQuestionIds(),
                original.consequences(), DecisionQuestion.State.ANSWERED, original.aliases(), original.origins(), original.sourceResolutions());
        var before = new ReformulationDocument(first.text(), first.sections(), first.statements(), List.of(answered), first.validation(), first.nodeResults());
        var result = engine.synthesizeAffected(baseline, before, List.of(),
                new ReformulationImpact(List.of(), List.of("Q", "P"), List.of(), List.of(), false));
        assertEquals(4, inputs.size(), "Each iteration needs exactly Q and P, without a repair request");
        assertSame(answered, result.questions().getFirst());
        assertEquals(1, result.questions().size());
        assertEquals("ANSWERED", inputs.getLast().path("children").get(0).path("questionProposals").get(0).path("state").stringValue());
    }

    private static ReformulationBaseline baseline(String snapshot, String mappings) {
        var scope = new ReformulationBaseline.Scope("repository", "workspace", "main", 1L, 1L);
        return ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope, 1, ORIGINAL),
                new ReformulationBaseline.Snapshot(scope, "a0", 1, snapshot),
                Map.of("catalogue", CATALOGUE, "relationMappings", "[]", "elementMappings", mappings), "de", "reformulation-v1");
    }

    private static Statement statement(String id, String wording, Statement.EditingOrigin origin) {
        return new Statement(id, wording, List.of(), Statement.Provenance.MODEL_ADDITION,
                List.of("Q"), List.of(), null, origin, "UNREVIEWED");
    }

    private static DecisionQuestion question(String id, DecisionQuestion.State state) {
        return new DecisionQuestion(id, new DecisionQuestion.Key("capture", "offline", "Q"), "Offline required?", List.of(), List.of(),
                new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT, List.of(), null, null, null),
                List.of(), List.of(), "Changes capture scope", state);
    }

    private static Statement findStatement(List<Statement> values, String id) {
        return values.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private static DecisionQuestion findQuestion(List<DecisionQuestion> values, String id) {
        return values.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private static NodeSynthesisResult emptyReply(NodeSynthesisInput input) {
        return new NodeSynthesisResult(input.nodeId(), "Summary", List.of(), input.directContributions().stream().map(Statement::id).toList(),
                List.of(), input.openDecisions().stream().map(DecisionQuestion::id).toList(), List.of(), List.of());
    }

    /** Engine unit tests replace only node synthesis; planning, aggregation and evidence handling are real. */
    private static final class RecordingNodes extends NodeReformulationService {
        private final List<NodeSynthesisInput> inputs = new ArrayList<>();
        private final Function<NodeSynthesisInput, NodeSynthesisResult> reply;

        private RecordingNodes(Function<NodeSynthesisInput, NodeSynthesisResult> reply) {
            super(null, null, JSON);
            this.reply = reply;
        }

        @Override public NodeSynthesisResult synthesize(NodeSynthesisInput input, ReformulationStepExecutor steps) {
            inputs.add(input);
            return reply.apply(input);
        }
    }
}
