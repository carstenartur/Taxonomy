package com.taxonomy.dsl.command;

import com.taxonomy.dsl.model.CatalogueReference;

import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;

/** Renderer- and framework-independent semantic intents. Identity is never an editable property. */
public sealed interface ArchitectureCommand {
    /** The server supplies actor and time after exact-context authorization. */
    record ReviewRequirementRealization(String requirementId,
                                        com.taxonomy.dsl.requirements.RequirementRealization.Review review)
            implements ArchitectureCommand {
        public ReviewRequirementRealization { Objects.requireNonNull(requirementId); Objects.requireNonNull(review); }
    }
    /** Internal frozen-source intent. The server builds this; it is absent from the public wire union. */
    record SnapshotResponsibility(String unitId, String name, String sourceId) {
        public SnapshotResponsibility {
            Objects.requireNonNull(unitId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(sourceId);
        }
    }
    record SnapshotElement(String id, String type, String title, String description, SnapshotResponsibility responsibility,
                           CatalogueReference catalogueReference) {
        public SnapshotElement(String id, String type, String title, String description, SnapshotResponsibility responsibility) {
            this(id, type, title, description, responsibility, null);
        }
        public SnapshotElement(String id, String type, String title, String description) {
            this(id, type, title, description, null);
        }
    }
    record AdoptAnalysisSnapshot(String viewId, String title, String description, String graphFingerprint,
                                 List<SnapshotElement> elements, List<RelationKey> relations,
                                 Map<String, String> provenance, List<SnapshotEvidence> evidence) implements ArchitectureCommand {
        /** Original command shape for exact replay of previously retained views. */
        public AdoptAnalysisSnapshot(String viewId, String title, String description, String graphFingerprint,
                                    List<SnapshotElement> elements, List<RelationKey> relations, Map<String, String> provenance) {
            this(viewId, title, description, graphFingerprint, elements, relations, provenance, null);
        }
        public AdoptAnalysisSnapshot {
            elements = List.copyOf(elements); relations = List.copyOf(relations); provenance = copyProperties(provenance);
            provenance.values().forEach(SnapshotEvidence::requireUtf8);
            if (evidence == null) {
                if (provenance.containsKey(SnapshotEvidence.FINGERPRINT))
                    throw SnapshotEvidence.invalid("Marked snapshot requires its frozen evidence");
                evidence = List.of();
            } else {
                evidence = List.copyOf(evidence);
                var fields = new LinkedHashMap<>(provenance);
                String digest = SnapshotEvidence.fingerprint(viewId, fields.get(SnapshotEvidence.SNAPSHOT), evidence);
                String prior = fields.put(SnapshotEvidence.FINGERPRINT, digest);
                if (prior != null && !prior.equals(digest)) throw SnapshotEvidence.invalid("Snapshot evidence fingerprint mismatch");
                provenance = Map.copyOf(fields);
            }
        }
    }
    record SetArchitectureBrief(Map<String, String> fields) implements ArchitectureCommand {
        public SetArchitectureBrief { fields = copyProperties(fields); }
    }
    record SetArchitectureReference(String id, Map<String, String> values) implements ArchitectureCommand {
        public SetArchitectureReference { Objects.requireNonNull(id); values = copyProperties(values); }
    }
    record DeleteArchitectureReference(String id) implements ArchitectureCommand {}

    record SetRequirementPlanning(String requirementId, com.taxonomy.dsl.planning.PlanningEntry entry)
            implements ArchitectureCommand {
        public SetRequirementPlanning { Objects.requireNonNull(requirementId); Objects.requireNonNull(entry); }
    }
    /** Internal reviewed import only. Not exposed as a public editor wire command. */
    record ImportRequirementPlanning(String requirementId, com.taxonomy.dsl.planning.PlanningEntry entry)
            implements ArchitectureCommand {
        public ImportRequirementPlanning { Objects.requireNonNull(requirementId); Objects.requireNonNull(entry); }
    }
    record DeleteRequirementPlanning(String requirementId, String entryId) implements ArchitectureCommand {
        public DeleteRequirementPlanning { Objects.requireNonNull(requirementId); Objects.requireNonNull(entryId); }
    }

    record CreateArchitectureElement(String id, String type, Map<String, String> properties)
            implements ArchitectureCommand {
        public CreateArchitectureElement { properties = copyProperties(properties); }
    }

    record UpdateArchitectureElement(String id, String type, Map<String, String> properties)
            implements ArchitectureCommand {
        public UpdateArchitectureElement { properties = copyProperties(properties); }
    }

    record CreateArchitecturePackage(String id, Map<String, String> properties) implements ArchitectureCommand {
        public CreateArchitecturePackage { properties = copyProperties(properties); }
    }
    record UpdateArchitecturePackage(String id, Map<String, String> properties) implements ArchitectureCommand {
        public UpdateArchitecturePackage { properties = copyProperties(properties); }
    }
    enum PackageMemberKind { PACKAGE, ELEMENT }
    /** Empty parent is root; null/-1 explicitly detaches an ELEMENT. */
    record PackagePlacement(PackageMemberKind kind, String memberId, String parentPackageId, int position) {}
    record SetArchitecturePackagePlacements(List<PackagePlacement> placements,
                                           java.util.Set<String> completeParentScopes) implements ArchitectureCommand {
        public SetArchitecturePackagePlacements {
            placements = List.copyOf(placements); completeParentScopes = java.util.Set.copyOf(completeParentScopes);
        }
    }
    record DeleteArchitecturePackage(String id) implements ArchitectureCommand {}

