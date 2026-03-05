package com.nato.taxonomy.service;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.nato.taxonomy.model.TaxonomyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local embedding service that uses the {@code sentence-transformers/all-MiniLM-L6-v2}
 * model via DJL and ONNX Runtime to score taxonomy nodes against a business requirement.
 *
 * <p>The model is <em>lazily loaded</em> on first use: it is downloaded from DJL's model
 * hub and cached in {@code ~/.djl.ai/} so subsequent starts are instant.  No API key is
 * required.
 *
 * <p>Enable this provider by setting {@code LLM_PROVIDER=LOCAL_ONNX} (or
 * {@code llm.provider=LOCAL_ONNX} in {@code application.properties}).
 */
@Service
public class LocalEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);

    /**
     * DJL model-zoo URL for the ONNX export of all-MiniLM-L6-v2.
     * The model is downloaded on first use and cached locally.
     */
    static final String MODEL_URL =
            "djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2";

    /**
     * Cosine-similarity threshold below which a node receives a score of 0.
     * Values between 0 and 1; lower = more permissive.
     */
    private static final double THRESHOLD = 0.25;

    private volatile ZooModel<String, float[]> model;
    private final Object initLock = new Object();

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /**
     * Returns the (lazily initialised) DJL model, loading it on first call.
     */
    ZooModel<String, float[]> getModel() throws Exception {
        if (model == null) {
            synchronized (initLock) {
                if (model == null) {
                    log.info("Loading all-MiniLM-L6-v2 via DJL / ONNX Runtime from {} …", MODEL_URL);
                    Criteria<String, float[]> criteria = Criteria.builder()
                            .optApplication(Application.NLP.TEXT_EMBEDDING)
                            .setTypes(String.class, float[].class)
                            .optModelUrls(MODEL_URL)
                            .optEngine("OnnxRuntime")
                            .build();
                    model = criteria.loadModel();
                    log.info("all-MiniLM-L6-v2 loaded successfully.");
                }
            }
        }
        return model;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the embedding vector for {@code text}.
     *
     * @throws Exception if the model cannot be loaded or inference fails
     */
    public float[] embed(String text) throws Exception {
        try (Predictor<String, float[]> predictor = getModel().newPredictor()) {
            return predictor.predict(text);
        }
    }

    /**
     * Computes the cosine similarity between two embedding vectors.
     * Returns a value in {@code [0, 1]} (clamped; negative raw values become 0).
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0, similarity);
    }

    /**
     * Scores each taxonomy node against {@code businessText} using cosine similarity
     * of their embeddings.  Scores are integers in {@code [0, 100]}.
     *
     * <ul>
     *   <li>Similarity ≤ {@link #THRESHOLD} → score 0 (no meaningful match)</li>
     *   <li>Similarity = 1.0 → score 100 (perfect match)</li>
     * </ul>
     *
     * <p>On any error all nodes receive a score of 0 and the exception is logged.
     */
    public Map<String, Integer> scoreNodes(String businessText, List<TaxonomyNode> nodes) {
        Map<String, Integer> scores = new HashMap<>();
        try {
            float[] businessEmbedding = embed(businessText);
            for (TaxonomyNode node : nodes) {
                String nodeText = buildNodeText(node);
                float[] nodeEmbedding = embed(nodeText);
                double similarity = cosineSimilarity(businessEmbedding, nodeEmbedding);
                int score = similarity <= THRESHOLD
                        ? 0
                        : (int) Math.round((similarity - THRESHOLD) / (1.0 - THRESHOLD) * 100);
                scores.put(node.getCode(), Math.min(100, Math.max(0, score)));
            }
            log.info("LOCAL_ONNX scores: {}", scores);
        } catch (Exception e) {
            log.error("Error computing embeddings for nodes; returning zero scores", e);
            for (TaxonomyNode node : nodes) {
                scores.put(node.getCode(), 0);
            }
        }
        return scores;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildNodeText(TaxonomyNode node) {
        StringBuilder sb = new StringBuilder(node.getName());
        if (node.getDescription() != null && !node.getDescription().isBlank()) {
            sb.append(". ").append(node.getDescription());
        }
        return sb.toString();
    }
}
