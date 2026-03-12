package com.taxonomy.dsl.diff;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;

import java.util.*;

/**
 * Converts a structural {@link ModelDiff} into a list of human-readable
 * {@link SemanticChange} instances.
 *
 * <p>While {@code ModelDiff} answers <em>"what objects differ?"</em>,
 * the describer answers <em>"what specifically changed and why does it matter?"</em>.
 * This is the foundation for:
 * <ul>
 *     <li>Reviewable architecture change logs</li>
 *     <li>Automated release notes</li>
 *     <li>Change impact summaries</li>
 *     <li>History search annotations</li>
 * </ul>
 *
 * <p>This class is a pure library component — no Spring, no JPA.
 */
public class SemanticDiffDescriber {

    /**
     * Derive semantic changes from a model diff.
     *
     * @param diff the structural diff (must not be {@code null})
     * @return an unmodifiable list of semantic changes, in deterministic order
     */
    public List<SemanticChange> describe(ModelDiff diff) {
        Objects.requireNonNull(diff, "diff must not be null");
        List<SemanticChange> changes = new ArrayList<>();

        describeAddedElements(diff, changes);
        describeRemovedElements(diff, changes);
        describeChangedElements(diff, changes);
        describeAddedRelations(diff, changes);
        describeRemovedRelations(diff, changes);
        describeChangedRelations(diff, changes);

        return Collections.unmodifiableList(changes);
    }

