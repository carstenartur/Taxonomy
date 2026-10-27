package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.ReconciliationInput;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.reformulation.Statement;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcilePromptCompactionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void frozenNodeDescriptionsDropOnlyRedundantValues() {
        var node = (ObjectNode) JSON.readTree("{\"code\":\"BP\",\"name\":\"Processes\",\"nameEn\":\"Processes\",\"nameDe\":null,"
                + "\"description\":\"Full text\",\"descriptionEn\":\"Full text\",\"descriptionDe\":\"Volltext\",\"dataset\":null}");
        ReconcilePromptBuilder.compact(node);
        assertThat(node.toString()).isEqualTo("{\"code\":\"BP\",\"name\":\"Processes\",\"description\":\"Full text\",\"descriptionDe\":\"Volltext\"}");
    }

    @Test
    void differingEnglishTextIsPreserved() {
        var node = (ObjectNode) JSON.readTree("{\"code\":\"BP\",\"name\":\"Prozesse\",\"nameEn\":\"Processes\","
                + "\"description\":\"Text\",\"descriptionEn\":\"Other text\"}");
        ReconcilePromptBuilder.compact(node);
        assertThat(node.path("nameEn").asText()).isEqualTo("Processes");
        assertThat(node.path("descriptionEn").asText()).isEqualTo("Other text");
    }

    @Test
    void compactionIsVersionedSoFrozenV1ProposalsKeepTheirExactEncoding() {
        String catalogue = "[{\"code\":\"BP\",\"name\":\"Processes\",\"nameEn\":\"Processes\",\"dataset\":null,\"children\":[]}]";
        var builder = new ReconcilePromptBuilder(JSON);
        var legacy = builder.scopedContext(baseline(catalogue, "reformulation-reconcile-v1"), java.util.Set.of("BP"));
        var unversioned = builder.scopedContext(baseline(catalogue, null), java.util.Set.of("BP"));
        var current = builder.scopedContext(baseline(catalogue, ReconcilePromptBuilder.PROMPT_VERSION), java.util.Set.of("BP"));
        assertThat(legacy.get("descriptions").toString()).contains("nameEn").contains("dataset");
        assertThat(unversioned).isEqualTo(legacy);
        assertThat(current.get("descriptions").toString()).doesNotContain("nameEn").doesNotContain("dataset");
        assertThat(ReconcilePromptBuilder.template()).startsWith("REQUIREMENT RECONCILIATION — " + ReconcilePromptBuilder.PROMPT_VERSION);
    }

    @Test
    void exactFullSourceCopiesRoundTripWithoutChangingDomainOrHistory() {
        String original = "Keine automatische Freigabe. \"Quelle\" mit äöü, 😀 und Zeilenumbruch\n".repeat(70);
        for (var provenance : List.of(Statement.Provenance.ORIGINAL, Statement.Provenance.ADOPTED_SOURCE)) {
            var statement = statement("source", original, provenance,
                    List.of(new Statement.SourceSpan(0, original.length(), original)));
            var input = sourceInput(original, List.of(statement), true);
            String before = JSON.writeValueAsString(input);
            String prompt = new ReconcilePromptBuilder(JSON).build(input, null);
            var encoded = data(prompt);

            assertThat(encoded.at("/baseline/originalText").asText()).isEqualTo(original);
            assertThat(encoded.at("/statements/0/wordingRef").asText()).isEqualTo("baseline.originalText");
            assertThat(encoded.at("/statements/0/sourceSpans/0/exactTextRef").asText()).isEqualTo("baseline.originalText");
            assertThat(encoded.at("/statements/0/wording").isMissingNode()).isTrue();
            assertThat(encoded.at("/statements/0/sourceSpans/0/exactText").isMissingNode()).isTrue();
            assertThat(prompt).contains("wordingRef", "exactTextRef", "SAME RECONCILIATION_DATA_JSON");
            // The short repeated discovery contexts do not pay for their own dictionary instruction.
            assertThat(encoded.has("discoveryContextTable")).isFalse();
            assertThat(encoded.path("inheritedDecisionContext"))
                    .isEqualTo(JSON.readTree(input.baseline().frozenContext().get("inheritedDecisionContext")));
            assertThat(resolveSourceReferences(encoded)).isEqualTo(inlineData(input));
            assertThat(JSON.writeValueAsString(input)).isEqualTo(before);
            assertThat(new ReconcilePromptBuilder(JSON).build(input, null)).isEqualTo(prompt);
        }
    }

    @Test
    void smallAndUnicodeSourceCopiesNeverIncreaseEitherBudgetMeasure() {
        for (String original : List.of("Zeit", "😀".repeat(100), "😀".repeat(400), "漢".repeat(400),
                "Quote \" and newline\n".repeat(200))) {
            var input = sourceInput(original, List.of(statement("source", original, Statement.Provenance.ADOPTED_SOURCE,
                    List.of(new Statement.SourceSpan(0, original.length(), original)))), false);
            String inline = input.baseline().frozenContext().get("reconcilePrompt")
                    + "\nRECONCILIATION_DATA_JSON\n" + JSON.writeValueAsString(inlineData(input));
            String prompt = new ReconcilePromptBuilder(JSON).build(input, null);

            assertThat(prompt.codePointCount(0, prompt.length())).isLessThanOrEqualTo(inline.codePointCount(0, inline.length()));
            assertThat(prompt.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(inline.getBytes(StandardCharsets.UTF_8).length);
            if (original.codePointCount(0, original.length()) <= 100) {
                assertThat(data(prompt).at("/statements/0/wording").asText()).isEqualTo(original);
                assertThat(prompt).isEqualTo(inline);
            }
            assertThat(resolveSourceReferences(data(prompt))).isEqualTo(inlineData(input));
        }
    }

    @Test
    void sourceReferencesPreserveNonmatchingWordingPartialSpansAndOtherProvenance() {
        String original = "Quelle 😀 mit unveränderten Bedingungen. ".repeat(80);
        var full = new Statement.SourceSpan(0, original.length(), original);
        var partial = new Statement.SourceSpan(0, 6, original.substring(0, 6));
        var shifted = new Statement.SourceSpan(1, original.length() + 1, original);
        var statements = List.of(
                statement("source", original, Statement.Provenance.ADOPTED_SOURCE, List.of(full, partial, shifted)),
                statement("changed", original + " Abweichung.", Statement.Provenance.ORIGINAL, List.of(full)),
                statement("model", original, Statement.Provenance.MODEL_ADDITION, List.of(full)));
        var input = sourceInput(original, statements, false);
        var encoded = data(new ReconcilePromptBuilder(JSON).build(input, null));
        var expected = inlineData(input);

        assertThat(encoded.at("/statements/0/wordingRef").asText()).isEqualTo("baseline.originalText");
        assertThat(encoded.at("/statements/0/sourceSpans/0/exactTextRef").asText()).isEqualTo("baseline.originalText");
        for (String path : List.of("/statements/0/sourceSpans/1", "/statements/0/sourceSpans/2", "/statements/1", "/statements/2")) {
            assertThat(encoded.at(path)).isEqualTo(expected.at(path));
        }
        assertThat(resolveSourceReferences(encoded)).isEqualTo(expected);
    }

    private static Statement statement(String id, String wording, Statement.Provenance provenance,
                                       List<Statement.SourceSpan> spans) {
        return new Statement(id, wording, spans, provenance, List.of("CO"), List.of("review-question"),
                "Nur nach manueller Prüfung", Statement.EditingOrigin.SOURCE, "REJECTED");
    }

    private static ReconciliationInput sourceInput(String original, List<Statement> statements, boolean history) {
        var scope = new ReformulationBaseline.Scope("repo", "ws", "main", 1, 1);
        var frozen = new HashMap<>(ReconcilePromptBuilder.freeze(Map.of()));
        frozen.put("catalogue", "[{\"code\":\"CO\",\"description\":\"Complete communications context\",\"children\":[]}]");
        frozen.put("sourceVersion", JSON.writeValueAsString(Map.of("id", 2, "text", original)));
        if (history) {
            var question = Map.of("discoveries", List.of(Map.of("context", "x".repeat(129))));
            frozen.put("inheritedDecisionContext", JSON.writeValueAsString(List.of(Map.of(
                    "statements", statements, "questions", List.of(question, question),
                    "humanAnswers", List.of(Map.of("values", List.of("Online"), "rationale", "Explicit decision")),
                    "historicalReview", Map.of("state", "REJECTED")))));
        }
        var baseline = ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope, 2, original),
                new ReformulationBaseline.Snapshot(scope, "s", 2, "{\"rawScores\":{\"CO\":40}}"),
                frozen, "de", "reformulation-v1");
        return new ReconciliationInput(baseline, 1, List.of(), statements, List.of(), List.of(),
                Map.of(), Map.of(), List.of("source"));
    }

    private static ObjectNode inlineData(ReconciliationInput input) {
        var expected = (ObjectNode) JSON.valueToTree(input);
        String history = input.baseline().frozenContext().get("inheritedDecisionContext");
        if (history != null) expected.set("inheritedDecisionContext", JSON.readTree(history));
        expected.set("frozenNodeContext", JSON.valueToTree(new ReconcilePromptBuilder(JSON).scopedContext(input.baseline(), Set.of("CO"))));
        var baseline = (ObjectNode) expected.path("baseline");
        baseline.remove("snapshotPayload");
        ((ObjectNode) baseline.path("frozenContext")).retain("project", "sourceVersion", "reconcilePromptVersion", "reconcileSchemaVersion");
        return (ObjectNode) JSON.readTree(JSON.writeValueAsString(expected));
    }

    private static JsonNode resolveSourceReferences(JsonNode encoded) {
        var decoded = encoded.deepCopy();
        var original = decoded.at("/baseline/originalText");
        decoded.path("statements").forEach(statement -> {
            var object = (ObjectNode) statement;
            if (object.has("wordingRef")) {
                assertThat(object.remove("wordingRef").asText()).isEqualTo("baseline.originalText");
                object.set("wording", original);
            }
            object.path("sourceSpans").forEach(span -> {
                var range = (ObjectNode) span;
                if (range.has("exactTextRef")) {
                    assertThat(range.remove("exactTextRef").asText()).isEqualTo("baseline.originalText");
                    range.set("exactText", original);
                }
            });
        });
        return decoded;
    }

    private static JsonNode data(String prompt) {
        String marker = "\nRECONCILIATION_DATA_JSON\n";
        return JSON.readTree(prompt.substring(prompt.lastIndexOf(marker) + marker.length()));
    }

    private static com.taxonomy.reformulation.ReformulationBaseline baseline(String catalogue, String reconcileVersion) {
        var scope = new com.taxonomy.reformulation.ReformulationBaseline.Scope("repo", "ws", "main", 1, 1);
        var frozen = new java.util.HashMap<String, String>();
        frozen.put("catalogue", catalogue);
        if (reconcileVersion != null) frozen.put("reconcilePromptVersion", reconcileVersion);
        return com.taxonomy.reformulation.ReformulationBaseline.freeze(
                new com.taxonomy.reformulation.ReformulationBaseline.Source(scope, 1, "text"),
                new com.taxonomy.reformulation.ReformulationBaseline.Snapshot(scope, "s", 1, "{\"rawScores\":{}}"),
                frozen, "de", "reformulation-v1");
    }
}
