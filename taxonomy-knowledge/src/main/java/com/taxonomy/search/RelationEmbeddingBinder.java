package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.RelationEmbeddingText;
import org.hibernate.search.engine.backend.document.DocumentElement;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.hibernate.search.engine.backend.types.Projectable;
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
                .use("id").use("repositoryId").use("workspaceId").use("workspaceScopeKey")
                .use("ownerUsername").use("hasEmbedding")
                .use("relationType")
                .use("description")
                .use("sourceNode.code").use("targetNode.code")
                .use("sourceNode.nameEn")
                .use("targetNode.nameEn");

        IndexFieldReference<float[]> embeddingField =
                EmbeddingBridgeSupport.createEmbeddingField(context);

        context.bridge(TaxonomyRelation.class, new Bridge(embeddingField, EmbeddingBridgeSupport.createEmbeddingModelField(context),
                context.indexSchemaElement().field(CatalogueIndexFingerprint.FIELD,
                        f -> f.asString().projectable(Projectable.YES)).toReference()));
    }

    public static final class Bridge implements TypeBridge<TaxonomyRelation> {

        private final IndexFieldReference<float[]> embeddingField;
        private final IndexFieldReference<String> modelField;
        private final IndexFieldReference<String> contentField;

        Bridge(IndexFieldReference<float[]> embeddingField, IndexFieldReference<String> modelField,
                IndexFieldReference<String> contentField) {
            this.embeddingField = embeddingField;
            this.modelField = modelField;
            this.contentField = contentField;
        }

        @Override
        public void write(DocumentElement target, TaxonomyRelation relation,
                TypeBridgeWriteContext context) {
            target.addValue(contentField, CatalogueIndexFingerprint.relation(relation));
            EmbeddingBridgeSupport.writeEmbedding(target, embeddingField, modelField, relation,
                    Bridge::buildEnrichedText);
        }

        public static String buildEnrichedText(TaxonomyRelation relation) {
            return RelationEmbeddingText.build(
                    relation.getSourceNode() == null ? null : relation.getSourceNode().getNameEn(),
                    relation.getRelationType(),
                    relation.getTargetNode() == null ? null : relation.getTargetNode().getNameEn(),
                    relation.getDescription(), java.util.Locale.getDefault());
        }
    }
}
