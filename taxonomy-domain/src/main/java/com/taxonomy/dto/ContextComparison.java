package com.taxonomy.dto;

import java.util.List;

/**
 * Result of comparing two architecture contexts.
 *
 * <p>Provides a multi-level view of changes:
 * <ol>
 *   <li><b>Summary</b> — counts of added/changed/removed elements and relations</li>
 *   <li><b>Semantic changes</b> — individual change descriptions</li>
 *   <li><b>Raw DSL diff</b> — textual diff for expert users</li>
 * </ol>
 *
 * @param left          the left (older/source) context reference
 * @param right         the right (newer/target) context reference
 * @param summary       change counts
 * @param changes       individual semantic changes
 * @param rawDslDiff    raw DSL text diff (optional, may be null)
 */
public record ContextComparison(
    ContextRef left,
    ContextRef right,
    DiffSummary summary,
    List<SemanticChange> changes,
    String rawDslDiff
) {

    /**
     * Counts of changes grouped by category.
     *
     * @param elementsAdded    number of new elements
     * @param elementsChanged  number of modified elements
     * @param elementsRemoved  number of deleted elements
     * @param relationsAdded   number of new relations
     * @param relationsChanged number of modified relations
     * @param relationsRemoved number of deleted relations
     */
    public record DiffSummary(
        int elementsAdded,
        int elementsChanged,
        int elementsRemoved,
        int relationsAdded,
        int relationsChanged,
        int relationsRemoved
    ) {
        public int totalChanges() {
            return elementsAdded + elementsChanged + elementsRemoved
                 + relationsAdded + relationsChanged + relationsRemoved;
        }
    }
}
