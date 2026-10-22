package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRecordedEvidenceQaTest {
    @Test void completeNodeEvidenceUsesMeasuredLimitsWithoutChangingHistoricalProfiles() throws Exception {
        var run = fixture("run"); var decision = fixture("decision"); var architecture = fixture("architecture");
        assertThat(ScenarioDocumentQa.pageProfile(run, decision, architecture))
                .isEqualTo(new ScenarioDocumentQa.PageProfile(168, 54));
        addNodeReasons(architecture);
        var profile = ScenarioDocumentQa.pageProfile(run, decision, architecture);
        assertThat(profile).isEqualTo(new ScenarioDocumentQa.PageProfile(178, 59));
        assertThat(ScenarioDocumentQa.pageProfile(Map.of(), decision, architecture))
                .isEqualTo(new ScenarioDocumentQa.PageProfile(74, 12));
        for (String name : List.of("decision.docx", "report.docx")) {
            int limit = name.equals("decision.docx") ? 178 : 59;
            assertThat(ScenarioDocumentQa.checkText(name, pages(limit), "", List.of("Source"), true, profile))
                    .containsEntry("pages", limit);
            assertThatThrownBy(() -> ScenarioDocumentQa.checkText(name, pages(limit + 1), "", List.of("Source"), true, profile))
                    .hasMessageContaining("limit " + limit);
        }
    }

    @Test void incompleteOrMalformedReasonInventoryCannotClaimTheNewProfile() throws Exception {
        var run = fixture("run"); var decision = fixture("decision");
        for (String mutation : List.of("missing", "extra", "blank", "null", "number", "not-map")) {
            var architecture = fixture("architecture");
            var reasons = addNodeReasons(architecture);
            String first = reasons.keySet().iterator().next();
            switch (mutation) {
                case "missing" -> reasons.remove(first);
                case "extra" -> reasons.put("not-a-graph-node", "Unexpected reason");
                case "blank" -> reasons.put(first, " ");
                case "null" -> reasons.put(first, null);
                case "number" -> reasons.put(first, 1);
                case "not-map" -> architecture.put("nodeReasons", List.of());
                default -> throw new AssertionError(mutation);
            }
            assertThatThrownBy(() -> ScenarioDocumentQa.pageProfile(run, decision, architecture))
                    .as(mutation).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void renderedContentOracleRequiresCompleteNodeAndSavedRelationshipReasons() throws Exception {
        String node = "Long node evidence. ".repeat(80) + "NODE-END";
        String selection = "Recorded selection. ".repeat(80) + "SELECTION-END";
        String review = "Original review comment. ".repeat(80) + "REVIEW-END";
        var architecture = Map.<String, Object>of("nodeReasons", Map.of("node", node),
                "relations", Map.of("node|other|SUPPORTS", Map.of("presenceReason", selection, "decisionComment", review)));
        var expected = ScenarioDocumentQa.expectedRecordedArchitectureText(architecture);
        assertThat(expected).containsExactly(node, selection, review);
        for (String omitted : expected) {
            String rendered = String.join(" ", expected.stream().filter(value -> !value.equals(omitted)).toList());
            assertThatThrownBy(() -> ScenarioDocumentQa.checkText("report.docx", page(rendered), "", expected, true))
                    .hasMessageContaining("missing text");
        }
        assertThat(ScenarioDocumentQa.checkText("report.docx", page(String.join(" ", expected)), "", expected, true))
                .containsEntry("contentAssertions", 3);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> addNodeReasons(Map<String, Object> architecture) {
        var diagram = (Map<String, Object>) architecture.get("diagram");
        var reasons = new LinkedHashMap<String, Object>();
        for (var node : (List<Map<String, Object>>) diagram.get("nodes")) {
            reasons.put((String) node.get("id"), "Authored reason for " + node.get("id"));
        }
        architecture.put("nodeReasons", reasons);
        return reasons;
    }

    private static Map<String, Object> fixture(String name) throws Exception {
        try (var resource = ScenarioRecordedEvidenceQaTest.class.getResourceAsStream(
                    "/scenario-documents/complete-flood-v1/" + name + ".json.gz")) {
            assertThat(resource).isNotNull();
            try (var input = new GZIPInputStream(resource)) {
                return FlatJson.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static String pages(int count) {
        return "<html><doc>" + "<page width='595' height='842'><word yMin='51' yMax='62'>Source</word></page>".repeat(count) + "</doc></html>";
    }
    private static String page(String text) {
        return pages(1).replace(">Source<", ">" + text + "<");
    }
}
