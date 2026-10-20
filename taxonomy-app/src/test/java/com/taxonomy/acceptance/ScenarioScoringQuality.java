package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement coverage and real-catalogue checks, separate from response transport equality. */
public final class ScenarioScoringQuality {
    private static final Set<String> ROOTS = Set.of("BP", "BR", "CI", "CO", "CP", "CR", "IP", "UA");
    private record Duty(String anchor, String function, List<String> nodes) { }
    private static final List<Duty> DUTIES = List.of(
            new Duty("F1", "Acquire published observations and preserve usable source/time context", List.of("BP-1017", "BP-1866")),
            new Duty("F1", "Present environmental and area information", List.of("CI-1051", "IP-1116", "UA-1580")),
            new Duty("F2", "Administer and operate the information service", List.of("BP-2009", "CI-1060", "UA-1522")),
            new Duty("F3", "Manage area subscriptions and distribute selected information", List.of("BP-1521", "BP-1535", "CR-1098")),
            new Duty("F3", "Keep the text, phone and email choices represented", List.of("CO-1048", "CO-1066", "CR-1037")),
            new Duty("F4", "Maintain contact identity and dispose of removed information", List.of("CR-1022", "BP-1530")),
            new Duty("F5", "Retrieve batches and retain data freshness context", List.of("BP-1017", "BP-1866")),
            new Duty("A1", "Queue delivery and retain its audit history", List.of("CR-1097", "CR-1198")),
            new Duty("A1", "Protect subscriber information and access", List.of("BP-1536", "CR-1006", "CR-1007")));

    private ScenarioScoringQuality() { }

    public static Map<String, Object> verify(JsonNode fixture, JsonNode catalogue, JsonNode analysis) {
        var nodes = new LinkedHashMap<String, JsonNode>();
        index(catalogue, nodes);
        var expected = new LinkedHashMap<String, JsonNode>();
        var scopes = new LinkedHashMap<String, JsonNode>();
        for (var rule : fixture.path("replies")) {
            assertThat(scopes.put(rule.path("id").asText(), rule)).isNull();
            for (var answer : rule.path("answers").properties()) {
                String code = answer.getKey();
                assertThat(nodes).as("Real catalogue identity %s", code).containsKey(code);
                assertThat(expected.put(code, answer.getValue())).as("One actual assessment per candidate %s", code).isNull();
                assertThat(analysis.path("scores").path(code).isNumber()).as("Retained numeric assessment for %s", code).isTrue();
                assertThat(analysis.path("scores").path(code).decimalValue())
                        .as("Transport preservation for %s (not semantic accuracy)", code)
                        .isEqualByComparingTo(answer.getValue().path("score").decimalValue());
                assertThat(analysis.path("reasons").path(code).asText()).isEqualTo(answer.getValue().path("reason").asText());
                assertThat(answer.getValue().path("reason").asText()).containsPattern("\\b(?:F[1-5]|A1)\\b");
                assertThat(answer.getValue().path("reason").asText().split("\\s+").length)
                        .as("Standard reason policy for %s", code).isLessThanOrEqualTo(60);
            }
        }

        var leaves = new TreeSet<String>();
        for (var entry : expected.entrySet()) {
            String code = entry.getKey();
            JsonNode node = nodes.get(code);
            BigDecimal score = entry.getValue().path("score").decimalValue();
            if (score.signum() <= 0 || product(node)) continue;
            List<String> categories = new ArrayList<>(), products = new ArrayList<>();
            for (var child : node.path("children")) {
                (product(child) ? products : categories).add(child.path("code").asText());
            }
            boolean moreSpecificMatch = java.util.stream.Stream.concat(categories.stream(), products.stream())
                    .anyMatch(child -> expected.containsKey(child) && expected.get(child).path("score").asDouble() > 0);
            if (!moreSpecificMatch) leaves.add(code);
            if (!categories.isEmpty()) {
                JsonNode rule = scopes.get(code + "-categories");
                assertThat(rule).as("Every positive category is expanded: %s", code).isNotNull();
                assertThat(rule.path("parentScore").decimalValue()).isEqualByComparingTo(score);
                assertThat(strings(rule.path("keys"))).as("Complete real child set below %s", code)
                        .containsExactlyInAnyOrderElementsOf(categories);
            }
            var assessedProducts = new TreeSet<String>();
            scopes.entrySet().stream().filter(scope -> scope.getKey().startsWith(code + "-products-"))
                    .forEach(scope -> assessedProducts.addAll(strings(scope.getValue().path("keys"))));
            assertThat(assessedProducts).as("Independent concrete products below %s", code)
                    .containsExactlyInAnyOrderElementsOf(products);
        }
        assertThat(leaves).containsExactlyInAnyOrderElementsOf(strings(fixture.path("expectedLeaves")));
        for (var path : fixture.path("expectedLeafPaths").properties()) {
            List<String> codes = strings(path.getValue());
            assertThat(ROOTS).contains(codes.getFirst());
            assertThat(codes.getLast()).isEqualTo(path.getKey());
            for (int index = 1; index < codes.size(); index++) {
                assertThat(nodes.get(codes.get(index)).path("parentCode").asText()).isEqualTo(codes.get(index - 1));
            }
        }

        // These obligations come from F1–F5/A1, not from the authored response values.
        // Their positive presence is necessary, but relationships and constraints still need separate review.
        var coverage = new ArrayList<Map<String, Object>>();
        for (var duty : DUTIES) {
            for (String code : duty.nodes()) {
                assertThat(analysis.path("scores").path(code).asDouble()).as("%s: %s (%s)", duty.anchor(), duty.function(), code)
                        .isGreaterThan(0);
            }
            coverage.add(Map.of("anchor", duty.anchor(), "function", duty.function(), "nodes", duty.nodes()));
        }
        for (String excluded : List.of("BP-1055", "BP-1263", "BP-1877", "BP-1246", "BP-2007", "BR-1223")) {
            assertThat(analysis.path("scores").path(excluded).asDouble()).as("Unrequested assessment/SLA/design workflow %s", excluded).isZero();
        }
        assertThat(analysis.path("scores").path("BP-1017").asDouble()).isBetween(0.0, 99.999);
        assertThat(leaves.stream().filter(code -> code.startsWith("BP-")).count())
                .as("Data acquisition cannot stand for subscriptions, disposal, protection and operations").isGreaterThan(1);
        return Map.of("referenceVersion", "flood-classification-v2", "evidenceKind", "AUTHORED_REFERENCE_NOT_LIVE_MODEL",
                "completeOfferedScopes", scopes.size(), "explicitCandidateAssessments", expected.size(),
                "positiveAssessmentFrontier", leaves, "requirementCoverage", coverage,
                "scoreSemantics", "Independent root relevance followed by absolute inherited allocations; neither confidence nor percent implementation coverage.",
                "acceptanceBoundary", "Catalogue classification and transport fidelity only. Directed relations, constraints and the actual Sparx export have separate quality gates.");
    }

    private static boolean product(JsonNode node) { return "PRODUCT".equals(node.path("analysisRole").asText()); }
    private static List<String> strings(JsonNode array) {
        var values = new ArrayList<String>(); array.forEach(value -> values.add(value.asText())); return values;
    }
    private static void index(JsonNode tree, Map<String, JsonNode> nodes) {
        for (var node : tree) { nodes.put(node.path("code").asText(), node); index(node.path("children"), nodes); }
    }
}
