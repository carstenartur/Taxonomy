package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Rule-based {@link DiagramSelectionPolicy} that curates a raw {@link DiagramModel}
 * according to a {@link DiagramSelectionConfig}.
 *
 * <p>All curation decisions (visibility, suppression, containment, ordering,
 * limits) are driven by the config flags — no logic is hard-coded for specific
 * taxonomy categories.  The named sub-classes
 * ({@link LeafOnlyDiagramSelectionPolicy}, {@link ClusteringDiagramSelectionPolicy},
 * {@link TraceDiagramSelectionPolicy}) are thin wrappers that provide convenient
 * preset configurations.</p>
 */
public class ConfigurableDiagramSelectionPolicy implements DiagramSelectionPolicy {

    private final DiagramSelectionConfig config;

    public ConfigurableDiagramSelectionPolicy(DiagramSelectionConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** Returns the active configuration. */
    public DiagramSelectionConfig config() {
        return config;
    }

    @Override
    public DiagramModel apply(DiagramModel rawModel) {
        if (rawModel == null || rawModel.nodes().isEmpty()) {
            return rawModel;
        }

        List<DiagramNode> nodes = new ArrayList<>(rawModel.nodes());
        List<DiagramEdge> edges = new ArrayList<>(rawModel.edges());

        // ── 1. Relevance filter ─────────────────────────────────────────
        if (config.minRelevance() > 0.0) {
            nodes.removeIf(n -> n.relevance() < config.minRelevance()
                    && !n.anchor() && !n.selectedForImpact());
        }

        // ── 2. Collect categories that still have concrete (depth > 1) nodes ──
        Set<String> categoriesWithConcrete = nodes.stream()
                .filter(n -> n.depth() > 1 && n.type() != null)
                .map(DiagramNode::type)
                .collect(Collectors.toSet());

        // ── 3. Root-node suppression ────────────────────────────────────
        if (config.suppressRootNodes()) {
            nodes.removeIf(n -> isRootId(n.id())
                    && n.depth() <= 1
                    && categoriesWithConcrete.contains(n.type()));
        }

        // ── 4. Scaffolding suppression ──────────────────────────────────
        if (config.suppressScaffoldingNodes()) {
            nodes.removeIf(n -> n.depth() <= 1
                    && !isRootId(n.id())
                    && categoriesWithConcrete.contains(n.type()));
        }

        // ── 5. Leaf-only mode ───────────────────────────────────────────
        if (config.leafOnlyMode()) {
            nodes = applyLeafOnly(nodes);
            // Rebuild edges with re-routing
            edges = rerouteEdges(rawModel.edges(), nodes, rawModel.nodes());
        }

        // ── 6. Clustering / containment ─────────────────────────────────
        if (config.allowIntermediateAsClusters()) {
            nodes = applyClustering(nodes);
        }

        // ── 7. Sort: anchors first, then by relevance descending ────────
        nodes.sort(Comparator
                .comparing((DiagramNode n) -> n.anchor() ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(DiagramNode::relevance).reversed()));

        // ── 8. Limit nodes ──────────────────────────────────────────────
        if (nodes.size() > config.maxNodes()) {
            nodes = new ArrayList<>(nodes.subList(0, config.maxNodes()));
        }

        // ── 9. Filter edges by surviving nodes ──────────────────────────
        Set<String> nodeIds = nodes.stream().map(DiagramNode::id).collect(Collectors.toSet());
        edges.removeIf(e -> !nodeIds.contains(e.sourceId()) || !nodeIds.contains(e.targetId()));

        // ── 10. Sort edges: impact first, then by relevance ─────────────
        if (config.preferCrossCategoryRelations()) {
            edges.sort(Comparator
                    .comparing((DiagramEdge e) -> "impact".equals(e.relationCategory()) ? 0 : 1)
                    .thenComparing(Comparator.comparingDouble(DiagramEdge::relevance).reversed()));
        } else {
            edges.sort(Comparator.comparingDouble(DiagramEdge::relevance).reversed());
        }

        // ── 11. Limit edges ─────────────────────────────────────────────
        if (edges.size() > config.maxEdges()) {
            edges = new ArrayList<>(edges.subList(0, config.maxEdges()));
        }

        return new DiagramModel(rawModel.title(), nodes, edges, rawModel.layout());
    }

    // ── Leaf-only logic ─────────────────────────────────────────────────

    /**
     * Keeps only leaf nodes per layer.  When a layer has only non-leaf nodes,
     * they are retained as fallback.
     */
    private List<DiagramNode> applyLeafOnly(List<DiagramNode> nodes) {
        Map<String, List<DiagramNode>> leafByType = new LinkedHashMap<>();
        Map<String, List<DiagramNode>> nonLeafByType = new LinkedHashMap<>();

        // Nodes that are referenced as parentId by another node in this model are
        // intermediate nodes and must be treated as non-leaves, even if they are
        // neither a root (e.g. "BP") nor a scaffolding node (e.g. "BP-1000").
        Set<String> parentIds = nodes.stream()
                .map(DiagramNode::parentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (DiagramNode n : nodes) {
            if (isRootId(n.id()) || isScaffoldingId(n.id()) || parentIds.contains(n.id())) {
                nonLeafByType.computeIfAbsent(n.type(), k -> new ArrayList<>()).add(n);
            } else {
                leafByType.computeIfAbsent(n.type(), k -> new ArrayList<>()).add(n);
            }
        }

        List<DiagramNode> result = new ArrayList<>();
        Set<String> allTypes = new LinkedHashSet<>();
        nodes.stream()
                .sorted(Comparator.comparingInt(DiagramNode::layer))
                .forEach(n -> allTypes.add(n.type()));

        for (String type : allTypes) {
            List<DiagramNode> leaves = leafByType.getOrDefault(type, List.of());
            if (!leaves.isEmpty()) {
                result.addAll(leaves);
            } else {
                result.addAll(nonLeafByType.getOrDefault(type, List.of()));
            }
        }
        return result;
    }

    /**
     * Re-routes edges from suppressed nodes to surviving nodes in the same layer,
     * distributing load across multiple survivors when available.
     */
    private List<DiagramEdge> rerouteEdges(List<DiagramEdge> originalEdges,
                                            List<DiagramNode> survivingNodes,
                                            List<DiagramNode> allOriginalNodes) {
        Set<String> survivorIds = survivingNodes.stream()
                .map(DiagramNode::id).collect(Collectors.toSet());

        // Build reroute map using the load-balancing strategy
        Map<String, String> reroute = new LinkedHashMap<>();
        Map<String, List<DiagramNode>> survivorsByType = survivingNodes.stream()
                .collect(Collectors.groupingBy(DiagramNode::type));
        EdgeRerouteStrategy strategy = new EdgeRerouteStrategy();

        for (DiagramNode orig : allOriginalNodes) {
            if (!survivorIds.contains(orig.id())) {
                List<DiagramNode> sameType = survivorsByType.getOrDefault(orig.type(), List.of());
                strategy.selectTarget(orig, sameType)
                        .ifPresent(target -> reroute.put(orig.id(), target));
            }
        }

        List<DiagramEdge> result = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        for (DiagramEdge e : originalEdges) {
            String src = reroute.getOrDefault(e.sourceId(), e.sourceId());
            String tgt = reroute.getOrDefault(e.targetId(), e.targetId());
            if (!survivorIds.contains(src) || !survivorIds.contains(tgt)) continue;
            if (src.equals(tgt)) continue;
            String sig = src + "->" + tgt + ":" + e.relationType();
            if (signatures.add(sig)) {
                result.add(new DiagramEdge(e.id(), src, tgt,
                        e.relationType(), e.relevance(), e.relationCategory()));
            }
        }
        return result;
    }

    // ── Clustering / containment logic ──────────────────────────────────

    /**
     * Applies clustering: intermediate nodes that group ≥ 2 children become
     * visual containers; those with exactly 1 child are collapsed (child lifted).
     * Works generically across all taxonomy categories.
     */
    private List<DiagramNode> applyClustering(List<DiagramNode> nodes) {
        // Index nodes by id
        Map<String, DiagramNode> byId = nodes.stream()
                .collect(Collectors.toMap(DiagramNode::id, n -> n, (a, b) -> a));

        // Count children per parentId (only counting children present in the model)
        Map<String, List<DiagramNode>> childrenOf = new LinkedHashMap<>();
        for (DiagramNode n : nodes) {
            if (n.parentId() != null && byId.containsKey(n.parentId())) {
                childrenOf.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n);
            }
        }

        List<DiagramNode> result = new ArrayList<>();
        Set<String> collapsedParents = new HashSet<>();

        for (DiagramNode n : nodes) {
            List<DiagramNode> children = childrenOf.getOrDefault(n.id(), List.of());
            if (children.isEmpty()) {
                // Leaf or no-child node — keep as-is
                result.add(n);
            } else if (children.size() == 1 && config.collapseRedundantParentChild()) {
                // Single child: suppress parent, lift child (child keeps its own identity)
                collapsedParents.add(n.id());
            } else if (children.size() >= 2) {
                // Multiple children: mark as container
                result.add(new DiagramNode(n.id(), n.label(), n.type(),
                        n.relevance(), n.anchor(), n.layer(),
                        n.depth(), n.selectedForImpact(), n.parentId(), true));
            } else {
                // Single child but collapse disabled — keep as normal node
                result.add(n);
            }
        }

        // Update parentId of children whose parent was collapsed
        List<DiagramNode> finalResult = new ArrayList<>();
        for (DiagramNode n : result) {
            if (n.parentId() != null && collapsedParents.contains(n.parentId())) {
                // Lift: clear the parent reference (or re-parent to grandparent)
                DiagramNode grandparent = byId.get(n.parentId());
                String newParent = grandparent != null ? grandparent.parentId() : null;
                finalResult.add(new DiagramNode(n.id(), n.label(), n.type(),
                        n.relevance(), n.anchor(), n.layer(),
                        n.depth(), n.selectedForImpact(), newParent, n.container()));
            } else {
                finalResult.add(n);
            }
        }
        return finalResult;
    }

    // ── Utility methods ─────────────────────────────────────────────────

    /** Returns {@code true} if the ID is a two-letter taxonomy root code (no dash). */
    static boolean isRootId(String id) {
        return id != null && !id.contains("-") && id.length() <= 2;
    }

    /**
     * Returns {@code true} if the ID matches the taxonomy scaffolding pattern
     * {@code XX-1000} (two uppercase letters, dash, 1000).
     */
    static boolean isScaffoldingId(String id) {
        if (id == null || id.length() != 7) return false;
        if (id.charAt(2) != '-') return false;
        char c0 = id.charAt(0), c1 = id.charAt(1);
        if (c0 < 'A' || c0 > 'Z' || c1 < 'A' || c1 > 'Z') return false;
        return id.endsWith("-1000");
    }
}
