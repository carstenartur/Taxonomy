package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

/** The storage migration must preserve every authored decision, including its exact neutral wording. */
class ScenarioExclusionResourceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void compactResourceRetainsAllAuthoredDecisionsAndUncertainties() throws Exception {
        try (var input = getClass().getResourceAsStream("/scenarios/flood-relation-exclusions.json")) {
            assertEquals(2, JSON.readTree(input).path("schemaVersion").asInt(), "Use the compact authored storage format");
        }
        JsonNode expanded;
        try (var input = getClass().getResourceAsStream("/scenarios/flood-relation-exclusions.json")) {
            expanded = ScenarioExclusionResource.expand(JSON.readTree(input));
        }
        assertEquals(383, expanded.path("rules").size());
        assertEquals(14, expanded.path("openScopes").size());
        assertEquals("7afae5cf18c493b963a0d983798647494acd7ea487f6f4fc578ef5c397b05f1c",
                sha256(sorted(expanded).toString()),
                "Canonical SHA-256 of all fields, ordered decisions and exact authored explanations");
    }

    @Test
    void unknownOrAmbiguousRationaleCannotSilentlySupplyAnAnswer() throws Exception {
        var resource = JSON.readTree("""
                {"schemaVersion":2,"defaults":{},"rationales":{"known":"Exact authored reason"},
                 "rules":[{"id":"case","rationaleRef":"missing"}],"openScopes":[]}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ScenarioExclusionResource.expand(resource)).getMessage().contains("Unknown exclusion rationale"));
        var rule = (tools.jackson.databind.node.ObjectNode) resource.at("/rules/0");
        rule.put("rationaleRef", "known").put("rationale", "Conflicting inline reason");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ScenarioExclusionResource.expand(resource)).getMessage().contains("Ambiguous"));
    }

    @Test
    void defaultsCannotBroadenTheSourceOrCandidateSubtreeScope() throws Exception {
        var resource = JSON.readTree("""
                {"schemaVersion":2,"defaults":{"sourceIds":["unexpected-source"]},"rationales":{},
                 "rules":[],"openScopes":[]}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ScenarioExclusionResource.expand(resource)).getMessage().contains("Unsupported exclusion default"));
    }

    private static String sha256(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var properties = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> properties.put(entry.getKey(), sorted(entry.getValue())));
            var result = JSON.createObjectNode(); properties.forEach(result::set); return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode(); value.forEach(item -> result.add(sorted(item))); return result;
        }
        return value;
    }
}
