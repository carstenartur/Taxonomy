package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Exact evidence and budget contracts; no claim of live-model semantic quality. */
public final class GroupedEvidenceFidelityChecks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "Arbeitszeiterfassung. Keine automatische Freigabe.";
    private GroupedEvidenceFidelityChecks() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "statements" -> aggregateRetainsFormulatedStatements();
            case "unique" -> uniqueDiscoveryContextRemainsAvailable();
            case "dictionary" -> repeatedContextsAreResolvedInsideThisPrompt();
            case "gateway" -> realGatewayReceivesGroupFormulations();
            case "budget" -> irreducibleContextFailsWithoutTruncation();
            case "origins" -> mergedQuestionOriginsAndRepairKeepExactContext();
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.println("GROUPED_EVIDENCE_OK " + args[0]);
    }

    static void aggregateRetainsFormulatedStatements() {
        var base = BoundedNodeSynthesisDriver.input(ORIGINAL, 2, 50);
        var q = question("q-group", "Context");
        var statement = new Statement("s-group", "Buchungen nur nach ausdrücklicher Freigabe weitergeben.",
                List.of(), Statement.Provenance.MODEL_ADDITION, List.of("BP"), List.of(q.id()),
                "Nur bei bestätigter Freigabe; sonst keine Weitergabe.",
                Statement.EditingOrigin.MODEL, "UNREVIEWED");
        var part = new NodeSynthesisResult("BP", "Kurze Zusammenfassung ohne die Randbedingung",
                List.of(statement), List.of("detail-0"), List.of(q), List.of(), List.of(), List.of());
        var aggregate = new BoundedNodeSynthesis(JSON).aggregate(base, List.of(part));
        check(aggregate.children().getFirst().statementProposals().equals(List.of(statement)),
                "Aggregate lost the group's complete proposed wording, conditions or provenance");
        var payload = data(new ReformulationPromptBuilder(JSON).build(aggregate, null));
        check(payload.path("children").get(0).path("statementProposals").get(0).equals(JSON.valueToTree(statement)),
                "Provider prompt differs from the complete group proposal");
    }

    static void uniqueDiscoveryContextRemainsAvailable() {
        String unique = "Taxonomiekontext ".repeat(90) + "Entscheidend: Terminal funktioniert nur im lokalen Netz.";
        var input = aggregateWithQuestions(List.of(question("q-unique", unique)), List.of());
        String before = JSON.writeValueAsString(input);
        var data = data(new ReformulationPromptBuilder(JSON).build(input, null));
        check(context(data, data.path("openDecisions").get(0).path("discoveries").get(0)).equals(unique),
                "Unique discovery context was replaced by an unavailable external reference");
        check(before.equals(JSON.writeValueAsString(input)), "Prompt preparation changed persisted evidence");
        check(data.at("/baseline/originalText").asText().equals(ORIGINAL), "Original changed");
    }

    static void repeatedContextsAreResolvedInsideThisPrompt() {
        String shared = "Gemeinsamer Kontext \"wörtlich\" äöü\n".repeat(60) + "Wichtige Bedingung am Ende.";
        var a = question("q-a", shared);
        var b = question("q-b", shared);
        var different = question("q-c", shared + " Anderer Geltungsbereich.");
        var input = aggregateWithQuestions(List.of(a, different),
                List.of(new NodeSynthesisResult("BP", "Teil", List.of(), List.of(), List.of(b), List.of(), List.of(), List.of())));
        String before = JSON.writeValueAsString(input);
        String prompt = new ReformulationPromptBuilder(JSON).build(input, null);
        var data = data(prompt);
        check(data.path("discoveryContextTable").size() == 1,
                "Repeated context must be stored once inside this same provider prompt");
        var first = data.path("openDecisions").get(0).path("discoveries").get(0);
        var second = data.path("children").get(0).path("questionProposals").get(0).path("discoveries").get(0);
        check(first.has("contextRef") && second.has("contextRef"), "Duplicate contexts were not factored");
        check(context(data, first).equals(shared) && context(data, second).equals(shared), "In-prompt context reference lost text");
        check(context(data, data.path("openDecisions").get(1).path("discoveries").get(0)).equals(shared + " Anderer Geltungsbereich."),
                "Different context at the same location was merged");
        check(prompt.contains("discoveryContextTable") && before.equals(JSON.writeValueAsString(input)), "Canonical evidence was mutated");
        check(prompt.equals(new ReformulationPromptBuilder(JSON).build(input, null)), "Prompt encoding is not deterministic");
    }

    static void realGatewayReceivesGroupFormulations() throws Exception {
        try (var f = new BoundedNodeSynthesisDriver.Fixture(16000)) {
            var input = BoundedNodeSynthesisDriver.input(ORIGINAL, 6, 2500);
            var result = f.nodes.synthesize(input);
            check(f.prompts.size() == 3, "Expected two complete groups plus one parent");
            var last = data(f.prompts.getLast());
            check(last.path("children").size() == 2, "Missing group inputs");
            for (var child : last.path("children")) {
                check(child.path("statementProposals").size() == 1,
                        "The real provider received only IDs instead of each group's wording");
                check(child.path("statementProposals").get(0).path("wording").asText().startsWith("Vorgeschlagener Teiltext"),
                        "Group proposal was not passed through");
            }
            check(result.statementProposals().size() == 3, "Published grouped detail changed");
            check(result.preservedStatementIds().size() == 6 && result.preservedQuestionIds().contains("existing-question"), "Source references lost");
            for (String prompt : f.prompts) {
                f.policy.requireWithinBudget(prompt, "CUSTOM_OPENAI");
                check(data(prompt).at("/baseline/originalText").asText().equals(ORIGINAL), "Original changed");
            }
            check(f.failure.get() == null, "Authored provider fixture failed: " + f.failure.get());
        }
    }

    static void irreducibleContextFailsWithoutTruncation() throws Exception {
        try (var f = new BoundedNodeSynthesisDriver.Fixture(16000)) {
            var base = BoundedNodeSynthesisDriver.input(ORIGINAL, 0, 0);
            var terminals = new ArrayList<Map<String,Object>>();
            for (int i = 0; i < 6; i++) terminals.add(Map.of("id", "fixture-terminal-" + i,
                    "description", "Unique source " + i + ": " + "z".repeat(2800)));
            var input = new NodeSynthesisInput(base.baseline(), base.nodeId(), base.parentId(),
                    JSON.writeValueAsString(Map.of("current", "Parent", "terminalContributions", terminals)),
                    base.sourceAnchors(), base.directContributions(), base.children(), base.boundaryEdges(),
                    base.answers(), base.openDecisions(), base.preservationContract());
            var saved = new ArrayList<NodeSynthesisResult>();
            var steps = ReformulationStepExecutor.of((kind, request, type, action) -> {
                Object result = action.get();
                if (kind.equals("NODE_GROUP")) saved.add((NodeSynthesisResult) result);
                return result;
            });
            try {
                f.nodes.synthesize(input, steps);
                throw new AssertionError("Aggregate succeeded only by silently discarding unique discovery context");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().startsWith("INPUT_TOO_LARGE_FOR_PROVIDER"), "Wrong failure: " + expected);
            }
            check(saved.size() == 2 && f.prompts.size() == 2, "Should preserve two groups and send no oversized aggregate");
            check(saved.stream().flatMap(p -> p.questionProposals().stream()).flatMap(q -> q.discoveries().stream())
                    .allMatch(d -> d.context().contains("z".repeat(2800))), "Saved discovery evidence was shortened");
        }
    }

    static void mergedQuestionOriginsAndRepairKeepExactContext() {
        String context = "Historische Herkunft ".repeat(60) + "Wichtig: niemals automatisch bestätigen.";
        var first = question("q-first", context);
        var merged = new DecisionQuestion("q-merged", first.key(), first.wording(), first.discoveries(),
                first.affectedStatementIds(), first.answerSchema(), first.prerequisites(), first.dependentQuestionIds(),
                first.consequences(), first.state(), List.of(first.id()), List.of(first.origin()), List.of());
        var input = aggregateWithQuestions(List.of(merged), List.of());
        String before = JSON.writeValueAsString(input);
        var prompts = new ReformulationPromptBuilder(JSON);
        var payload = data(prompts.build(input, "Keep all question IDs; data \"<not executable>\"."));
        var q = payload.path("openDecisions").get(0);
        check(context(payload, q.path("discoveries").get(0)).equals(context), "Current discovery context lost");
        check(context(payload, q.path("origins").get(0).path("discoveries").get(0)).equals(context), "Merged origin context lost");
        check(q.path("aliases").get(0).asText().equals(first.id()), "Merged alias lost");
        check(payload.path("discoveryContextTable").size() == 1, "Merged origin duplicates were not factored losslessly");
        check(before.equals(JSON.writeValueAsString(input)), "Repair changed immutable evidence");
        // Small inputs retain inline context and do not pay for an unused dictionary.
        var small = data(prompts.build(aggregateWithQuestions(List.of(question("small", "Short context")), List.of()), null));
        check(!small.has("discoveryContextTable"), "Unused dictionary grew a small prompt");
        check(small.path("openDecisions").get(0).path("discoveries").get(0).path("context").asText().equals("Short context"),
                "Small inline context changed");
    }

    private static NodeSynthesisInput aggregateWithQuestions(List<DecisionQuestion> open, List<NodeSynthesisResult> parts) {
        var base = BoundedNodeSynthesisDriver.input(ORIGINAL, 0, 0);
        var input = new NodeSynthesisInput(base.baseline(), base.nodeId(), base.parentId(), base.nodeDescription(),
                base.sourceAnchors(), base.directContributions(), base.children(), base.boundaryEdges(), base.answers(), open, base.preservationContract());
        return new BoundedNodeSynthesis(JSON).aggregate(input, parts);
    }
    private static DecisionQuestion question(String id, String context) {
        return new DecisionQuestion(id, new DecisionQuestion.Key("time", "access", id), "Welche Erfassung?",
                List.of(new DecisionQuestion.Discovery("BP", context, "Noch nicht festgelegt", List.of(), List.of("BP"), List.of())),
                List.of(), new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT, List.of(), null, null, null),
                List.of(), List.of(), "Erfassungsweg klären", DecisionQuestion.State.OPEN);
    }
    private static JsonNode data(String prompt) { return BoundedNodeSynthesisDriver.data(prompt); }
    private static String context(JsonNode data, JsonNode discovery) {
        if (discovery.has("contextRef")) return data.path("discoveryContextTable").path(discovery.path("contextRef").asText()).asText();
        return discovery.path("context").asText();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
