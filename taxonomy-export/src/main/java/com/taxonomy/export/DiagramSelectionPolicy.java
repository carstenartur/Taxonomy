package com.taxonomy.export;

import com.taxonomy.diagram.DiagramModel;

/**
 * Strategy interface for curating a raw {@link DiagramModel} before rendering.
 *
 * <p>A selection policy decides <em>which</em> nodes and relations are visible,
 * which are suppressed, and which nodes become visual containers/clusters.
 * The renderer is only responsible for rendering the curated result — it must
 * not contain the main selection logic.</p>
 *
 * <p>Different policies can be plugged in to produce different diagram views
 * from the same underlying data:</p>
 * <ul>
 *   <li>{@link LeafOnlyDiagramSelectionPolicy} — leaf-only showcase diagrams</li>
 *   <li>{@link ClusteringDiagramSelectionPolicy} — clustered impact diagrams
 *       with intermediate nodes as visual containers</li>
 *   <li>{@link TraceDiagramSelectionPolicy} — full hierarchy for traceability</li>
 * </ul>
 *
 * <p>Every policy is rule-based and configurable via {@link DiagramSelectionConfig}.</p>
 */
@FunctionalInterface
public interface DiagramSelectionPolicy {

    /**
     * Curates the given raw diagram model according to this policy's rules.
     *
     * @param rawModel the unfiltered diagram model produced by projection
     * @return a new {@link DiagramModel} with filtered, sorted, and annotated
     *         nodes and edges ready for rendering
     */
    DiagramModel apply(DiagramModel rawModel);
}
