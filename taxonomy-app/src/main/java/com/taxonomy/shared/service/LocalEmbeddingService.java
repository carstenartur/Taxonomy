package com.taxonomy.shared.service;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.catalog.model.TaxonomyNode;
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
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.search.NodeEmbeddingBinder;

/**
 * Local embedding service that scores taxonomy nodes against a business requirement using
 * the {@code BAAI/bge-small-en-v1.5} ONNX model loaded via DJL.
 *
 * <h2>Architecture</h2>
 * <p>The DJL model is <em>lazily initialised</em> on first use — application startup is not
 * slowed down and no model is downloaded unless actually needed.
 *
 * <p>Vector storage and KNN retrieval are handled by Hibernate Search (Lucene backend).
 * The {@code @VectorField(name = "embedding")} on {@link TaxonomyNode} (via
 * {@link com.taxonomy.search.NodeEmbeddingBinder}) stores the pre-computed embedding.
 * Queries use {@code f.knn(k).field("embedding").matching(queryVector)}.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code TAXONOMY_EMBEDDING_ENABLED} (default {@code true}) — set to {@code false} to
 *       disable all embedding and semantic search globally.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_MODEL_DIR} — path to a pre-downloaded model directory;
 *       empty = auto-download from HuggingFace into {@code ~/.djl.ai/cache/taxonomy/}.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_MODEL_NAME} — HuggingFace model URL or local path;
 *       default {@code https://huggingface.co/BAAI/bge-small-en-v1.5}.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD} (default {@code true}) — set to
 *       {@code false} to prevent runtime model downloads (CI mode).  When disabled, a local
 *       model must be provided via {@code TAXONOMY_EMBEDDING_MODEL_DIR}.</li>
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

    /**
     * Default HuggingFace model identifier for downloading the ONNX export.
     *
     * <p><strong>Note:</strong> The previous default {@code djl://ai.djl.huggingface/BAAI/bge-small-en-v1.5}
     * never worked because DJL's {@code HfModelZoo} registers with GROUP_ID
     * {@code ai.djl.huggingface.pytorch} and only supports the PyTorch engine —
     * not OnnxRuntime. We now download the ONNX model files directly from
     * HuggingFace into a local cache directory.
     */
    public static final String DEFAULT_MODEL_URL =
            "https://huggingface.co/BAAI/bge-small-en-v1.5";

    /** Base URL pattern for resolving individual files from a HuggingFace model repo. */
    private static final String HF_RESOLVE_PATTERN = "%s/resolve/main/%s";

    /** Files to download from HuggingFace for a working ONNX model directory. */
    private static final String[] HF_MODEL_FILES = {
            "onnx/model.onnx",
            "tokenizer.json"
    };

    /**
     * Default query prefix for asymmetric retrieval with BGE models.
     * Prepended to query texts (but not document texts) so the model produces
     * retrieval-oriented embeddings that work better for search and scoring.
     */
    static final String DEFAULT_QUERY_PREFIX =
            "Represent this sentence for searching relevant passages: ";

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

    @Value("${embedding.model.name:https://huggingface.co/BAAI/bge-small-en-v1.5}")
    private String modelName;

    @Value("${embedding.query.prefix:Represent this sentence for searching relevant passages: }")
    private String queryPrefix;

    @Value("${embedding.allow-download:true}")
    private boolean allowDownload;

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
                    // When downloads are disabled, only local paths are allowed
                    if (!allowDownload && (url.startsWith("http://") || url.startsWith("https://")
                            || url.startsWith("djl://"))) {
                        modelLoadFailed = true;
                        log.error("Model download disabled (embedding.allow-download=false) "
                                + "and no local model found. Set TAXONOMY_EMBEDDING_MODEL_DIR.");
                        throw new IllegalStateException(
                                "No local model and download disabled (TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false)");
                    }
                    log.info("Loading embedding model via DJL / ONNX Runtime from {} …", url);
                    try {
                        model = loadModel(url);
                        log.info("Embedding model loaded successfully.");
                    } catch (Exception | LinkageError primary) {
                        // LinkageError covers UnsatisfiedLinkError / NoClassDefFoundError
                        // from native DJL / ONNX Runtime library loading failures.
                        modelLoadFailed = true;
                        log.error("Failed to load embedding model from '{}'; semantic search disabled. Error: {}",
                                url, primary.getMessage());
                        if (primary instanceof Exception ex) throw ex;
                        throw new Exception("Native library loading failed", primary);
                    }
                }
            }
        }
        return model;
    }

    /**
     * Loads a DJL ONNX model from the given URL or local path.
     *
     * <p>Supported URL schemes:
     * <ul>
     *   <li>{@code https://huggingface.co/{org}/{model}} — downloads {@code onnx/model.onnx}
     *       and {@code tokenizer.json} to a local cache directory, generates
     *       {@code serving.properties}, and loads from there.</li>
     *   <li>Local directory path — loads directly (auto-generates {@code serving.properties}
     *       if missing).</li>
     *   <li>{@code file://} prefix — same as local directory.</li>
     * </ul>
     */
    private ZooModel<String, float[]> loadModel(String url) throws Exception {
        String localPath;

        if (url.startsWith("https://huggingface.co/") || url.startsWith("http://huggingface.co/")) {
            localPath = downloadHuggingFaceModel(url);
        } else if (url.startsWith("djl://")) {
            // Legacy djl:// URLs never worked with OnnxRuntime — extract the model ID
            // and try to download from HuggingFace instead.
            // djl://ai.djl.huggingface/BAAI/bge-small-en-v1.5 → BAAI/bge-small-en-v1.5
            String modelId = url.replaceFirst("djl://[^/]+/", "");
            String hfUrl = "https://huggingface.co/" + modelId;
            log.warn("Migrating legacy djl:// URL to HuggingFace download: {} → {}", url, hfUrl);
            localPath = downloadHuggingFaceModel(hfUrl);
        } else {
            // Local path or file: URI
            if (url.startsWith("file:")) {
                try {
                    // Use URI + Paths to correctly handle file:/, file:///, and Windows drive letters
                    localPath = java.nio.file.Paths.get(java.net.URI.create(url)).toString();
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid file: URI '{}', falling back to raw path handling", url, e);
                    localPath = url.replaceFirst("^file:(//)?", "");
                }
            } else {
                localPath = url;
            }
        }

        ensureServingProperties(localPath);

        // Convert local path to a proper file: URI (handles Windows drive letters + backslashes)
        String fileUrl = java.nio.file.Path.of(localPath).toUri().toString();
        log.info("Loading DJL model from local path: {} (URI: {})", localPath, fileUrl);
        try {
            return Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls(fileUrl)
                    .optEngine("OnnxRuntime")
                    .build().loadModel();
        } catch (Exception e) {
            log.error("DJL Criteria.loadModel() failed for URI '{}': {}", fileUrl, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Downloads the ONNX model files from a HuggingFace repository URL into a local
     * cache directory under {@code ~/.djl.ai/cache/taxonomy/}.
     *
     * <p>Downloads are skipped if the files already exist locally (idempotent).
     *
     * @param hfRepoUrl e.g. {@code https://huggingface.co/BAAI/bge-small-en-v1.5}
     * @return absolute path to the local model directory
     */
    private String downloadHuggingFaceModel(String hfRepoUrl) throws Exception {
        // Derive a cache directory name from the repo URL
        // https://huggingface.co/BAAI/bge-small-en-v1.5 → BAAI--bge-small-en-v1.5
        String repoId = hfRepoUrl
                .replaceFirst("https?://huggingface\\.co/", "")
                .replaceAll("[/\\\\]", "--");
        java.nio.file.Path cacheDir = java.nio.file.Path.of(
                System.getProperty("user.home"), ".djl.ai", "cache", "taxonomy", repoId);
        java.nio.file.Files.createDirectories(cacheDir);

        String baseUrl = hfRepoUrl.endsWith("/")
                ? hfRepoUrl.substring(0, hfRepoUrl.length() - 1) : hfRepoUrl;

        for (String relPath : HF_MODEL_FILES) {
            String fileUrl = String.format(HF_RESOLVE_PATTERN, baseUrl, relPath);
            // Flatten onnx/model.onnx → model.onnx in local cache
            String localName = relPath.contains("/")
                    ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
            java.nio.file.Path localFile = cacheDir.resolve(localName);

            if (java.nio.file.Files.exists(localFile) && java.nio.file.Files.size(localFile) > 0) {
                log.debug("Model file already cached: {}", localFile);
                continue;
            }

            log.info("Downloading {} → {}", fileUrl, localFile);
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(fileUrl))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .GET()
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response;
            try {
                response = httpClient.send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Download interrupted for " + fileUrl, ie);
            }
            if (response.statusCode() != 200) {
                modelLoadFailed = true;
                throw new Exception("Failed to download " + fileUrl
                        + ": HTTP " + response.statusCode());
            }
            try (java.io.InputStream in = response.body()) {
                java.nio.file.Files.copy(in, localFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Downloaded {} ({} bytes)", localName, java.nio.file.Files.size(localFile));
        }

        return cacheDir.toAbsolutePath().toString();
    }

    /**
     * If {@code url} points to a local directory that contains an ONNX model file but no
     * valid {@code serving.properties}, this method generates a minimal one so that DJL can
     * discover the model.  Also detects and repairs malformed files (e.g. with leading
     * whitespace from YAML heredoc indentation in CI workflows).
     */
    private static final String SERVING_PROPERTIES_CONTENT =
            "engine=OnnxRuntime\n"
            + "option.modelName=model\n"
            + "translatorFactory=ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory\n"
            + "option.mapLocation=true\n";

    private void ensureServingProperties(String url) {
        try {
            String path = url.startsWith("file://") ? url.substring("file://".length()) : url;
            java.nio.file.Path dir = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.isDirectory(dir)) return;
            java.nio.file.Path servingProps = dir.resolve("serving.properties");

            // Check if existing file has valid content (correct engine + translator factory)
            if (java.nio.file.Files.exists(servingProps)) {
                String existing = java.nio.file.Files.readString(servingProps);
                if (existing.contains("engine=OnnxRuntime")
                        && existing.contains("TextEmbeddingTranslatorFactory")) {
                    return; // file is valid
                }
                log.warn("serving.properties exists but is missing OnnxRuntime engine or "
                        + "TextEmbeddingTranslatorFactory; regenerating");
                // fall through to regeneration
            }

            // Only generate if there is actually an ONNX model file present
            boolean hasOnnx;
            try (var files = java.nio.file.Files.list(dir)) {
                hasOnnx = files.anyMatch(p -> p.getFileName().toString().endsWith(".onnx"));
            }
            if (!hasOnnx) return;
            java.nio.file.Files.writeString(servingProps, SERVING_PROPERTIES_CONTENT);
            log.info("Auto-generated serving.properties in {}", dir);
        } catch (Exception e) {
            log.warn("Could not auto-generate serving.properties: {}", e.getMessage());
        }
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
     * Returns the DJL embedding vector for {@code text} (document embedding, no prefix).
     * Used for indexing taxonomy nodes — call {@link #embedQuery(String)} for search queries.
     *
     * @throws Exception if the model cannot be loaded or inference fails
     */
    public float[] embed(String text) throws Exception {
        try (Predictor<String, float[]> predictor = getModel().newPredictor()) {
            return predictor.predict(text);
        }
    }

    /**
     * Returns the DJL embedding vector for a <em>query</em> text, prepending the
     * configured query prefix ({@code embedding.query.prefix}) for asymmetric retrieval.
     * BGE models produce better search results when the query is prefixed.
     *
     * @throws Exception if the model cannot be loaded or inference fails
     */
    public float[] embedQuery(String text) throws Exception {
        String prefixed = (queryPrefix != null && !queryPrefix.isEmpty())
                ? queryPrefix + text : text;
        return embed(prefixed);
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
            float[] queryVector = embedQuery(businessText);
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
            float[] queryVector = embedQuery(queryText);
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
