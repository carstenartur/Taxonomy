package com.taxonomy.analysis.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit mock-mode demonstration, not an assessment of the supplied requirement.
 * The regular extraction, navigation and verification protocol still executes.
 */
final class MockRelationReplies {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PREFIX = "You assess requirement-scoped architectural relationships. Protocol: relation-downwalk-v1.\n";
    private static final String SOURCE = "UA-1574";
    private static final String LABEL = "MOCK demonstration — not a semantic assessment of the supplied requirement";
    private static final Map<String, List<String>> PATHS = Map.of(
            "USES", List.of("CR", "CR-1000", "CR-1011", "CR-1014", "CR-1005"),
            "CONSUMES", List.of("IP", "IP-1000", "IP-1049", "IP-1039", "IP-1136"));

    private MockRelationReplies() { }

    static String reply(String prompt) {
        if (prompt == null || !prompt.startsWith(PREFIX)) {
            return "[]";
        }
        int input = prompt.indexOf("INPUT\n");
        if (input < 0) {
            throw new IllegalArgumentException("Missing mock relation input");
        }
        JsonNode query = JSON.readTree(prompt.substring(input + 6));
        String original = query.path("original").asString();
        if (original.isBlank()) {
            throw new IllegalArgumentException("Missing mock original");
        }
        if (query.has("nodes")) {
            List<Map<String, Object>> selections = new ArrayList<>();
            for (JsonNode node : query.path("nodes")) {
                boolean source = SOURCE.equals(node.path("id").asString());
                selections.add(Map.of("nodeId", node.path("id").asString(),
                        "outcome", source ? "EXPLICIT" : "REJECT",
                        "contributions", source ? List.of(Map.of("text", LABEL + ": communication application",
                                "quote", original, "condition", "")) : List.of(),
                        "rationale", LABEL + (source ? ": selected fixture source" : ": not a fixture source"),
                        "question", ""));
            }
            return JSON.writeValueAsString(Map.of("selections", selections));
        }
        List<Map<String, Object>> decisions = new ArrayList<>();
        String source = query.path("contribution").path("source").path("id").asString();
        List<String> path = SOURCE.equals(source) && "OUTGOING".equals(query.path("direction").asString())
                ? PATHS.getOrDefault(query.path("type").asString(), List.of()) : List.of();
        boolean verify = "VERIFY".equals(query.path("phase").asString());
        for (JsonNode node : query.path("candidates")) {
            String id = node.path("id").asString();
            boolean match = !path.isEmpty() && id.equals(path.getLast());
            String contribution = LABEL + ": " + ("CONSUMES".equals(query.path("type").asString())
                    ? "read treatment records" : "use audio communication service");
            if (verify && match) {
                JsonNode proposal = query.path("proposal");
                match = id.equals(proposal.path("targetId").asString())
                        && contribution.equals(proposal.path("contribution").asString())
                        && original.equals(proposal.path("quote").asString())
                        && "REQUIRED".equals(proposal.path("necessity").asString())
                        && proposal.path("condition").asString().isEmpty()
                        && proposal.path("alternativeGroup").asString().isEmpty();
            }
            String outcome = match ? (verify ? "VERIFIED" : "MATCH")
                    : !verify && path.contains(id) ? "DESCEND" : "REJECT";
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("targetId", id);
            decision.put("outcome", outcome);
            decision.put("contribution", match ? contribution : "");
            decision.put("quote", match ? original : "");
            decision.put("necessity", match ? "REQUIRED" : null);
            decision.put("condition", "");
            decision.put("alternativeGroup", "");
            decision.put("rationale", LABEL + ": " + outcome);
            decision.put("question", "");
            decisions.add(decision);
        }
        return JSON.writeValueAsString(Map.of("decisions", decisions));
    }
}
