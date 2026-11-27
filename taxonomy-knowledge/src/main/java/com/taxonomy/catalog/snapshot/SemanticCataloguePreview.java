package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.util.VectorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reuses local document vectors from immutable catalogue inputs across interactive edits. */
@Service
public class SemanticCataloguePreview {
    private static final Logger log = LoggerFactory.getLogger(SemanticCataloguePreview.class);
    private static final int MAX_REVISIONS = 2;
    private static final long RETRY_DELAY = Duration.ofMinutes(1).toNanos();
    private final LocalEmbeddingService model;
    @Value("${embedding.preview.cache-directory:}")
    private String cacheDirectory = "";
    // Identity of the lexical index binds a generation to exactly the same immutable catalogue rows.
    private final Map<CataloguePreviewIndex, Generation> ready = new LinkedHashMap<>(4, 0.75f, true);
    private Thread preparing;
    private CataloguePreviewIndex failed;
    private long failedAt;
    private boolean closed;

    private record Generation(String modelKey, Map<String, float[]> vectors) {}

    public SemanticCataloguePreview(LocalEmbeddingService model) {
        this.model = model;
    }

    /** Start document inference before a requirement exists, without delaying the caller. */
    public void prepare(CataloguePreviewIndex index, List<RootCatalogueSnapshot> snapshots) {
        if (model.isAvailable()) generation(index, snapshots);
    }

    public Optional<CataloguePreviewIndex.Result> query(CataloguePreviewIndex index,
            List<RootCatalogueSnapshot> snapshot, String text, Set<String> roots) {
        if (!model.isAvailable()) return Optional.empty();
        Generation generation = generation(index, snapshot);
        if (generation == null) return Optional.empty();
        try {
            if (!generation.modelKey().equals(model.embeddingIndexKey())) {
                synchronized (this) {
                    ready.remove(index, generation);
                }
                return Optional.empty();
            }
            float[] query = model.embedQuery(text);
            if (!PreviewVectorStore.valid(query) || !generation.modelKey().equals(model.embeddingIndexKey()))
                return Optional.empty();
            var scores = new HashMap<String, Double>();
            generation.vectors().forEach((code, vector) -> {
                double similarity = VectorUtil.cosine(query, vector);
                if (similarity > 0) scores.put(code, Math.min(100.0, 100.0 * similarity));
            });
            return Optional.of(index.project(scores, roots, text));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Semantic catalogue preview query unavailable ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private synchronized Generation generation(CataloguePreviewIndex index, List<RootCatalogueSnapshot> snapshot) {
        if (closed) return null;
        Generation existing = ready.get(index);
        if (existing == null && preparing == null
                && (failed != index || System.nanoTime() - failedAt >= RETRY_DELAY)) {
            // No unbounded queue on a burst of edits or branch switches. A later request can admit another revision.
            preparing = Thread.ofVirtual().name("catalogue-preview-vectors").unstarted(() -> build(index, snapshot));
            preparing.start();
        }
        return existing;
    }

    private void build(CataloguePreviewIndex index, List<RootCatalogueSnapshot> snapshots) {
        try {
            String key = model.embeddingIndexKey();
            var store = new PreviewVectorStore(cacheDirectory.isBlank() ? null : Path.of(cacheDirectory));
            var manifest = PreviewVectorStore.manifest(snapshots, key);
            Map<String, float[]> vectors = store.read(manifest).orElse(null);
            if (vectors == null) {
                vectors = new HashMap<>();
                for (var entry : manifest.documents().entrySet()) {
                    if (Thread.currentThread().isInterrupted()) return;
                    float[] vector = model.embedDocument(entry.getValue());
                    if (!PreviewVectorStore.valid(vector))
                        throw new IllegalStateException("Invalid catalogue document vector");
                    vectors.put(entry.getKey(), vector);
                }
                if (!key.equals(model.embeddingIndexKey())) return;
                store.write(manifest, vectors);
            }
            synchronized (this) {
                if (!closed && key.equals(model.embeddingIndexKey())) {
                    if (ready.size() >= MAX_REVISIONS) ready.remove(ready.keySet().iterator().next());
                    ready.put(index, new Generation(key, Map.copyOf(vectors)));
                    failed = null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            synchronized (this) {
                failed = index;
                failedAt = System.nanoTime();
            }
            log.warn("Semantic catalogue preview preparation unavailable ({})", e.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                preparing = null;
            }
        }
    }

    @PreDestroy
    synchronized void stop() {
        closed = true;
        if (preparing != null) preparing.interrupt();
        ready.clear();
        failed = null;
    }
}
