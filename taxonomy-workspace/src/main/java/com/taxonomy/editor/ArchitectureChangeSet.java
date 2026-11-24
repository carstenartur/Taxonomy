package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureRelation;
import com.taxonomy.dsl.command.ArchitectureCommand.RelationKey;
import com.taxonomy.dsl.command.ArchitectureCommand.UpsertRequirementMapping;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch.BlockChange;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureCommandPort.Metadata;

import java.util.List;
import java.util.Map;

/** Review one element, relationship and requirement allocation against a retained architecture version. */
public final class ArchitectureChangeSet {
    private ArchitectureChangeSet() {}

    public record Relation(String sourceId, String relationType, String targetId, Map<String, String> properties) {
        public Relation {
            if (sourceId == null || relationType == null || targetId == null) {
                throw new IllegalArgumentException("Relationship source, type and target are required");
            }
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    public record Allocation(String requirementId, String elementId) {
        public Allocation {
            if (requirementId == null || elementId == null) {
                throw new IllegalArgumentException("Allocation requirement and element are required");
            }
        }
    }

    public record Request(Context context, Metadata metadata, CreateArchitectureElement element,
                          Relation relation, Allocation allocation, String previewFingerprint) {
        public Request {
            if (context == null || context.commit() == null || metadata == null || element == null
                    || relation == null || allocation == null) {
                throw new IllegalArgumentException("An exact Git checkpoint, review metadata and all three changes are required");
            }
        }

        List<ArchitectureCommand> commands() {
            return List.of(element,
                    new CreateArchitectureRelation(new RelationKey(relation.sourceId(), relation.relationType(),
                            relation.targetId()), "accepted", relation.properties()),
                    new UpsertRequirementMapping(allocation.requirementId(), allocation.elementId(), metadata.rationale(), Map.of()));
        }
    }

    public record Preview(Context context, String fingerprint, List<BlockChange> changes) {}
}
