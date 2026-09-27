package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.dto.AiTargetDtos.*;
import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class InheritedDiscoveryContextTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void nodeEncodesRepeatedHistoricalDiscoveriesWithoutLosingDecisions() { verify(false); }
    @Test void reconciliationEncodesRepeatedHistoricalDiscoveriesWithoutLosingDecisions() { verify(true); }

    private void verify(boolean reconcile) {
        var base = WalkUpReformulationTest.baseline();
        var history = json.createArrayNode();
        var entry = history.addObject().put("historicalEvidenceHash", "unchanged-historical-evidence")
                .put("adoptionActor", "architect").put("adoptionRationale", "Preserve the explicit choice");
        entry.putArray("statements");
        entry.putArray("humanAnswers").addObject().put("questionId", "q-0").put("state", "DEFERRED")
                .put("rationale", "Need human evidence").putArray("values");
        var questions = entry.putArray("questions");
        String repeated = "Historical context äöü 😀 with \"quoted\" conditions.\n".repeat(900);
        for (int i = 0; i < 3; i++) {
            var question = questions.addObject().put("id", "q-" + i).put("wording", "Concrete decision " + i).put("state", "DEFERRED");
            question.putObject("key").put("scope", "global");
            var discovery = question.putArray("discoveries").addObject().put("nodeId", "A").put("context", repeated);
            discovery.putArray("nodeIds").add("A"); discovery.putArray("edgeIds");
            question.putArray("origins").addObject().putArray("discoveries").add(discovery.deepCopy());
        }
        assertThat(history.toString().length()).isGreaterThan(120_000);
        String archived = history.toString();
        var frozen = new HashMap<>(base.frozenContext()); frozen.put("inheritedDecisionContext", archived);
        frozen.putAll(ReconcilePromptBuilder.freeze(Map.of()));
        var baseline = new ReformulationBaseline(base.scope(), base.sourceVersionId(), base.originalText(), base.originalTextHash(),
                base.snapshotId(), base.snapshotPayload(), frozen, base.language(), base.algorithmVersion());
        String marker = reconcile ? "\nRECONCILIATION_DATA_JSON\n" : "\nINPUT_DATA_JSON\n";
        String prompt = reconcile ? new ReconcilePromptBuilder(json).build(new ReconciliationInput(baseline, 1,
                List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of()), null)
                : new ReformulationPromptBuilder(json).build(new NodeSynthesisInput(baseline, "A", null, "A",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "Preserve source"), null);
        var data = json.readTree(prompt.substring(prompt.indexOf(marker) + marker.length()));
        assertThat(data.path("discoveryContextTable").size()).isEqualTo(1);
        var restored = data.path("inheritedDecisionContext").deepCopy();
        restore(restored, data.path("discoveryContextTable"));
        assertThat(restored).isEqualTo(history);
        assertThat(baseline.frozenContext().get("inheritedDecisionContext")).isEqualTo(archived);
        assertThat(data.at("/baseline/originalText").asText()).isEqualTo(base.originalText());
        var target = new AiTargetDescriptor("test", "Test", "CUSTOM_OPENAI", "default-budget", AiTargetMode.REMOTE,
                AiTargetHealth.READY, true, false, false, new PromptBudget(120_000, 262_144, 30_000), "test", null);
        new AiPromptBudgetPolicy(null).requireWithinBudget(prompt, target);
    }
    private void restore(JsonNode value, JsonNode table) {
        if (value.isObject()) {
            var object = (ObjectNode) value;
            if (object.has("contextRef")) {
                String key = object.remove("contextRef").asText(); assertThat(table.has(key)).isTrue();
                object.set("context", table.path(key));
            }
            object.properties().forEach(e -> restore(e.getValue(), table));
        } else if (value.isArray()) value.forEach(v -> restore(v, table));
    }
}
