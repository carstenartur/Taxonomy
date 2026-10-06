package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.EmbeddingModelProfile;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LocalModelTargetIdentityTest {
    @Test
    void explicitLegacyProfileRetainsItsExistingTargetIdentity() {
        var embeddings = new LocalEmbeddingService();
        ReflectionTestUtils.setField(embeddings, "embeddingEnabled", true);
        ReflectionTestUtils.setField(embeddings, "modelProfile", EmbeddingModelProfile.BGE_SMALL_EN);
        var configuration = new LlmProviderConfig(embeddings);
        ReflectionTestUtils.setField(configuration, "llmProviderConfig", "LOCAL_ONNX");

        var target = new AiTargetCatalogService(configuration).activeTarget();

        assertThat(target.model()).isEqualTo("bge-small-en-v1.5");
        assertThat(target.targetId()).isEqualTo("local-onnx:bge-small-en-v1.5");
        assertThat(ReflectionTestUtils.getField(embeddings, "model")).isNull();
    }

    @Test
    void localTargetDescribesTheDefaultMultilingualModelWithoutLoadingIt() {
        var embeddings = new LocalEmbeddingService();
        ReflectionTestUtils.setField(embeddings, "embeddingEnabled", true);
        var configuration = new LlmProviderConfig(embeddings);
        ReflectionTestUtils.setField(configuration, "llmProviderConfig", "LOCAL_ONNX");

        var target = new AiTargetCatalogService(configuration).activeTarget();

        assertThat(target.model()).isEqualTo("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2");
        assertThat(target.displayName()).contains("paraphrase-multilingual-MiniLM-L12-v2");
        assertThat(ReflectionTestUtils.getField(embeddings, "model")).isNull();
    }
}
