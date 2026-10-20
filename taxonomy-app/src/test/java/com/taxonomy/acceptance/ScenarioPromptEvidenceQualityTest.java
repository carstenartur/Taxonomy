package com.taxonomy.acceptance;

import com.taxonomy.analysis.relations.RelationSearchProtocol;
import com.taxonomy.analysis.service.PromptTemplateService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.*;

class ScenarioPromptEvidenceQualityTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "Read published observations and protect subscriber details.";
    private static final String FULL_CONTEXT = "\nSource hierarchy context (ancestors, not additional candidates):\n";
    private static final String COMPACT_CONTEXT = "\nAssessed source hierarchy (relation-source-context-v1; ancestor IDs and names are context, not additional candidates):\n";
    private static final String CANDIDATES = "\nNodes to evaluate (only the following candidates):\n";
    private static final String BP_ROOT_CONTEXT = "\nRoot catalogue context (root-catalogue-context-v1; immediate source children, not additional candidates):\n"
            + "  BP-1000: Business Processes - Enterprise process functions.\n";
    private static final String BP_CONTEXT = FULL_CONTEXT
            + "  BP: Business Processes - Process taxonomy.\n"
            + "  BP-1000: Business Processes - Enterprise process functions.\n";
    private static final String CR_CONTEXT = FULL_CONTEXT
            + "  CR: Core Services - Service taxonomy.\n"
            + "  CR-1000: Core Services - Shared service functions.\n";
    private static final String BP_DESCRIPTION = "Acquire existing published observations.";
    private static final String CR_DESCRIPTION = "Control authorized access to protected information.";
    private static final String BP_COMPACT = BP_DESCRIPTION + COMPACT_CONTEXT
            + "  BP: Business Processes\n  BP-1000: Business Processes\n";
    private static final Set<String> OVERLAY = Set.of("IP-2130", "IP-1051", "BP-9000");

    @Test void acceptsActualProtocolPhasesAgainstIndependentCatalogueFacts() {
        var report = verify(catalogue(), calls(), snapshots());
        assertThat(report.get("outcome")).isEqualTo("PASS");
        assertThat(report.get("classificationCandidateAssessments")).isEqualTo(2);
    }

    @Test void changedCandidateNameCannotKeepItsStoredPerfectAnswer() {
        var calls = calls();
        changeInput(calls, 3, input -> ((ObjectNode) input.at("/candidates/0")).put("name", "Unrelated service"));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("catalogue name");
    }

    @Test void extractMustIncludeTheWholeSourceHierarchyBeforeCompactingIt() {
        var calls = calls();
        changeInput(calls, 2, input -> ((ObjectNode) input.at("/nodes/0")).put("description", BP_DESCRIPTION));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("full catalogue description");
    }

    @Test void offeredCandidateCannotLoseItsAncestorDescriptions() {
        var calls = calls();
        changeInput(calls, 3, input -> ((ObjectNode) input.at("/candidates/0")).put("description", CR_DESCRIPTION));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("full catalogue description");
    }

    @Test void compactSourceCannotLoseItsOwnDescription() {
        var calls = calls();
        changeInput(calls, 3, input -> ((ObjectNode) input.at("/contribution/source"))
                .put("description", BP_COMPACT.substring(BP_DESCRIPTION.length())));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("assessed source description");
    }

    @Test void verifyUsesTheSameBoundSourceAndFullCandidateContext() {
        var calls = calls();
        changeInput(calls, 4, input -> ((ObjectNode) input.at("/contribution/source"))
                .put("description", BP_COMPACT.replace("  BP-1000: Business Processes", "  CR-1000: Core Services")));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("assessed source description");
    }

    @Test void aDifferentSourceContributionCannotBorrowTheEarlierExtraction() {
        var calls = calls();
        changeInput(calls, 3, input -> ((ObjectNode) input.path("contribution")).put("text", "Create new warning authority."));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("previous full source assessment");
    }

    @Test void phaseAndItsInstructionCannotDisagree() {
        var calls = calls();
        changeInput(calls, 3, input -> input.put("phase", "VERIFY"));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("phase instruction");
    }

    @Test void staleRelationSemanticsCannotKeepItsAcceptedPromptEvidence() {
        var calls = calls();
        changePrompt(calls, 3, calls.get(3).prompt().replace("relation-semantics-v2", "relation-semantics-v1"));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Typed relation scope instruction");
    }

    @Test void classificationCannotLoseACandidateWhileRetainingItsRequestedKey() {
        var calls = calls();
        changePrompt(calls, 1, calls.get(1).prompt().replace("BP-1017: Data Acquisition - " + BP_DESCRIPTION + "\n", ""));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("classification candidate block");
    }

    @Test void classificationCannotUseAnotherBranchesContext() {
        var calls = calls();
        changePrompt(calls, 1, calls.get(1).prompt().replace(BP_CONTEXT, CR_CONTEXT));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("classification candidate block");
    }

    @Test void independentRootCannotUseAnAlteredRootDescription() {
        var calls = calls();
        changePrompt(calls, 0, calls.get(0).prompt().replace("BP: Business Processes - Process taxonomy.",
                "BP: Business Processes - Unrelated invented scope."));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("classification candidate block");
    }

    @Test void independentRootContextMustMatchOnlyItsImmediateOriginalSourceChildren() {
        for (String alteredContext : List.of(
                BP_ROOT_CONTEXT.replace(" - Enterprise process functions.", ""),
                BP_ROOT_CONTEXT.replace("BP-1000: Business Processes", "BP-1000: Invented scope"),
                BP_ROOT_CONTEXT.replace("  BP-1000: Business Processes - Enterprise process functions.\n", ""),
                BP_ROOT_CONTEXT + "  BP-1017: Data Acquisition - " + BP_DESCRIPTION + "\n",
                BP_ROOT_CONTEXT + "  BP-9000: Navigation Alias - Overlay navigation only.\n",
                BP_ROOT_CONTEXT + "  CR-1000: Core Services - Shared service functions.\n")) {
            var calls = calls();
            changePrompt(calls, 0, calls.getFirst().prompt().replace(BP_ROOT_CONTEXT, alteredContext));
            assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("classification candidate block");
        }
    }

    @Test void eachMixedParentContextAndOverlayBoundaryIsChecked() {
        var calls = calls();
        calls.add(classification("mixed-ip", templates().renderPrompt("IP", ORIGINAL,
                overlayNotice("IP-2130") + CANDIDATES + "IP-2130: Alerts and Warnings - Published alerts.\n"
                        + FULL_CONTEXT + "  IP: Information Products - Information taxonomy.\n"
                        + "  IP-1000: Information Products - Structured information.\n"
                        + "  IP-1069: Mapping Information Products - Contextual information.\n"
                        + CANDIDATES + "IP-1097: Situational Awareness Tracks - Track positions.\n",
                25, "IP-2130, IP-1097")));
        assertThat(verify(catalogue(), calls, snapshots()).get("outcome")).isEqualTo("PASS");
        changePrompt(calls, 5, calls.get(5).prompt().replace(overlayNotice("IP-2130"), BP_CONTEXT));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("classification candidate block");
    }

    @Test void concreteProductPromptRetainsItsOwnTextAndOverlayNotice() {
        var calls = calls();
        calls.add(classification("product", templates().renderProductPrompt(ORIGINAL,
                overlayNotice("IP-1051") + CANDIDATES + "IP-1051: Hazards - Identified hazards.\n", "IP-1051", 50)));
        assertThat(verify(catalogue(), calls, snapshots()).get("outcome")).isEqualTo("PASS");
        changePrompt(calls, 5, calls.get(5).prompt().replace("Identified hazards.", "Invented restrictions."));
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("classification candidate block");
    }

    @Test void fourOfferedRelationNodesCannotMasqueradeAsTheNormalBatchSize() {
        var calls = calls();
        changeInput(calls, 2, input -> {
            var nodes = (ArrayNode) input.path("nodes");
            for (int index = 0; index < 3; index++) nodes.add(nodes.get(0).deepCopy());
        });
        assertThatThrownBy(() -> verify(catalogue(), calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Relation batch exceeds normal limit");
    }

    @Test void promptBudgetIncludesResponseSchemaEvenWhenPlainPromptWouldFit() {
        var catalogue = catalogue();
        var calls = calls();
        int length = 15_600 - calls.get(3).prompt().length() + CR_DESCRIPTION.length();
        String own = "a".repeat(length);
        find(catalogue, "CR-1007").put("descriptionEn", own);
        changeInput(calls, 3, input -> ((ObjectNode) input.at("/candidates/0")).put("description", own + CR_CONTEXT));
        assertThat(calls.get(3).prompt().length()).isLessThan(16_000);
        assertThat(RelationSearchProtocol.requestCharacters(calls.get(3).prompt())).isGreaterThan(16_000);
        assertThatThrownBy(() -> verify(catalogue, calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Relation request exceeds normal limit");
    }

    @Test void promptBudgetAlsoCountsUtf8Bytes() {
        var catalogue = catalogue();
        var calls = calls();
        String own = "漢".repeat(5_000);
        find(catalogue, "CR-1007").put("descriptionEn", own);
        changeInput(calls, 3, input -> ((ObjectNode) input.at("/candidates/0")).put("description", own + CR_CONTEXT));
        assertThat(calls.get(3).prompt().length()).isLessThan(16_000);
        assertThat(RelationSearchProtocol.requestCharacters(calls.get(3).prompt())).isGreaterThan(16_000);
        assertThatThrownBy(() -> verify(catalogue, calls, snapshots()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Relation request exceeds normal limit");
    }

    @Test void everyInitialAndContinuedSnapshotKeepsTheNormalCallBudget() {
        var snapshots = snapshots();
        ((ObjectNode) snapshots.getFirst().at("/analysis/relationSearchReport")).put("maxCalls", 256);
        assertThatThrownBy(() -> verify(catalogue(), calls(), snapshots))
                .isInstanceOf(AssertionError.class).hasMessageContaining("normal 24-call budget");
    }

    @Test void missingBudgetMustNotCoerceToAnAcceptedZero() {
        var snapshots = snapshots();
        ((ObjectNode) snapshots.getFirst().at("/analysis/relationSearchReport")).remove("maxCalls");
        assertThatThrownBy(() -> verify(catalogue(), calls(), snapshots))
                .isInstanceOf(AssertionError.class).hasMessageContaining("normal 24-call budget");
    }

    private static Map<String, Object> verify(JsonNode catalogue, List<ScenarioLlmPlayback.Call> calls, List<JsonNode> snapshots) {
        return ScenarioPromptEvidenceQuality.verify(catalogue, OVERLAY, ORIGINAL, calls, snapshots);
    }

    private static List<ScenarioLlmPlayback.Call> calls() {
        var calls = new ArrayList<ScenarioLlmPlayback.Call>();
        calls.add(classification("root", templates().renderRootPrompt("BP", ORIGINAL,
                BP_ROOT_CONTEXT + CANDIDATES + "BP: Business Processes - Process taxonomy.\n")));
        calls.add(classification("categories", templates().renderPrompt("BP", ORIGINAL,
                BP_CONTEXT + CANDIDATES + "BP-1017: Data Acquisition - " + BP_DESCRIPTION + "\n", 90, "BP-1017")));
        var protocol = new RelationSearchProtocol(prompt -> {
            var input = JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6));
            String answer;
            if (input.has("nodes")) answer = JSON.writeValueAsString(Map.of("selections", List.of(Map.of(
                    "nodeId", "BP-1017", "outcome", "EXPLICIT", "contributions", List.of(Map.of(
                            "text", "Read published observations.", "requirementQuote", ORIGINAL, "condition", "")),
                    "rationale", "The original requests existing observations.", "question", ""))));
            else if (input.path("phase").asString().equals("VERIFY")) answer = JSON.writeValueAsString(Map.of("decisions", List.of(Map.of(
                    "targetId", "CR-1007", "outcome", "VERIFIED", "rationale", "The proposed access function retains the original scope.", "question", ""))));
            else answer = JSON.writeValueAsString(Map.of("decisions", List.of(Map.of(
                    "targetId", "CR-1007", "outcome", "MATCH", "contribution", "Protect subscriber details.",
                    "requirementQuote", ORIGINAL, "necessity", "REQUIRED", "condition", "", "alternativeGroup", "",
                    "rationale", "The original requests protected subscriber details.", "question", ""))));
            calls.add(call("relation:" + calls.size(), prompt, JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", answer)))))));
            return answer;
        });
        var source = new Node("BP-1017", "BP", "Data Acquisition", BP_DESCRIPTION + BP_CONTEXT, false, BP_COMPACT);
        var target = new Node("CR-1007", "CR", "Access Control Services", CR_DESCRIPTION + CR_CONTEXT, false);
        var contribution = protocol.contributions(ORIGINAL, List.of(source)).getFirst().contributions().getFirst();
        var proposal = protocol.evaluate(new Query(ORIGINAL, contribution, "USES", Direction.OUTGOING, Phase.NAVIGATE, List.of(target), null)).getFirst();
        protocol.evaluate(new Query(ORIGINAL, contribution, "USES", Direction.OUTGOING, Phase.VERIFY, List.of(target), proposal));
        return calls;
    }

    private static ScenarioLlmPlayback.Call classification(String id, String prompt) { return call(id, prompt, "{}"); }
    private static ScenarioLlmPlayback.Call call(String id, String prompt, String response) {
        return new ScenarioLlmPlayback.Call(id, ScenarioLlmPlayback.sha256(prompt), ScenarioLlmPlayback.sha256(response), prompt, response);
    }
    private static void changePrompt(List<ScenarioLlmPlayback.Call> calls, int index, String prompt) {
        var call = calls.get(index); calls.set(index, call(call.ruleId(), prompt, call.response()));
    }
    private static void changeInput(List<ScenarioLlmPlayback.Call> calls, int index, Consumer<ObjectNode> mutation) {
        var prompt = calls.get(index).prompt(); int offset = prompt.indexOf("INPUT\n") + 6;
        var input = (ObjectNode) JSON.readTree(prompt.substring(offset)); mutation.accept(input);
        changePrompt(calls, index, prompt.substring(0, offset) + JSON.writeValueAsString(input));
    }
    private static PromptTemplateService templates() {
        var templates = new PromptTemplateService(); templates.loadDefaults(); return templates;
    }
    private static String overlayNotice(String code) {
        return "\nClassification/navigation only: the parent assignment above " + code
                + " comes from an overlay, not established source hierarchy. Do not inherit restrictions or infer irrelevance from that assignment.";
    }
    private static List<JsonNode> snapshots() {
        return new ArrayList<>(List.of(JSON.readTree("""
                {"summary":{"id":"initial"},"analysis":{"relationSearchReport":{"maxCalls":24,"totalCalls":24}}}
                """), JSON.readTree("""
                {"summary":{"id":"continued"},"analysis":{"relationSearchReport":{"maxCalls":24,"totalCalls":7}}}
                """)));
    }
    private static ArrayNode catalogue() {
        var tree = JSON.createArrayNode();
        var bp = node("BP", "BP", "Business Processes", "Process taxonomy.", "", "CATEGORY");
        var processes = node("BP-1000", "BP", "Business Processes", "Enterprise process functions.", "BP", "CATEGORY");
        processes.withArray("children").add(node("BP-1017", "BP", "Data Acquisition", BP_DESCRIPTION, "BP-1000", "CATEGORY"));
        bp.withArray("children").add(processes);
        bp.withArray("children").add(node("BP-9000", "BP", "Navigation Alias", "Overlay navigation only.", "BP", "CATEGORY"));
        tree.add(bp);
        var cr = node("CR", "CR", "Core Services", "Service taxonomy.", "", "CATEGORY");
        var services = node("CR-1000", "CR", "Core Services", "Shared service functions.", "CR", "CATEGORY");
        services.withArray("children").add(node("CR-1007", "CR", "Access Control Services", CR_DESCRIPTION, "CR-1000", "CATEGORY"));
        cr.withArray("children").add(services); tree.add(cr);
        var ip = node("IP", "IP", "Information Products", "Information taxonomy.", "", "CATEGORY");
        var information = node("IP-1000", "IP", "Information Products", "Structured information.", "IP", "CATEGORY");
        var mapping = node("IP-1069", "IP", "Mapping Information Products", "Contextual information.", "IP-1000", "CATEGORY");
        mapping.withArray("children").add(node("IP-2130", "IP", "Alerts and Warnings", "Published alerts.", "IP-1069", "PRODUCT_FAMILY"));
        mapping.withArray("children").add(node("IP-1097", "IP", "Situational Awareness Tracks", "Track positions.", "IP-1069", "PRODUCT_FAMILY"));
        information.withArray("children").add(mapping);
        information.withArray("children").add(node("IP-1051", "IP", "Hazards", "Identified hazards.", "IP-1000", "PRODUCT"));
        ip.withArray("children").add(information); tree.add(ip);
        return tree;
    }
    private static ObjectNode node(String code, String root, String name, String description, String parent, String role) {
        var node = JSON.createObjectNode().put("code", code).put("taxonomyRoot", root).put("nameEn", name)
                .put("descriptionEn", description).put("parentCode", parent).put("analysisRole", role).put("taxonomyContainer", parent.isEmpty());
        node.putArray("children"); return node;
    }
    private static ObjectNode find(JsonNode nodes, String code) {
        for (JsonNode node : nodes) {
            if (node.path("code").asString().equals(code)) return (ObjectNode) node;
            var found = find(node.path("children"), code); if (found != null) return found;
        }
        return null;
    }
}