    /**
     * Produce a compact text summary of semantic changes.
     *
     * @param diff the structural diff
     * @return multi-line summary suitable for commit messages or change logs
     */
    public String summarize(ModelDiff diff) {
        List<SemanticChange> changes = describe(diff);
        if (changes.isEmpty()) {
            return "No changes detected.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(changes.size()).append(" semantic change(s):\n");

        Map<SemanticChangeType, Long> counts = new LinkedHashMap<>();
        for (SemanticChange c : changes) {
            counts.merge(c.changeType(), 1L, Long::sum);
        }
        for (Map.Entry<SemanticChangeType, Long> entry : counts.entrySet()) {
            sb.append("  - ").append(entry.getKey().getLabel())
              .append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    // ── Added elements ───────────────────────────────────────────────

    private void describeAddedElements(ModelDiff diff, List<SemanticChange> out) {
        for (ArchitectureElement e : diff.addedElements()) {
            String desc = String.format("Added %s '%s' (%s)",
                    safe(e.getType()), safe(e.getTitle()), safe(e.getId()));
            out.add(SemanticChange.element(SemanticChangeType.ELEMENT_ADDED, e.getId(), desc));
        }
    }

    // ── Removed elements ─────────────────────────────────────────────

    private void describeRemovedElements(ModelDiff diff, List<SemanticChange> out) {
        for (ArchitectureElement e : diff.removedElements()) {
            String desc = String.format("Removed %s '%s' (%s)",
                    safe(e.getType()), safe(e.getTitle()), safe(e.getId()));
            out.add(SemanticChange.element(SemanticChangeType.ELEMENT_REMOVED, e.getId(), desc));
        }
    }

    // ── Changed elements ─────────────────────────────────────────────

    private void describeChangedElements(ModelDiff diff, List<SemanticChange> out) {
        for (ModelDiff.ElementChange ch : diff.changedElements()) {
            ArchitectureElement before = ch.before();
            ArchitectureElement after = ch.after();

            if (!Objects.equals(before.getType(), after.getType())) {
                out.add(SemanticChange.element(SemanticChangeType.ELEMENT_TYPE_CHANGED, after.getId(),
                        String.format("Type changed from '%s' to '%s' for %s",
                                safe(before.getType()), safe(after.getType()), safe(after.getId())),
                        before.getType(), after.getType()));
            }
            if (!Objects.equals(before.getTitle(), after.getTitle())) {
                out.add(SemanticChange.element(SemanticChangeType.ELEMENT_TITLE_CHANGED, after.getId(),
                        String.format("Title changed from '%s' to '%s' for %s",
                                safe(before.getTitle()), safe(after.getTitle()), safe(after.getId())),
                        before.getTitle(), after.getTitle()));
            }
            if (!Objects.equals(before.getDescription(), after.getDescription())) {
                out.add(SemanticChange.element(SemanticChangeType.ELEMENT_DESCRIPTION_CHANGED, after.getId(),
                        String.format("Description changed for %s", safe(after.getId())),
                        before.getDescription(), after.getDescription()));
            }
            if (!Objects.equals(before.getTaxonomy(), after.getTaxonomy())) {
                out.add(SemanticChange.element(SemanticChangeType.ELEMENT_TAXONOMY_CHANGED, after.getId(),
                        String.format("Taxonomy changed from '%s' to '%s' for %s",
                                safe(before.getTaxonomy()), safe(after.getTaxonomy()), safe(after.getId())),
                        before.getTaxonomy(), after.getTaxonomy()));
            }
            if (!Objects.equals(before.getExtensions(), after.getExtensions())) {
                out.add(SemanticChange.element(SemanticChangeType.ELEMENT_EXTENSIONS_CHANGED, after.getId(),
                        String.format("Extensions changed for %s", safe(after.getId()))));
            }
        }
    }

    // ── Added relations ──────────────────────────────────────────────

    private void describeAddedRelations(ModelDiff diff, List<SemanticChange> out) {
        for (ArchitectureRelation r : diff.addedRelations()) {
            String key = relationKey(r);
            String desc = String.format("Added relation %s → %s → %s",
                    safe(r.getSourceId()), safe(r.getRelationType()), safe(r.getTargetId()));
            out.add(SemanticChange.relation(SemanticChangeType.RELATION_ADDED, key, desc));
        }
    }

    // ── Removed relations ────────────────────────────────────────────

    private void describeRemovedRelations(ModelDiff diff, List<SemanticChange> out) {
        for (ArchitectureRelation r : diff.removedRelations()) {
            String key = relationKey(r);
            String desc = String.format("Removed relation %s → %s → %s",
                    safe(r.getSourceId()), safe(r.getRelationType()), safe(r.getTargetId()));
            out.add(SemanticChange.relation(SemanticChangeType.RELATION_REMOVED, key, desc));
        }
    }

    // ── Changed relations ────────────────────────────────────────────

    private void describeChangedRelations(ModelDiff diff, List<SemanticChange> out) {
        for (ModelDiff.RelationChange ch : diff.changedRelations()) {
            ArchitectureRelation before = ch.before();
            ArchitectureRelation after = ch.after();
            String key = relationKey(after);

            if (!Objects.equals(before.getStatus(), after.getStatus())) {
                out.add(SemanticChange.relation(SemanticChangeType.RELATION_STATUS_CHANGED, key,
                        String.format("Status changed from '%s' to '%s' for %s",
                                safe(before.getStatus()), safe(after.getStatus()), key),
                        before.getStatus(), after.getStatus()));
            }
            if (!Objects.equals(before.getConfidence(), after.getConfidence())) {
                out.add(SemanticChange.relation(SemanticChangeType.RELATION_CONFIDENCE_CHANGED, key,
                        String.format("Confidence changed from %s to %s for %s",
                                safe(str(before.getConfidence())), safe(str(after.getConfidence())), key),
                        str(before.getConfidence()), str(after.getConfidence())));
            }
            if (!Objects.equals(before.getProvenance(), after.getProvenance())) {
                out.add(SemanticChange.relation(SemanticChangeType.RELATION_PROVENANCE_CHANGED, key,
                        String.format("Provenance changed from '%s' to '%s' for %s",
                                safe(before.getProvenance()), safe(after.getProvenance()), key),
                        before.getProvenance(), after.getProvenance()));
            }
            if (!Objects.equals(before.getExtensions(), after.getExtensions())) {
                out.add(SemanticChange.relation(SemanticChangeType.RELATION_EXTENSIONS_CHANGED, key,
                        String.format("Extensions changed for %s", key)));
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String relationKey(ArchitectureRelation r) {
        return r.getSourceId() + "|" + r.getRelationType() + "|" + r.getTargetId();
    }

    private static String safe(String value) {
        return value != null ? value : "<none>";
    }

    private static String str(Double value) {
        return value != null ? String.valueOf(value) : null;
    }
}
