package com.taxonomy.dsl.diff;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SemanticDiffDescriber} and {@link DiffSummary}.
 *
 * <p>Pure JUnit 5 — no Spring context required.
 */
class SemanticDiffDescriberTest {

    private ModelDiffer differ;
    private SemanticDiffDescriber describer;

    @BeforeEach
    void setUp() {
        differ = new ModelDiffer();
        describer = new SemanticDiffDescriber();
    }

    // ── Empty / null ─────────────────────────────────────────────────

    @Test
    void emptyDiffProducesNoSemanticChanges() {
        ModelDiff diff = differ.diff(null, null);
        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).isEmpty();
    }

    @Test
    void summarizeEmptyDiffReturnsNoChanges() {
        ModelDiff diff = differ.diff(null, null);
        String summary = describer.summarize(diff);
        assertThat(summary).isEqualTo("No changes detected.");
    }

    // ── Element additions/removals ───────────────────────────────────

    @Test
    void addedElementProducesSemanticChange() {
        CanonicalArchitectureModel after = modelWith(elem("CP-1", "Capability", "Auth"));
        ModelDiff diff = differ.diff(null, after);

        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).changeType()).isEqualTo(SemanticChangeType.ELEMENT_ADDED);
        assertThat(changes.get(0).entityKind()).isEqualTo("element");
        assertThat(changes.get(0).entityId()).isEqualTo("CP-1");
        assertThat(changes.get(0).description()).contains("Auth");
    }

    @Test
    void removedElementProducesSemanticChange() {
        CanonicalArchitectureModel before = modelWith(elem("CP-1", "Capability", "Auth"));
        ModelDiff diff = differ.diff(before, null);

        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).changeType()).isEqualTo(SemanticChangeType.ELEMENT_REMOVED);
        assertThat(changes.get(0).entityId()).isEqualTo("CP-1");
    }

    // ── Element property changes ─────────────────────────────────────

    @Test
    void changedTitleProducesSemanticChange() {
        CanonicalArchitectureModel before = modelWith(elem("CP-1", "Capability", "Auth"));
        CanonicalArchitectureModel after = modelWith(elem("CP-1", "Capability", "Authentication Service"));
        ModelDiff diff = differ.diff(before, after);

        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).changeType()).isEqualTo(SemanticChangeType.ELEMENT_TITLE_CHANGED);
        assertThat(changes.get(0).beforeValue()).isEqualTo("Auth");
        assertThat(changes.get(0).afterValue()).isEqualTo("Authentication Service");
        assertThat(changes.get(0).description()).contains("Auth").contains("Authentication Service");
    }

    @Test
    void changedTypeProducesSemanticChange() {
        CanonicalArchitectureModel before = modelWith(elem("CP-1", "Capability", "Auth"));
        ArchitectureElement changed = new ArchitectureElement("CP-1", "Service", "Auth", null, null);
        CanonicalArchitectureModel after = new CanonicalArchitectureModel();
        after.getElements().add(changed);

        ModelDiff diff = differ.diff(before, after);
        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.ELEMENT_TYPE_CHANGED);
    }

    @Test
    void changedDescriptionProducesSemanticChange() {
        ArchitectureElement before = new ArchitectureElement("CP-1", "Capability", "Auth", "Old desc", null);
        ArchitectureElement after = new ArchitectureElement("CP-1", "Capability", "Auth", "New desc", null);
        CanonicalArchitectureModel m1 = new CanonicalArchitectureModel();
        m1.getElements().add(before);
        CanonicalArchitectureModel m2 = new CanonicalArchitectureModel();
        m2.getElements().add(after);

        ModelDiff diff = differ.diff(m1, m2);
        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.ELEMENT_DESCRIPTION_CHANGED);
    }

    @Test
    void changedTaxonomyProducesSemanticChange() {
        ArchitectureElement before = new ArchitectureElement("CP-1", "Capability", "Auth", null, "CP");
        ArchitectureElement after = new ArchitectureElement("CP-1", "Capability", "Auth", null, "BP");
        CanonicalArchitectureModel m1 = new CanonicalArchitectureModel();
        m1.getElements().add(before);
        CanonicalArchitectureModel m2 = new CanonicalArchitectureModel();
        m2.getElements().add(after);

        ModelDiff diff = differ.diff(m1, m2);
        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.ELEMENT_TAXONOMY_CHANGED);
    }

    // ── Relation changes ─────────────────────────────────────────────

    @Test
    void addedRelationProducesSemanticChange() {
        CanonicalArchitectureModel after = modelWith(rel("CP-1", "REALIZES", "BP-1"));
        ModelDiff diff = differ.diff(null, after);

        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).changeType()).isEqualTo(SemanticChangeType.RELATION_ADDED);
        assertThat(changes.get(0).entityKind()).isEqualTo("relation");
        assertThat(changes.get(0).description()).contains("CP-1").contains("REALIZES").contains("BP-1");
    }

    @Test
    void removedRelationProducesSemanticChange() {
        CanonicalArchitectureModel before = modelWith(rel("CP-1", "REALIZES", "BP-1"));
        ModelDiff diff = differ.diff(before, null);

        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).changeType()).isEqualTo(SemanticChangeType.RELATION_REMOVED);
    }

    @Test
    void changedRelationStatusProducesSemanticChange() {
        ArchitectureRelation beforeRel = new ArchitectureRelation("CP-1", "REALIZES", "BP-1");
        beforeRel.setStatus("provisional");
        CanonicalArchitectureModel before = new CanonicalArchitectureModel();
        before.getRelations().add(beforeRel);

        ArchitectureRelation afterRel = new ArchitectureRelation("CP-1", "REALIZES", "BP-1");
        afterRel.setStatus("accepted");
        CanonicalArchitectureModel after = new CanonicalArchitectureModel();
        after.getRelations().add(afterRel);

        ModelDiff diff = differ.diff(before, after);
        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).anyMatch(c ->
                c.changeType() == SemanticChangeType.RELATION_STATUS_CHANGED
                && "provisional".equals(c.beforeValue())
                && "accepted".equals(c.afterValue()));
    }

    @Test
    void changedRelationConfidenceProducesSemanticChange() {
        ArchitectureRelation beforeRel = new ArchitectureRelation("CP-1", "REALIZES", "BP-1");
        beforeRel.setConfidence(0.5);
        CanonicalArchitectureModel before = new CanonicalArchitectureModel();
        before.getRelations().add(beforeRel);

        ArchitectureRelation afterRel = new ArchitectureRelation("CP-1", "REALIZES", "BP-1");
        afterRel.setConfidence(0.9);
        CanonicalArchitectureModel after = new CanonicalArchitectureModel();
        after.getRelations().add(afterRel);

        ModelDiff diff = differ.diff(before, after);
        List<SemanticChange> changes = describer.describe(diff);
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.RELATION_CONFIDENCE_CHANGED);
    }

    // ── Complex scenario ─────────────────────────────────────────────

    @Test
    void complexDiffProducesMultipleSemanticChanges() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth"),
                elem("CP-2", "Capability", "Crypto"),
                elem("CP-3", "Capability", "Removed"),
                rel("CP-1", "REALIZES", "BP-1"),
                rel("CP-3", "SUPPORTS", "BP-3")
        );
        CanonicalArchitectureModel after = modelWith(
                elem("CP-1", "Capability", "Authentication"),  // changed title
                elem("CP-2", "Capability", "Crypto"),           // unchanged
                elem("CP-4", "Capability", "New"),              // added
                rel("CP-1", "REALIZES", "BP-1"),                // unchanged
                rel("CP-4", "DEPENDS_ON", "CP-2")               // added
        );

        ModelDiff diff = differ.diff(before, after);
        List<SemanticChange> changes = describer.describe(diff);

        // Expected: added CP-4, removed CP-3, changed CP-1 title, added rel, removed rel
        assertThat(changes).hasSizeGreaterThanOrEqualTo(5);
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.ELEMENT_ADDED
                && "CP-4".equals(c.entityId()));
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.ELEMENT_REMOVED
                && "CP-3".equals(c.entityId()));
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.ELEMENT_TITLE_CHANGED
                && "CP-1".equals(c.entityId()));
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.RELATION_ADDED);
        assertThat(changes).anyMatch(c -> c.changeType() == SemanticChangeType.RELATION_REMOVED);
    }

    // ── DiffSummary ──────────────────────────────────────────────────

    @Test
    void diffSummaryFromDiff() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth"),
                rel("CP-1", "REALIZES", "BP-1")
        );
        CanonicalArchitectureModel after = modelWith(
                elem("CP-1", "Capability", "Authentication"),
                elem("CP-2", "Capability", "New"),
                rel("CP-1", "REALIZES", "BP-1"),
                rel("CP-2", "SUPPORTS", "BP-2")
        );

        ModelDiff diff = differ.diff(before, after);
        DiffSummary summary = DiffSummary.fromDiff(diff);

        assertThat(summary.totalChanges()).isEqualTo(3);
        assertThat(summary.addedElementCount()).isEqualTo(1);
        assertThat(summary.changedElementCount()).isEqualTo(1);
        assertThat(summary.addedRelationCount()).isEqualTo(1);
        assertThat(summary.semanticChanges()).isNotEmpty();
        assertThat(summary.changeTypeCounts()).containsKey("ELEMENT_ADDED");
        assertThat(summary.changeTypeCounts()).containsKey("ELEMENT_TITLE_CHANGED");
        assertThat(summary.changeTypeCounts()).containsKey("RELATION_ADDED");
    }

    @Test
    void summarizeProducesReadableText() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth")
        );
        CanonicalArchitectureModel after = modelWith(
                elem("CP-1", "Capability", "Authentication"),
                elem("CP-2", "Capability", "New")
        );

        ModelDiff diff = differ.diff(before, after);
        String summary = describer.summarize(diff);
        assertThat(summary).contains("semantic change(s)");
        assertThat(summary).contains("Element added");
        assertThat(summary).contains("Element title changed");
    }

    // ── Test helpers ─────────────────────────────────────────────────

    private CanonicalArchitectureModel modelWith(Object... items) {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        for (Object item : items) {
            if (item instanceof ArchitectureElement e) {
                model.getElements().add(e);
            } else if (item instanceof ArchitectureRelation r) {
                model.getRelations().add(r);
            }
        }
        return model;
    }

    private ArchitectureElement elem(String id, String type, String title) {
        return new ArchitectureElement(id, type, title, null, null);
    }

    private ArchitectureRelation rel(String sourceId, String relationType, String targetId) {
        return new ArchitectureRelation(sourceId, relationType, targetId);
    }
}
