package com.taxonomy.dsl.command;

import java.util.Map;
import java.util.Objects;

/** Renderer- and framework-independent semantic intents. Identity is never an editable property. */
public sealed interface ArchitectureCommand {
    record CreateArchitectureElement(String id, String type, Map<String, String> properties)
            implements ArchitectureCommand {
        public CreateArchitectureElement { properties = Map.copyOf(properties); }
    }

    record UpdateArchitectureElement(String id, String type, Map<String, String> properties)
            implements ArchitectureCommand {
        public UpdateArchitectureElement { properties = Map.copyOf(properties); }
    }

    record DeleteArchitectureElement(String id) implements ArchitectureCommand {}

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
}
