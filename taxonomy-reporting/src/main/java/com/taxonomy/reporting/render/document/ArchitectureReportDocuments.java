package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;

import com.taxonomy.diagram.*;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument.*;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import java.util.*;

/** Builds presentation rows from already captured graph evidence. */
public final class ArchitectureReportDocuments {
    private ArchitectureReportDocuments() {}

    public static ArchitectureReportDocument from(
            String title,
            String language,
            String requirement,
            String scope,
            String recommendation,
            List<String> gaps,
            DiagramModel graph,
            DiagramScene scene,
            DecisionTreeOverview tree,
            SnapshotEvidence evidence) {
        var labels = new com.taxonomy.reporting.render.decision.DecisionReportLabels(language);
        return new ArchitectureReportDocument(
                title,
                language,
                requirement,
                scope,
                recommendation,
                gaps,
                graph,
                scene,
                graph.nodes().stream()
                        .sorted(Comparator.comparing(DiagramNode::id))
                        .map(
                                n ->
                                        new ElementRow(
                                                n.id(),
                                                n.label(),
                                                n.type(),
                                                n.relevance(),
                                                n.anchor(),
                                                n.parentId(),
                                                n.container()))
                        .toList(),
                graph.edges().stream()
                        .sorted(Comparator.comparing(DiagramEdge::id))
                        .map(
                                e ->
                                        new RelationRow(
                                                e.id(),
                                                e.sourceId(),
                                                e.targetId(),
                                                e.relationType(),
                                                e.relationCategory(),
                                                e.relevance()))
                        .toList(),
                List.of(
                        new LegendEntry("●", labels.anchor()),
                        new LegendEntry("→", labels.directedRelation()),
                        new LegendEntry("□", labels.container())),
                tree,
                evidence);
    }

}
