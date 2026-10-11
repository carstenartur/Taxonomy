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
}
