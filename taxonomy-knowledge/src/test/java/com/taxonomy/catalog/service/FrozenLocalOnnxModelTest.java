package com.taxonomy.catalog.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Selected only by the existing ONNX verification lane, whose pinned model is a required prerequisite. */
@Tag("onnx")
class FrozenLocalOnnxModelTest {
    @Test
    void actualModelVectorsPreserveFilteredLuceneScoresInsideAFrozenRoot() throws Exception {
        var service = new LocalEmbeddingService();
        ReflectionTestUtils.setField(service, "embeddingEnabled", true);
        ReflectionTestUtils.setField(service, "allowDownload", false);
        String configured = System.getenv("TAXONOMY_EMBEDDING_MODEL_DIR");
        Path directory = configured == null || configured.isBlank()
                ? Path.of("..", "models", "multilingual-minilm") : Path.of(configured);
        ReflectionTestUtils.setField(service, "modelDir", directory.toAbsolutePath().normalize().toString());
        try {
            var identity = service.embeddingIdentity();
            String rootText = "Capability packages.\nSupport coordinated medical communications.";
            String childText = "Secure voice communications.\nOutgoing: supports Medical coordination.";
            var snapshot = FrozenLocalEmbeddingTest.snapshot(FrozenLocalEmbeddingTest.SOURCE, "CP", identity, rootText, childText);
            var expected = FrozenLocalEmbeddingTest.lucene(Map.of("CP", service.embedDocument(rootText),
                    "shared-node", service.embedDocument(childText)), List.of("CP", "shared-node"), service.embedQuery("requirement"));
            try (var ignored = FrozenLocalEmbeddingTest.bind(snapshot)) {
                service.validateFrozenModel();
                assertThat(service.scoreFrozenNodes("requirement", com.taxonomy.catalog.snapshot.FrozenCatalogueContext.current().allNodes()))
                        .isEqualTo(expected);
                assertThat(service.frozenCacheStatistics().vectors()).isEqualTo(2);
            }
        } finally {
            service.closeModel();
        }
    }
}
