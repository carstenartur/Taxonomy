package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Dependency and mapping checks only; the host also validates endpoint kinds and canonical semantics. */
final class QeaSelectionValidator {
    static final Set<String> ELEMENT_TYPES = Set.of("Capability", "Process", "CoreService", "COIService",
            "CommunicationsService", "UserApplication", "InformationProduct", "BusinessRole", "System", "Component");
    private static final Set<String> RELATION_TYPES = Set.of("DEPENDS_ON", "REALIZES", "COMMUNICATES_WITH", "RELATED_TO",
            "CONSUMES", "CONTAINS", "SUPPORTS", "USES", "FULFILLS", "ASSIGNED_TO", "PRODUCES", "REQUIRES");
    private QeaSelectionValidator() { }

    static void validate(OutboundRequest selection) {
        if (selection == null || selection.context() == null || selection.document() == null)
            throw fail("QEA_PROFILE", "A reviewed QEA selection is required");
        var document = selection.document();
        if (!SparxQeaConnector.PROFILE.equals(document.profile()) || !SparxQeaConnector.VERSION.equals(document.profileVersion())
                || !document.profile().equals(selection.context().profile()) || !document.profileVersion().equals(selection.context().profileVersion()))
            throw fail("QEA_PROFILE", "Reviewed selection must use sparx-qea profile version 1");
        String model = QeaIds.guid(document.metadata().get("identifier"), "QEA_MODEL_IDENTITY");
        if ((long) document.artifacts().size() + document.relations().size() + document.placements().size() > QeaDatabase.MAX_ROWS * 8L)
            throw fail("QEA_LIMIT", "The reviewed projection exceeds the supported item budget");
        Map<String, Artifact> artifacts = new HashMap<>();
        Set<String> allIds = new HashSet<>();
        for (Artifact artifact : document.artifacts()) {
            unique(allIds, artifact.id());
            artifacts.put(artifact.id(), artifact);
            if (artifact.kind() == ArtifactKind.ELEMENT && !ELEMENT_TYPES.contains(artifact.extensions().getOrDefault("canonicalType", "")))
                throw fail("QEA_ELEMENT_MAPPING_REQUIRED", "Reject or explicitly map each unsupported element before apply");
        }
        for (Relation relation : document.relations()) {
            unique(allIds, relation.id());
            String projection = relation.extensions().getOrDefault("nativeProjection", "ARCHITECTURE_RELATION");
            if (projection.equals("PRESERVE_ONLY")) continue;
            if (!Set.of("ARCHITECTURE_RELATION", "REQUIREMENT_MAPPING").contains(projection))
                throw fail("QEA_RELATION_MAPPING_REQUIRED", "Select a supported relation projection");
            if (!artifacts.containsKey(relation.source()) || !artifacts.containsKey(relation.target()))
                throw fail("QEA_SELECTION_DEPENDENCY", "An accepted connector endpoint is missing or unresolved; reject it or choose preserve-only");
            if (projection.equals("ARCHITECTURE_RELATION") && !RELATION_TYPES.contains(relation.extensions().getOrDefault("canonicalType", "")))
                throw fail("QEA_RELATION_MAPPING_REQUIRED", "Reject, explicitly map, or preserve each unsupported connector");
            String direction = relation.extensions().getOrDefault("direction", "Unspecified");
            if (!Set.of("Source -> Destination", "Destination -> Source", "Unspecified").contains(direction)
                    || direction.equals("Unspecified") && !"RELATED_TO".equals(relation.extensions().get("canonicalType")))
                throw fail("QEA_RELATION_DIRECTION", "Ambiguous or bidirectional source connectors require preserve-only review");
        }
        Map<String, Placement> placements = new HashMap<>();
        for (Placement placement : document.placements()) { unique(allIds, placement.id()); placements.put(placement.id(), placement); }
        for (Placement placement : document.placements()) {
            if (!artifacts.containsKey(placement.artifactId())) dependency("An accepted placement member is outside the selected scope");
            boolean packagePlacement = placement.containerId().equals(model);
            Artifact container = artifacts.get(placement.containerId());
            if (!packagePlacement && (container == null || container.kind() != ArtifactKind.VIEW))
                dependency("An accepted diagram placement needs its selected view");
            String declared = placement.attributes().get("placementKind");
            if (declared != null && !declared.equals(packagePlacement ? "package" : "diagram")) dependency("Placement kind contradicts its container");
            if (!packagePlacement && placement.parentId() != null) dependency("Diagram membership cannot inherit package-placement ancestry");
            Set<String> ancestors = new HashSet<>();
            Placement cursor = placement;
            while (cursor.parentId() != null && !cursor.parentId().isEmpty()) {
                if (!ancestors.add(cursor.id())) dependency("Accepted placement ancestry is cyclic");
                if (ancestors.size() > QeaDatabase.MAX_DEPTH) throw fail("QEA_LIMIT", "Accepted placement ancestry exceeds 128 levels");
                Placement parent = placements.get(cursor.parentId());
                Artifact parentArtifact = parent == null ? null : artifacts.get(parent.artifactId());
                if (parent == null || !parent.containerId().equals(model) || parentArtifact == null || parentArtifact.kind() != ArtifactKind.SPECIFICATION)
                    dependency("An accepted package parent is missing, rejected, or not a package placement");
                cursor = parent;
            }
        }
    }
    private static void unique(Set<String> ids, String id) {
        if (!ids.add(id)) throw fail("QEA_DUPLICATE_IDENTITY", "The selected projection repeats an external identity");
    }
    private static void dependency(String message) { throw fail("QEA_SELECTION_DEPENDENCY", message); }
}
