package com.nato.taxonomy.service;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local embedding service that scores taxonomy nodes against a business requirement using
 * the {@code sentence-transformers/all-MiniLM-L6-v2} ONNX model loaded via DJL.
 *
 * <h2>Architecture</h2>
 * <p>Follows the same pattern as {@link SearchService}:
 * <ul>
 *   <li>A {@link ByteBuffersDirectory} in-memory Lucene index stores one
 *       {@link KnnFloatVectorField} (dimension 384, cosine similarity) per taxonomy node.
 *       This mirrors how Hibernate Search uses {@code @VectorField} + {@code f.knn()} in the
 *       sandbox project (sandbox-jgit-storage-hibernate).</li>
 *   <li>The vector index and the DJL model are both <em>lazily initialised</em> on first use —
 *       application startup is not slowed down and no model is downloaded unless actually needed.</li>
 *   <li>On each scoring call only the <em>query</em> text is embedded; the node vectors are
 *       already in the index. A {@link KnnFloatVectorQuery} with a {@link BooleanQuery}
 *       pre-filter restricts the ANN search to the specific batch of nodes, matching
 *       Hibernate Search's {@code knn()} predicate filtering approach.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code JGIT_EMBEDDING_ENABLED} (default {@code true}) — set to {@code false} to
 *       disable all embedding and semantic search globally.</li>
 *   <li>{@code JGIT_EMBEDDING_MODEL_DIR} — path to a pre-downloaded model directory;
 *       empty = DJL auto-download to {@code ~/.djl.ai/}.</li>
 *   <li>{@code JGIT_EMBEDDING_MODEL_NAME} — DJL model URL;
 *       default {@code djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2}.</li>
 * </ul>
 *
 * <h2>Graceful degradation</h2>
 * <p>When {@code JGIT_EMBEDDING_ENABLED=false} or the model fails to load, all semantic
 * search methods return empty results without throwing, and {@link #isAvailable()} returns
 * {@code false}.
 *
 * <h2>Scoring</h2>
 * <p>Lucene's COSINE similarity function returns {@code (1 + cosineSim) / 2 ∈ [0, 1]}.
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

    /** Embedding dimension produced by all-MiniLM-L6-v2. */
    private static final int EMBEDDING_DIM = 384;

    /** Lucene document field names. */
    private static final String FIELD_CODE      = "code";
    private static final String FIELD_EMBEDDING = "embedding";

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

    // ── Lucene KNN vector index (lazy, built once from all taxonomy nodes) ────

    private volatile Directory vectorDirectory;
    private final Object indexLock = new Object();

    /** Flat node DTO cache for returning semantic search results without re-querying JPA. */
    private final Map<String, TaxonomyNodeDto> nodeCache = new ConcurrentHashMap<>();

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final TaxonomyNodeRepository nodeRepository;

    public LocalEmbeddingService(TaxonomyNodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} if embedding is configured as enabled
     * ({@code JGIT_EMBEDDING_ENABLED=true}, which is the default).
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
            throw new IllegalStateException("Embedding is disabled (JGIT_EMBEDDING_ENABLED=false)");
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

    // ── Vector index lifecycle ────────────────────────────────────────────────

    /**
     * Returns the lazily built Lucene {@link ByteBuffersDirectory} that holds one
     * {@link KnnFloatVectorField} per taxonomy node.
     *
     * <p>On first call this method loads all nodes from the database, embeds every
     * node's text with DJL, and writes the vectors into the in-memory index.
     * Subsequent calls return the cached directory instantly.
     */
    Directory getVectorDirectory() throws Exception {
        if (vectorDirectory == null) {
            synchronized (indexLock) {
                if (vectorDirectory == null) {
                    List<TaxonomyNode> all = nodeRepository.findAll();
                    log.info("Building KNN vector index for {} taxonomy nodes …", all.size());
                    vectorDirectory = buildVectorIndex(all);
                    log.info("KNN vector index built ({} documents).", all.size());
                }
            }
        }
        return vectorDirectory;
    }

    /**
     * Builds a {@link ByteBuffersDirectory} following the same pattern as
     * {@link SearchService#buildIndex}: one document per node, one
     * {@link KnnFloatVectorField} (COSINE, dim 384) storing the node's text embedding.
     * Also populates the flat DTO cache for result retrieval.
     */
    private Directory buildVectorIndex(List<TaxonomyNode> nodes) throws Exception {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig();
        try (IndexWriter writer = new IndexWriter(dir, config)) {
            for (TaxonomyNode node : nodes) {
                float[] embedding = embed(buildNodeText(node));
                Document doc = new Document();
                doc.add(new StringField(FIELD_CODE, node.getCode(), Field.Store.YES));
                doc.add(new KnnFloatVectorField(FIELD_EMBEDDING, embedding,
                        VectorSimilarityFunction.COSINE));
                writer.addDocument(doc);
                nodeCache.put(node.getCode(), toFlatDto(node));
            }
        }
        return dir;
    }

    /**
     * Invalidates the cached vector index so it will be rebuilt on the next scoring call.
     * Called by {@link TaxonomyService} after taxonomy data is reloaded.
     */
    public void invalidateVectorIndex() {
        synchronized (indexLock) {
            if (vectorDirectory != null) {
                try {
                    vectorDirectory.close();
                } catch (IOException e) {
                    log.warn("Failed to close KNN vector directory during invalidation", e);
                }
                vectorDirectory = null;
                nodeCache.clear();
                log.info("KNN vector index invalidated; will be rebuilt on next use.");
            }
        }
    }

    /**
     * Returns the number of nodes currently in the vector index, or 0 if not yet built.
     * Used by the embedding-status endpoint.
     */
    public int indexedNodeCount() {
        Directory dir;
        synchronized (indexLock) {
            dir = vectorDirectory;
        }
        if (dir == null) return 0;
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            return reader.numDocs();
        } catch (IOException e) {
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
     * Scores each taxonomy node against {@code businessText} using Lucene
     * {@link KnnFloatVectorQuery}.  Used by {@link LlmService} when
     * {@code LLM_PROVIDER=LOCAL_ONNX}.
     *
     * <p>On any error all nodes receive score 0 and the exception is logged.
     */
    public Map<String, Integer> scoreNodes(String businessText, List<TaxonomyNode> nodes) {
        Map<String, Integer> scores = new HashMap<>();
        for (TaxonomyNode node : nodes) {
            scores.put(node.getCode(), 0);
        }

        if (!isAvailable()) return scores;

        try {
            float[] queryVector = embed(businessText);

            BooleanQuery.Builder filterBuilder = new BooleanQuery.Builder();
            for (TaxonomyNode node : nodes) {
                filterBuilder.add(
                        new TermQuery(new Term(FIELD_CODE, node.getCode())),
                        BooleanClause.Occur.SHOULD);
            }
            Query nodeFilter = filterBuilder.build();

            KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery(
                    FIELD_EMBEDDING, queryVector, nodes.size(), nodeFilter);

            try (DirectoryReader reader = DirectoryReader.open(getVectorDirectory())) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(knnQuery, nodes.size());

                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    String code = doc.get(FIELD_CODE);
                    double cosineSim = 2.0 * sd.score - 1.0;
                    int score = cosineSim <= THRESHOLD
                            ? 0
                            : (int) Math.min(100,
                                    Math.round((cosineSim - THRESHOLD) / (1.0 - THRESHOLD) * 100));
                    scores.put(code, score);
                }
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
     * <p>Analogous to {@code GitDatabaseQueryService.semanticSearch()} in the sandbox project.
     *
     * @param queryText natural-language description (e.g. "secure voice communications")
     * @param topK      maximum number of results
     * @return ranked list of matching taxonomy nodes (flat DTOs, no children)
     */
    public List<TaxonomyNodeDto> semanticSearch(String queryText, int topK) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            float[] queryVector = embed(queryText);
            KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery(
                    FIELD_EMBEDDING, queryVector, topK);

            try (DirectoryReader reader = DirectoryReader.open(getVectorDirectory())) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(knnQuery, topK);

                List<TaxonomyNodeDto> results = new ArrayList<>();
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    String code = doc.get(FIELD_CODE);
                    TaxonomyNodeDto dto = nodeCache.get(code);
                    if (dto != null) results.add(dto);
                }
                return results;
            }
        } catch (Exception e) {
            log.error("Semantic search failed for query '{}': {}", queryText, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Find taxonomy nodes semantically similar to a given node, identified by its code.
     * Uses the pre-built vector of the source node as the query vector.
     * Excludes the source node itself from the results.
     *
     * <p>Analogous to {@code GitDatabaseQueryService.findSimilarCode()} in the sandbox project.
     *
     * @param nodeCode code of the reference node (e.g. "BP.001")
     * @param topK     maximum number of similar nodes to return
     * @return ranked list of similar taxonomy node DTOs
     */
    public List<TaxonomyNodeDto> findSimilarNodes(String nodeCode, int topK) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            // Embed the reference node's text (rather than fetching from the index)
            // to keep the implementation simple and avoid Lucene stored-vector API differences
            TaxonomyNodeDto dto = nodeCache.get(nodeCode);
            if (dto == null) {
                // Build index if not yet done
                getVectorDirectory();
                dto = nodeCache.get(nodeCode);
            }
            if (dto == null) {
                log.warn("Node '{}' not found in embedding cache", nodeCode);
                return Collections.emptyList();
            }

            String nodeText = buildNodeText(dto);
            float[] queryVector = embed(nodeText);

            // Retrieve topK+1 so we can exclude the source node itself
            KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery(
                    FIELD_EMBEDDING, queryVector, topK + 1);

            try (DirectoryReader reader = DirectoryReader.open(getVectorDirectory())) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(knnQuery, topK + 1);

                List<TaxonomyNodeDto> results = new ArrayList<>();
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    String code = doc.get(FIELD_CODE);
                    if (code.equals(nodeCode)) continue; // exclude self
                    TaxonomyNodeDto result = nodeCache.get(code);
                    if (result != null) {
                        results.add(result);
                        if (results.size() >= topK) break;
                    }
                }
                return results;
            }
        } catch (Exception e) {
            log.error("findSimilarNodes failed for node '{}': {}", nodeCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildNodeText(TaxonomyNode node) {
        StringBuilder sb = new StringBuilder(node.getName());
        if (node.getDescription() != null && !node.getDescription().isBlank()) {
            sb.append(". ").append(node.getDescription());
        }
        return sb.toString();
    }

    private String buildNodeText(TaxonomyNodeDto dto) {
        StringBuilder sb = new StringBuilder(dto.getName());
        if (dto.getDescriptionEn() != null && !dto.getDescriptionEn().isBlank()) {
            sb.append(". ").append(dto.getDescriptionEn());
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
