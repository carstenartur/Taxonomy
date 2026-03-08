package com.nato.taxonomy.service;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Local embedding service that scores taxonomy nodes against a business requirement using
 * the {@code sentence-transformers/all-MiniLM-L6-v2} ONNX model loaded via DJL.
 *
 * <h2>Architecture</h2>
 * <p>The DJL model is <em>lazily initialised</em> on first use — application startup is not
 * slowed down and no model is downloaded unless actually needed.
 *
 * <p>Vector storage and KNN retrieval are handled by Hibernate Search (Lucene backend).
 * The {@code @VectorField(name = "embedding")} on {@link TaxonomyNode} (via
 * {@link com.nato.taxonomy.search.NodeEmbeddingBinder}) stores the pre-computed embedding.
 * Queries use {@code f.knn(k).field("embedding").matching(queryVector)}.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code TAXONOMY_EMBEDDING_ENABLED} (default {@code true}) — set to {@code false} to
 *       disable all embedding and semantic search globally.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_MODEL_DIR} — path to a pre-downloaded model directory;
 *       empty = DJL auto-download to {@code ~/.djl.ai/}.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_MODEL_NAME} — DJL model URL;
 *       default {@code djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2}.</li>
 * </ul>
 *
 * <h2>Graceful degradation</h2>
 * <p>When {@code TAXONOMY_EMBEDDING_ENABLED=false} or the model fails to load, all semantic
 * search methods return empty results without throwing, and {@link #isAvailable()} returns
 * {@code false}.
 *
 * <h2>Scoring</h2>
 * <p>Hibernate Search's KNN query returns cosine similarity scores in [0, 1].
 * Raw cosine similarity is recovered as {@code 2 * luceneScore - 1} and mapped to 0–100.
 *
 * <p>Enable as the LLM provider with {@code LLM_PROVIDER=LOCAL_ONNX}.  No API key required.
 */
@Service
public class LocalEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);

    /** Default DJL model-zoo URL for the ONNX export of all-MiniLM-L6-v2. */
    static final String DEFAULT_MODEL_URL =
            "djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2";

    /**
     * Raw cosine-similarity threshold below which a node receives score 0.
     * Value in (-1, 1); 0.25 means "weak or no semantic overlap".
     */
    static final double THRESHOLD = 0.25;

    // ── Configuration (from application.properties / env vars) ───────────────

    @Value("${embedding.enabled:true}")
    private boolean embeddingEnabled;

    @Value("${embedding.model.dir:}")
    private String modelDir;

    @Value("${embedding.model.name:djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2}")
    private String modelName;

    // ── DJL model (lazy) ──────────────────────────────────────────────────────

    private volatile ZooModel<String, float[]> model;
    private volatile boolean modelLoadFailed = false;
    private final Object modelLock = new Object();

    // ── Dependencies ──────────────────────────────────────────────────────────

    @PersistenceContext
    private EntityManager entityManager;

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} if embedding is configured as enabled
     * ({@code TAXONOMY_EMBEDDING_ENABLED=true}, which is the default).
     */
    public boolean isEnabled() {
        return embeddingEnabled;
    }

    /**
     * Returns {@code true} if embedding is globally enabled AND the DJL model loaded
     * successfully (or has not been tried yet).
     */
    public boolean isAvailable() {
        return embeddingEnabled && !modelLoadFailed;
    }

    /**
     * Returns the effective DJL model URL: {@link #modelDir} if set (offline cache),
     * otherwise {@link #modelName}.
     */
    public String effectiveModelUrl() {
        return (modelDir != null && !modelDir.isBlank()) ? modelDir : modelName;
    }

    /** Returns the lazily loaded DJL model, downloading it on first call. */
    ZooModel<String, float[]> getModel() throws Exception {
        if (!embeddingEnabled) {
            throw new IllegalStateException("Embedding is disabled (TAXONOMY_EMBEDDING_ENABLED=false)");
        }
        if (modelLoadFailed) {
            throw new IllegalStateException("DJL model failed to load previously; embedding unavailable");
        }
        if (model == null) {
            synchronized (modelLock) {
                if (model == null) {
                    String url = effectiveModelUrl();
                    log.info("Loading all-MiniLM-L6-v2 via DJL / ONNX Runtime from {} …", url);
                    try {
                        Criteria.Builder<String, float[]> builder = Criteria.builder()
                                .optApplication(Application.NLP.TEXT_EMBEDDING)
                                .setTypes(String.class, float[].class)
                                .optModelUrls(url)
                                .optEngine("OnnxRuntime");
                        model = builder.build().loadModel();
                        log.info("all-MiniLM-L6-v2 loaded successfully.");
                    } catch (Exception e) {
                        modelLoadFailed = true;
                        log.error("Failed to load DJL model from '{}'; semantic search disabled. Error: {}",
                                url, e.getMessage());
                        throw e;
                    }
                }
            }
        }
        return model;
    }

    // ── Index status ──────────────────────────────────────────────────────────

    /**
     * Returns the number of nodes currently in the Hibernate Search index.
     * Used by the embedding-status endpoint.
     */
    @Transactional(readOnly = true)
    public int indexedNodeCount() {
        try {
            SearchSession session = Search.session(entityManager);
            return (int) session.search(TaxonomyNode.class)
                    .where(f -> f.matchAll())
                    .fetchTotalHitCount();
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the DJL embedding vector for {@code text}.
     *
     * @throws Exception if the model cannot be loaded or inference fails
     */
    public float[] embed(String text) throws Exception {
        try (Predictor<String, float[]> predictor = getModel().newPredictor()) {
            return predictor.predict(text);
        }
    }

    /**
     * Scores each taxonomy node against {@code businessText} using Hibernate Search
     * KNN vector query.  Used by {@link LlmService} when {@code LLM_PROVIDER=LOCAL_ONNX}.
     *
     * <p>Scores are derived from the Hibernate Search / Lucene KNN score (which uses
     * {@code (1 + cosineSimilarity) / 2}) and scaled to the 0–100 % range:
     * {@code percentage = clamp(round((2 * luceneScore - 1) * 100), 0, 100)}.
     *
     * <p>On any error all nodes receive score 0 and the exception is logged.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> scoreNodes(String businessText, List<TaxonomyNode> nodes) {
        Map<String, Integer> scores = new HashMap<>();
        for (TaxonomyNode node : nodes) {
            scores.put(node.getCode(), 0);
        }

        if (!isAvailable()) return scores;

        try {
            float[] queryVector = embed(businessText);
            List<String> nodeCodes = nodes.stream()
                    .map(TaxonomyNode::getCode).collect(Collectors.toList());

            SearchSession session = Search.session(entityManager);
            // Use score projection so we can map Lucene scores to percentages
            List<List<?>> hits = session.search(TaxonomyNode.class)
                    .select(f -> f.composite(f.entity(TaxonomyNode.class), f.score()))
                    .where(f -> f.knn(nodes.size())
                            .field("embedding")
                            .matching(queryVector)
                            .filter(f.terms().field("code").matchingAny(nodeCodes)))
                    .fetchHits(nodes.size());

            for (List<?> hit : hits) {
                TaxonomyNode node = (TaxonomyNode) hit.get(0);
                float luceneScore = (Float) hit.get(1);
                // Lucene COSINE KNN score = (1 + cosineSim) / 2 → cosineSim in [-1, 1]
                // Map cosineSim to percentage: cosineSim = 2 * luceneScore - 1
                int percentage = (int) Math.round((2.0 * luceneScore - 1.0) * 100.0);
                percentage = Math.max(0, Math.min(100, percentage));
                scores.put(node.getCode(), percentage);
            }

            log.info("LOCAL_ONNX scores: {}", scores);

        } catch (Exception e) {
            log.error("Error in KNN vector scoring; returning zero scores", e);
        }

        return scores;
    }

    /**
     * Semantic search across the full taxonomy index.
     * Returns up to {@code topK} taxonomy node DTOs ranked by cosine similarity to
     * {@code queryText}.  Returns an empty list when embedding is not available.
     *
     * @param queryText natural-language description (e.g. "secure voice communications")
     * @param topK      maximum number of results
     * @return ranked list of matching taxonomy nodes (flat DTOs, no children)
     */
    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> semanticSearch(String queryText, int topK) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            float[] queryVector = embed(queryText);
            SearchSession session = Search.session(entityManager);
            List<TaxonomyNode> hits = session.search(TaxonomyNode.class)
                    .where(f -> f.knn(topK).field("embedding").matching(queryVector))
                    .fetchHits(topK);
            return hits.stream().map(this::toFlatDto).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Semantic search failed for query '{}': {}", queryText, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Find taxonomy nodes semantically similar to a given node, identified by its code.
     * Uses the node's enriched text as the query.
     * Excludes the source node itself from the results.
     *
     * @param nodeCode code of the reference node (e.g. "BP.001")
     * @param topK     maximum number of similar nodes to return
     * @return ranked list of similar taxonomy node DTOs
     */
    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> findSimilarNodes(String nodeCode, int topK) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            TaxonomyNode node = entityManager.createQuery(
                            "SELECT n FROM TaxonomyNode n WHERE n.code = :code", TaxonomyNode.class)
                    .setParameter("code", nodeCode)
                    .getResultStream().findFirst().orElse(null);
            if (node == null) {
                log.warn("Node '{}' not found in database", nodeCode);
                return Collections.emptyList();
            }

            String nodeText = buildNodeText(node);
            float[] queryVector = embed(nodeText);

            SearchSession session = Search.session(entityManager);
            // Retrieve topK+1 so we can exclude the source node
            List<TaxonomyNode> hits = session.search(TaxonomyNode.class)
                    .where(f -> f.knn(topK + 1).field("embedding").matching(queryVector))
                    .fetchHits(topK + 1);

            return hits.stream()
                    .filter(n -> !nodeCode.equals(n.getCode()))
                    .limit(topK)
                    .map(this::toFlatDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("findSimilarNodes failed for node '{}': {}", nodeCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildNodeText(TaxonomyNode node) {
        StringBuilder sb = new StringBuilder(node.getNameEn() != null ? node.getNameEn() : "");
        if (node.getDescriptionEn() != null && !node.getDescriptionEn().isBlank()) {
            sb.append(". ").append(node.getDescriptionEn());
        }
        return sb.toString();
    }

    private TaxonomyNodeDto toFlatDto(TaxonomyNode node) {
        TaxonomyNodeDto dto = new TaxonomyNodeDto();
        dto.setId(node.getId());
        dto.setCode(node.getCode());
        dto.setUuid(node.getUuid());
        dto.setNameEn(node.getNameEn());
        dto.setNameDe(node.getNameDe());
        dto.setDescriptionEn(node.getDescriptionEn());
        dto.setDescriptionDe(node.getDescriptionDe());
        dto.setParentCode(node.getParentCode());
        dto.setTaxonomyRoot(node.getTaxonomyRoot());
        dto.setLevel(node.getLevel());
        dto.setDataset(node.getDataset());
        dto.setExternalId(node.getExternalId());
        dto.setSource(node.getSource());
        dto.setReference(node.getReference());
        dto.setSortOrder(node.getSortOrder());
        dto.setState(node.getState());
        return dto;
    }
}
