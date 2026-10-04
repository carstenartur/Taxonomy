package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService.NodeMetadata;
import com.taxonomy.catalog.service.CatalogueOverlayService.OverlayMetadata;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned scalar analysis catalogue evidence, suitable for durable JSON storage.
 * No JPA associations, mutable relation projections, vectors or current-source references are retained.
 */
public record RootCatalogueSnapshot(int schemaVersion, CatalogueSourceIdentity source, String rootCode,
                                    List<Node> nodes, OverlayMetadata overlayMetadata,
                                    CatalogueSourceJournal.Snapshot catalogueProvenance,
                                    RootEmbeddingSnapshot embeddings) {
    public static final int SCHEMA_VERSION = 2;

    public RootCatalogueSnapshot(int schemaVersion, CatalogueSourceIdentity source, String rootCode,
                                  List<Node> nodes, OverlayMetadata overlayMetadata,
                                  CatalogueSourceJournal.Snapshot catalogueProvenance) {
        this(schemaVersion, source, rootCode, nodes, overlayMetadata, catalogueProvenance, null);
    }

    public RootCatalogueSnapshot {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported catalogue snapshot schema");
        Objects.requireNonNull(source, "source");
        CatalogueRoot.require(rootCode);
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(overlayMetadata, "overlayMetadata");
        requireMatchingOverlay(catalogueProvenance, overlayMetadata);
        Map<String, Node> byCode = new HashMap<>();
        for (Node node : nodes) {
            if (!rootCode.equals(node.taxonomyRoot()) || byCode.putIfAbsent(node.code(), node) != null) {
                throw new IllegalArgumentException("Duplicate or foreign node in root snapshot: " + node.code());
            }
        }
        if (embeddings != null && (!source.equals(embeddings.source()) || !rootCode.equals(embeddings.rootCode())
                || !byCode.keySet().equals(embeddings.nodeTexts().keySet()))) {
            throw new IllegalArgumentException("Frozen embeddings must cover exactly the source and nodes of this root");
        }
        Node root = byCode.get(rootCode);
        if (root == null || root.parentCode() != null || root.level() != 0) {
            throw new IllegalArgumentException("Root snapshot must contain its parentless catalogue root");
        }
        Set<String> reached = new HashSet<>(Set.of(rootCode));
        for (Node node : nodes) {
            Set<String> path = new HashSet<>();
            Node current = node;
            while (!reached.contains(current.code())) {
                if (!path.add(current.code())) throw new IllegalArgumentException("Cycle in root snapshot");
                current = byCode.get(current.parentCode());
                if (current == null) throw new IllegalArgumentException("Missing parent in root snapshot: " + node.code());
            }
            reached.addAll(path);
        }
    }

    public RootCatalogueSnapshot withEmbeddings(RootEmbeddingSnapshot evidence) {
        return new RootCatalogueSnapshot(schemaVersion, source, rootCode, nodes, overlayMetadata, catalogueProvenance, evidence);
    }

    static void requireMatchingOverlay(CatalogueSourceJournal.Snapshot provenance, OverlayMetadata overlay) {
        Objects.requireNonNull(provenance, "catalogueProvenance");
        if (provenance.id() == null || !java.util.UUID.fromString(provenance.id()).toString().equals(provenance.id())
                || provenance.createdAt() == null || provenance.workbook() == null || provenance.relations() == null)
            throw new IllegalArgumentException("Invalid catalogue generation evidence");
        var recorded = Objects.requireNonNull(provenance.overlay(), "catalogueProvenance.overlay");
        boolean matches = overlay.enabled()
                ? recorded.use() == CatalogueSourceJournal.Use.APPLIED && recorded.sha256() != null
                    && recorded.sha256().equals(overlay.sha256())
                : recorded.use() == CatalogueSourceJournal.Use.NOT_USED && recorded.sha256() == null;
        if (!matches) throw new IllegalArgumentException("Runtime overlay differs from retained catalogue generation");
    }

    /** Official catalogue fields and frozen overlay semantics; every collection is immutable. */
    public record Node(Long id, String code, String uuid, String nameEn, String nameDe,
                       String descriptionEn, String descriptionDe, String parentCode,
                       String taxonomyRoot, int level, String dataset, String externalId,
                       String source, String reference, Integer sortOrder, String state,
                       NodeMetadata metadata, boolean parentPatch) {
        public Node {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("Snapshot node code is required");
            CatalogueRoot.require(taxonomyRoot);
            Objects.requireNonNull(nameEn, "nameEn");
            Objects.requireNonNull(metadata, "metadata");
            if (metadata.analysisRole() == null
                    || !Set.of("CATEGORY", "PRODUCT", "PRODUCT_FAMILY").contains(metadata.analysisRole())) {
                throw new IllegalArgumentException("Unsupported snapshot analysis role");
            }
            metadata = new NodeMetadata(metadata.analysisRole(), List.copyOf(metadata.secondaryClassificationCodes()),
                    metadata.confidence(), metadata.reviewRequired(), metadata.justification());
        }

        public static Node capture(TaxonomyNode node, NodeMetadata metadata, boolean parentPatch) {
            return new Node(node.getId(), node.getCode(), node.getUuid(), node.getNameEn(), node.getNameDe(),
                    node.getDescriptionEn(), node.getDescriptionDe(), node.getParentCode(), node.getTaxonomyRoot(),
                    node.getLevel(), node.getDataset(), node.getExternalId(), node.getSource(), node.getReference(),
                    node.getSortOrder(), node.getState(), metadata, parentPatch);
        }

        TaxonomyNode detached() {
            TaxonomyNode node = new TaxonomyNode();
            node.setId(id);
            node.setCode(code);
            node.setUuid(uuid);
            node.setNameEn(nameEn);
            node.setNameDe(nameDe);
            node.setDescriptionEn(descriptionEn);
            node.setDescriptionDe(descriptionDe);
            node.setParentCode(parentCode);
            node.setTaxonomyRoot(taxonomyRoot);
            node.setLevel(level);
            node.setDataset(dataset);
            node.setExternalId(externalId);
            node.setSource(source);
            node.setReference(reference);
            node.setSortOrder(sortOrder);
            node.setState(state);
            return node;
        }
    }
}
