package com.nato.taxonomy.dsl.diff;

import com.nato.taxonomy.dsl.model.ArchitectureElement;
import com.nato.taxonomy.dsl.model.ArchitectureRelation;

import java.util.List;

/**
 * Result of diffing two {@link com.nato.taxonomy.dsl.model.CanonicalArchitectureModel} instances.
 *
 * <p>Contains the sets of added, removed, and changed elements and relations
 * between the "before" and "after" models. Used for incremental
 * materialization — only the delta needs to be applied to the database.
 *
 * @param addedElements    elements present in "after" but not in "before"
 * @param removedElements  elements present in "before" but not in "after"
 * @param changedElements  elements present in both but with different properties
 * @param addedRelations   relations present in "after" but not in "before"
 * @param removedRelations relations present in "before" but not in "after"
 * @param changedRelations relations present in both but with different properties
 */
public record ModelDiff(
        List<ArchitectureElement> addedElements,
        List<ArchitectureElement> removedElements,
        List<ElementChange> changedElements,
        List<ArchitectureRelation> addedRelations,
        List<ArchitectureRelation> removedRelations,
        List<RelationChange> changedRelations
) {

    /** True when nothing changed between the two models. */
    public boolean isEmpty() {
        return addedElements.isEmpty() && removedElements.isEmpty() && changedElements.isEmpty()
                && addedRelations.isEmpty() && removedRelations.isEmpty() && changedRelations.isEmpty();
    }

    /** Total number of changes (useful for summary display). */
    public int totalChanges() {
        return addedElements.size() + removedElements.size() + changedElements.size()
                + addedRelations.size() + removedRelations.size() + changedRelations.size();
    }

    /**
     * An element that exists in both models but has different properties.
     *
     * @param before the element in the "before" model
     * @param after  the element in the "after" model
     */
    public record ElementChange(ArchitectureElement before, ArchitectureElement after) {}

    /**
     * A relation that exists in both models but has different properties.
     *
     * @param before the relation in the "before" model
     * @param after  the relation in the "after" model
     */
    public record RelationChange(ArchitectureRelation before, ArchitectureRelation after) {}
}
