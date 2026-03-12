package com.nato.taxonomy.dsl.diff;

import com.nato.taxonomy.dsl.model.ArchitectureElement;
import com.nato.taxonomy.dsl.model.ArchitectureRelation;
import com.nato.taxonomy.dsl.model.CanonicalArchitectureModel;

import java.util.*;

/**
 * Computes a semantic diff between two {@link CanonicalArchitectureModel} instances.
 *
 * <p>The differ compares elements by their {@code id} and relations by
 * their composite key {@code (sourceId, relationType, targetId)}. This
 * produces a {@link ModelDiff} containing added, removed, and changed
 * items that can be used for incremental materialization.
 *
 * <p>This class is a pure library component — no Spring, no JPA, no
 * external dependencies beyond JDK 17.
 */
public class ModelDiffer {

    /**
     * Compute the diff between two canonical architecture models.
     *
     * @param before the "old" model (may be {@code null} to represent an empty baseline)
     * @param after  the "new" model (may be {@code null} to represent deletion of everything)
     * @return the diff result
     */
    public ModelDiff diff(CanonicalArchitectureModel before, CanonicalArchitectureModel after) {
        CanonicalArchitectureModel effectiveBefore = before != null ? before : new CanonicalArchitectureModel();
        CanonicalArchitectureModel effectiveAfter = after != null ? after : new CanonicalArchitectureModel();

        return new ModelDiff(
                diffAddedElements(effectiveBefore, effectiveAfter),
                diffRemovedElements(effectiveBefore, effectiveAfter),
                diffChangedElements(effectiveBefore, effectiveAfter),
                diffAddedRelations(effectiveBefore, effectiveAfter),
                diffRemovedRelations(effectiveBefore, effectiveAfter),
                diffChangedRelations(effectiveBefore, effectiveAfter)
        );
    }

    // ── Element diffing ──────────────────────────────────────────────

    private List<ArchitectureElement> diffAddedElements(CanonicalArchitectureModel before,
                                                         CanonicalArchitectureModel after) {
        Set<String> beforeIds = before.allElementIds();
        List<ArchitectureElement> added = new ArrayList<>();
        for (ArchitectureElement e : after.getElements()) {
            if (!beforeIds.contains(e.getId())) {
                added.add(e);
            }
        }
        return added;
    }

    private List<ArchitectureElement> diffRemovedElements(CanonicalArchitectureModel before,
                                                           CanonicalArchitectureModel after) {
        Set<String> afterIds = after.allElementIds();
        List<ArchitectureElement> removed = new ArrayList<>();
        for (ArchitectureElement e : before.getElements()) {
            if (!afterIds.contains(e.getId())) {
                removed.add(e);
            }
        }
        return removed;
    }

    private List<ModelDiff.ElementChange> diffChangedElements(CanonicalArchitectureModel before,
                                                               CanonicalArchitectureModel after) {
        Map<String, ArchitectureElement> beforeMap = indexElementsById(before);
        List<ModelDiff.ElementChange> changed = new ArrayList<>();
        for (ArchitectureElement afterElem : after.getElements()) {
            ArchitectureElement beforeElem = beforeMap.get(afterElem.getId());
            if (beforeElem != null && !elementsEqual(beforeElem, afterElem)) {
                changed.add(new ModelDiff.ElementChange(beforeElem, afterElem));
            }
        }
        return changed;
    }

    // ── Relation diffing ─────────────────────────────────────────────

    private List<ArchitectureRelation> diffAddedRelations(CanonicalArchitectureModel before,
                                                           CanonicalArchitectureModel after) {
        Set<String> beforeKeys = indexRelationKeys(before);
        List<ArchitectureRelation> added = new ArrayList<>();
        for (ArchitectureRelation r : after.getRelations()) {
            if (!beforeKeys.contains(relationKey(r))) {
                added.add(r);
            }
        }
        return added;
    }

    private List<ArchitectureRelation> diffRemovedRelations(CanonicalArchitectureModel before,
                                                             CanonicalArchitectureModel after) {
        Set<String> afterKeys = indexRelationKeys(after);
        List<ArchitectureRelation> removed = new ArrayList<>();
        for (ArchitectureRelation r : before.getRelations()) {
            if (!afterKeys.contains(relationKey(r))) {
                removed.add(r);
            }
        }
        return removed;
    }

    private List<ModelDiff.RelationChange> diffChangedRelations(CanonicalArchitectureModel before,
                                                                 CanonicalArchitectureModel after) {
        Map<String, ArchitectureRelation> beforeMap = indexRelationsByKey(before);
        List<ModelDiff.RelationChange> changed = new ArrayList<>();
        for (ArchitectureRelation afterRel : after.getRelations()) {
            ArchitectureRelation beforeRel = beforeMap.get(relationKey(afterRel));
            if (beforeRel != null && !relationsEqual(beforeRel, afterRel)) {
                changed.add(new ModelDiff.RelationChange(beforeRel, afterRel));
            }
        }
        return changed;
    }

    // ── Equality helpers ─────────────────────────────────────────────

    private boolean elementsEqual(ArchitectureElement a, ArchitectureElement b) {
        return Objects.equals(a.getId(), b.getId())
                && Objects.equals(a.getType(), b.getType())
                && Objects.equals(a.getTitle(), b.getTitle())
                && Objects.equals(a.getDescription(), b.getDescription())
                && Objects.equals(a.getTaxonomy(), b.getTaxonomy())
                && Objects.equals(a.getExtensions(), b.getExtensions());
    }

    private boolean relationsEqual(ArchitectureRelation a, ArchitectureRelation b) {
        return Objects.equals(a.getSourceId(), b.getSourceId())
                && Objects.equals(a.getRelationType(), b.getRelationType())
                && Objects.equals(a.getTargetId(), b.getTargetId())
                && Objects.equals(a.getStatus(), b.getStatus())
                && Objects.equals(a.getConfidence(), b.getConfidence())
                && Objects.equals(a.getProvenance(), b.getProvenance())
                && Objects.equals(a.getExtensions(), b.getExtensions());
    }

    // ── Indexing helpers ─────────────────────────────────────────────

    private Map<String, ArchitectureElement> indexElementsById(CanonicalArchitectureModel model) {
        Map<String, ArchitectureElement> map = new LinkedHashMap<>();
        for (ArchitectureElement e : model.getElements()) {
            map.put(e.getId(), e);
        }
        return map;
    }

    /**
     * Composite key for a relation: {@code sourceId|relationType|targetId}.
     */
    private String relationKey(ArchitectureRelation r) {
        return r.getSourceId() + "|" + r.getRelationType() + "|" + r.getTargetId();
    }

    private Set<String> indexRelationKeys(CanonicalArchitectureModel model) {
        Set<String> keys = new LinkedHashSet<>();
        for (ArchitectureRelation r : model.getRelations()) {
            keys.add(relationKey(r));
        }
        return keys;
    }

    private Map<String, ArchitectureRelation> indexRelationsByKey(CanonicalArchitectureModel model) {
        Map<String, ArchitectureRelation> map = new LinkedHashMap<>();
        for (ArchitectureRelation r : model.getRelations()) {
            map.put(relationKey(r), r);
        }
        return map;
    }
}
