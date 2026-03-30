package com.taxonomy.export;

/**
 * Rule-based configuration for {@link DiagramSelectionPolicy} implementations.
 *
 * <p>Each boolean flag enables or disables a specific curation rule.
 * Preset factory methods provide well-tested defaults for common use-cases.</p>
 *
 * @param suppressRootNodes            suppress two-letter root codes (e.g. "CP") when concrete descendants exist
 * @param suppressScaffoldingNodes     suppress first-level containers matching {@code XX-1000} when concrete nodes exist
 * @param collapseRedundantParentChild when an intermediate has exactly one strong child, suppress the intermediate and lift the child
 * @param leafOnlyMode                 only show the deepest (leaf) nodes; suppress all intermediate and root nodes
 * @param allowIntermediateAsClusters  retain intermediate nodes as visual containers when they group ≥ 2 strong children
 * @param showSeedContextNodes         include seed-context origin nodes in the output
 * @param preferCrossCategoryRelations prioritise cross-category (impact) edges over same-category (trace) edges
 * @param minRelevance                 minimum relevance threshold for node inclusion (0.0–1.0); anchors and impact-selected nodes bypass this
 * @param maxNodes                     maximum number of nodes in the curated model
 * @param maxEdges                     maximum number of edges in the curated model
 */
public record DiagramSelectionConfig(
        boolean suppressRootNodes,
        boolean suppressScaffoldingNodes,
        boolean collapseRedundantParentChild,
        boolean leafOnlyMode,
        boolean allowIntermediateAsClusters,
        boolean showSeedContextNodes,
        boolean preferCrossCategoryRelations,
        double minRelevance,
        int maxNodes,
        int maxEdges
) {

    /** Default configuration matching the current {@code DiagramProjectionService} behaviour. */
    public static DiagramSelectionConfig defaultImpact() {
        return new DiagramSelectionConfig(
                true,   // suppressRootNodes
                true,   // suppressScaffoldingNodes
                false,  // collapseRedundantParentChild
                false,  // leafOnlyMode
                false,  // allowIntermediateAsClusters
                false,  // showSeedContextNodes
                true,   // preferCrossCategoryRelations
                0.35,   // minRelevance
                25,     // maxNodes
                40      // maxEdges
        );
    }

    /** Leaf-only mode for showcase / README diagrams. */
    public static DiagramSelectionConfig leafOnly() {
        return new DiagramSelectionConfig(
                true,   // suppressRootNodes
                true,   // suppressScaffoldingNodes
                true,   // collapseRedundantParentChild
                true,   // leafOnlyMode
                false,  // allowIntermediateAsClusters
                false,  // showSeedContextNodes
                true,   // preferCrossCategoryRelations
                0.0,    // minRelevance — keep all that survive the leaf filter
                25,     // maxNodes
                12      // maxEdges
        );
    }

    /** Clustering mode: intermediate nodes become visual containers when they group multiple strong children. */
    public static DiagramSelectionConfig clustering() {
        return new DiagramSelectionConfig(
                true,   // suppressRootNodes
                true,   // suppressScaffoldingNodes
                true,   // collapseRedundantParentChild
                false,  // leafOnlyMode
                true,   // allowIntermediateAsClusters
                false,  // showSeedContextNodes
                true,   // preferCrossCategoryRelations
                0.35,   // minRelevance
                30,     // maxNodes — more room for cluster containers
                40      // maxEdges
        );
    }

    /** Trace mode: preserve full hierarchy for traceability — nothing suppressed. */
    public static DiagramSelectionConfig trace() {
        return new DiagramSelectionConfig(
                false,  // suppressRootNodes
                false,  // suppressScaffoldingNodes
                false,  // collapseRedundantParentChild
                false,  // leafOnlyMode
                false,  // allowIntermediateAsClusters
                true,   // showSeedContextNodes
                false,  // preferCrossCategoryRelations
                0.0,    // minRelevance — show everything
                50,     // maxNodes
                60      // maxEdges
        );
    }
}
