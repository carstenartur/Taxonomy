package com.taxonomy.search;

import com.taxonomy.config.SpringContextHolder;
import com.taxonomy.model.TaxonomyRelation;
import com.taxonomy.service.LocalEmbeddingService;
import org.hibernate.search.engine.backend.document.DocumentElement;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.hibernate.search.engine.backend.types.VectorSimilarity;
import org.hibernate.search.mapper.pojo.bridge.TypeBridge;
import org.hibernate.search.mapper.pojo.bridge.binding.TypeBindingContext;
import org.hibernate.search.mapper.pojo.bridge.mapping.programmatic.TypeBinder;
import org.hibernate.search.mapper.pojo.bridge.runtime.TypeBridgeWriteContext;

/**
 * Hibernate Search {@link TypeBinder} that computes a DJL/ONNX embedding vector for
 * a {@link TaxonomyRelation} and writes it to an {@code "embedding"} {@code @VectorField}.
 *
 * <p>The enriched text is formed as:
 * {@code "{sourceName} {relationType} {targetName}. {description}"}
 *
 * <p>Graceful degradation: when the DJL model is unavailable the bridge writes nothing.
 */
public class RelationEmbeddingBinder implements TypeBinder {

    @Override
    public void bind(TypeBindingContext context) {
        context.dependencies()
                .use("relationType")
                .use("description")
                .use("sourceNode.nameEn")
                .use("targetNode.nameEn");

        IndexFieldReference<float[]> embeddingField = context.indexSchemaElement()
                .field("embedding",
                        f -> f.asFloatVector().dimension(384).vectorSimilarity(VectorSimilarity.COSINE))
                .toReference();

        context.bridge(TaxonomyRelation.class, new Bridge(embeddingField));
    }

    public static final class Bridge implements TypeBridge<TaxonomyRelation> {

        private final IndexFieldReference<float[]> embeddingField;

        Bridge(IndexFieldReference<float[]> embeddingField) {
            this.embeddingField = embeddingField;
        }

        @Override
        public void write(DocumentElement target, TaxonomyRelation relation,
                TypeBridgeWriteContext context) {
            try {
                LocalEmbeddingService svc = SpringContextHolder.getBean(LocalEmbeddingService.class);
                if (svc == null || !svc.isAvailable()) return;
                String text = buildEnrichedText(relation);
                float[] vector = svc.embed(text);
                target.addValue(embeddingField, vector);
            } catch (Exception ignored) {
                // graceful degradation
            }
        }

        public static String buildEnrichedText(TaxonomyRelation relation) {
            String sourceName = (relation.getSourceNode() != null
                    && relation.getSourceNode().getNameEn() != null)
                    ? relation.getSourceNode().getNameEn() : "";
            String targetName = (relation.getTargetNode() != null
                    && relation.getTargetNode().getNameEn() != null)
                    ? relation.getTargetNode().getNameEn() : "";
            String relType = relation.getRelationType() != null
                    ? relation.getRelationType().name().toLowerCase().replace('_', ' ') : "";
            StringBuilder sb = new StringBuilder();
            sb.append(sourceName).append(" ").append(relType).append(" ").append(targetName);
            if (relation.getDescription() != null && !relation.getDescription().isBlank()) {
                sb.append(". ").append(relation.getDescription());
            }
            return sb.toString().trim();
        }
    }
}
