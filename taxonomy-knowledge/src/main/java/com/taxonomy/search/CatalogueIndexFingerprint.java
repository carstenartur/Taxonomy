package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Versioned identity of the complete searchable projection of one database row.
 * Stored inside the same Lucene document as its text/vector fields, so partial
 * indexing, equal row counts, and restored indexes from another database cannot
 * masquerade as a complete current projection. No private text is retained here.
 */
final class CatalogueIndexFingerprint {
    static final String FIELD = "catalogueContentKey";
    // Bump when analyzers, indexed fields or the document-text contract change.
    private static final String SCHEMA = "catalogue-search-v1-lucene-9.12.3";

    static final String NODE_ROWS = """
            select n.id, n.code, n.uuid, n.nameEn, n.nameDe, n.descriptionEn, n.descriptionDe,
                   n.parentCode, n.taxonomyRoot, n.level, n.externalId, n.hasEmbedding,
                   n.incomingRelationCount, n.outgoingRelationCount, n.requirementCoverageCount, n.graphRole
            from TaxonomyNode n
            where n.id > :after order by n.id
            """;
    static final String RELATION_ROWS = """
            select r.id, r.repositoryId, r.sourceNode.code, r.sourceNode.nameEn,
                   r.targetNode.code, r.targetNode.nameEn, r.relationType, r.workspaceId,
                   r.workspaceScopeKey, r.ownerUsername, r.description, r.hasEmbedding
            from TaxonomyRelation r
            where r.id > :after order by r.id
            """;

    private CatalogueIndexFingerprint() { }

    static String node(TaxonomyNode node) {
        return nodeRow(new Object[] { node.getId(), node.getCode(), node.getUuid(), node.getNameEn(), node.getNameDe(),
                node.getDescriptionEn(), node.getDescriptionDe(), node.getParentCode(), node.getTaxonomyRoot(),
                node.getLevel(), node.getExternalId(), node.isHasEmbedding(), node.getIncomingRelationCount(),
                node.getOutgoingRelationCount(), node.getRequirementCoverageCount(), node.getGraphRole() });
    }

    static String relation(TaxonomyRelation relation) {
        return relationRow(new Object[] { relation.getId(), relation.getRepositoryId(),
                relation.getSourceNode().getCode(), relation.getSourceNode().getNameEn(),
                relation.getTargetNode().getCode(), relation.getTargetNode().getNameEn(),
                relation.getRelationType(), relation.getWorkspaceId(), relation.getWorkspaceScopeKey(),
                relation.getOwnerUsername(), relation.getDescription(), relation.isHasEmbedding() });
    }

    static String nodeRow(Object[] row) { return fingerprint("node", row); }

    static String relationRow(Object[] row) {
        // RelationEmbeddingText currently uses the default locale for relation labels.
        return fingerprint("relation:" + Locale.getDefault().toLanguageTag(), row);
    }

    private static String fingerprint(String kind, Object[] row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, SCHEMA);
            add(digest, kind);
            for (Object value : row) add(digest, value == null ? null : value.toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }
}
