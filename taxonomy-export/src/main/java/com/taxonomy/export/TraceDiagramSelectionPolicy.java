package com.taxonomy.export;

/**
 * Selection policy that preserves the <strong>full hierarchy</strong> for traceability.
 *
 * <p>No nodes or relations are suppressed — root codes, scaffolding containers,
 * and intermediate nodes are all retained.  This mode is intended for scoring
 * trace views where every hop in the propagation chain must be visible.</p>
 */
public class TraceDiagramSelectionPolicy extends ConfigurableDiagramSelectionPolicy {

    public TraceDiagramSelectionPolicy() {
        super(DiagramSelectionConfig.trace());
    }

    public TraceDiagramSelectionPolicy(DiagramSelectionConfig config) {
        super(config);
    }
}
