package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Authored remote replies keyed by semantic input, never by call order. */
final class ScenarioReformulationPlayback {
    private static final String NODE = "\nINPUT_DATA_JSON\n";
    private static final String RECONCILE = "\nRECONCILIATION_DATA_JSON\n";
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, JsonNode> rules = new LinkedHashMap<>();
    private final ReformulationCivilianReplies civilian;

    ScenarioReformulationPlayback(JsonNode fixture) {
        civilian = fixture.path("civilianReformulation").asBoolean(false) ? new ReformulationCivilianReplies(fixture) : null;
        Set<String> ids = new HashSet<>();
        for (var rule : fixture.path("reformulationReplies")) {
            require(!rule.path("id").asText().isBlank() && ids.add(rule.path("id").asText()), "Duplicate or missing reformulation ID");
            require(Set.of("NODE", "REWORD", "RECONCILE").contains(rule.path("task").asText()), "Unknown reformulation task");
            require(!rule.path("originalText").asText().isBlank() && rule.path("response").isObject(), "Missing reformulation source or response");
            require(rules.putIfAbsent(signature(rule), rule.deepCopy()) == null, "Duplicate reformulation response scope");
        }
    }

    static boolean accepts(String prompt) {
        return prompt.startsWith("REFORMULATION PROMPT ") || prompt.startsWith("REQUIREMENT RECONCILIATION ");
    }

    Reply respond(String prompt) {
        boolean reconcile = prompt.startsWith("REQUIREMENT RECONCILIATION ");
        String marker = reconcile ? RECONCILE : NODE;
        int offset = prompt.indexOf(marker);
        require(offset >= 0 && offset == prompt.lastIndexOf(marker)
                && !prompt.contains(reconcile ? NODE : RECONCILE), "Missing or ambiguous reformulation input");
        String payload = prompt.substring(offset + marker.length());
        int repair = payload.indexOf("\nVALIDATION_ERRORS");
        if (repair >= 0) {
            String suffix = payload.substring(repair);
            require(suffix.startsWith("\nVALIDATION_ERRORS: ")
                    || suffix.startsWith("\nVALIDATION_ERRORS (repair the same input once): "), "Unknown repair suffix");
            json.readTree(suffix.substring(suffix.indexOf(": ") + 2));
            payload = payload.substring(0, repair);
        }
        JsonNode input = json.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(payload);
        String original = input.at("/baseline/originalText").asText();
        require(ScenarioLlmPlayback.sha256(original).equals(input.at("/baseline/originalTextHash").asText()), "Invalid full source hash");
        String contract = input.path("preservationContract").asText();
        String task;
        if (reconcile) task = "RECONCILE";
        else if (contract.startsWith("Only reword this affected section.")
                || contract.startsWith("Preserve exact original and prior evidence. Re-synthesize only this affected section,")) task = "REWORD";
        else {
            require(contract.equals("Preserve all original anchors and child IDs verbatim; additions are unreviewed."), "Unknown node preservation contract");
            task = "NODE";
        }
        var scope = json.createObjectNode().put("task", task).put("originalText", original)
                .put("nodeId", input.path("nodeId").asText());
        var children = scope.putArray("children");
        input.path("children").forEach(child -> children.add(child.path("nodeId").asText()));
        var sections = scope.putArray("sections");
        input.path("sections").forEach(section -> sections.add(section.path("id").asText()));
        var edges = scope.putObject("boundaryEdges");
        input.path("boundaryEdges").properties().forEach(edge -> edges.set(edge.getKey(), json.readTree(edge.getValue().asText())));
        scope.set("answers", input.path("answers"));
        if (civilian != null) return civilian.respond(task, input);
        JsonNode rule = rules.get(signature(scope));
        require(rule != null, "Unknown reformulation response scope: " + task + "/" + input.path("nodeId").asText());
        return new Reply(rule.path("id").asText(), json.writeValueAsString(rule.path("response")));
    }

    private String signature(JsonNode scope) {
        var key = new TreeMap<String, Object>();
        key.put("task", scope.path("task").asText());
        key.put("originalText", scope.path("originalText").asText());
        key.put("nodeId", scope.path("nodeId").asText());
        key.put("children", identities(scope.path("children")));
        key.put("sections", identities(scope.path("sections")));
        key.put("boundaryEdges", canonical(scope.path("boundaryEdges")));
        var answers = new TreeMap<String, Object>();
        for (var answer : scope.path("answers")) {
            String question = answer.path("questionId").asText();
            require(!question.isBlank(), "Missing answer question identity");
            var state = new TreeMap<String, Object>();
            for (String field : List.of("questionId", "values", "state", "disposition", "otherText", "rationale", "supersedes"))
                state.put(field, canonical(answer.path(field)));
            // Retain answer history: two events for a question are not silently collapsed.
            require(answers.putIfAbsent(question + ":" + answer.path("id").asText(), state) == null, "Duplicate answer identity");
        }
        key.put("answers", answers);
        return json.writeValueAsString(key);
    }

    private static SortedSet<String> identities(JsonNode values) {
        var result = new TreeSet<String>();
        for (var value : values) require(!value.asText().isBlank() && result.add(value.asText()), "Missing or duplicate reformulation identity");
        return result;
    }

    private Object canonical(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (value.isObject()) {
            var result = new TreeMap<String, Object>();
            value.properties().forEach(entry -> result.put(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            var result = new ArrayList<Object>();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return json.treeToValue(value, Object.class);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    record Reply(String id, String content) { }
}
