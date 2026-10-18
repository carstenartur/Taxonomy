package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Test-only storage expansion. Shared text never invents or broadens an authored scope. */
final class ScenarioExclusionResource {
    private ScenarioExclusionResource() { }

    static JsonNode expand(JsonNode resource) {
        require(resource.isObject() && resource.path("schemaVersion").asInt() == 2,
                "Unknown compact exclusion resource schema");
        JsonNode defaults = resource.path("defaults"), rationales = resource.path("rationales");
        require(defaults.isObject() && rationales.isObject(), "Missing explicit exclusion defaults or rationales");
        defaults.propertyNames().forEach(key -> require(Set.of("phases", "sourceContributionIds", "candidateIds")
                .contains(key), "Unsupported exclusion default: " + key));
        rationales.properties().forEach(entry -> require(entry.getValue().isString()
                && !entry.getValue().asString().isBlank(), "Empty shared exclusion rationale: " + entry.getKey()));
        ObjectNode expanded = ((ObjectNode) resource).deepCopy();
        expanded.put("schemaVersion", 1); expanded.remove("defaults"); expanded.remove("rationales");
        for (String section : List.of("rules", "openScopes")) {
            require(resource.path(section).isArray(), "Missing explicit exclusion section: " + section);
            var entries = expanded.putArray(section);
            for (JsonNode entry : resource.path(section)) {
                require(entry.isObject(), "Invalid authored exclusion entry");
                ObjectNode resolved = ((ObjectNode) defaults).deepCopy();
                entry.properties().forEach(field -> resolved.set(field.getKey(), field.getValue().deepCopy()));
                if (entry.has("rationaleRef")) {
                    require(!entry.has("rationale"), "Ambiguous inline and shared exclusion rationale");
                    JsonNode ref = entry.path("rationaleRef");
                    require(ref.isString() && rationales.has(ref.asString()), "Unknown exclusion rationale: " + ref);
                    resolved.remove("rationaleRef");
                    resolved.set("rationale", rationales.get(ref.asString()).deepCopy());
                }
                require(resolved.path("rationale").isString() && !resolved.path("rationale").asString().isBlank(),
                        "Missing authored exclusion rationale");
                entries.add(resolved);
            }
        }
        return expanded;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
