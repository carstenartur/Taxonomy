package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.NodeEmbeddingText;
import org.hibernate.search.engine.backend.document.DocumentElement;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.hibernate.search.engine.backend.types.Projectable;
import org.hibernate.search.mapper.pojo.bridge.TypeBridge;
import org.hibernate.search.mapper.pojo.bridge.binding.TypeBindingContext;
import org.hibernate.search.mapper.pojo.bridge.mapping.programmatic.TypeBinder;
import org.hibernate.search.mapper.pojo.bridge.runtime.TypeBridgeWriteContext;

/**
 * Hibernate Search {@link TypeBinder} that computes a DJL/ONNX embedding vector for
 * a {@link TaxonomyNode} and writes it to an {@code "embedding"} {@code @VectorField}.
 *
 * <p>The shared catalogue vector uses the node's English name and description.
 * Repository and working-copy relations belong to their selected graph generations.
 *
 * <p>Graceful degradation: when the DJL model is unavailable the bridge writes nothing,
 * leaving the document without an embedding vector. KNN queries will not match it, which
 * mirrors the existing behaviour where the vector index is absent.
 */
public class NodeEmbeddingBinder implements TypeBinder {

    @Override
    public void bind(TypeBindingContext context) {
        context.dependencies().use("id").use("code").use("uuid").use("nameEn").use("nameDe")
                .use("descriptionEn").use("descriptionDe").use("parentCode").use("taxonomyRoot")
                .use("level").use("externalId").use("hasEmbedding").use("incomingRelationCount")
                .use("outgoingRelationCount").use("requirementCoverageCount").use("graphRole");

        IndexFieldReference<float[]> embeddingField =
                EmbeddingBridgeSupport.createEmbeddingField(context);

        context.bridge(TaxonomyNode.class, new Bridge(embeddingField, EmbeddingBridgeSupport.createEmbeddingModelField(context),
                context.indexSchemaElement().field(CatalogueIndexFingerprint.FIELD,
                        f -> f.asString().projectable(Projectable.YES)).toReference()));
    }

    public static final class Bridge implements TypeBridge<TaxonomyNode> {

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
        public void write(DocumentElement target, TaxonomyNode node,
                TypeBridgeWriteContext context) {
            target.addValue(contentField, CatalogueIndexFingerprint.node(node));
            EmbeddingBridgeSupport.writeEmbedding(target, embeddingField, modelField, node,
                    Bridge::buildEnrichedText);
        }

        public static String buildEnrichedText(TaxonomyNode node) {
            return NodeEmbeddingText.globalReferenceText(node);
        }
    }
}
