package com.taxonomy.search;

import com.taxonomy.shared.config.SpringContextHolder;
import com.taxonomy.shared.service.LocalEmbeddingService;
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

    /** The vector dimension used by the ONNX embedding model (all-MiniLM-L6-v2). */
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

    /**
     * Writes an embedding vector to the Lucene document, or silently does nothing when the
     * local embedding model is unavailable (graceful degradation).
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
                                           T entity,
                                           Function<T, String> textBuilder) {
        try {
            LocalEmbeddingService svc = SpringContextHolder.getBean(LocalEmbeddingService.class);
            if (svc == null || !svc.isAvailable()) return;
            String text = textBuilder.apply(entity);
            float[] vector = svc.embed(text);
            target.addValue(embeddingField, vector);
        } catch (Exception ignored) {
            // graceful degradation – document will be indexed without a vector
        }
    }
}
