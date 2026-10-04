package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;

/**
 * Shared document-text formatter for catalogue embeddings.
 *
 * <p>Preserves the supplied relation order and the existing indexed text exactly.
 * Callers own the source and ordering of the node and relation data; this helper
 * performs no lookup and does not depend on the search/index adapter.
 */
public final class NodeEmbeddingText {
    private NodeEmbeddingText() {
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
