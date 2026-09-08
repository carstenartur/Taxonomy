package com.taxonomy.dsl.command;

import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;

/** Renderer- and framework-independent semantic intents. Identity is never an editable property. */
public sealed interface ArchitectureCommand {
    record CreateArchitectureElement(String id, String type, Map<String, String> properties)
            implements ArchitectureCommand {
        public CreateArchitectureElement { properties = copyProperties(properties); }
    }

    record UpdateArchitectureElement(String id, String type, Map<String, String> properties)
            implements ArchitectureCommand {
        public UpdateArchitectureElement { properties = copyProperties(properties); }
    }

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

    record CreateArchitectureRelation(RelationKey relation, String status) implements ArchitectureCommand {}
    record UpdateArchitectureRelation(RelationKey relation, String status) implements ArchitectureCommand {}
    record DeleteArchitectureRelation(RelationKey relation) implements ArchitectureCommand {}

    /** Semantic containment; visual coordinates never enter this command. Null parent means ungroup. */
    record MoveOrGroupElement(String id, String parentId) implements ArchitectureCommand {}

    /** Reviewed integration metadata is a bounded extension of an existing semantic object. */
    record SetExchangeProperties(String objectKind, String id, Map<String, String> properties) implements ArchitectureCommand {
        public SetExchangeProperties { properties = copyProperties(properties); }
    }
    record UpsertArchitectureView(String id, String title, List<String> members, Map<String, String> properties) implements ArchitectureCommand {
        public UpsertArchitectureView { members = List.copyOf(members); properties = copyProperties(properties); }
    }
    record DeleteArchitectureView(String id) implements ArchitectureCommand {}
    /** Safe, reviewed interchange evidence belongs to its explicit integration/version boundary. */
    record StoreExchangeEvidence(String id, String profile, String profileVersion, String fingerprint, String source) implements ArchitectureCommand {}
}
