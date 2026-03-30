package com.taxonomy.export;

import com.taxonomy.diagram.DiagramModel;

/**
 * Selection policy that produces <strong>clustered impact</strong> diagrams.
 *
 * <p>Intermediate nodes that group two or more strong children are retained as
 * visual containers — the children appear <em>inside</em> their parent cluster
 * rather than as flat peers.  An intermediate with only a single strong child
 * is collapsed: the child is lifted and the intermediate suppressed.</p>
 *
 * <p>This containment is <strong>visual only</strong>.  Container-flagged nodes
 * carry {@link com.taxonomy.diagram.DiagramNode#container()}{@code == true} so
 * that export formats (ArchiMate, Structurizr) can distinguish grouping
 * constructs from semantically valid architecture elements.</p>
 */
public class ClusteringDiagramSelectionPolicy extends ConfigurableDiagramSelectionPolicy {

    public ClusteringDiagramSelectionPolicy() {
        super(DiagramSelectionConfig.clustering());
    }

    public ClusteringDiagramSelectionPolicy(DiagramSelectionConfig config) {
        super(config);
    }
}
