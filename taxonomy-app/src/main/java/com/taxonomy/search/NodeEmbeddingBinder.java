package com.taxonomy.search;

import com.taxonomy.config.SpringContextHolder;
import com.taxonomy.model.TaxonomyNode;
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

        IndexFieldReference<float[]> embeddingField = context.indexSchemaElement()
                .field("embedding",
                        f -> f.asFloatVector().dimension(384).vectorSimilarity(VectorSimilarity.COSINE))
                .toReference();

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
            try {
                LocalEmbeddingService svc = SpringContextHolder.getBean(LocalEmbeddingService.class);
                if (svc == null || !svc.isAvailable()) return;
                String text = buildEnrichedText(node);
                float[] vector = svc.embed(text);
                target.addValue(embeddingField, vector);
            } catch (Exception ignored) {
                // graceful degradation – document will be indexed without a vector
            }
        }

        public static String buildEnrichedText(TaxonomyNode node) {
            StringBuilder sb = new StringBuilder();
            if (node.getNameEn() != null) sb.append(node.getNameEn()).append(".\n");
            if (node.getDescriptionEn() != null && !node.getDescriptionEn().isBlank()) {
                sb.append(node.getDescriptionEn()).append("\n");
            }
            if (!node.getOutgoingRelations().isEmpty()) {
                sb.append("Outgoing: ");
                for (TaxonomyRelation r : node.getOutgoingRelations()) {
                    if (r.getRelationType() == null) continue;
                    sb.append(r.getRelationType().name().toLowerCase().replace('_', ' '));
                    String targetName = (r.getTargetNode() != null && r.getTargetNode().getNameEn() != null)
                            ? r.getTargetNode().getNameEn() : "";
                    sb.append(" ").append(targetName).append(", ");
                }
                if (sb.toString().endsWith(", ")) {
                    sb.setLength(sb.length() - 2); // remove trailing ", "
                }
                sb.append(".\n");
            }
            if (!node.getIncomingRelations().isEmpty()) {
                sb.append("Incoming: ");
                for (TaxonomyRelation r : node.getIncomingRelations()) {
                    if (r.getRelationType() == null) continue;
                    sb.append(r.getRelationType().name().toLowerCase().replace('_', ' '));
                    String sourceName = (r.getSourceNode() != null && r.getSourceNode().getNameEn() != null)
                            ? r.getSourceNode().getNameEn() : "";
                    sb.append(" ").append(sourceName).append(", ");
                }
                if (sb.toString().endsWith(", ")) {
                    sb.setLength(sb.length() - 2);
                }
                sb.append(".\n");
            }
            return sb.toString().trim();
        }
    }
}
