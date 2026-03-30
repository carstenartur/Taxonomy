package com.taxonomy;

import ai.djl.engine.Engine;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stufe 1: Plain Java — DJL/ONNX Runtime loading + inference.
 * <p>
 * Proves that the ONNX Runtime native library ({@code libonnxruntime.so} / {@code onnxruntime.dll})
 * loads correctly and the bge-small-en-v1.5 model produces valid embedding vectors.
 * <p>
 * <strong>No Spring, no server, no HTTP.</strong>
 * <p>
 * Opt-in: only runs when the {@code runOnnxTests} system property is set.
 * Run with: {@code mvn test -DrunOnnxTests -Dtest=OnnxModelDirectTest}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "runOnnxTests", matches = ".*")
class OnnxModelDirectTest {

    private static final String HF_REPO_URL = "https://huggingface.co/BAAI/bge-small-en-v1.5";
    private static final String HF_RESOLVE_PATTERN = "%s/resolve/main/%s";
    private static final String[] HF_MODEL_FILES = {"onnx/model.onnx", "tokenizer.json"};

    private static final String SERVING_PROPERTIES_CONTENT =
            "engine=OnnxRuntime\n"
                    + "option.modelName=model\n"
                    + "translatorFactory=ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory\n"
                    + "option.mapLocation=true\n"
                    + "option.includeTokenTypes=true\n";

    private static ZooModel<String, float[]> model;
    private static Path cacheDir;

    @BeforeAll
    static void loadModel() throws Exception {
        // Download from HuggingFace (or use cache) — mirrors LocalEmbeddingService.loadModel()
        cacheDir = Path.of(System.getProperty("user.home"),
                ".djl.ai", "cache", "taxonomy", "BAAI--bge-small-en-v1.5");
        Files.createDirectories(cacheDir);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String relPath : HF_MODEL_FILES) {
            String fileUrl = String.format(HF_RESOLVE_PATTERN, HF_REPO_URL, relPath);
            String localName = relPath.contains("/")
                    ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
            Path localFile = cacheDir.resolve(localName);

            if (Files.exists(localFile) && Files.size(localFile) > 0) {
                continue;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fileUrl))
                    .timeout(Duration.ofMinutes(5))
                    .GET().build();
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).as("Download " + fileUrl).isEqualTo(200);
            try (InputStream in = response.body()) {
                Files.copy(in, localFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Ensure serving.properties exists
        Path servingProps = cacheDir.resolve("serving.properties");
        if (!Files.exists(servingProps)
                || !Files.readString(servingProps).contains("OnnxRuntime")) {
            Files.writeString(servingProps, SERVING_PROPERTIES_CONTENT);
        }

        model = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(cacheDir)
                .optModelName("model")
                .optEngine("OnnxRuntime")
                .optArgument("includeTokenTypes", true)
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                .build().loadModel();
    }

    @AfterAll
    static void closeModel() {
        if (model != null) model.close();
    }

    // ── Test 1.1 ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void onnxRuntimeEngineIsAvailable() {
        Engine engine = Engine.getEngine("OnnxRuntime");
        assertThat(engine).isNotNull();
        assertThat(engine.getEngineName()).isEqualTo("OnnxRuntime");
    }

    // ── Test 1.2 ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void modelDownloadsAndLoads() {
        assertThat(model).isNotNull();
    }

    // ── Test 1.3 ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void modelProducesNonEmptyEmbedding() throws Exception {
        float[] embedding = embed("hospital communication");
        assertThat(embedding).isNotNull();
        assertThat(embedding).hasSize(384); // bge-small-en-v1.5 dimension
    }

    // ── Test 1.4 ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void embeddingVectorIsNormalized() throws Exception {
        float[] vec = embed("hospital communication");
        double norm = l2Norm(vec);
        assertThat(norm).isBetween(0.95, 1.05); // BGE models produce normalized vectors
    }

    // ── Test 1.5 ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void twoTextsProduceDifferentVectors() throws Exception {
        float[] a = embed("hospital");
        float[] b = embed("satellite");
        assertThat(a).isNotEqualTo(b);
    }

    // ── Test 1.6 ─────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void similarTextsHaveHighCosineSimilarity() throws Exception {
        float[] a = embed("voice communication");
        float[] b = embed("audio data exchange");
        double similarity = cosine(a, b);
        assertThat(similarity).isGreaterThan(0.7);
    }

    // ── Test 1.7 ─────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void unrelatedTextsHaveLowCosineSimilarity() throws Exception {
        float[] a = embed("hospital");
        float[] b = embed("tax regulation");
        double similarity = cosine(a, b);
        assertThat(similarity).isLessThan(0.4);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float[] embed(String text) throws Exception {
        try (Predictor<String, float[]> predictor = model.newPredictor()) {
            return predictor.predict(text);
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double l2Norm(float[] vec) {
        double sum = 0;
        for (float v : vec) sum += v * v;
        return Math.sqrt(sum);
    }
}
