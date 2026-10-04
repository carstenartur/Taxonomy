package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.CatalogueOverlayService.OverlayMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads only the immutable roots bound to one task; returned entity-shaped values are detached copies. */
public final class FrozenCatalogueView {
    private final CatalogueSourceIdentity source;
    private final Map<String, RootCatalogueSnapshot> roots = new LinkedHashMap<>();
    private final Map<String, RootCatalogueSnapshot.Node> nodes = new LinkedHashMap<>();

    FrozenCatalogueView(CatalogueSourceIdentity expectedSource, Set<String> requiredRoots,
                        Collection<RootCatalogueSnapshot> snapshots) {
        if (expectedSource == null) throw new IllegalArgumentException("Expected source is required");
        if (requiredRoots == null || requiredRoots.isEmpty()) throw new IllegalArgumentException("Required roots are empty");
        requiredRoots.forEach(CatalogueRoot::require);
        this.source = expectedSource;
        snapshots.stream().sorted(Comparator.comparing(RootCatalogueSnapshot::rootCode)).forEach(snapshot -> {
            if (!source.equals(snapshot.source())) throw new IllegalArgumentException("Catalogue snapshot source mismatch");
            if (roots.putIfAbsent(snapshot.rootCode(), snapshot) != null) {
                throw new IllegalArgumentException("Duplicate snapshot roots");
            }
            for (var node : snapshot.nodes()) {
                if (nodes.putIfAbsent(node.code(), node) != null) {
                    throw new IllegalArgumentException("Overlapping node IDs inside snapshot roots");
                }
            }
        });
        if (!roots.keySet().equals(requiredRoots)) {
            throw new IllegalArgumentException("Snapshot roots do not exactly match required roots");
        }
        OverlayMetadata overlay = overlayMetadata();
        if (roots.values().stream().anyMatch(root -> !overlay.equals(root.overlayMetadata()))) {
            throw new IllegalArgumentException("Inconsistent overlay evidence in snapshot roots");
        }
        var provenance = roots.values().iterator().next().catalogueProvenance();
        if (roots.values().stream().anyMatch(root -> !provenance.equals(root.catalogueProvenance()))) {
            throw new IllegalArgumentException("Inconsistent catalogue generation in snapshot roots");
        }
    }

    public CatalogueSourceIdentity source() { return source; }

    public RootEmbeddingSnapshot embeddingSnapshot(String rootCode) {
        RootCatalogueSnapshot root = roots.get(rootCode);
        if (root == null || root.embeddings() == null) {
            throw new IllegalStateException("Required frozen embedding evidence is absent from the bound root");
        }
        return root.embeddings();
    }

    public RootCatalogueSnapshot.Node requireNode(String code) {
        RootCatalogueSnapshot.Node node = nodes.get(code);
        if (node == null) throw new IllegalStateException("Node is absent from bound catalogue snapshot: " + code);
        return node;
    }

    public OverlayMetadata overlayMetadata() { return roots.values().iterator().next().overlayMetadata(); }

    public List<TaxonomyNode> rootNodes() {
        return roots.keySet().stream().map(this::node).toList();
    }

    public List<TaxonomyNode> allNodes() {
        return nodes.values().stream().map(RootCatalogueSnapshot.Node::detached).toList();
    }

    public TaxonomyNode node(String code) {
        var evidence = requireNode(code);
        return detachedRoot(evidence.taxonomyRoot()).get(code);
    }

    public List<TaxonomyNode> children(String code) {
        return List.copyOf(node(code).getChildren());
    }

    /** Re-resolve caller inputs by recorded identity; mutable caller data is never prompt authority. */
    public List<TaxonomyNode> canonicalNodes(List<TaxonomyNode> candidates) {
        List<TaxonomyNode> result = new ArrayList<>();
        for (TaxonomyNode candidate : candidates) {
            if (candidate == null) throw new IllegalArgumentException("Null assessment candidate");
            var recorded = requireNode(candidate.getCode());
            if (!recorded.taxonomyRoot().equals(candidate.getTaxonomyRoot())) {
                throw new IllegalArgumentException("Assessment candidate belongs to a foreign snapshot root");
            }
            result.add(recorded.detached());
        }
        return result;
    }

    private Map<String, TaxonomyNode> detachedRoot(String rootCode) {
        Map<String, TaxonomyNode> copies = new LinkedHashMap<>();
        for (var node : roots.get(rootCode).nodes()) copies.put(node.code(), node.detached());
        for (var node : copies.values()) {
            if (node.getParentCode() != null) {
                TaxonomyNode parent = copies.get(node.getParentCode());
                node.setParent(parent);
                parent.getChildren().add(node);
            }
        }
        copies.values().forEach(node -> node.getChildren().sort(
                Comparator.comparing(TaxonomyNode::getNameEn)));
        return copies;
    }
}
