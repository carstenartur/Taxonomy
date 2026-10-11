package com.taxonomy.analysis.reformulation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcilePromptCompactionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void frozenNodeDescriptionsDropOnlyRedundantValues() {
        var node = (ObjectNode) JSON.readTree("{\"code\":\"BP\",\"name\":\"Processes\",\"nameEn\":\"Processes\",\"nameDe\":null,"
                + "\"description\":\"Full text\",\"descriptionEn\":\"Full text\",\"descriptionDe\":\"Volltext\",\"dataset\":null}");
        ReconcilePromptBuilder.compact(node);
        assertThat(node.toString()).isEqualTo("{\"code\":\"BP\",\"name\":\"Processes\",\"description\":\"Full text\",\"descriptionDe\":\"Volltext\"}");
    }

    @Test
    void differingEnglishTextIsPreserved() {
        var node = (ObjectNode) JSON.readTree("{\"code\":\"BP\",\"name\":\"Prozesse\",\"nameEn\":\"Processes\","
                + "\"description\":\"Text\",\"descriptionEn\":\"Other text\"}");
        ReconcilePromptBuilder.compact(node);
        assertThat(node.path("nameEn").asText()).isEqualTo("Processes");
        assertThat(node.path("descriptionEn").asText()).isEqualTo("Other text");
    }

    @Test
    void compactionIsVersionedSoFrozenV1ProposalsKeepTheirExactEncoding() {
        String catalogue = "[{\"code\":\"BP\",\"name\":\"Processes\",\"nameEn\":\"Processes\",\"dataset\":null,\"children\":[]}]";
        var builder = new ReconcilePromptBuilder(JSON);
        var legacy = builder.scopedContext(baseline(catalogue, "reformulation-reconcile-v1"), java.util.Set.of("BP"));
        var unversioned = builder.scopedContext(baseline(catalogue, null), java.util.Set.of("BP"));
        var current = builder.scopedContext(baseline(catalogue, ReconcilePromptBuilder.PROMPT_VERSION), java.util.Set.of("BP"));
        assertThat(legacy.get("descriptions").toString()).contains("nameEn").contains("dataset");
        assertThat(unversioned).isEqualTo(legacy);
        assertThat(current.get("descriptions").toString()).doesNotContain("nameEn").doesNotContain("dataset");
        assertThat(ReconcilePromptBuilder.template()).startsWith("REQUIREMENT RECONCILIATION — " + ReconcilePromptBuilder.PROMPT_VERSION);
    }

    private static com.taxonomy.reformulation.ReformulationBaseline baseline(String catalogue, String reconcileVersion) {
        var scope = new com.taxonomy.reformulation.ReformulationBaseline.Scope("repo", "ws", "main", 1, 1);
        var frozen = new java.util.HashMap<String, String>();
        frozen.put("catalogue", catalogue);
        if (reconcileVersion != null) frozen.put("reconcilePromptVersion", reconcileVersion);
        return com.taxonomy.reformulation.ReformulationBaseline.freeze(
                new com.taxonomy.reformulation.ReformulationBaseline.Source(scope, 1, "text"),
                new com.taxonomy.reformulation.ReformulationBaseline.Snapshot(scope, "s", 1, "{\"rawScores\":{}}"),
                frozen, "de", "reformulation-v1");
    }
}
