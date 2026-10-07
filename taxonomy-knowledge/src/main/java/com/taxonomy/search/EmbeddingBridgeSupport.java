package com.taxonomy.search;

import com.taxonomy.search.config.SpringContextHolder;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import org.hibernate.search.engine.backend.document.DocumentElement;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.hibernate.search.engine.backend.types.VectorSimilarity;
import org.hibernate.search.mapper.pojo.bridge.binding.TypeBindingContext;

import java.util.function.Function;

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
        try {
            LocalEmbeddingService svc = SpringContextHolder.getBean(LocalEmbeddingService.class);
            if (svc == null || !svc.isEnabled() || !svc.isAvailable()) return;
            String text = textBuilder.apply(entity);
            float[] vector = svc.embedDocument(text);
            String identity = svc.embeddingIndexKey();
            target.addValue(embeddingField, vector);
            target.addValue(modelField, identity);
        } catch (Exception | LinkageError failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // Keep full-text indexing usable, but invalidate semantic readiness.
            var health = SpringContextHolder.getBean(EmbeddingIndexHealth.class);
            if (health != null) health.recordFailure(entity);
        }
    }
}
