package com.taxonomy.search;

import com.taxonomy.search.config.SpringContextHolder;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import org.hibernate.search.engine.backend.document.DocumentElement;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.hibernate.search.engine.backend.types.VectorSimilarity;
import org.hibernate.search.mapper.pojo.bridge.binding.TypeBindingContext;

import java.util.function.Function;
import java.util.Map;

/**
 * Shared utilities for embedding binders ({@link NodeEmbeddingBinder} and
 * {@link RelationEmbeddingBinder}).
 *
 * <p>Eliminates duplication of the common embedding-field creation and the
 * graceful-degradation write pattern that both binders share.
 */
public final class EmbeddingBridgeSupport {

    /** The vector dimension used by the ONNX embedding model (both supported profiles). */
    static final int VECTOR_DIMENSION = 384;

    record PreparedEmbedding(String contentKey, float[] vector, String modelKey) { }

    private static final ThreadLocal<Map<String, PreparedEmbedding>> prepared = new ThreadLocal<>();

    private EmbeddingBridgeSupport() { /* utility class */ }

    /**
     * Creates the {@code "embedding"} float-vector index field on the given binding context.
     *
     * @param context the Hibernate Search type-binding context
     * @return reference to the created embedding field
     */
    public static IndexFieldReference<float[]> createEmbeddingField(TypeBindingContext context) {
        return context.indexSchemaElement()
                .field("embedding",
                        f -> f.asFloatVector().dimension(VECTOR_DIMENSION)
                                .vectorSimilarity(VectorSimilarity.COSINE))
                .toReference();
    }

    /** Keyword identifying the exact model bytes and inference contract of this vector. */
    public static IndexFieldReference<String> createEmbeddingModelField(TypeBindingContext context) {
        return context.indexSchemaElement().field("embeddingModel", f -> f.asString()).toReference();
    }

    /**
     * Writes an embedding vector to the Lucene document only when semantic embeddings were
     * explicitly enabled and the local service is available. Otherwise the document is indexed
     * without a vector and no model initialisation or runtime download is attempted.
     *
     * @param target         the Lucene document being written
     * @param embeddingField reference to the {@code "embedding"} index field
     * @param entity         the JPA entity being indexed
     * @param textBuilder    function that converts the entity to the enriched text used for
     *                       vector computation
     * @param <T>            entity type
     */
    public static <T> void writeEmbedding(DocumentElement target,
                                           IndexFieldReference<float[]> embeddingField,
                                           IndexFieldReference<String> modelField,
                                           T entity,
                                           Function<T, String> textBuilder) {
        PreparedEmbedding value;
        Map<String, PreparedEmbedding> refresh = prepared.get();
        if (refresh != null) {
            value = refresh.get(contentKey(entity));
            if (value == null) throw new IllegalStateException("Embedding refresh source changed before indexing");
        } else {
            value = compute(entity, textBuilder);
        }
        if (value.vector() != null) {
            try {
                target.addValue(embeddingField, value.vector());
                target.addValue(modelField, value.modelKey());
            } catch (Exception | LinkageError failure) {
                recordFailure(entity, failure);
            }
        }
    }

    /** Inference runs before acquiring any database write locks. */
    static PreparedEmbedding prepare(Object entity) {
        if (entity instanceof TaxonomyNode node) return compute(node, NodeEmbeddingBinder.Bridge::buildEnrichedText);
        if (entity instanceof TaxonomyRelation relation) return compute(relation, RelationEmbeddingBinder.Bridge::buildEnrichedText);
        throw new IllegalArgumentException("Unsupported catalogue index entity");
    }

    static String contentKey(Object entity) {
        if (entity instanceof TaxonomyNode node) return CatalogueIndexFingerprint.node(node);
        if (entity instanceof TaxonomyRelation relation) return CatalogueIndexFingerprint.relation(relation);
        throw new IllegalArgumentException("Unsupported catalogue index entity");
    }

    /** Scope must end before this worker can index an unrelated ORM transaction. */
    static void withPrepared(Map<String, PreparedEmbedding> values, Runnable indexing) {
        Map<String, PreparedEmbedding> previous = prepared.get();
        prepared.set(values);
        try {
            indexing.run();
        } finally {
            if (previous == null) prepared.remove();
            else prepared.set(previous);
        }
    }

    private static <T> PreparedEmbedding compute(T entity, Function<T, String> textBuilder) {
        String key = contentKey(entity);
        try {
            var startup = SpringContextHolder.getBean(AppInitializationStateService.class);
            if (startup != null && !startup.isReady()) return new PreparedEmbedding(key, null, null);
            LocalEmbeddingService svc = SpringContextHolder.getBean(LocalEmbeddingService.class);
            if (svc == null || !svc.isEnabled() || !svc.isAvailable()) return new PreparedEmbedding(key, null, null);
            String text = textBuilder.apply(entity);
            float[] vector = svc.embedDocument(text);
            String identity = svc.embeddingIndexKey();
            return new PreparedEmbedding(key, vector, identity);
        } catch (Exception | LinkageError failure) {
            recordFailure(entity, failure);
            return new PreparedEmbedding(key, null, null);
        }
    }

    private static void recordFailure(Object entity, Throwable failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        // Keep full-text indexing usable, but invalidate semantic readiness.
        var health = SpringContextHolder.getBean(EmbeddingIndexHealth.class);
        if (health != null) health.recordFailure(entity);
    }
}
