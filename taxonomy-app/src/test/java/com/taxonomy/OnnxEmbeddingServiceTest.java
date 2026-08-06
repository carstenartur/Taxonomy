package com.taxonomy;

import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@link LocalEmbeddingService} works end-to-end as a Spring bean:
 * pinned-model loading, ONNX Runtime loading, and embedding inference.
 *
 * <p>This is an explicit ONNX verification test. Ordinary and database-focused
 * Maven suites exclude the {@code onnx} group; the {@code ci} and {@code onnx}
 * profiles provide the pinned model and deliberately execute it.</p>
 */
@Tag("onnx")
@SpringBootTest(properties = {
        "embedding.enabled=true",
        "embedding.allow-download=false"
})
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
