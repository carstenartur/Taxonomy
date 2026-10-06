package com.taxonomy.catalog.service;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import com.taxonomy.catalog.snapshot.FrozenCatalogueContext;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.search.NodeEmbeddingBinder;
import com.taxonomy.error.SearchUnavailableException;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Local embedding service that scores taxonomy nodes against a business requirement using
 * an explicitly selected 384-dimensional ONNX model loaded via DJL. The default
 * multilingual MiniLM contract supports German requirements against the English catalogue;
 * {@code BGE_SMALL_EN} retains the English-only BGE inference contract.
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
 *       empty = the selected profile's pinned upstream export.</li>
 *   <li>{@code TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD} (default {@code false}) — set to
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
 * <p>When embedding is disabled or the model fails to load, {@link #isAvailable()}
 * returns {@code false}. Search methods report unavailable execution explicitly;
 * an empty result represents a completed search only.
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
            "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2";

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

    @Value("${embedding.model.name:}")
    private String modelName;

    @Value("${embedding.model.profile:MULTILINGUAL_MINILM_L12}")
    private EmbeddingModelProfile modelProfile = EmbeddingModelProfile.MULTILINGUAL_MINILM_L12;

    @Value("${embedding.query.prefix:${TAXONOMY_EMBEDDING_QUERY_PREFIX:#{null}}}")
    private String queryPrefix;

    @Value("${embedding.allow-download:false}")
    private boolean allowDownload;

    private volatile ZooModel<String, float[]> model;
    private volatile EmbeddingModelIdentity loadedModelIdentity;
    private volatile boolean modelLoadFailed;
    private volatile boolean closed;
    private final Object modelLock = new Object();
    private final ReentrantReadWriteLock modelLifecycleLock = new ReentrantReadWriteLock();
    private final FrozenEmbeddingCache frozenCache = new FrozenEmbeddingCache();
    // Bound native activation memory even when index loading and requests overlap.
    private final java.util.concurrent.Semaphore inferenceSlots = new java.util.concurrent.Semaphore(2, true);

    @Value("${embedding.frozen.cache.max-vectors:8192}")
    private int frozenCacheMaxVectors = 8192;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private CatalogueRuntimePolicy catalogueRuntimePolicy = CatalogueRuntimePolicy.fullCatalogue();

    public boolean isEnabled() {
        return embeddingEnabled;
    }

    public boolean isAvailable() {
        return embeddingEnabled && !modelLoadFailed && !closed;
    }

    public String effectiveModelUrl() {
        return modelDir != null && !modelDir.isBlank() ? modelDir
                : modelName != null && !modelName.isBlank() ? modelName : modelProfile.modelUrl();
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
                    log.info("Loading embedding model via DJL / ONNX Runtime (profile={})", modelProfile);
                    try {
                        model = loadModel(url);
                        log.info("Embedding model loaded successfully.");
                    } catch (Exception | LinkageError primary) {
                        modelLoadFailed = true;
                        log.error("Embedding model load failed; semantic search disabled (code=MODEL_LOAD_FAILED)");
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
            log.warn("Migrating legacy DJL model location to HuggingFace download");
            localPath = downloadHuggingFaceModel(hfUrl);
        } else if (url.startsWith("file:")) {
            try {
                localPath = java.nio.file.Paths.get(java.net.URI.create(url)).toString();
            } catch (IllegalArgumentException exception) {
                log.warn("Invalid model file URI; trying raw path handling (code=MODEL_FILE_URI_INVALID)");
                localPath = url.replaceFirst("^file:(//)?", "");
            }
        } else {
            localPath = url;
        }

        ensureServingProperties(localPath);

        java.nio.file.Path modelPath = java.nio.file.Path.of(localPath);
        log.info("Loading local embedding model artifacts (profile={})", modelProfile);
        try {
            EmbeddingModelIdentity before = EmbeddingModelIdentity.capture(modelPath, effectiveQueryPrefix(), modelProfile);
            for (EmbeddingModelProfile knownProfile : EmbeddingModelProfile.values()) {
                if (knownProfile != modelProfile && knownProfile.modelSha256().equals(before.modelSha256())) {
                    throw new IllegalStateException("Embedding weights belong to " + knownProfile.name()
                            + " but the selected profile is " + modelProfile.name()
                            + ". Set TAXONOMY_EMBEDDING_MODEL_PROFILE=" + knownProfile.name()
                            + " or re-provision the selected profile's model bundle.");
                }
            }
            ZooModel<String, float[]> loaded = modelCriteria(modelPath, modelProfile).loadModel();
            try {
                if (!before.equals(EmbeddingModelIdentity.capture(modelPath, effectiveQueryPrefix(), modelProfile))) {
                    throw new IllegalStateException("Embedding artifacts changed while the model was loading");
                }
                loadedModelIdentity = new EmbeddingModelIdentity(before.modelSha256(), before.tokenizerSha256(),
                        before.configurationSha256(), before.queryPrefix(), before.inferenceVersion()
                        + ":runtime-" + loaded.getNDManager().getEngine().getVersion());
                return loaded;
            } catch (Exception failure) {
                loaded.close();
                throw failure;
            }
        } catch (Exception exception) {
            log.error("Local embedding artifacts could not be loaded (code=MODEL_ARTIFACT_LOAD_FAILED)");
            throw exception;
        }
    }

    /**
     * Shared by queries, index bridges and frozen workers. Model-specific pooling
     * and trained token limits are explicit; selecting a file never changes them.
     * Both profiles produce unit-normalized vectors of 384 dimensions.
     */
    static Criteria<String, float[]> modelCriteria(java.nio.file.Path modelPath) {
        return modelCriteria(modelPath, EmbeddingModelProfile.MULTILINGUAL_MINILM_L12);
    }

    static Criteria<String, float[]> modelCriteria(java.nio.file.Path modelPath, EmbeddingModelProfile profile) {
        return Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(modelPath)
                .optModelName("model")
                .optEngine("OnnxRuntime")
                .optArgument("includeTokenTypes", true)
                .optArgument("pooling", profile.pooling())
                .optArgument("maxLength", profile.maxTokens())
                .optArgument("truncation", true)
                .optArgument("normalize", true)
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                .build();
    }

    private String downloadHuggingFaceModel(String hfRepoUrl) throws Exception {
        String baseUrl = hfRepoUrl.endsWith("/")
                ? hfRepoUrl.substring(0, hfRepoUrl.length() - 1) : hfRepoUrl;
        boolean pinnedProfile = baseUrl.equals(modelProfile.modelUrl());
        String repoId = baseUrl.replaceFirst("https?://huggingface\\.co/", "")
                .replaceAll("[/\\\\]", "--");
        java.nio.file.Path cacheDir = java.nio.file.Path.of(
                System.getProperty("user.home"), ".djl.ai", "cache", "taxonomy", repoId);
        if (pinnedProfile) {
            cacheDir = cacheDir.resolve(modelProfile.name()).resolve(modelProfile.revision());
        }
        Map<String, String> pinnedDigests = pinnedProfile ? modelProfile.fileSha256() : Map.of();
        List<String> localNames = pinnedProfile ? List.of("model.onnx", "tokenizer.json",
                "tokenizer_config.json", "special_tokens_map.json", "config.json")
                : List.of("model.onnx", "tokenizer.json");

        // Reject corrupt existing pinned files before any network request. Missing files may be
        // fetched once, but a checksum failure must never trigger a blind retry or cache fallback.
        boolean complete = true;
        for (String name : localNames) {
            java.nio.file.Path file = cacheDir.resolve(name);
            if (java.nio.file.Files.exists(file) && pinnedProfile) {
                verifyPinnedFile(file, pinnedDigests.get(name));
            }
            if (!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) == 0) {
                complete = false;
            }
        }
        if (complete) return cacheDir.toAbsolutePath().toString();

        java.nio.file.Files.createDirectories(cacheDir.getParent());
        java.nio.file.Path temporary = java.nio.file.Files.createTempDirectory(
                cacheDir.getParent(), cacheDir.getFileName() + ".download.");
        try {
            for (String name : localNames) {
                java.nio.file.Path existing = cacheDir.resolve(name);
                java.nio.file.Path target = temporary.resolve(name);
                if (java.nio.file.Files.isRegularFile(existing) && java.nio.file.Files.size(existing) > 0) {
                    java.nio.file.Files.copy(existing, target);
                    continue;
                }
                String remotePath = name.equals("model.onnx")
                        ? pinnedProfile ? modelProfile.modelFile() : HF_MODEL_FILES[0] : name;
                String fileUrl = pinnedProfile
                        ? baseUrl + "/resolve/" + modelProfile.revision() + "/" + remotePath
                        : String.format(HF_RESOLVE_PATTERN, baseUrl, remotePath);
                downloadModelFile(fileUrl, target);
            }
            if (pinnedProfile) {
                for (String name : localNames) verifyPinnedFile(temporary.resolve(name), pinnedDigests.get(name));
            }
            java.nio.file.Files.createDirectories(cacheDir);
            for (String name : localNames) {
                java.nio.file.Files.move(temporary.resolve(name), cacheDir.resolve(name),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return cacheDir.toAbsolutePath().toString();
        } finally {
            try (var paths = java.nio.file.Files.walk(temporary)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void verifyPinnedFile(java.nio.file.Path file, String expected) throws java.io.IOException {
        if (!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) == 0) {
            throw new java.io.IOException("Pinned embedding artifact " + file.getFileName()
                    + " is missing or empty; remove the invalid cache and re-provision the pinned bundle.");
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = java.nio.file.Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(expected)) {
                throw new java.io.IOException("Pinned embedding artifact " + file.getFileName()
                        + " failed SHA-256 verification; remove the invalid cache and re-provision the pinned bundle.");
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void downloadModelFile(String fileUrl, java.nio.file.Path target) throws Exception {
        log.info("Downloading embedding model artifact");
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(fileUrl)).timeout(java.time.Duration.ofMinutes(5)).GET().build();
        java.net.http.HttpResponse<java.io.InputStream> response;
        try {
            response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Download interrupted for " + fileUrl, exception);
        }
        try (java.io.InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                modelLoadFailed = true;
                throw new java.io.IOException("Failed to download " + fileUrl + ": HTTP " + response.statusCode());
            }
            java.nio.file.Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
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
            log.info("Generated local embedding serving.properties");
        } catch (Exception exception) {
            log.warn("Could not generate embedding serving.properties (code=MODEL_SERVING_CONFIG_FAILED)");
        }
    }

    @Transactional(readOnly = true)
    public int indexedNodeCount() {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
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
        inferenceSlots.acquire();
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
            inferenceSlots.release();
        }
    }

    public float[] embedQuery(String text) throws Exception {
        return embed(effectiveQueryPrefix() + text);
    }

    /** Document inference follows the same explicit contract in the index and frozen workers. */
    public float[] embedDocument(String text) throws Exception {
        return embed(modelProfile.documentPrefix() + text);
    }

    public EmbeddingModelProfile modelProfile() { return modelProfile; }

    /** Safe configured profile label; custom paths and URLs are never exposed as a model identifier. */
    public String configuredModelId() {
        return modelProfile == EmbeddingModelProfile.BGE_SMALL_EN ? "bge-small-en-v1.5"
                : modelProfile.modelUrl().substring("https://huggingface.co/".length());
    }

    public String effectiveQueryPrefix() {
        return queryPrefix == null ? modelProfile.queryPrefix() : queryPrefix;
    }

    public String embeddingIndexKey() throws Exception { return embeddingIdentity().indexKey(); }

    /** Exact loaded artifact identity. Does not confuse a configured URL with the bytes used for inference. */
    public EmbeddingModelIdentity embeddingIdentity() throws Exception {
        var readLock = modelLifecycleLock.readLock();
        readLock.lock();
        try {
            getModel();
            if (loadedModelIdentity == null) throw new IllegalStateException("Loaded embedding model identity is unavailable");
            return new EmbeddingModelIdentity(loadedModelIdentity.modelSha256(), loadedModelIdentity.tokenizerSha256(),
                    loadedModelIdentity.configurationSha256(), effectiveQueryPrefix(),
                    loadedModelIdentity.inferenceVersion());
        } finally {
            readLock.unlock();
        }
    }

    /** Preflight all bound roots before traversal, so incompatible or absent evidence fails the durable task. */
    public void validateFrozenModel() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Frozen embedding validation requires no database transaction");
        }
        var catalogue = FrozenCatalogueContext.current();
        if (catalogue == null) throw new IllegalStateException("Frozen embedding validation requires a bound catalogue");
        if (!isAvailable()) throw new IllegalStateException("Frozen embedding model is unavailable");
        try {
            var roots = catalogue.rootNodes();
            catalogueRuntimePolicy.requireConfiguredRoots(roots.stream().map(TaxonomyNode::getCode).collect(Collectors.toSet()));
            roots.forEach(root -> catalogue.embeddingSnapshot(root.getCode()));
            var identity = embeddingIdentity();
            for (var root : roots) {
                if (!identity.equals(catalogue.embeddingSnapshot(root.getCode()).model())) {
                    throw new IllegalStateException("Frozen embedding model does not match the worker model configuration");
                }
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Frozen embedding validation failed", failure);
        }
    }

    /** Worker inference is deliberately outside a database transaction and reads only its frozen candidate texts. */
    public Map<String, Integer> scoreFrozenNodes(String businessText, List<TaxonomyNode> nodes) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Frozen embedding inference requires no database transaction");
        }
        var catalogue = FrozenCatalogueContext.current();
        if (catalogue == null) throw new IllegalStateException("Frozen embedding inference requires a bound catalogue");
        catalogueRuntimePolicy.requireConfiguredRoots(nodes.stream().map(TaxonomyNode::getTaxonomyRoot)
                .collect(Collectors.toSet()));
        if (!isAvailable()) throw new IllegalStateException("Frozen embedding model is unavailable");
        var readLock = modelLifecycleLock.readLock();
        readLock.lock();
        try {
            return frozenCache.score(catalogue, embeddingIdentity(), businessText, nodes, this, frozenCacheMaxVectors);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Frozen embedding inference failed", failure);
        } finally {
            readLock.unlock();
        }
    }

    public record FrozenCacheStatistics(int vectors, long vectorBytes) { }

    /** Vector payload only; excludes Java object overhead and native model memory. */
    public FrozenCacheStatistics frozenCacheStatistics() { return frozenCache.statistics(); }

    @Transactional(readOnly = true)
    public Map<String, Integer> scoreNodes(String businessText, List<TaxonomyNode> nodes) {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        Map<String, Integer> scores = new HashMap<>();
        for (TaxonomyNode node : nodes) {
            scores.put(node.getCode(), 0);
        }

        if (!isAvailable()) {
            return scores;
        }

        try {
            float[] queryVector = embedQuery(businessText);
            String indexKey = embeddingIndexKey();
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
                            .filter(factory.bool()
                                    .must(factory.terms().field("code").matchingAny(nodeCodes))
                                    .must(factory.match().field("embeddingModel").matching(indexKey))))
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
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        if (topK <= 0) return Collections.emptyList();
        if (!isAvailable()) {
            throw new SearchUnavailableException();
        }
        try {
            float[] queryVector = embedQuery(queryText);
            String indexKey = embeddingIndexKey();
            SearchSession session = Search.session(entityManager);
            List<TaxonomyNode> hits = session.search(TaxonomyNode.class)
                    // Lucene's approximate search uses k for graph exploration too.
                    // A top-10 response must not restrict the search to 10 candidates:
                    // a nearer vector can otherwise remain undiscovered.
                    .where(factory -> factory.knn(semanticCandidateCount(topK))
                            .field("embedding")
                            .matching(queryVector)
                            .filter(factory.match().field("embeddingModel").matching(indexKey)))
                    .fetchHits(topK);
            return hits.stream()
                    .map(this::toFlatDto)
                    .collect(Collectors.toList());
        } catch (Exception | LinkageError exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Semantic search failed (code=SEMANTIC_SEARCH_FAILED)");
            throw new SearchUnavailableException();
        }
    }

    /**
     * Keep ANN candidate exploration separate from the caller's result limit.
     * Oversample small pages by ten, with a 100-candidate floor and a 1000-candidate
     * ceiling on extra work. The API already bounds result pages; larger internal
     * callers must still receive their requested count. Long arithmetic avoids
     * overflow before applying the ceiling. This improves recall, not exactness.
     */
    static int semanticCandidateCount(int requestedResults) {
        if (requestedResults <= 0) {
            throw new IllegalArgumentException("Semantic result count must be positive");
        }
        long candidates = Math.min(1000L, Math.max(100L, 10L * requestedResults));
        return (int) Math.max(requestedResults, candidates);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> findSimilarNodes(String nodeCode, int topK) {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        if (topK <= 0) return Collections.emptyList();
        if (!isAvailable()) {
            throw new SearchUnavailableException();
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
                log.warn("Similar-node source was not found (code=SIMILAR_NODE_NOT_FOUND)");
                return Collections.emptyList();
            }

            float[] queryVector = embedDocument(NodeEmbeddingText.buildEnrichedText(node));
            String indexKey = embeddingIndexKey();
            SearchSession session = Search.session(entityManager);
            List<TaxonomyNode> hits = session.search(TaxonomyNode.class)
                    .where(factory -> factory.knn(topK + 1)
                            .field("embedding")
                            .matching(queryVector)
                            .filter(factory.match().field("embeddingModel").matching(indexKey)))
                    .fetchHits(topK + 1);

            return hits.stream()
                    .filter(candidate -> !nodeCode.equals(candidate.getCode()))
                    .limit(topK)
                    .map(this::toFlatDto)
                    .collect(Collectors.toList());
        } catch (Exception | LinkageError exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Similar-node search failed (code=SIMILAR_SEARCH_FAILED)");
            throw new SearchUnavailableException();
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
                frozenCache.clear();
                ZooModel<String, float[]> currentModel = model;
                model = null;
                loadedModelIdentity = null;
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
