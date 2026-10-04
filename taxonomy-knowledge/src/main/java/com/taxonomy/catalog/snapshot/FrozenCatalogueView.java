package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.CatalogueOverlayService.OverlayMetadata;

import java.util.ArrayList;
import java.util.AbstractList;
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
    private final Map<String, List<String>> childrenByParent;

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
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (var node : nodes.values()) {
            if (node.parentCode() != null) {
                adjacency.computeIfAbsent(node.parentCode(), ignored -> new ArrayList<>()).add(node.code());
            }
        }
        adjacency.replaceAll((parent, children) -> children.stream()
                .sorted(Comparator.comparing(code -> nodes.get(code).nameEn())).toList());
        childrenByParent = Map.copyOf(adjacency);
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
        return new DetachedGraph().node(code);
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

    /** Each read owns its mutable copies; adjacency always comes from immutable recorded evidence. */
    private final class DetachedGraph {
        private final Map<String, TaxonomyNode> copies = new LinkedHashMap<>();

        private TaxonomyNode node(String code) {
            var evidence = requireNode(code);
            List<RootCatalogueSnapshot.Node> ancestors = new ArrayList<>();
            while (evidence != null && !copies.containsKey(evidence.code())) {
                ancestors.add(evidence);
                evidence = evidence.parentCode() == null ? null : requireNode(evidence.parentCode());
            }
            TaxonomyNode parent = evidence == null ? null : copies.get(evidence.code());
            for (int i = ancestors.size() - 1; i >= 0; i--) {
                var recorded = ancestors.get(i);
                TaxonomyNode copy = recorded.detached();
                copy.setParent(parent);
                copy.setChildren(new DetachedChildren(childrenByParent.getOrDefault(recorded.code(), List.of())));
                copies.put(recorded.code(), copy);
                parent = copy;
            }
            return copies.get(code);
        }

        /** Preserve ordinary mutable list behavior without copying branches a caller never visits. */
        private final class DetachedChildren extends AbstractList<TaxonomyNode> {
            private final List<String> codes;
            private List<TaxonomyNode> values;

            private DetachedChildren(List<String> codes) { this.codes = codes; }

            private List<TaxonomyNode> values() {
                if (values == null) {
                    values = new ArrayList<>(codes.size());
                    for (String code : codes) values.add(node(code));
                }
                return values;
            }

            @Override public TaxonomyNode get(int index) { return values().get(index); }
            @Override public int size() { return values == null ? codes.size() : values.size(); }
            @Override public TaxonomyNode set(int index, TaxonomyNode element) { return values().set(index, element); }
            @Override public void add(int index, TaxonomyNode element) {
                values().add(index, element);
                modCount++;
            }
            @Override public TaxonomyNode remove(int index) {
                TaxonomyNode removed = values().remove(index);
                modCount++;
                return removed;
            }
        }
    }
}
