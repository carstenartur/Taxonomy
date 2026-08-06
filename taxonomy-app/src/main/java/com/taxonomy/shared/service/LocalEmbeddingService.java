package com.taxonomy.shared.service;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.search.NodeEmbeddingBinder;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Local embedding service that scores taxonomy nodes against a business requirement using
 * the {@code BAAI/bge-small-en-v1.5} ONNX model loaded via DJL.
 *
 * <h2>Architecture</h2>
 * <p>The DJL model is <em>lazily initialised</em> on first use. Application startup is not
 * slowed down, and no model is loaded or downloaded unless semantic embeddings were explicitly
 * enabled and the service is actually used.
 *
 * <p>Vector storage and KNN retrieval are handled by Hibernate Search (Lucene backend).
 * The {@code @VectorField(name = "embedding")} on {@link TaxonomyNode} (via
 * {@link NodeEmbeddingBinder}) stores the pre-computed embedding.
 * Queries use {@code f.knn(k).field("embedding").matching(queryVector)}.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code TAXONOMY_EMBEDDING_ENABLED} (default {@code false}) — must be set to
 *       {@code true} to enable embedding and semantic search.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_MODEL_DIR} — path to a pre-downloaded model directory;
 *       preferred for production and container deployments.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_MODEL_NAME} — HuggingFace model URL or local path;
 *       default {@code https://huggingface.co/BAAI/bge-small-en-v1.5}.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD} (default {@code false}) — must be set to
 *       {@code true} before a remote model may be downloaded at runtime.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <p>The service owns the lazily loaded {@link ZooModel}. Every predictor is closed after
 * one inference and the model is closed exactly once during Spring shutdown. A lifecycle
 * read/write lock prevents shutdown from closing native ONNX resources while an inference
 * is active.</p>
 *
 * <h2>Graceful degradation</h2>
 * <p>When embedding is disabled or the model fails to load, semantic search methods return
 * empty results without throwing, and {@link #isAvailable()} returns {@code false}.
 *
 * <h2>Scoring</h2>
 * <p>Hibernate Search's KNN query returns cosine similarity scores in [0, 1].
 * Raw cosine similarity is recovered as {@code 2 * luceneScore - 1} and mapped to 0–100.
 *
 * <p>Enable as the LLM provider with {@code LLM_PROVIDER=LOCAL_ONNX}. No API key required,
 * but semantic embeddings still require the explicit embedding opt-in.
 */
@Service
public class LocalEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);

    public static final String DEFAULT_MODEL_URL =
            "https://huggingface.co/BAAI/bge-small-en-v1.5";

    private static final String HF_RESOLVE_PATTERN = "%s/resolve/main/%s";

    private static final String[] HF_MODEL_FILES = {
            "onnx/model.onnx",
            "tokenizer.json"
    };

    static final String DEFAULT_QUERY_PREFIX =
            "Represent this sentence for searching relevant passages: ";

    static final double THRESHOLD = 0.25;

    @Value("${embedding.enabled:false}")
    private boolean embeddingEnabled;

    @Value("${embedding.model.dir:}")
    private String modelDir;

    @Value("${embedding.model.name:https://huggingface.co/BAAI/bge-small-en-v1.5}")
    private String modelName;

    @Value("${embedding.query.prefix:Represent this sentence for searching relevant passages: }")
    private String queryPrefix;

    @Value("${embedding.allow-download:false}")
    private boolean allowDownload;

    private volatile ZooModel<String, float[]> model;
    private volatile boolean modelLoadFailed;
    private volatile boolean closed;
    private final Object modelLock = new Object();
    private final ReentrantReadWriteLock modelLifecycleLock = new ReentrantReadWriteLock();

    @PersistenceContext
    private EntityManager entityManager;

    public boolean isEnabled() {
        return embeddingEnabled;
    }

    public boolean isAvailable() {
        return embeddingEnabled && !modelLoadFailed && !closed;
    }

    /** Returns whether an operator explicitly permitted remote runtime model downloads. */
    public boolean isDownloadAllowed() {
        return allowDownload;
    }

    /** Returns whether the native embedding model has already been initialised. */
    public boolean isModelLoaded() {
        return model != null && !closed;
    }

    /** Returns whether a mounted/local model directory was explicitly configured. */
    public boolean hasLocalModelConfigured() {
        return modelDir != null && !modelDir.isBlank();
    }

    public String effectiveModelUrl() {
        return modelDir != null && !modelDir.isBlank() ? modelDir : modelName;
    }

    /** Returns the lazily loaded DJL model, downloading it on first use when allowed. */
    ZooModel<String, float[]> getModel() throws Exception {
        if (closed) {
            throw new IllegalStateException("Embedding model service is shutting down");
        }
        if (!embeddingEnabled) {
            throw new IllegalStateException(
                    "Embedding is disabled (TAXONOMY_EMBEDDING_ENABLED=false)");
        }
        if (modelLoadFailed) {
            throw new IllegalStateException(
                    "DJL model failed to load previously; embedding unavailable");
        }
        if (model == null) {
            synchronized (modelLock) {
                if (closed) {
                    throw new IllegalStateException("Embedding model service is shutting down");
                }
                if (model == null) {
                    String url = effectiveModelUrl();
                    if (!allowDownload && (url.startsWith("http://")
                            || url.startsWith("https://")
                            || url.startsWith("djl://"))) {
                        modelLoadFailed = true;
                        log.error("Model download disabled (embedding.allow-download=false) "
                                + "and no local model found. Set TAXONOMY_EMBEDDING_MODEL_DIR.");
                        throw new IllegalStateException(
                                "No local model and download disabled "
                                        + "(TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false)");
                    }
                    log.info("Loading embedding model via DJL / ONNX Runtime from {} …", url);
                    try {
                        model = loadModel(url);
                        log.info("Embedding model loaded successfully.");
                    } catch (Exception | LinkageError primary) {
                        modelLoadFailed = true;
                        log.error("Failed to load embedding model from '{}'; "
                                        + "semantic search disabled. Error: {}",
                                url, primary.getMessage());
                        if (primary instanceof Exception exception) {
                            throw exception;
                        }
                        throw new Exception("Native library loading failed", primary);
                    }
                }
            }
        }
        return model;
    }

    private ZooModel<String, float[]> loadModel(String url) throws Exception {
        String localPath;

        if (url.startsWith("https://huggingface.co/")
                || url.startsWith("http://huggingface.co/")) {
            localPath = downloadHuggingFaceModel(url);
        } else if (url.startsWith("djl://")) {
            String modelId = url.replaceFirst("djl://[^/]+/", "");
            String hfUrl = "https://huggingface.co/" + modelId;
            log.warn("Migrating legacy djl:// URL to HuggingFace download: {} → {}", url, hfUrl);
            localPath = downloadHuggingFaceModel(hfUrl);
        } else if (url.startsWith("file:")) {
            try {
                localPath = java.nio.file.Paths.get(java.net.URI.create(url)).toString();
            } catch (IllegalArgumentException exception) {
                log.warn("Invalid file: URI '{}', falling back to raw path handling", url,
                        exception);
                localPath = url.replaceFirst("^file:(//)?", "");
            }
        } else {
            localPath = url;
        }

        ensureServingProperties(localPath);

        java.nio.file.Path modelPath = java.nio.file.Path.of(localPath);
        log.info("Loading DJL model from local path: {}", modelPath.toAbsolutePath());
        try {
            return Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelPath(modelPath)
                    .optModelName("model")
                    .optEngine("OnnxRuntime")
                    .optArgument("includeTokenTypes", true)
                    .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                    .build()
                    .loadModel();
        } catch (Exception exception) {
            log.error("DJL Criteria.loadModel() failed for path '{}': {}",
                    modelPath.toAbsolutePath(), exception.getMessage(), exception);
            throw exception;
        }
    }

    private String downloadHuggingFaceModel(String hfRepoUrl) throws Exception {
        String repoId = hfRepoUrl
                .replaceFirst("https?://huggingface\\.co/", "")
                .replaceAll("[/\\\\]", "--");
        java.nio.file.Path cacheDir = java.nio.file.Path.of(
                System.getProperty("user.home"), ".djl.ai", "cache", "taxonomy", repoId);
        java.nio.file.Files.createDirectories(cacheDir);

        String baseUrl = hfRepoUrl.endsWith("/")
                ? hfRepoUrl.substring(0, hfRepoUrl.length() - 1)
                : hfRepoUrl;

        for (String relPath : HF_MODEL_FILES) {
            String fileUrl = String.format(HF_RESOLVE_PATTERN, baseUrl, relPath);
            String localName = relPath.contains("/")
                    ? relPath.substring(relPath.lastIndexOf('/') + 1)
                    : relPath;
            java.nio.file.Path localFile = cacheDir.resolve(localName);

            if (java.nio.file.Files.exists(localFile)
                    && java.nio.file.Files.size(localFile) > 1024) {
                log.debug("Model file already cached: {}", localFile);
                continue;
            }
            if (!allowDownload) {
                throw new IllegalStateException(
                        "Model file missing and runtime download disabled: " + localName);
            }

            log.info("Downloading model file: {}", fileUrl);
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(fileUrl))
                    .GET()
                    .build();
            java.net.http.HttpResponse<java.nio.file.Path> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofFile(localFile));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                java.nio.file.Files.deleteIfExists(localFile);
                throw new IllegalStateException(
                        "Failed to download model file " + localName
                                + " (HTTP " + response.statusCode() + ")");
            }
        }
        return cacheDir.toString();
    }

    private void ensureServingProperties(String localPath) throws Exception {
        java.nio.file.Path servingProperties = java.nio.file.Path.of(localPath)
                .resolve("serving.properties");
        if (java.nio.file.Files.exists(servingProperties)) return;

        String properties = "engine=OnnxRuntime\n"
                + "option.modelName=model\n"
                + "option.mapLocation=true\n";
        java.nio.file.Files.writeString(servingProperties, properties);
    }

    /** Embed a document text. */
    public float[] embed(String text) throws Exception {
        return predict(text == null ? "" : text);
    }

    /** Embed a search query using the configured asymmetric-retrieval prefix. */
    public float[] embedQuery(String query) throws Exception {
        return predict((queryPrefix == null ? DEFAULT_QUERY_PREFIX : queryPrefix)
                + (query == null ? "" : query));
    }

    private float[] predict(String text) throws Exception {
        modelLifecycleLock.readLock().lock();
        try {
            ZooModel<String, float[]> currentModel = getModel();
            try (Predictor<String, float[]> predictor = currentModel.newPredictor()) {
                return predictor.predict(text);
            }
        } finally {
            modelLifecycleLock.readLock().unlock();
        }
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> semanticSearch(String query, int limit) {
        if (!isAvailable() || query == null || query.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        try {
            float[] queryVector = embedQuery(query);
            SearchSession searchSession = Search.session(entityManager);
            return searchSession.search(TaxonomyNode.class)
                    .where(f -> f.knn(limit)
                            .field("embedding")
                            .matching(queryVector))
                    .fetchHits(limit)
                    .stream()
                    .map(TaxonomyNodeDto::fromEntity)
                    .toList();
        } catch (Exception exception) {
            log.warn("Semantic search unavailable: {}", exception.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> findSimilarNodes(String code, int limit) {
        if (!isAvailable() || code == null || code.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        try {
            TaxonomyNode node = entityManager.find(TaxonomyNode.class, code);
            if (node == null) return Collections.emptyList();
            float[] vector = embed(NodeEmbeddingBinder.Bridge.buildEnrichedText(node));
            SearchSession searchSession = Search.session(entityManager);
            return searchSession.search(TaxonomyNode.class)
                    .where(f -> f.knn(limit + 1)
                            .field("embedding")
                            .matching(vector))
                    .fetchHits(limit + 1)
                    .stream()
                    .filter(candidate -> !code.equals(candidate.getCode()))
                    .limit(limit)
                    .map(TaxonomyNodeDto::fromEntity)
                    .toList();
        } catch (Exception exception) {
            log.warn("Similar-node search unavailable: {}", exception.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public int indexedNodeCount() {
        if (!embeddingEnabled) return 0;
        try {
            SearchSession searchSession = Search.session(entityManager);
            return Math.toIntExact(searchSession.search(TaxonomyNode.class)
                    .where(f -> f.exists().field("embedding"))
                    .fetchTotalHitCount());
        } catch (Exception exception) {
            log.debug("Cannot count indexed embedding nodes: {}", exception.getMessage());
            return 0;
        }
    }

    /** Scores taxonomy nodes against a business requirement using cosine similarity. */
    @Transactional(readOnly = true)
    public Map<String, LlmService.NodeRelevance> scoreNodes(
            String businessText, List<TaxonomyNode> nodes) {
        if (!isAvailable() || businessText == null || businessText.isBlank()
                || nodes == null || nodes.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            float[] queryVector = embedQuery(businessText);
            SearchSession searchSession = Search.session(entityManager);
            int requestedHits = Math.max(nodes.size(), 1);
            Map<String, TaxonomyNode> requestedByCode = nodes.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(node -> node.getCode() != null)
                    .collect(Collectors.toMap(TaxonomyNode::getCode, node -> node,
                            (left, right) -> left));
            if (requestedByCode.isEmpty()) return Collections.emptyMap();

            Map<String, Double> scoresByCode = searchSession.search(TaxonomyNode.class)
                    .select(f -> f.composite(
                            f.id(String.class),
                            f.score()))
                    .where(f -> f.bool(b -> {
                        b.must(f.knn(requestedHits)
                                .field("embedding")
                                .matching(queryVector));
                        b.filter(f.id().matchingAny(requestedByCode.keySet()));
                    }))
                    .fetchHits(requestedHits)
                    .stream()
                    .collect(Collectors.toMap(
                            hit -> hit.get(0),
                            hit -> ((Number) hit.get(1)).doubleValue(),
                            Math::max));

            Map<String, LlmService.NodeRelevance> result = new HashMap<>();
            for (TaxonomyNode node : nodes) {
                if (node == null || node.getCode() == null) continue;
                double luceneScore = scoresByCode.getOrDefault(node.getCode(), 0.0);
                double cosineSimilarity = Math.max(-1.0, Math.min(1.0,
                        (2.0 * luceneScore) - 1.0));
                int relevance = cosineSimilarity >= THRESHOLD
                        ? (int) Math.round(cosineSimilarity * 100.0)
                        : 0;
                result.put(node.getCode(), new LlmService.NodeRelevance(
                        relevance,
                        relevance > 0
                                ? "Local semantic similarity " + relevance + "%"
                                : "Below local semantic threshold"));
            }
            return result;
        } catch (Exception exception) {
            log.warn("Local embedding scoring unavailable: {}", exception.getMessage());
            return Collections.emptyMap();
        }
    }

    @PreDestroy
    public void close() {
        modelLifecycleLock.writeLock().lock();
        try {
            closed = true;
            ZooModel<String, float[]> currentModel = model;
            model = null;
            if (currentModel != null) {
                currentModel.close();
            }
        } finally {
            modelLifecycleLock.writeLock().unlock();
        }
    }
}
