package com.taxonomy.portfolio.workbench;

import com.taxonomy.archimate.ArchiMateExportMetadata;
import com.taxonomy.archimate.ArchiMateLoss;
import com.taxonomy.archimate.ArchiMateProperty;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allowlisted immutable authority and authorized human decision data for external handoff. */
final class ArchiMateSnapshotMetadata {
    private ArchiMateSnapshotMetadata() { }

    static ArchiMateExportMetadata from(Projection projection, DiagramModel graph, String graphSha256) {
        Map<String, ArchiMateProperty> properties = new LinkedHashMap<>();
        List<ArchiMateLoss> losses = new ArrayList<>();
        String id = projection.snapshotId();
        put(properties, "snapshotId", id);
        put(properties, "snapshotStatus", projection.snapshotStatus());
        put(properties, "snapshotCreatedAt", projection.snapshotCreatedAt());
        put(properties, "projectId", projection.projectId());
        put(properties, "requirementId", projection.requirementId());
        put(properties, "workspaceId", projection.workspaceId());
        put(properties, "branch", projection.branchName());
        put(properties, "authoritativeCommit", projection.commitSha());
        put(properties, "canonicalGraphSha256", graphSha256);
        if (projection.exportProvenance() != null) {
            put(properties, "requirementVersionId", projection.exportProvenance().requirementVersionId());
            put(properties, "taxonomyFingerprint", projection.exportProvenance().taxonomyFingerprint());
            put(properties, "repositoryId", projection.exportProvenance().repositoryId());
        }
        for (String field : List.of("requirementVersionId", "taxonomyFingerprint", "repositoryId", "selectionProfileFingerprint")) {
            if (!properties.containsKey("taxonomy." + field)) {
                losses.add(new ArchiMateLoss("model", id, field, "OMITTED",
                        "Not retained in the authorized persisted snapshot projection; no current-state value was substituted."));
            }
        }
        for (String field : List.of("requirementText", "providerPayload", "prompt", "sourceEvidence", "warnings")) {
            losses.add(new ArchiMateLoss("model", id, field, "OMITTED",
                    "Outside the authorized model exchange subset; use the canonical evidence report when separately authorized."));
        }
        Map<String, Map<String, ArchiMateProperty>> elements = new LinkedHashMap<>();
        for (var node : graph.nodes()) {
            if (node.container()) continue;
            Map<String, ArchiMateProperty> values = new LinkedHashMap<>();
            var metadata = projection.elements().get(node.id());
            if (metadata != null) {
                values.put("taxonomy.directScore", ArchiMateProperty.number(metadata.directScore()));
                values.put("taxonomy.confidence", ArchiMateProperty.number(metadata.confidence()));
                put(values, "taxonomyRoot", metadata.taxonomyRoot());
                put(values, "mappingOrigin", metadata.mappingOrigin());
                put(values, "hierarchyPath", metadata.hierarchyPath());
                put(values, "reviewStatus", metadata.reviewStatus());
                put(values, "actionStatus", metadata.actionStatus());
                put(values, "actionEvidence", metadata.actionEvidence());
                put(values, "decisionBy", metadata.decisionBy());
                put(values, "decisionAt", metadata.decisionAt());
                put(values, "decisionRationale", metadata.decisionComment());
                losses.add(new ArchiMateLoss("element", node.id(), "presenceReason", "OMITTED",
                        "Provider-derived explanation excluded; selection flags and reviewed human rationale are retained."));
            } else {
                losses.add(new ArchiMateLoss("element", node.id(), "reviewMetadata", "OMITTED",
                        "No persisted mapping metadata is available for this selected element."));
            }
            elements.put(node.id(), values);
        }
        Map<String, Map<String, ArchiMateProperty>> relations = new LinkedHashMap<>();
        for (var edge : graph.edges()) {
            var metadata = projection.relations().get(edge.sourceId() + "|" + edge.targetId() + "|" + edge.relationType());
            if (metadata == null) continue;
            Map<String, ArchiMateProperty> values = new LinkedHashMap<>();
            values.put("taxonomy.confidence", ArchiMateProperty.number(metadata.confidence()));
            put(values, "relationOrigin", metadata.relationOrigin());
            put(values, "reviewStatus", metadata.reviewStatus());
            put(values, "decisionBy", metadata.decisionBy());
            put(values, "decisionAt", metadata.decisionAt());
            put(values, "decisionRationale", metadata.decisionComment());
            relations.put(edge.id(), values);
            losses.add(new ArchiMateLoss("relationship", edge.id(), "presenceReason", "OMITTED",
                    "Provider-derived explanation excluded; reviewed human rationale is retained."));
        }
        return new ArchiMateExportMetadata("snapshot-" + id, properties, elements, relations, losses);
    }

    private static void put(Map<String, ArchiMateProperty> properties, String name, Object value) {
        if (value != null) properties.put("taxonomy." + name, ArchiMateProperty.text(value.toString()));
    }
}