    record UpsertOrganization(String id, Map<String, String> properties) implements ArchitectureCommand {
        public UpsertOrganization { properties = copyProperties(properties); }
    }
    record DeleteOrganization(String id) implements ArchitectureCommand {}
    record SetCatalogueResponsibility(String code, String organizationId) implements ArchitectureCommand {}
    record UpsertRequirementMapping(String requirementIdentity, String elementId, String rationale,
                                    Map<String, String> exchangeProperties) implements ArchitectureCommand {
        public UpsertRequirementMapping { exchangeProperties = copyProperties(exchangeProperties); }
    }
    record DeleteRequirementMapping(String requirementIdentity, String elementId) implements ArchitectureCommand {}

    record DeleteArchitectureElement(String id) implements ArchitectureCommand {}

    private static Map<String, String> copyProperties(Map<String, String> properties) {
        if (properties == null) throw new ArchitectureDslCommands.CommandProblem("INVALID_VALUE", "properties",
                "Properties must be a map of non-null strings", List.of());
        Map<String, String> snapshot = new LinkedHashMap<>(properties);
        snapshot.forEach((key, value) -> {
            if (key == null || value == null) throw new ArchitectureDslCommands.CommandProblem("INVALID_VALUE",
                    key == null ? "properties" : key, "Property names and values must be non-null strings", List.of());
        });
        return Map.copyOf(snapshot);
    }

    record RelationKey(String sourceId, String relationType, String targetId) {
        public RelationKey {
            Objects.requireNonNull(sourceId);
            Objects.requireNonNull(relationType);
            Objects.requireNonNull(targetId);
        }
        public String id() { return sourceId + " " + relationType + " " + targetId; }
    }

    record CreateArchitectureRelation(RelationKey relation, String status, Map<String, String> properties)
            implements ArchitectureCommand {
        public CreateArchitectureRelation {
            properties = copyProperties(properties);
            var allowed = java.util.Set.of("provenance", "x-focus-rationale", "x-focus-evidence",
                    "x-focus-revision", "x-focus-method", "x-focus-source-hash");
            properties.forEach((key, value) -> {
                if (!allowed.contains(key)) throw new ArchitectureDslCommands.CommandProblem("READ_ONLY_PROPERTY", key,
                        "Property is not editable on a created relation", List.of());
                if (value.length() > 8000) throw new ArchitectureDslCommands.CommandProblem("VALUE_TOO_LONG", key,
                        "Property exceeds 8000 characters", List.of());
                if (value.chars().anyMatch(character -> Character.isISOControl(character)
                        || character == 0xfffe || character == 0xffff)) {
                    throw new ArchitectureDslCommands.CommandProblem("INVALID_VALUE", key,
                            "Property contains unsupported control characters", List.of());
                }
            });
        }

        public CreateArchitectureRelation(RelationKey relation, String status) {
            this(relation, status, Map.of());
        }
    }
    record CreateArchitectureRelations(List<CreateArchitectureRelation> relations) implements ArchitectureCommand {
        public CreateArchitectureRelations {
            if (relations == null || relations.size() < 2 || relations.size() > 32 || relations.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Select 2 to 32 relationships");
            }
            relations = List.copyOf(relations);
            if (relations.stream().map(CreateArchitectureRelation::relation).distinct().count() != relations.size()) {
                throw new IllegalArgumentException("Select each relationship only once");
            }
        }
    }
    record UpdateArchitectureRelation(RelationKey relation, String status) implements ArchitectureCommand {}
    record DeleteArchitectureRelation(RelationKey relation) implements ArchitectureCommand {}

    /** Semantic containment; visual coordinates never enter this command. Null parent means ungroup. */
    record MoveOrGroupElement(String id, String parentId) implements ArchitectureCommand {}

    /** Reviewed integration metadata is a bounded extension of an existing semantic object. */
    record SetExchangeProperties(String objectKind, String id, Map<String, String> properties) implements ArchitectureCommand {
        public SetExchangeProperties { properties = copyProperties(properties); }
    }
    record ClearArchitectureElementProperties(String id, java.util.Set<String> properties) implements ArchitectureCommand {
        public ClearArchitectureElementProperties { properties = java.util.Set.copyOf(properties); }
    }
    record UpsertArchitectureView(String id, String title, String description, List<String> members, Map<String, String> properties) implements ArchitectureCommand {
        public UpsertArchitectureView { members = List.copyOf(members); properties = copyProperties(properties); }
    }
    record DeleteArchitectureView(String id) implements ArchitectureCommand {}
    /** Safe, reviewed interchange evidence belongs to its explicit integration/version boundary. */
    record StoreExchangeEvidence(String id, String profile, String profileVersion, String fingerprint, String source) implements ArchitectureCommand {}
}
