package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.FrozenCatalogueView;
import com.taxonomy.identity.StableIdentityHash;
import org.apache.lucene.index.VectorSimilarityFunction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded vectors of explicitly requested frozen candidates; no JPA, global index or query-result cache. */
final class FrozenEmbeddingCache {
    private final Map<Key, float[]> vectors = new LinkedHashMap<>(16, .75f, true);

    Map<String, Integer> score(FrozenCatalogueView catalogue, EmbeddingModelIdentity activeModel,
                               String businessText, List<TaxonomyNode> candidates,
                               LocalEmbeddingService embeddings, int capacity) throws Exception {
        if (capacity < 1 || capacity > 100_000) throw new IllegalArgumentException("Invalid frozen embedding cache limit");
        var nodes = catalogue.canonicalNodes(candidates);
        for (var node : nodes) {
            var evidence = catalogue.embeddingSnapshot(node.getTaxonomyRoot());
            if (!activeModel.equals(evidence.model())) {
                throw new IllegalStateException("Frozen embedding model does not match the worker model configuration");
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        if (nodes.isEmpty()) return result;
        float[] query = checked(embeddings.embedQuery(businessText));
        for (var node : nodes) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Frozen embedding inference interrupted");
            var evidence = catalogue.embeddingSnapshot(node.getTaxonomyRoot());
            String text = evidence.nodeTexts().get(node.getCode());
            if (text == null) throw new IllegalStateException("Missing frozen embedding text for candidate");
            var key = new Key(catalogue.source(), node.getTaxonomyRoot(), activeModel,
                    evidence.textVersion(), node.getCode(), StableIdentityHash.sha256(text));
            float[] vector = get(key);
            if (vector == null) vector = retain(key, checked(embeddings.embed(text)), capacity);
            float score = VectorSimilarityFunction.COSINE.compare(query, vector);
            result.put(node.getCode(), Math.max(0, Math.min(100,
                    (int) Math.round((2.0 * score - 1.0) * 100.0))));
        }
        return result;
    }

    private synchronized float[] get(Key key) { return vectors.get(key); }

    private synchronized float[] retain(Key key, float[] vector, int capacity) {
        float[] existing = vectors.get(key);
        if (existing != null) return existing;
        vectors.put(key, vector.clone());
        while (vectors.size() > capacity) vectors.remove(vectors.keySet().iterator().next());
        return vector;
    }

    synchronized LocalEmbeddingService.FrozenCacheStatistics statistics() {
        return new LocalEmbeddingService.FrozenCacheStatistics(vectors.size(),
                vectors.values().stream().mapToLong(vector -> (long) vector.length * Float.BYTES).sum());
    }

    synchronized void clear() { vectors.clear(); }

    private static float[] checked(float[] vector) {
        if (vector == null || vector.length != 384) throw new IllegalStateException("Frozen embedding vector dimension is invalid");
        double squared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Frozen embedding vector is not finite");
            squared += (double) value * value;
        }
        if (squared == 0) throw new IllegalStateException("Frozen embedding vector has no direction");
        return vector;
    }

    private record Key(CatalogueSourceIdentity source, String root, EmbeddingModelIdentity model,
                       String textVersion, String code, String textHash) { }
}
