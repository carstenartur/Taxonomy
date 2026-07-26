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
 * <p>The DJL model is <em>lazily initialised</em> on first use — application startup is not
 * slowed down and no model is downloaded unless actually needed.
 *
 * <p>Vector storage and KNN retrieval are handled by Hibernate Search (Lucene backend).
 * The {@code @VectorField(name = "embedding")} on {@link TaxonomyNode} (via
 * {@link NodeEmbeddingBinder}) stores the pre-computed embedding.
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
 *       {@code false} to prevent runtime model downloads. When disabled, a local
 *       model must be provided via {@code TAXONOMY_EMBEDDING_MODEL_DIR}.</li>
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
 * <p>Enable as the LLM provider with {@code LLM_PROVIDER=LOCAL_ONNX}. No API key required.
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
                    && java.nio.file.Files.size(localFile) > 0) {
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
                        request,
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Download interrupted for " + fileUrl, exception);
            }
            if (response.statusCode() != 200) {
                modelLoadFailed = true;
                throw new Exception("Failed to download " + fileUrl
                        + ": HTTP " + response.statusCode());
            }
            try (java.io.InputStream input = response.body()) {
                java.nio.file.Files.copy(
                        input,
                        localFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Downloaded {} ({} bytes)", localName,
                    java.nio.file.Files.size(localFile));
        }

        return cacheDir.toAbsolutePath().toString();
    }

    private static final String SERVING_PROPERTIES_CONTENT =
            "engine=OnnxRuntime\n"
                    + "option.modelName=model\n"
                    + "translatorFactory=ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory\n"
                    + "option.mapLocation=true\n"
                    + "option.includeTokenTypes=true\n";

    private void ensureServingProperties(String url) {
        try {
            String path = url.startsWith("file://")
                    ? url.substring("file://".length())
                    : url;
            java.nio.file.Path directory = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.isDirectory(directory)) {
                return;
            }
            java.nio.file.Path servingProperties = directory.resolve("serving.properties");

            if (java.nio.file.Files.exists(servingProperties)) {
                String existing = java.nio.file.Files.readString(servingProperties);
                if (existing.contains("engine=OnnxRuntime")
                        && existing.contains("TextEmbeddingTranslatorFactory")
                        && existing.contains("includeTokenTypes=true")) {
                    return;
                }
                log.warn("serving.properties exists but is missing required ONNX settings; "
                        + "regenerating");
            }

            boolean hasOnnx;
            try (var files = java.nio.file.Files.list(directory)) {
                hasOnnx = files.anyMatch(file -> file.getFileName().toString().endsWith(".onnx"));
            }
            if (!hasOnnx) {
                return;
            }
            java.nio.file.Files.writeString(servingProperties, SERVING_PROPERTIES_CONTENT);
            log.info("Auto-generated serving.properties in {}", directory);
        } catch (Exception exception) {
            log.warn("Could not auto-generate serving.properties: {}", exception.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public int indexedNodeCount() {
        try {
            SearchSession session = Search.session(entityManager);
            return (int) session.search(TaxonomyNode.class)
                    .where(factory -> factory.matchAll())
                    .fetchTotalHitCount();
        } catch (Exception exception) {
            return 0;
        }
    }

    public float[] embed(String text) throws Exception {
        var readLock = modelLifecycleLock.readLock();
        readLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Embedding model service is shutting down");
            }
            try (Predictor<String, float[]> predictor = getModel().newPredictor()) {
                return predictor.predict(text);
            }
        } finally {
            readLock.unlock();
        }
    }

    public float[] embedQuery(String text) throws Exception {
        String prefixed = queryPrefix != null && !queryPrefix.isEmpty()
                ? queryPrefix + text
                : text;
        return embed(prefixed);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> scoreNodes(String businessText, List<TaxonomyNode> nodes) {
        Map<String, Integer> scores = new HashMap<>();
        for (TaxonomyNode node : nodes) {
            scores.put(node.getCode(), 0);
        }

        if (!isAvailable()) {
            return scores;
        }

        try {
            float[] queryVector = embedQuery(businessText);
            List<String> nodeCodes = nodes.stream()
                    .map(TaxonomyNode::getCode)
                    .collect(Collectors.toList());

            SearchSession session = Search.session(entityManager);
            List<List<?>> hits = session.search(TaxonomyNode.class)
                    .select(factory -> factory.composite(
                            factory.entity(TaxonomyNode.class),
                            factory.score()))
                    .where(factory -> factory.knn(nodes.size())
                            .field("embedding")
                            .matching(queryVector)
                            .filter(factory.terms().field("code").matchingAny(nodeCodes)))
                    .fetchHits(nodes.size());

            for (List<?> hit : hits) {
                TaxonomyNode node = (TaxonomyNode) hit.get(0);
                float luceneScore = (Float) hit.get(1);
                int percentage = (int) Math.round((2.0 * luceneScore - 1.0) * 100.0);
                percentage = Math.max(0, Math.min(100, percentage));
                scores.put(node.getCode(), percentage);
            }

            log.info("LOCAL_ONNX scores: {}", scores);
        } catch (Exception exception) {
            log.error("Error in KNN vector scoring; returning zero scores", exception);
        }

        return scores;
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> semanticSearch(String queryText, int topK) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        try {
            float[] queryVector = embedQuery(queryText);
            SearchSession session = Search.session(entityManager);
            List<TaxonomyNode> hits = session.search(TaxonomyNode.class)
                    .where(factory -> factory.knn(topK)
                            .field("embedding")
                            .matching(queryVector))
                    .fetchHits(topK);
            return hits.stream()
                    .map(this::toFlatDto)
                    .collect(Collectors.toList());
        } catch (Exception exception) {
            log.error("Semantic search failed for query '{}': {}",
                    queryText, exception.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> findSimilarNodes(String nodeCode, int topK) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        try {
            TaxonomyNode node = entityManager.createQuery(
                            "SELECT n FROM TaxonomyNode n WHERE n.code = :code",
                            TaxonomyNode.class)
                    .setParameter("code", nodeCode)
                    .getResultStream()
                    .findFirst()
                    .orElse(null);
            if (node == null) {
                log.warn("Node '{}' not found in database", nodeCode);
                return Collections.emptyList();
            }

            float[] queryVector = embed(buildNodeText(node));
            SearchSession session = Search.session(entityManager);
            List<TaxonomyNode> hits = session.search(TaxonomyNode.class)
                    .where(factory -> factory.knn(topK + 1)
                            .field("embedding")
                            .matching(queryVector))
                    .fetchHits(topK + 1);

            return hits.stream()
                    .filter(candidate -> !nodeCode.equals(candidate.getCode()))
                    .limit(topK)
                    .map(this::toFlatDto)
                    .collect(Collectors.toList());
        } catch (Exception exception) {
            log.error("findSimilarNodes failed for node '{}': {}",
                    nodeCode, exception.getMessage());
            return Collections.emptyList();
        }
    }

    @PreDestroy
    void closeModel() {
        var writeLock = modelLifecycleLock.writeLock();
        writeLock.lock();
        try {
            synchronized (modelLock) {
                if (closed) {
                    return;
                }
                closed = true;
                ZooModel<String, float[]> currentModel = model;
                model = null;
                if (currentModel != null) {
                    try {
                        currentModel.close();
                        log.info("Closed DJL embedding model.");
                    } catch (RuntimeException exception) {
                        log.warn("Failed to close DJL embedding model cleanly", exception);
                    }
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    private String buildNodeText(TaxonomyNode node) {
        StringBuilder text = new StringBuilder(
                node.getNameEn() != null ? node.getNameEn() : "");
        if (node.getDescriptionEn() != null && !node.getDescriptionEn().isBlank()) {
            text.append(". ").append(node.getDescriptionEn());
        }
        return text.toString();
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
