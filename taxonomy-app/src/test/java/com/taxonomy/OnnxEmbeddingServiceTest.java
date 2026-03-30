package com.taxonomy;

import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@link LocalEmbeddingService} works end-to-end as a Spring bean:
 * lazy model download from HuggingFace, ONNX Runtime loading, and embedding inference.
 * <p>
 * <strong>No CI-cache, no manual curl, no {@code resolveModelCacheDir}.</strong>
 * The service downloads the model itself on first use — exactly the same codepath
 * used in production.
 */
@SpringBootTest
class OnnxEmbeddingServiceTest {

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Test
    void embedReturnsVector384() throws Exception {
        float[] vec = embeddingService.embed("hello world");
        assertThat(vec).hasSize(384); // BGE-small-en-v1.5 dimension
    }

    @Test
    void embedQueryReturnsVector384() throws Exception {
        float[] vec = embeddingService.embedQuery("hello world");
        assertThat(vec).hasSize(384);
    }

    @Test
    void queryAndDocumentVectorsDiffer() throws Exception {
        // BGE uses a query prefix for asymmetric retrieval — vectors must differ
        float[] docVec = embeddingService.embed("test");
        float[] queryVec = embeddingService.embedQuery("test");
        assertThat(queryVec).isNotEqualTo(docVec);
    }

    @Test
    void serviceReportsAvailableAfterFirstEmbed() throws Exception {
        embeddingService.embed("trigger lazy load");
        assertThat(embeddingService.isEnabled()).isTrue();
        assertThat(embeddingService.isAvailable()).isTrue();
    }
}
