package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for evidence reaching the next bottom-up synthesis step. */
class FrozenReformulationEvidenceTest {
    private static final String ORIGINAL = "Arbeitszeiterfassung. Keine Standortüberwachung.";
    private static final String CATALOGUE = """
            [{"code":"P","children":[{"code":"Q","children":[{"code":"L"}]}]}]
            """;
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rawScores\":{\"P\":0.5,\"Q\":0.25,\"L\":0.125}}",
            "{\"scores\":{\"P\":0.5,\"Q\":0.25,\"L\":0.125}}",
            "{\"rawScores\":null,\"scores\":{\"P\":0.5,\"Q\":0.25,\"L\":0.125}}"
    })
    void positiveFractionalContributionsReachTheirParentsWithoutRounding(String payload) {
        var inputs = capture(baseline(payload, "[]"));
        assertEquals(List.of("Q", "P"), inputs.stream().map(NodeSynthesisInput::nodeId).toList());
        var context = json.readTree(inputs.getFirst().nodeDescription());
        assertEquals(0.125, context.at("/terminalContributions/0/score").asDouble());
        assertEquals(0.25, context.at("/directParentContributions/0/score").asDouble());
        inputs.forEach(input -> assertEquals(ORIGINAL, input.baseline().originalText()));
    }

    @Test
    void directMappingKeepsDecimalContributionEvenWithUnscoredAncestors() {
        var inputs = capture(baseline("{\"scores\":{}}", "[{\"nodeCode\":\"L\",\"directScore\":0.00125}]"));
        assertEquals(List.of("Q", "P"), inputs.stream().map(NodeSynthesisInput::nodeId).toList());
        assertEquals(0.00125, json.readTree(inputs.getFirst().nodeDescription())
                .at("/terminalContributions/0/score").asDouble());
    }

    @Test
    void missingDirectAssessmentDoesNotEraseFrozenPositiveEvidence() {
        var inputs = capture(baseline("{\"rawScores\":{\"L\":0.125}}",
                "[{\"nodeCode\":\"L\",\"directScore\":null}]"));
        assertEquals(List.of("Q", "P"), inputs.stream().map(NodeSynthesisInput::nodeId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rawScores\":{},\"scores\":{\"L\":10}}",
            "{\"rawScores\":{\"L\":0},\"scores\":{\"L\":10}}",
            "{\"rawScores\":{\"L\":null}}",
            "{}"
    })
    void emptyZeroOrUnknownEvidenceDoesNotInventPositiveContributions(String payload) {
        var inputs = capture(baseline(payload, "[]"));
        assertEquals(List.of(WalkUpPlanner.DOCUMENT_ROOT), inputs.stream().map(NodeSynthesisInput::nodeId).toList());
        assertEquals(ORIGINAL, inputs.getFirst().baseline().originalText());
    }

    @Test
    void explicitZeroDirectAssessmentOverridesOlderPositiveScore() {
        var inputs = capture(baseline("{\"rawScores\":{\"L\":10}}", "[{\"nodeCode\":\"L\",\"directScore\":0}]"));
        assertEquals(List.of(WalkUpPlanner.DOCUMENT_ROOT), inputs.stream().map(NodeSynthesisInput::nodeId).toList());
    }

    @Test
    void historicalIntegerPromptMetadataKeepsItsNumericSpelling() {
        var input = capture(baseline("{\"scores\":{\"P\":40,\"Q\":30,\"L\":20}}", "[]")).getLast();
        assertTrue(input.nodeDescription().contains("\"score\":40"));
        assertFalse(input.nodeDescription().contains("\"score\":40.0"));
        assertFalse(input.nodeDescription().contains("4E+1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"scores\":{\"L\":\"0.5\"}}", "{\"scores\":{\"L\":true}}",
            "{\"scores\":{\"L\":-1}}", "{\"scores\":{\"L\":101}}",
            "{\"scores\":{\"L\":1e-400}}", "{\"scores\":{\"L\":1e400}}",
            "{\"rawScores\":[]}", "{\"scores\":[]}", "[]",
            "{\"scores\":{\"L\":1,\"L\":0}}", "{\"scores\":{}} {}"
    })
    void malformedEvidenceIsRejectedBeforeSynthesisRatherThanConvertedToZero(String payload) {
        var engine = engine(input -> { fail("Malformed baseline reached model boundary"); return null; });
        assertThrows(IllegalArgumentException.class, () -> engine.synthesize(baseline(payload, "[]"), List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"nodeCode\":\"L\",\"directScore\":\"0.5\"}]",
            "[{\"nodeCode\":\"L\",\"directScore\":101}]",
            "[{\"nodeCode\":\"L\",\"directScore\":1e-400}]",
            "[{\"directScore\":1}]", "{}"
    })
    void malformedDirectMappingsAreRejectedBeforeSynthesis(String mappings) {
        var engine = engine(input -> { fail("Malformed mapping reached model boundary"); return null; });
        assertThrows(IllegalArgumentException.class, () -> engine.synthesize(baseline("{}", mappings), List.of(), List.of()));
    }

    @ParameterizedTest
    @EnumSource(value = DecisionQuestion.State.class, names = {"ANSWERED", "DEFERRED", "NOT_APPLICABLE", "CONFLICT"})
    void rewordingPreservesExactRetainedQuestionAndParentEvidence(DecisionQuestion.State state) {
        var retained = question("q-existing", state);
        var before = document(retained);
        var result = engine(input -> reply(input, List.of(), List.of(question(retained.id(), DecisionQuestion.State.OPEN))))
                .synthesizeAffected(baseline("{}", "[]"), before, List.of(), impact());
        assertEquals(List.of(retained), result.questions());
        assertEquals(List.of(retained), result.nodeResults().getFirst().questionProposals());
        assertEquals(state, before.questions().getFirst().state());
        assertEquals(ORIGINAL, before.text());
    }

    @Test
    void replayOfMergedQuestionAliasDoesNotSplitOrReopenDecision() {
        var source = question("q-canonical", DecisionQuestion.State.DEFERRED);
        var retained = new DecisionQuestion(source.id(), source.key(), source.wording(), source.discoveries(),
                source.affectedStatementIds(), source.answerSchema(), source.prerequisites(), source.dependentQuestionIds(),
                source.consequences(), source.state(), List.of("q-alias"), List.of(), List.of());
        var result = engine(input -> reply(input, List.of(), List.of(question("q-alias", DecisionQuestion.State.OPEN))))
                .synthesizeAffected(baseline("{}", "[]"), document(retained), List.of(), impact());
        assertEquals(List.of(retained), result.questions());
        assertEquals(List.of(retained), result.nodeResults().getFirst().questionProposals());
        assertEquals(List.of(retained.id()), result.sections().getFirst().questionIds());
    }

    @Test
    void fullSynthesisPreservesManualWordingAndDecisionsInEveryParentInput() {
        var question = question("q-existing", DecisionQuestion.State.DEFERRED);
        var retained = new Statement("s-existing", "Manuell präzisiert: keine Standortüberwachung.", List.of(),
                Statement.Provenance.MODEL_ADDITION, List.of(), List.of(question.id()), null, Statement.EditingOrigin.HUMAN, "UNREVIEWED");
        var replay = new Statement(retained.id(), "Alte Modellformulierung.", List.of(), Statement.Provenance.MODEL_ADDITION,
                List.of(), List.of(question.id()), null, Statement.EditingOrigin.MODEL, "UNREVIEWED");
        var inputs = new ArrayList<NodeSynthesisInput>();
        var result = engine(input -> {
            inputs.add(input);
            if (input.nodeId().equals("P")) {
                var child = input.children().getFirst();
                assertEquals(retained, child.statementProposals().stream().filter(s -> s.id().equals(retained.id())).findFirst().orElseThrow());
                assertEquals(List.of(question), child.questionProposals());
            }
            return reply(input, List.of(replay), List.of(question(question.id(), DecisionQuestion.State.OPEN)));
        }).synthesize(baseline("{\"scores\":{\"P\":50,\"Q\":30,\"L\":20}}", "[]"), List.of(retained), List.of(), List.of(question));
        assertEquals(List.of("Q", "P"), inputs.stream().map(NodeSynthesisInput::nodeId).toList());
        assertTrue(result.text().contains(retained.wording()));
        assertFalse(result.text().contains(replay.wording()));
        assertEquals(List.of(question), result.questions());
        result.nodeResults().forEach(node -> assertEquals(retained, node.statementProposals().stream()
                .filter(s -> s.id().equals(retained.id())).findFirst().orElseThrow()));
    }

    @Test
    void retainingDecisionsDoesNotDiscardGenuinelyNewQuestionsOrConflicts() {
        var retained = question("q-existing", DecisionQuestion.State.ANSWERED);
        var additional = question("q-new", DecisionQuestion.State.OPEN);
        var conflict = new ValidationReport.Finding(ValidationReport.Kind.CONFLICT, "NEW_CONFLICT",
                "Neue Entscheidung erforderlich", List.of(), List.of());
        var result = engine(input -> new NodeSynthesisResult(input.nodeId(), "Neuer Hinweis", List.of(), List.of(),
                List.of(question(retained.id(), DecisionQuestion.State.OPEN), additional), List.of(retained.id()), List.of(), List.of(conflict)))
                .synthesizeAffected(baseline("{}", "[]"), document(retained), List.of(), impact());
        assertEquals(List.of(retained, additional), result.questions());
        assertTrue(result.validation().findings().contains(conflict));
    }

    @Test
    void identicalRawQuestionReplyDoesNotReopenDeferredDecision() {
        var parser = new ReformulationResponseParser(json);
        var engine = engine(input -> parser.parse(rawQuestionReply(input), input));
        var baseline = baseline("{}", "[]");
        var first = engine.synthesize(baseline, List.of(), List.of());
        var generated = first.questions().getFirst();
        var deferred = new DecisionQuestion(generated.id(), generated.key(), generated.wording(), generated.discoveries(),
                generated.affectedStatementIds(), generated.answerSchema(), generated.prerequisites(),
                generated.dependentQuestionIds(), generated.consequences(), DecisionQuestion.State.DEFERRED);
        var before = new ReformulationDocument(first.text(), first.sections(), first.statements(), List.of(deferred),
                first.validation(), first.nodeResults());
        var second = engine.synthesizeAffected(baseline, before, List.of(), impact());
        assertEquals(List.of(deferred), second.questions());
        assertEquals(List.of(deferred), second.nodeResults().getFirst().questionProposals());
        assertEquals(ORIGINAL, baseline.originalText());
    }

    private String rawQuestionReply(NodeSynthesisInput input) {
        var reply = json.readTree("""
                {"summary":"Offline-Bedarf muss geklärt werden.","statementProposals":[],
                 "preservedStatementIds":[],"preservedQuestionIds":[],"uncoveredSourceRefs":[],"conflictCandidates":[],
                 "questionProposals":[{"subject":"time","dimension":"offline","scope":"global",
                   "wording":"Ist Offline-Erfassung erforderlich?","rationale":"Verbindung kann fehlen.",
                   "affectedStatementIds":[],"sourceSpans":[],"nodeIds":[],"edgeIds":[],
                   "answerSchema":{"kind":"BOOLEAN","options":[],"unit":null,"minimum":null,"maximum":null},
                   "prerequisites":[],"consequences":"Offline-Synchronisation beeinflusst die Architektur."}]}
                """);
        var object = (tools.jackson.databind.node.ObjectNode) reply;
        object.set("preservedStatementIds", json.valueToTree(input.directContributions().stream().map(Statement::id).toList()));
        object.set("preservedQuestionIds", json.valueToTree(input.openDecisions().stream().map(DecisionQuestion::id).toList()));
        return json.writeValueAsString(object);
    }

    private List<NodeSynthesisInput> capture(ReformulationBaseline baseline) {
        var inputs = new ArrayList<NodeSynthesisInput>();
        engine(input -> { inputs.add(input); return reply(input, List.of(), List.of()); }).synthesize(baseline, List.of(), List.of());
        return inputs;
    }

    private FrozenReformulationEngine engine(Function<NodeSynthesisInput, NodeSynthesisResult> handler) {
        // Unit-level synthesis boundary only; the production planner/engine/evidence handling run normally.
        var service = new NodeReformulationService(null, null, json) {
            @Override public NodeSynthesisResult synthesize(NodeSynthesisInput input, ReformulationStepExecutor steps) {
                return handler.apply(input);
            }
        };
        return new FrozenReformulationEngine(service, json);
    }

    private static NodeSynthesisResult reply(NodeSynthesisInput input, List<Statement> statements, List<DecisionQuestion> questions) {
        return new NodeSynthesisResult(input.nodeId(), "Zusammenfassung", statements,
                input.directContributions().stream().map(Statement::id).toList(), questions,
                input.openDecisions().stream().map(DecisionQuestion::id).toList(), List.of(), List.of());
    }

    private static DecisionQuestion question(String id, DecisionQuestion.State state) {
        return new DecisionQuestion(id, new DecisionQuestion.Key("time", "offline", "global"), "Ist Offline-Erfassung erforderlich?",
                List.of(), List.of(), new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.BOOLEAN, List.of(), null, null, null),
                List.of(), List.of(), "Offline-Synchronisation beeinflusst die Architektur.", state);
    }

    private static ReformulationDocument document(DecisionQuestion question) {
        var section = new Section("@document", null, "Angebot", "Bisher", List.of(), List.of(), List.of(question.id()));
        return new ReformulationDocument(ORIGINAL, List.of(section), List.of(), List.of(question), new ValidationReport(List.of()), List.of());
    }

    private static ReformulationImpact impact() {
        return new ReformulationImpact(List.of(), List.of("@document"), List.of(), List.of(), false);
    }

    private static ReformulationBaseline baseline(String payload, String mappings) {
        var scope = new ReformulationBaseline.Scope("repo", "workspace", "main", 1, 1);
        return ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope, 1, ORIGINAL),
                new ReformulationBaseline.Snapshot(scope, "snapshot", 1, payload),
                Map.of("catalogue", CATALOGUE, "relationMappings", "[]", "elementMappings", mappings), "de", "reformulation-v1");
    }
}
