package com.taxonomy;

import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stufe 2: Spring Boot Service — {@link LocalEmbeddingService} with real ONNX model.
 * <p>
 * Proves that {@code LocalEmbeddingService} is correctly wired as a Spring bean and the
 * service methods ({@code embed()}, {@code embedQuery()}, {@code isAvailable()}) work with
 * the real bge-small-en-v1.5 model.
 * <p>
 * Opt-in: only runs when the {@code runOnnxTests} system property is set.
 * Run with: {@code mvn test -DrunOnnxTests -Dtest=OnnxEmbeddingServiceTest}
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "runOnnxTests", matches = ".*")
class OnnxEmbeddingServiceTest {

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Value("${embedding.enabled:true}")
    private boolean embeddingEnabled;

    @Value("${embedding.model.name:}")
    private String modelName;

    // ── Test 2.1 ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void serviceIsEnabledAndAvailable() throws Exception {
        assertThat(embeddingService.isEnabled()).isTrue();
        // Trigger lazy model loading by calling embed()
        embeddingService.embed("warm-up text");
        assertThat(embeddingService.isAvailable()).isTrue();
    }

    // ── Test 2.2 ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void effectiveModelUrlPointsToHuggingFace() {
        String url = embeddingService.effectiveModelUrl();
        assertThat(url).startsWith("https://huggingface.co/");
    }

    // ── Test 2.3 ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void embedReturnsVector384() throws Exception {
        float[] vector = embeddingService.embed("test text");
        assertThat(vector).isNotNull();
        assertThat(vector).hasSize(384);
    }

    // ── Test 2.4 ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void embedQueryPrependsPrefix() throws Exception {
        float[] docVec = embeddingService.embed("test");
        float[] queryVec = embeddingService.embedQuery("test");
        // The query prefix changes the embedding; the vectors should differ
        assertThat(queryVec).isNotEqualTo(docVec);
    }

    // ── Test 2.5 ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void embeddingPropertyInjectionWorksCorrectly() {
        // Verify @Value injection from application.properties
        assertThat(embeddingEnabled).isTrue();
        assertThat(modelName).isNotBlank();
    }
}
