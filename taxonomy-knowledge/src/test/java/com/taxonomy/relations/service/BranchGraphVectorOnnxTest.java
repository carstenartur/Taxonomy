package com.taxonomy.relations.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("onnx")
class BranchGraphVectorOnnxTest {
    @Test void authenticModelAndTokenizerIdentifyBothNativeVectorFieldsAcrossIndependentInstances() throws Exception {
        var first = localModel();
        var second = localModel();
        String communications = "Secure voice communications for medical teams";
        String food = "Purchase and store dry food for long voyages";
        try {
            var model = first.embeddingIdentity();
            assertThat(model.modelSha256()).matches("[a-f0-9]{64}");
            assertThat(model.tokenizerSha256()).matches("[a-f0-9]{64}");
            var source = new BranchGraphVectorSource("repository", "copy", "draft", "4".repeat(40),
                    "retained-workbook", "a".repeat(64), model.indexKey(), "b".repeat(64), 2);
            BranchGraphVectorIndex.Archive durable;
            try (var index = new BranchGraphVectorIndex.Builder(source.key())) {
                index.node("secure-comms", communications, first.embedDocument(communications));
                index.node("food", food, first.embedDocument(food));
                String supports = communications + " supports emergency relief";
                String supplies = food + " supplies storage facilities";
                index.relation("secure-comms", "relief", "CP", "SUPPORTS", supports, first.embedDocument(supports));
                index.relation("food", "storage", "IP", "SUPPLIES", supplies, first.embedDocument(supplies));
                durable = index.finish();
            }
            ReflectionTestUtils.invokeMethod(first, "closeModel");
            assertThat(second.embeddingIdentity()).isEqualTo(model);
            var hits = BranchGraphVectorIndex.search(durable, second.embedQuery(communications), 1, 1);
            assertThat(hits.nodes()).containsExactly("secure-comms");
            assertThat(hits.relations()).containsExactly(new BranchGraphVectorIndex.RelationHit("CP", "SUPPORTS"));
        } finally {
            ReflectionTestUtils.invokeMethod(first, "closeModel");
            ReflectionTestUtils.invokeMethod(second, "closeModel");
        }
    }

    private static LocalEmbeddingService localModel() {
        var service = new LocalEmbeddingService();
        ReflectionTestUtils.setField(service, "embeddingEnabled", true);
        ReflectionTestUtils.setField(service, "allowDownload", false);
        String configured = System.getenv("TAXONOMY_EMBEDDING_MODEL_DIR");
        var directory = configured == null || configured.isBlank() ? Path.of("..", "models", "multilingual-minilm") : Path.of(configured);
        ReflectionTestUtils.setField(service, "modelDir", directory.toAbsolutePath().normalize().toString());
        return service;
    }
}
