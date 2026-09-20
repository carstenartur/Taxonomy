package com.taxonomy.architecture.report;

import com.taxonomy.diagram.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Immutable evidence shared by both frozen Word artifacts. The graph content digest includes saved
 * node labels as frozen evidence; report, diagram and scene presentation titles are excluded.
 * This is a content digest, not a name-insensitive identity digest.
 */
public record ArchitectureReportDocument(
        String title,
        String languageTag,
        String requirement,
        String scope,
        String recommendation,
        List<String> unresolvedGaps,
        DiagramModel diagram,
        DiagramScene scene,
        List<ElementRow> elements,
        List<RelationRow> relations,
        List<LegendEntry> legend,
        DecisionTreeOverview decisionTree,
        SnapshotEvidence evidence) {
    public record ElementRow(
            String id,
            String title,
            String type,
            double relevance,
            boolean anchor,
            String parentId,
            boolean container) {}

    public record RelationRow(
            String id,
            String sourceId,
            String targetId,
            String type,
            String category,
            double relevance) {}

    public record LegendEntry(String symbol, String meaning) {}

    public record SnapshotEvidence(
            Long projectId,
            Long requirementId,
            Long requirementVersionId,
            Integer requirementVersionNumber,
            String snapshotId,
            String repositoryId,
            String workspaceId,
            String branch,
            String commit,
            String provider,
            String model,
            String taxonomyFingerprint,
            String graphSha256) {}

    public ArchitectureReportDocument {
        ArchitectureFigurePlanner.validate(diagram, scene);
        diagram =
                new DiagramModel(
                        diagram.title(),
                        List.copyOf(diagram.nodes()),
                        List.copyOf(diagram.edges()),
                        diagram.layout());
        unresolvedGaps = List.copyOf(unresolvedGaps);
        elements = List.copyOf(elements);
        relations = List.copyOf(relations);
        legend = List.copyOf(legend);
        if (evidence == null || !graphSha256(diagram).equals(evidence.graphSha256()))
            throw new IllegalArgumentException(
                    "Frozen graph digest disagrees with source evidence");
    }

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
        var labels = new com.taxonomy.architecture.decision.DecisionReportLabels(language);
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

    public static String graphSha256(DiagramModel graph) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            field(out, "taxonomy-frozen-graph-v1");
            for (var n :
                    graph.nodes().stream().sorted(Comparator.comparing(DiagramNode::id)).toList()) {
                field(out, "node");
                field(out, n.id());
                field(out, n.label());
                field(out, n.type());
                out.writeDouble(n.relevance());
                out.writeBoolean(n.anchor());
                out.writeInt(n.layer());
                out.writeInt(n.depth());
                out.writeBoolean(n.selectedForImpact());
                field(out, n.parentId());
                out.writeBoolean(n.container());
            }
            for (var e :
                    graph.edges().stream().sorted(Comparator.comparing(DiagramEdge::id)).toList()) {
                field(out, "edge");
                field(out, e.id());
                field(out, e.sourceId());
                field(out, e.targetId());
                field(out, e.relationType());
                out.writeDouble(e.relevance());
                field(out, e.relationCategory());
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not fingerprint architecture graph", exception);
        }
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
