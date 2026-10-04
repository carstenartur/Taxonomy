package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.NodeEmbeddingText;
import org.hibernate.search.engine.backend.document.DocumentElement;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.hibernate.search.mapper.pojo.bridge.TypeBridge;
import org.hibernate.search.mapper.pojo.bridge.binding.TypeBindingContext;
import org.hibernate.search.mapper.pojo.bridge.mapping.programmatic.TypeBinder;
import org.hibernate.search.mapper.pojo.bridge.runtime.TypeBridgeWriteContext;

/**
 * Hibernate Search {@link TypeBinder} that computes a DJL/ONNX embedding vector for
 * a {@link TaxonomyNode} and writes it to an {@code "embedding"} {@code @VectorField}.
 *
 * <p>The enriched text used for embedding includes:
 * <ul>
 *   <li>The node's English name and description.</li>
 *   <li>Outgoing and incoming relation summaries (e.g. "Outgoing: supports X, Y").</li>
 * </ul>
 *
 * <p>Graceful degradation: when the DJL model is unavailable the bridge writes nothing,
 * leaving the document without an embedding vector. KNN queries will not match it, which
 * mirrors the existing behaviour where the vector index is absent.
 */
public class NodeEmbeddingBinder implements TypeBinder {

    @Override
    public void bind(TypeBindingContext context) {
        context.dependencies()
                .use("nameEn")
                .use("descriptionEn")
                .use("outgoingRelations.relationType")
                .use("outgoingRelations.targetNode.nameEn")
                .use("incomingRelations.relationType")
                .use("incomingRelations.sourceNode.nameEn");

        IndexFieldReference<float[]> embeddingField =
                EmbeddingBridgeSupport.createEmbeddingField(context);

        context.bridge(TaxonomyNode.class, new Bridge(embeddingField));
    }

    public static final class Bridge implements TypeBridge<TaxonomyNode> {

        private final IndexFieldReference<float[]> embeddingField;

        Bridge(IndexFieldReference<float[]> embeddingField) {
            this.embeddingField = embeddingField;
        }

        @Override
        public void write(DocumentElement target, TaxonomyNode node,
                TypeBridgeWriteContext context) {
            EmbeddingBridgeSupport.writeEmbedding(target, embeddingField, node,
                    Bridge::buildEnrichedText);
        }

        public static String buildEnrichedText(TaxonomyNode node) {
            return NodeEmbeddingText.buildEnrichedText(node);
        }
    }
}
