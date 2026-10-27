package com.taxonomy.portfolio.workbench;

import com.taxonomy.visio.VisioExportMetadata;
import com.taxonomy.visio.VisioLoss;
import com.taxonomy.visio.VisioProperty;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allowlisted immutable authority and authorized human decision data for external handoff. */
final class VisioSnapshotMetadata {
    private VisioSnapshotMetadata() { }

    static VisioExportMetadata from(Projection projection, DiagramModel graph, String graphSha256) {
        return from(projection, graph, graphSha256, null);
    }

    static VisioExportMetadata from(Projection projection, DiagramModel graph, String graphSha256,
            VisioExportContextService.ExportContext context) {
        Map<String, VisioProperty> properties = new LinkedHashMap<>();
        if (context != null) properties.putAll(context.documentProperties());
        List<VisioLoss> losses = new ArrayList<>();
        String id = projection.snapshotId();
        put(properties, "snapshotId", id);
        put(properties, "requirementText", projection.requirementText());
        put(properties, "requirementKey", projection.requirementKey());
        put(properties, "requirementTitle", projection.requirementTitle());
        put(properties, "projectKey", projection.projectKey());
        put(properties, "projectTitle", projection.projectTitle());
        put(properties, "snapshotStatus", projection.snapshotStatus());
        put(properties, "snapshotCreatedAt", projection.snapshotCreatedAt());
        if (projection.snapshotCreatedAt() != null) {
            put(properties, "generatedAt", projection.snapshotCreatedAt());
            put(properties, "timestampPolicy", "SOURCE_SNAPSHOT_CREATED_AT");
        } else {
            losses.add(new VisioLoss("document", id, "generatedAt", "OMITTED", "No persisted timestamp; no wall-clock value was substituted in this deterministic export."));
        }
        put(properties, "projectId", projection.projectId());
        put(properties, "requirementId", projection.requirementId());
        put(properties, "workspaceId", projection.workspaceId());
        put(properties, "branch", projection.branchName());
        put(properties, "authoritativeCommit", projection.commitSha());
        put(properties, "canonicalGraphSha256", graphSha256);
        if (projection.exportProvenance() != null) {
            put(properties, "requirementVersionId", projection.exportProvenance().requirementVersionId());
            put(properties, "requirementVersionCreatedBy", projection.exportProvenance().requirementVersionCreatedBy());
            put(properties, "requirementVersionCreatedAt", projection.exportProvenance().requirementVersionCreatedAt());
            put(properties, "taxonomyFingerprint", projection.exportProvenance().taxonomyFingerprint());
            put(properties, "repositoryId", projection.exportProvenance().repositoryId());
        }
        for (String field : List.of("requirementVersionId", "requirementVersionCreatedBy", "requirementVersionCreatedAt",
                "taxonomyFingerprint", "repositoryId", "selectionProfileFingerprint", "requirementText",
                "generatorName", "generatorVersion", "exportedBy", "exportedAt", "requirementUrl")) {
            if (!properties.containsKey("taxonomy." + field)) {
                losses.add(new VisioLoss("model", id, field, "OMITTED",
                        field.equals("requirementUrl")
                                ? "No editing link is available; consult the visible requirement URL status. No request-derived or relative URL was substituted."
                                : "Unavailable in the authorized snapshot or explicit export context; no current-state value was substituted."));
            }
        }
        for (String field : List.of("providerPayload", "prompt", "sourceEvidence", "warnings")) {
            losses.add(new VisioLoss("model", id, field, "OMITTED",
                    field.equals("sourceEvidence")
                            ? "Raw model-level provider/source evidence is omitted; recorded scoped relationship evidence is retained where present."
                            : "Outside the authorized model exchange subset; use the canonical evidence report when separately authorized."));
        }
        Map<String, Map<String, VisioProperty>> elements = new LinkedHashMap<>();
        for (var node : graph.nodes()) {
            if (node.container()) continue;
            Map<String, VisioProperty> values = new LinkedHashMap<>();
            var metadata = projection.elements().get(node.id());
            if (metadata != null) {
                values.put("taxonomy.directScore", VisioProperty.number(metadata.directScore()));
                values.put("taxonomy.confidence", VisioProperty.number(metadata.confidence()));
                put(values, "taxonomyRoot", metadata.taxonomyRoot());
                put(values, "mappingOrigin", metadata.mappingOrigin());
                put(values, "hierarchyPath", metadata.hierarchyPath());
                put(values, "reviewStatus", metadata.reviewStatus());
                put(values, "actionStatus", metadata.actionStatus());
                put(values, "actionEvidence", metadata.actionEvidence());
                put(values, "decisionBy", metadata.decisionBy());
                put(values, "decisionAt", metadata.decisionAt());
                put(values, "decisionRationale", metadata.decisionComment());
                put(values, "presenceReason", projection.nodeReasons().get(node.id()));
                if (!projection.nodeReasons().containsKey(node.id())) {
                    losses.add(new VisioLoss("element", node.id(), "presenceReason", "OMITTED",
                            "No original explanation was recorded for this selected element."));
                }
            } else {
                losses.add(new VisioLoss("element", node.id(), "reviewMetadata", "OMITTED",
                        "No persisted mapping metadata is available for this selected element."));
            }
            if (projection.nodeReasons().containsKey(node.id()))
                put(values, "presenceReason", projection.nodeReasons().get(node.id()));
            elements.put(node.id(), values);
        }
        Map<String, Map<String, VisioProperty>> relations = new LinkedHashMap<>();
        for (var edge : graph.edges()) {
            var metadata = projection.relations().get(edge.sourceId() + "|" + edge.targetId() + "|" + edge.relationType());
            if (metadata == null) {
                losses.add(new VisioLoss("relationship", edge.id(), "reviewMetadata", "OMITTED", "No persisted review metadata is available for this selected relationship."));
                continue;
            }
            Map<String, VisioProperty> values = new LinkedHashMap<>();
            values.put("taxonomy.confidence", VisioProperty.number(metadata.confidence()));
            put(values, "relationOrigin", metadata.relationOrigin());
            put(values, "reviewStatus", metadata.reviewStatus());
            put(values, "decisionBy", metadata.decisionBy());
            put(values, "decisionAt", metadata.decisionAt());
            put(values, "decisionRationale", metadata.decisionComment());
            // Selection evidence is separate from the neutral relation's recorded presence reason.
            put(values, "selectionReason", metadata.presenceReason());
            relations.put(edge.id(), values);
            if (edge.metadata() == null || edge.metadata().presenceReason() == null) {
                losses.add(new VisioLoss("relationship", edge.id(), "presenceReason", "OMITTED",
                        "No recorded neutral relationship presence reason is exported; reviewed human rationale is retained."));
            }
        }
        return new VisioExportMetadata(properties, elements, relations, losses);
    }

    private static void put(Map<String, VisioProperty> properties, String name, Object value) {
        if (value != null) properties.put("taxonomy." + name, VisioProperty.text(value.toString()));
    }
}
