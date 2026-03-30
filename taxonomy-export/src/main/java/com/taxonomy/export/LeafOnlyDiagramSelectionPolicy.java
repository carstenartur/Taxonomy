package com.taxonomy.export;

/**
 * Selection policy that produces <strong>leaf-only</strong> diagrams.
 *
 * <p>All intermediate, scaffolding, and root nodes are suppressed when concrete
 * leaf nodes exist.  Edges from suppressed nodes are re-routed to the best
 * surviving leaf in the same layer so cross-layer relations remain visible.</p>
 *
 * <p>This is the policy behind the showcase / README diagram mode.</p>
 */
public class LeafOnlyDiagramSelectionPolicy extends ConfigurableDiagramSelectionPolicy {

    public LeafOnlyDiagramSelectionPolicy() {
        super(DiagramSelectionConfig.leafOnly());
    }

    public LeafOnlyDiagramSelectionPolicy(DiagramSelectionConfig config) {
        super(config);
    }
}
