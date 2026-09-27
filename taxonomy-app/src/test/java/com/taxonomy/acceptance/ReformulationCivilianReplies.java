package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Authored replies for the explicitly selected BP/IP catalogue front, with runtime ID binding. */
final class ReformulationCivilianReplies {
    private final ObjectMapper json = new ObjectMapper();
    private final String original;
    private static final Map<String, Set<String>> CHILDREN = Map.of(
            "BP-1060", Set.of(), "BP-1327", Set.of("BP-1060"), "BP-1000", Set.of("BP-1327"), "BP", Set.of("BP-1000"),
            "IP-1102", Set.of(), "IP-1005", Set.of("IP-1102"), "IP-1000", Set.of("IP-1005"), "IP", Set.of("IP-1000"));
    ReformulationCivilianReplies(JsonNode fixture) { original = fixture.at("/requirement/text").asText(); }

    ScenarioReformulationPlayback.Reply respond(String task, JsonNode input) {
        require(original.equals(input.at("/baseline/originalText").asText()), "Unknown civilian source");
        checkEdges(input);
        if (!task.equals("REWORD")) require(input.path("boundaryEdges").size() == 1, "Missing civilian boundary edge");
        if (task.equals("RECONCILE")) {
            var sections = ids(input.path("sections"), "id");
            require(sections.equals(CHILDREN.keySet()), "Unknown civilian reconciliation sections: " + sections);
            return reply("civilian:RECONCILE", Map.of("affectedSectionIds", List.of(), "sourceResolutions", List.of(), "findings", List.of()));
        }
        String node = input.path("nodeId").asText();
        require(CHILDREN.containsKey(node), "Unknown civilian node: " + node);
        require(ids(input.path("children"), "nodeId").equals(CHILDREN.get(node)), "Unknown civilian child scope: " + node);
        if (task.equals("NODE")) require(input.path("answers").isEmpty(), "Unexpected initial answers");
        else checkAnswers(input);
        var statements = new TreeSet<String>();
        var questions = new TreeSet<String>();
        input.path("directContributions").forEach(s -> statements.add(s.path("id").asText()));
        input.path("openDecisions").forEach(q -> questions.add(q.path("id").asText()));
        input.path("children").forEach(child -> {
            child.path("statementProposals").forEach(s -> statements.add(s.path("id").asText()));
            child.path("preservedStatementIds").forEach(s -> statements.add(s.asText()));
            child.path("questionProposals").forEach(q -> questions.add(q.path("id").asText()));
            child.path("preservedQuestionIds").forEach(q -> questions.add(q.asText()));
        });
        var proposed = json.createArrayNode();
        var newQuestions = json.createArrayNode();
        if (task.equals("NODE") && Set.of("BP-1060", "IP-1102").contains(node)) {
            proposed.add(json.valueToTree(Map.of("wording", "Show stale or unavailable observations explicitly; keep the official alert channels.",
                    "provenance", "MODEL_ADDITION", "sourceSpans", List.of(), "architectureLinks", List.of(node),
                    "questionDependencies", List.of("new-question:0"), "conditionalValidity", "Subject to the explicit stale-data decision.")));
            newQuestions.add(question("stale-observation", "presentation", "flood-service", "How should stale observations be presented?",
                    "SINGLE_CHOICE", List.of("Show unavailable", "Retain last observation with timestamp"), null, null, null));
            if (node.equals("BP-1060")) newQuestions.add(question("acquisition", "stale-age", "flood-ingestion",
                    "What is the maximum age of a usable observation?", "NUMBER", List.of(), "minutes", 0.0, 1440.0));
            else newQuestions.add(question("publication", "stale-age", "flood-display",
                    "What is the maximum age of a usable observation?", "NUMBER", List.of(), "minutes", 0.0, 1440.0));
            for (var question : newQuestions) {
                ((ObjectNode) question).set("nodeIds", json.valueToTree(List.of(node.equals("BP-1060") ? "BP-1017" : "IP-1116")));
                ((ObjectNode) question).set("edgeIds", json.valueToTree(input.path("boundaryEdges").propertyNames()));
            }
        }
        var response = json.createObjectNode();
        response.put("summary", task.equals("REWORD")
                ? "Keep observations at most every 15 minutes; retain last observation with timestamp. Surface-water flooding remains outside scope; this is not safety-critical."
                : "Acquire and present observations at most every 15 minutes. Keep source and time visible. Surface-water flooding is outside scope; this is not a safety-critical replacement for official alerts.");
        response.set("statementProposals", proposed);
        response.set("questionProposals", newQuestions);
        response.set("preservedStatementIds", json.valueToTree(statements));
        response.set("preservedQuestionIds", json.valueToTree(questions));
        response.putArray("uncoveredSourceRefs"); response.putArray("conflictCandidates");
        return reply("civilian:" + task + ":" + node, response);
    }

    private ObjectNode question(String subject, String dimension, String scope, String wording,
            String kind, List<String> options, String unit, Double minimum, Double maximum) {
        var q = json.createObjectNode().put("subject", subject).put("dimension", dimension).put("scope", scope)
                .put("wording", wording).put("rationale", "Authored test decision: the source leaves this choice open.")
                .put("consequences", "Use the answer only in the affected section; preserve all original restrictions.");
        q.set("affectedStatementIds", json.valueToTree(List.of("new-statement:0")));
        q.putArray("sourceSpans"); q.putArray("nodeIds"); q.putArray("edgeIds"); q.putArray("prerequisites");
        var schema = q.putObject("answerSchema").put("kind", kind);
        schema.set("options", json.valueToTree(options)); schema.set("unit", json.valueToTree(unit));
        schema.set("minimum", json.valueToTree(minimum)); schema.set("maximum", json.valueToTree(maximum));
        return q;
    }
    private void checkEdges(JsonNode input) {
        for (var entry : input.path("boundaryEdges").properties()) {
            var edge = json.readTree(entry.getValue().asText());
            require(entry.getKey().equals("edge-" + edge.path("id").asLong()), "Unknown civilian edge identity");
            require(edge.path("snapshotId").asText().equals(input.at("/baseline/snapshotId").asText()), "Foreign civilian edge snapshot");
            require(edge.path("sourceCode").asText().equals("BP-1017") && edge.path("targetCode").asText().equals("IP-1116")
                    && edge.path("relationType").asText().equals("CONSUMES")
                    && edge.path("reviewStatus").asText().equals("PROPOSED"), "Unknown civilian boundary edge");
        }
    }
    private void checkAnswers(JsonNode input) {
        require(!input.path("answers").isEmpty(), "Missing civilian reword answer");
        Set<String> known = ids(input.path("openDecisions"), "id");
        for (var answer : input.path("answers")) {
            require(known.contains(answer.path("questionId").asText()), "Unknown civilian answer question");
            require(Set.of("ANSWERED", "DEFERRED").contains(answer.path("state").asText()), "Unknown civilian answer state");
            if (answer.path("state").asText().equals("ANSWERED")) require(answer.path("values").toString()
                    .equals("[\"Retain last observation with timestamp\"]"), "Unknown civilian answer value");
        }
    }
    private static Set<String> ids(JsonNode values, String field) {
        var ids = new TreeSet<String>();
        values.forEach(v -> { require(!v.path(field).asText().isBlank() && ids.add(v.path(field).asText()), "Duplicate/missing civilian identity"); });
        return ids;
    }
    private ScenarioReformulationPlayback.Reply reply(String id, Object body) { return new ScenarioReformulationPlayback.Reply(id, json.writeValueAsString(body)); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
