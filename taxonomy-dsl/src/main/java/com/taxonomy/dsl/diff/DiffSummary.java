package com.taxonomy.dsl.diff;

import java.util.List;
import java.util.Map;

/**
 * Compact summary of a {@link ModelDiff}, designed for quick display
 * in dashboards, change logs, and API responses.
 *
 * @param totalChanges           total number of structural changes
 * @param semanticChangeCount    number of semantic change descriptions
 * @param addedElementCount      number of added elements
 * @param removedElementCount    number of removed elements
 * @param changedElementCount    number of modified elements
 * @param addedRelationCount     number of added relations
 * @param removedRelationCount   number of removed relations
 * @param changedRelationCount   number of modified relations
 * @param changeTypeCounts       count per {@link SemanticChangeType}
 * @param semanticChanges        the full list of semantic changes
 */
public record DiffSummary(
        int totalChanges,
        int semanticChangeCount,
        int addedElementCount,
        int removedElementCount,
        int changedElementCount,
        int addedRelationCount,
        int removedRelationCount,
        int changedRelationCount,
        Map<String, Long> changeTypeCounts,
        List<SemanticChange> semanticChanges
) {

    /**
     * Create a summary from a {@link ModelDiff}.
     *
     * @param diff the structural diff
     * @return the computed summary
     */
    public static DiffSummary fromDiff(ModelDiff diff) {
        SemanticDiffDescriber describer = new SemanticDiffDescriber();
        List<SemanticChange> changes = describer.describe(diff);

        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (SemanticChange c : changes) {
            counts.merge(c.changeType().name(), 1L, Long::sum);
        }

        return new DiffSummary(
                diff.totalChanges(),
                changes.size(),
                diff.addedElements().size(),
                diff.removedElements().size(),
                diff.changedElements().size(),
                diff.addedRelations().size(),
                diff.removedRelations().size(),
                diff.changedRelations().size(),
                counts,
                changes
        );
    }
}
