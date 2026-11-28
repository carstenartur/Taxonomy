package com.taxonomy;

import com.taxonomy.acceptance.DirectedRelationMetrics.Evaluation;
import com.taxonomy.acceptance.DirectedRelationMetrics.Relation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AltscherbitzArchitectureStudyTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REPLAY = "/scenarios/altscherbitz-relation-replies-v1.json";
    private static final String EVIDENCE = "REPLAY_OF_INDEPENDENT_ASSISTANT_ASSESSMENTS";
    private final Path output = Path.of("target", "altscherbitz-architecture");

    @Test
    void assessAllThreeFrozenRequirementsWithTheProductionRelationSearchAndProjector() throws Exception {
        JsonNode recorded = AltscherbitzArchitectureQa.resource(REPLAY).required("responsesByPromptSha256");
        var seen = new HashSet<String>();
        var results = AltscherbitzArchitectureStudy.run(output, prompt -> {
            String digest = AltscherbitzArchitectureQa.sha256(prompt);
            seen.add(digest);
            return JSON.writeValueAsString(recorded.required(digest));
        }, EVIDENCE);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(recorded.propertyNames());
        assertThat(results).extracting(row -> row.get("stage")).containsExactly("product", "storage-service", "operations-process");
        for (var result : results) {
            assertThat(result.get("evidenceKind")).isEqualTo(EVIDENCE);
            assertThat((int) result.get("relationCalls")).isPositive();
            assertThat((List<?>) result.get("unsupportedRequiredApplicationOrRole")).isEmpty();
            assertThat((List<?>) result.get("sourceEvidenceMissingOrInvalid")).isEmpty();
        }

        // The captured assessor also proposed the process's USES perspective. Keep the original
        // SUPPORTS reference and its mismatch visible independently of the test execution status.
        assertThat(results.get(2).get("outcome")).isEqualTo("FAIL");
        var operations = (Evaluation) results.get(2).get("metrics");
        assertThat(operations.matchedRequired()).isOne();
        assertThat(operations.falsePositives()).contains(new Relation("BP-1503", "USES", "CR-1091"));
        // Reverse a real projected dependency. The score and retained quotes do not authorize changing direction.
        var stage = AltscherbitzArchitectureQa.resource(AltscherbitzArchitectureQa.REQUIREMENTS).required("cases").get(2);
        var ref = AltscherbitzArchitectureQa.resource(AltscherbitzArchitectureQa.ARCHITECTURE);
        var observed = (ObjectNode) JSON.readTree(output.resolve("operations-process-observation.json").toFile());
        var projected = (tools.jackson.databind.node.ArrayNode) observed.at("/analysis/architectureView/includedRelationships");
        assertThat(projected.isEmpty()).as("The independent frozen response corpus includes an observed directed dependency").isFalse();
        var edge = (ObjectNode) projected.valueStream().filter(value -> value.path("sourceCode").asString().equals("CR-1091")
                && value.path("relationType").asString().equals("SUPPORTS")).findFirst().orElseThrow();
        String service = edge.required("sourceCode").asString();
        String process = edge.required("targetCode").asString();
        edge.put("sourceCode", process).put("targetCode", service);
        var reversed = AltscherbitzArchitectureQa.evaluate(ref, stage, observed, EVIDENCE);
        assertThat(reversed.get("outcome")).isEqualTo("FAIL");
        assertThat(((Evaluation) reversed.get("metrics")).falsePositives()).contains(new Relation(process, "SUPPORTS", service));
        edge.put("sourceCode", "BR-1220").put("relationType", "ASSIGNED_TO").put("targetCode", process);
        var inventedRole = AltscherbitzArchitectureQa.evaluate(ref, stage, observed, EVIDENCE);
        assertThat(inventedRole.get("outcome")).isEqualTo("FAIL");
        assertThat(inventedRole.get("unsupportedRequiredApplicationOrRole"))
                .isEqualTo(List.of(new Relation("BR-1220", "ASSIGNED_TO", process)));

        ((ObjectNode) observed.at("/analysis/relationSearchReport")).put("originalSha256", "0".repeat(64));
        assertThatThrownBy(() -> AltscherbitzArchitectureQa.evaluate(ref, stage, observed, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different requirement");
    }

    @Test
    void historicalTaxonomyOnlySnapshotsDoNotBecomeSuccessfulArchitectureChecks() throws Exception {
        var stage = AltscherbitzArchitectureQa.resource(AltscherbitzArchitectureQa.REQUIREMENTS).required("cases").get(0);
        var ref = AltscherbitzArchitectureQa.resource(AltscherbitzArchitectureQa.ARCHITECTURE);
        var observation = JSON.createObjectNode();
        observation.putObject("summary").put("id", "historical");
        observation.putObject("analysis").putObject("modelBinding").putObject("scope").put("mode", "TAXONOMIES_ONLY");
        assertThat(AltscherbitzArchitectureQa.evaluate(ref, stage, observation, "HISTORICAL_SNAPSHOT"))
                .containsEntry("outcome", "NOT_RUN").doesNotContainKey("metrics");
    }
}
