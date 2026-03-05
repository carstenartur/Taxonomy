package com.nato.taxonomy.service;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

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
 *       application startup is not slowed down and no model is downloaded unless
 *       {@code LLM_PROVIDER=LOCAL_ONNX} is actually exercised.</li>
 *   <li>On each scoring call only the <em>query</em> text is embedded; the node vectors are
 *       already in the index.  A {@link KnnFloatVectorQuery} with a {@link BooleanQuery}
 *       pre-filter restricts the ANN search to the specific batch of nodes passed in,
 *       matching how Hibernate Search filters KNN results via its {@code knn()} predicate.</li>
 * </ul>
 *
 * <h2>Scoring</h2>
 * <p>Lucene's COSINE similarity function returns {@code (1 + cosineSim) / 2 ∈ [0, 1]}.
 * Raw cosine similarity is recovered as {@code 2 * luceneScore - 1} and then mapped to
 * a 0–100 integer using the configurable {@link #THRESHOLD}.
 *
 * <p>Enable with {@code LLM_PROVIDER=LOCAL_ONNX}.  No API key is required.
 */
@Service
public class LocalEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);

    /** DJL model-zoo URL for the ONNX export of all-MiniLM-L6-v2. */
    static final String MODEL_URL =
            "djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2";

    /** Embedding dimension produced by all-MiniLM-L6-v2. */
    private static final int EMBEDDING_DIM = 384;

    /** Lucene document field names. */
    private static final String FIELD_CODE      = "code";
    private static final String FIELD_EMBEDDING = "embedding";

    /**
     * Raw cosine-similarity threshold below which a node receives score 0.
     * Value in (-1, 1); default 0.25 means "weak or no semantic overlap".
     */
    static final double THRESHOLD = 0.25;

    // ── DJL model (lazy) ──────────────────────────────────────────────────────

    private volatile ZooModel<String, float[]> model;
    private final Object modelLock = new Object();

    // ── Lucene KNN vector index (lazy, built once from all taxonomy nodes) ────

    private volatile Directory vectorDirectory;
    private final Object indexLock = new Object();

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final TaxonomyNodeRepository nodeRepository;

    public LocalEmbeddingService(TaxonomyNodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /** Returns the lazily loaded DJL model, downloading it on first call. */
    ZooModel<String, float[]> getModel() throws Exception {
        if (model == null) {
            synchronized (modelLock) {
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

    // ── Vector index lifecycle ────────────────────────────────────────────────

    /**
     * Returns the lazily built Lucene {@link ByteBuffersDirectory} that holds one
     * {@link KnnFloatVectorField} per taxonomy node.
     *
     * <p>On first call this method loads all nodes from the database, embeds every
     * node's text with DJL, and writes the vectors into the in-memory index.
     * Subsequent calls return the cached directory instantly.
     */
    private Directory getVectorDirectory() throws Exception {
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
     */
    private Directory buildVectorIndex(List<TaxonomyNode> nodes) throws Exception {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(); // no text analyser needed
        try (IndexWriter writer = new IndexWriter(dir, config)) {
            for (TaxonomyNode node : nodes) {
                float[] embedding = embed(buildNodeText(node));
                Document doc = new Document();
                doc.add(new StringField(FIELD_CODE, node.getCode(), Field.Store.YES));
                doc.add(new KnnFloatVectorField(FIELD_EMBEDDING, embedding,
                        VectorSimilarityFunction.COSINE));
                writer.addDocument(doc);
            }
        }
        return dir;
    }

    /**
     * Invalidates the cached vector index so it will be rebuilt on the next scoring call.
     * Called by {@link com.nato.taxonomy.service.TaxonomyService} after taxonomy data is reloaded.
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
                log.info("KNN vector index invalidated; will be rebuilt on next use.");
            }
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
     * {@link KnnFloatVectorQuery} — the same vector-search primitive that
     * Hibernate Search uses for its {@code @VectorField} / {@code f.knn()} predicate.
     *
     * <p><b>Flow:</b>
     * <ol>
     *   <li>Embed only the {@code businessText} (one DJL inference call).</li>
     *   <li>Build a {@link BooleanQuery} filter restricted to the requested node codes.</li>
     *   <li>Execute a {@link KnnFloatVectorQuery} against the pre-built index — fast
     *       HNSW approximate nearest-neighbour search.</li>
     *   <li>Convert Lucene COSINE scores back to raw cosine similarity and map to 0–100.</li>
     * </ol>
     *
     * <p>On any error all nodes receive score 0 and the exception is logged.
     *
     * @param businessText the user's business requirement text
     * @param nodes        the taxonomy nodes to score (typically one batch/level)
     * @return map of node code → integer score in [0, 100]
     */
    public Map<String, Integer> scoreNodes(String businessText, List<TaxonomyNode> nodes) {
        // Initialise result map with 0 for every requested node
        Map<String, Integer> scores = new HashMap<>();
        for (TaxonomyNode node : nodes) {
            scores.put(node.getCode(), 0);
        }

        try {
            // 1. Embed the query (single DJL call)
            float[] queryVector = embed(businessText);

            // 2. Build a filter for exactly the requested nodes
            //    (same idea as Hibernate Search's must() filter in GitDatabaseQueryService)
            BooleanQuery.Builder filterBuilder = new BooleanQuery.Builder();
            for (TaxonomyNode node : nodes) {
                filterBuilder.add(
                        new TermQuery(new Term(FIELD_CODE, node.getCode())),
                        BooleanClause.Occur.SHOULD);
            }
            Query nodeFilter = filterBuilder.build();

            // 3. KNN search limited to the matching nodes
            KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery(
                    FIELD_EMBEDDING, queryVector, nodes.size(), nodeFilter);

            // 4. Open the (lazily built) vector index and execute the query
            try (DirectoryReader reader = DirectoryReader.open(getVectorDirectory())) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(knnQuery, nodes.size());

                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    String code = doc.get(FIELD_CODE);

                    // Lucene COSINE score = (1 + cosineSim) / 2  →  cosineSim = 2*score - 1
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
