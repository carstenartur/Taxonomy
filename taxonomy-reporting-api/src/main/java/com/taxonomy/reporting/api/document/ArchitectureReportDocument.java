package com.taxonomy.reporting.api.document;

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
        SnapshotEvidence evidence,
        Map<String, String> nodeReasons,
        Map<String, Map<String, String>> relationReasons) {

    /** Older callers retain their captured graph without inventing missing explanation evidence. */
    public ArchitectureReportDocument(String title, String languageTag, String requirement, String scope,
            String recommendation, List<String> unresolvedGaps, DiagramModel diagram, DiagramScene scene,
            List<ElementRow> elements, List<RelationRow> relations, List<LegendEntry> legend,
            DecisionTreeOverview decisionTree, SnapshotEvidence evidence) {
        this(title, languageTag, requirement, scope, recommendation, unresolvedGaps, diagram, scene,
                elements, relations, legend, decisionTree, evidence, Map.of(), Map.of());
    }

    /** Source-compatible constructor for reports created before separate relation mapping evidence. */
    public ArchitectureReportDocument(String title, String languageTag, String requirement, String scope,
            String recommendation, List<String> unresolvedGaps, DiagramModel diagram, DiagramScene scene,
            List<ElementRow> elements, List<RelationRow> relations, List<LegendEntry> legend,
            DecisionTreeOverview decisionTree, SnapshotEvidence evidence, Map<String, String> nodeReasons) {
        this(title, languageTag, requirement, scope, recommendation, unresolvedGaps, diagram, scene,
                elements, relations, legend, decisionTree, evidence, nodeReasons, Map.of());
    }

    public ArchitectureReportDocument withNodeReasons(Map<String, String> reasons) {
        return new ArchitectureReportDocument(title, languageTag, requirement, scope, recommendation,
                unresolvedGaps, diagram, scene, elements, relations, legend, decisionTree, evidence, reasons, relationReasons);
    }

    public ArchitectureReportDocument withRelationReasons(Map<String, Map<String, String>> reasons) {
        return new ArchitectureReportDocument(title, languageTag, requirement, scope, recommendation,
                unresolvedGaps, diagram, scene, elements, relations, legend, decisionTree, evidence, nodeReasons, reasons);
    }
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
        ArchitectureEvidence.validateEvidence(diagram, scene);
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
        nodeReasons = nodeReasons == null ? Map.of() : Map.copyOf(nodeReasons);
        var selectedReasons = new LinkedHashMap<String, Map<String, String>>();
        if (relationReasons != null) {
            for (var edge : diagram.edges()) {
                var fields = relationReasons.get(edge.id());
                if (fields == null) continue;
                var copied = new LinkedHashMap<String, String>();
                for (String field : List.of("presenceReason", "decisionComment")) {
                    String value = fields.get(field);
                    if (value != null && !value.isBlank()) copied.put(field, value);
                }
                if (!copied.isEmpty()) selectedReasons.put(edge.id(), Map.copyOf(copied));
            }
        }
        relationReasons = Map.copyOf(selectedReasons);
        if (evidence == null || !graphSha256(diagram).equals(evidence.graphSha256()))
            throw new IllegalArgumentException(
                    "Frozen graph digest disagrees with source evidence");
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
                // Preserve historical digests when provenance was absent; newly recorded evidence participates fully.
                if (e.metadata() != null) {
                    field(out, "relationship-provenance-v1");
                    provenance(out, e.metadata());
                }
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not fingerprint architecture graph", exception);
        }
    }

    private static void provenance(DataOutputStream out, DiagramRelationMetadata m) throws IOException {
        field(out, m.relationId() == null ? null : m.relationId().toString());
        field(out, m.origin() == null ? null : m.origin().name());
        out.writeBoolean(m.confidence() != null);
        if (m.confidence() != null) out.writeDouble(m.confidence());
        field(out, m.includedBecause()); field(out, m.derivationReason()); field(out, m.presenceReason());
        field(out, m.seedType() == null ? null : m.seedType().name());
        // Evidence collection order is presentation only; fingerprint every field in canonical byte order.
        var evidence = new ArrayList<byte[]>();
        for (var item : m.requirementEvidence()) {
            var bytes = new ByteArrayOutputStream();
            evidence(new DataOutputStream(bytes), item);
            evidence.add(bytes.toByteArray());
        }
        evidence.sort(Arrays::compareUnsigned);
        out.writeInt(evidence.size());
        for (byte[] item : evidence) { out.writeInt(item.length); out.write(item); }
    }

    private static void evidence(DataOutputStream out, com.taxonomy.dto.RelationSearchModel.Edge edge) throws IOException {
        var c = edge.contribution();
        evidenceNode(out, c.source()); field(out, c.text()); field(out, c.quote()); field(out, c.condition());
        evidenceNode(out, edge.target()); field(out, edge.type()); field(out, edge.direction().name());
        var d = edge.evidence();
        out.writeBoolean(d != null);
        if (d != null) {
            field(out, d.targetId()); field(out, d.outcome() == null ? null : d.outcome().name());
            field(out, d.contribution()); field(out, d.quote()); field(out, d.necessity() == null ? null : d.necessity().name());
            field(out, d.condition()); field(out, d.alternativeGroup()); field(out, d.rationale()); field(out, d.question());
        }
    }

    private static void evidenceNode(DataOutputStream out, com.taxonomy.dto.RelationSearchModel.Node node) throws IOException {
        field(out, node.id()); field(out, node.root()); field(out, node.name()); field(out, node.description());
        out.writeBoolean(node.container());
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
