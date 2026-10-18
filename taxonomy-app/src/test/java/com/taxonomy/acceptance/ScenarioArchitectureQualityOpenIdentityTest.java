package com.taxonomy.acceptance;

import com.taxonomy.acceptance.ScenarioArchitectureQualityEvidenceTest.Fixture;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.taxonomy.acceptance.ScenarioArchitectureQualityEvidenceTest.fixture;
import static com.taxonomy.acceptance.ScenarioArchitectureQualityEvidenceTest.verify;
import static org.junit.jupiter.api.Assertions.*;

/** A handwritten noncore identity question never relaxes source, budget or functional evidence gates. */
class ScenarioArchitectureQualityOpenIdentityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUESTION = "Is the F5 incoming observation batch already a Recognized Environmental Picture, or is its product identity unspecified?";

    @Test void aBoundedOpenInputIdentityDoesNotPreventFunctionalAcceptance() {
        var value = open(false, true);
        var result = verify(value);
        assertEquals("PASS_WITH_OPEN_POINTS", result.get("verdict"));
        assertEquals(Boolean.TRUE, result.get("functionalCoverageComplete"));
        assertEquals(Boolean.FALSE, result.get("sourceAndSearchEvidenceComplete"));
        assertEquals("PARTIAL", result.get("snapshotStatus"));
        assertEquals(20, result.get("satisfiedConstraintCount"));
        var points = JSON.valueToTree(result.get("openPoints"));
        assertEquals(1, points.size());
        assertEquals("X03", points.get(0).path("id").asText());
        assertEquals(QUESTION, points.get(0).path("question").asText());
        assertEquals("BP-1017", points.get(0).path("sourceId").asText());
        assertEquals("CONSUMES", points.get(0).path("type").asText());
        assertEquals("IP-1116", points.get(0).path("targetId").asText());
    }

    @Test void bothSearchOrientationsRetainOneCanonicalIdentityQuestion() {
        var result = verify(open(true, true));
        assertEquals("PASS_WITH_OPEN_POINTS", result.get("verdict"));
        assertEquals(1, JSON.valueToTree(result.get("openPoints")).size());
    }

    @Test void theDeliveredAcquisitionContributionMustDeclareTheUntypedInput() {
        assertRejected(open(false, false));
    }

    @Test void anAccurateOpenPointCannotHideAContradictoryInputProductClaim() {
        assertRejected(open(fixture("open-identity-conflict"), false));
    }

    @ParameterizedTest @ValueSource(strings = {"different-question", "different-type", "reversed-direction", "different-target", "category-target", "different-source-quote",
            "budget-rest", "unassessed-source", "pending-search", "missing-trace", "extra-open-trace", "extra-source-question",
            "recovery-paused", "uncertain-transport", "skipped-question", "unrelated-checkpoint", "extra-warning", "awaiting-decision", "missing-required-phone"})
    void onlyThePreciselyBoundNoncoreIdentityQuestionIsAdmissible(String mutation) {
        var value = open(false, true);
        ObjectNode search = search(value), analysis = (ObjectNode) value.snapshot().path("analysis");
        ObjectNode unfinished = (ObjectNode) search.path("result").path("unfinished").get(0);
        ObjectNode progress = (ObjectNode) search.path("progress");
        ObjectNode recovery = (ObjectNode) analysis.path("recovery");
        ObjectNode checkpoint = (ObjectNode) recovery.path("openQuestions").get(0);
        switch (mutation) {
            case "different-question" -> unfinished.put("question", "Which warning vendor must be used?");
            case "different-type" -> unfinished.put("type", "PRODUCES");
            case "reversed-direction" -> unfinished.put("direction", "INCOMING");
            case "different-target" -> unfinished.set("targets", JSON.valueToTree(List.of("IP-2130")));
            case "category-target" -> unfinished.set("targets", JSON.valueToTree(List.of("IP")));
            case "different-source-quote" -> {
                for (var source : search.path("sources")) if (source.path("node").path("id").asText().equals("BP-1017"))
                    for (var contribution : source.path("contributions")) if (contribution.path("quote").asText().startsWith("F5:"))
                        ((ObjectNode) contribution).put("quote", "F1: Give residents and public authorities an understandable view of published flood warnings, affected areas and water observations, including source and observation time.");
            }
            case "budget-rest" -> ((ArrayNode) search.path("result").path("unfinished")).add(JSON.valueToTree(Map.of(
                    "sourceId", "BP-1535", "type", "USES", "direction", "OUTGOING", "targets", List.of("CR"), "reason", "CALL_BUDGET", "question", "")));
            case "unassessed-source" -> progress.put("totalSources", progress.path("totalSources").asInt() + 1);
            case "pending-search" -> progress.put("pendingSearches", 1);
            case "missing-trace" -> ((ArrayNode) search.path("result").path("trace")).removeAll();
            case "extra-open-trace" -> ((ArrayNode) search.path("result").path("trace")).add(JSON.valueToTree(Map.of(
                    "sourceId", "BP-1535", "type", "USES", "direction", "OUTGOING", "phase", "NAVIGATE", "targetId", "CR-1097", "outcome", "UNRESOLVED", "rationale", "Unknown queue requirement.")));
            case "extra-source-question" -> ((ObjectNode) search.path("sources").get(0)).put("question", "Is phone support required?");
            case "recovery-paused" -> recovery.put("state", "PAUSED");
            case "uncertain-transport" -> checkpoint.put("outcomeUncertain", true);
            case "skipped-question" -> checkpoint.put("skipped", true);
            case "unrelated-checkpoint" -> checkpoint.put("error", "RELATION_UNRESOLVED: CR-1097: Is queue durability required?");
            case "extra-warning" -> ((ArrayNode) analysis.path("warnings")).add("MEMORY_PRESSURE: analysis stopped");
            case "awaiting-decision" -> search.put("stopReason", "AWAITING_DECISION: pending owner choice");
            case "missing-required-phone" -> {
                ArrayNode edges = (ArrayNode) value.projection().path("diagram").path("edges");
                for (int index = edges.size() - 1; index >= 0; index--) if (edges.get(index).path("id").asText().equals("R10")) edges.remove(index);
            }
            default -> throw new AssertionError(mutation);
        }
        assertRejected(value);
    }

    @Test void anOpenPointMustNotBeRelabelledAsACompleteSuccessfulAnalysis() {
        var value = open(false, true);
        ((ObjectNode) value.snapshot().path("summary")).put("status", "SUCCESS");
        ((ObjectNode) value.snapshot().path("analysis")).put("status", "SUCCESS");
        value.projection().put("snapshotStatus", "SUCCESS");
        assertRejected(withStatus(value, "SUCCESS"));
    }

    private static Fixture open(boolean bothDirections, boolean explicitUntypedInput) {
        return open(fixture(explicitUntypedInput ? "open-identity" : ""), bothDirections);
    }

    private static Fixture open(Fixture value, boolean bothDirections) {
        ObjectNode search = search(value), result = (ObjectNode) search.path("result"), progress = (ObjectNode) search.path("progress");
        List<Map<String, Object>> unfinished = new ArrayList<>(), trace = new ArrayList<>(), questions = new ArrayList<>();
        addQuestion(unfinished, trace, questions, "BP-1017", "IP-1116", "OUTGOING", "a");
        if (bothDirections) addQuestion(unfinished, trace, questions, "IP-1116", "BP-1017", "INCOMING", "b");
        for (var task : search.path("tasks")) {
            ObjectNode mutable = (ObjectNode) task; String source = task.path("sourceId").asText();
            if (source.equals("BP-1017") && task.path("targetRoot").asText().equals("IP")) mutable.put("state", "UNRESOLVED");
            if (bothDirections && source.equals("IP-1116") && task.path("targetRoot").asText().equals("BP")) mutable.put("state", "UNRESOLVED");
        }
        search.put("searchExhausted", false); search.put("totalCalls", 50); search.put("maxCalls", 256);
        progress.put("completedSearches", progress.path("totalSearches").asInt() - unfinished.size());
        progress.put("unresolvedSearches", unfinished.size());
        result.set("unfinished", JSON.valueToTree(unfinished)); result.set("trace", JSON.valueToTree(trace));
        String summary = "RELATION_SEARCH_PARTIAL: 50/256 evaluation attempts; " + unfinished.size()
                + " unfinished search batches. 0 unassessed sources; 0 pending and " + unfinished.size() + " unresolved source/target searches.";
        List<String> warnings = new ArrayList<>(List.of(summary));
        unfinished.forEach(item -> warnings.add(item.get("sourceId") + ": " + QUESTION));
        ObjectNode analysis = (ObjectNode) value.snapshot().path("analysis"); analysis.put("status", "PARTIAL"); analysis.put("errorMessage", summary);
        analysis.set("warnings", JSON.valueToTree(warnings));
        analysis.set("recovery", JSON.valueToTree(Map.of("id", "identity-question", "version", 1, "state", "COMPLETED_WITH_GAPS",
                "completedCalls", 48, "attemptedCalls", 50, "currentNode", "IP-1116", "openQuestions", questions)));
        ObjectNode snapshotSummary = (ObjectNode) value.snapshot().path("summary"); snapshotSummary.put("status", "PARTIAL"); snapshotSummary.put("errorMessage", summary);
        value.projection().put("snapshotStatus", "PARTIAL");
        return withStatus(value, "PARTIAL");
    }

    private static void addQuestion(List<Map<String, Object>> unfinished, List<Map<String, Object>> trace,
                                    List<Map<String, Object>> questions, String source, String target, String direction, String key) {
        unfinished.add(Map.of("sourceId", source, "type", "CONSUMES", "direction", direction, "targets", List.of(target), "reason", "UNRESOLVED", "question", QUESTION));
        trace.add(Map.of("sourceId", source, "type", "CONSUMES", "direction", direction, "phase", "NAVIGATE", "targetId", target,
                "outcome", "UNRESOLVED", "rationale", "F5 names an observation batch, but its prior recognition as an environmental picture is unspecified."));
        questions.add(Map.of("key", "R" + key.repeat(63), "nodes", List.of(target), "error", "RELATION_UNRESOLVED: " + target + ": " + QUESTION,
                "attempts", 1, "skipped", false, "outcomeUncertain", false));
    }

    private static ObjectNode search(Fixture value) { return (ObjectNode) value.snapshot().path("analysis").path("relationSearchReport"); }
    private static Fixture withStatus(Fixture before, String status) {
        var original = before.document(); var metadata = new LinkedHashMap<>(original.metadata()); metadata.put("taxonomy.snapshotStatus", status);
        var document = new ExchangeDocument(original.profile(), original.profileVersion(), original.externalVersion(), original.completeScope(), original.source(),
                original.artifacts(), original.relations(), original.placements(), metadata, original.losses());
        return new Fixture(before.snapshot(), before.projection(), document, new SparxXmiCodec("3").write(document));
    }
    private static void assertRejected(Fixture value) { assertThrows(AssertionError.class, () -> verify(value)); }
}
