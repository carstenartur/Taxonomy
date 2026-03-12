package com.taxonomy.dsl.diff;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ModelDiffer}.
 *
 * <p>Pure JUnit 5 — no Spring context required.
 */
class ModelDifferTest {

    private ModelDiffer differ;

    @BeforeEach
    void setUp() {
        differ = new ModelDiffer();
    }

    // ── Null / empty baseline ────────────────────────────────────────

    @Test
    void diffBothNullProducesEmptyDiff() {
        ModelDiff diff = differ.diff(null, null);
        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.totalChanges()).isZero();
    }

    @Test
    void diffIdenticalModelsProducesEmptyDiff() {
        CanonicalArchitectureModel model = modelWith(
                elem("CP-1", "Capability", "Auth"),
                rel("CP-1", "REALIZES", "BP-1")
        );
        ModelDiff diff = differ.diff(model, model);
        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void diffFromNullBeforeDetectsAllAsAdded() {
        CanonicalArchitectureModel after = modelWith(
                elem("CP-1", "Capability", "Auth"),
                rel("CP-1", "REALIZES", "BP-1")
        );
        ModelDiff diff = differ.diff(null, after);
        assertThat(diff.addedElements()).hasSize(1);
        assertThat(diff.addedRelations()).hasSize(1);
        assertThat(diff.removedElements()).isEmpty();
        assertThat(diff.removedRelations()).isEmpty();
    }

    @Test
    void diffToNullAfterDetectsAllAsRemoved() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth"),
                rel("CP-1", "REALIZES", "BP-1")
        );
        ModelDiff diff = differ.diff(before, null);
        assertThat(diff.removedElements()).hasSize(1);
        assertThat(diff.removedRelations()).hasSize(1);
        assertThat(diff.addedElements()).isEmpty();
        assertThat(diff.addedRelations()).isEmpty();
    }

    // ── Element diffing ──────────────────────────────────────────────

    @Test
    void detectsAddedElement() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth")
        );
        CanonicalArchitectureModel after = modelWith(
                elem("CP-1", "Capability", "Auth"),
                elem("CP-2", "Capability", "Crypto")
        );
        ModelDiff diff = differ.diff(before, after);
        assertThat(diff.addedElements()).hasSize(1);
        assertThat(diff.addedElements().get(0).getId()).isEqualTo("CP-2");
        assertThat(diff.removedElements()).isEmpty();
        assertThat(diff.changedElements()).isEmpty();
    }

    @Test
    void detectsRemovedElement() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth"),
                elem("CP-2", "Capability", "Crypto")
        );
        CanonicalArchitectureModel after = modelWith(
                elem("CP-1", "Capability", "Auth")
        );
        ModelDiff diff = differ.diff(before, after);
        assertThat(diff.removedElements()).hasSize(1);
        assertThat(diff.removedElements().get(0).getId()).isEqualTo("CP-2");
        assertThat(diff.addedElements()).isEmpty();
    }

    @Test
    void detectsChangedElementTitle() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth")
        );
        CanonicalArchitectureModel after = modelWith(
                elem("CP-1", "Capability", "Authentication Service")
        );
        ModelDiff diff = differ.diff(before, after);
        assertThat(diff.changedElements()).hasSize(1);
        assertThat(diff.changedElements().get(0).before().getTitle()).isEqualTo("Auth");
        assertThat(diff.changedElements().get(0).after().getTitle()).isEqualTo("Authentication Service");
        assertThat(diff.addedElements()).isEmpty();
        assertThat(diff.removedElements()).isEmpty();
    }

    @Test
    void detectsChangedElementType() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth")
        );
        ArchitectureElement changed = new ArchitectureElement("CP-1", "Service", "Auth", null, null);
        CanonicalArchitectureModel after = new CanonicalArchitectureModel();
        after.getElements().add(changed);

        ModelDiff diff = differ.diff(before, after);
        assertThat(diff.changedElements()).hasSize(1);
    }

    // ── Relation diffing ─────────────────────────────────────────────

    @Test
    void detectsAddedRelation() {
        CanonicalArchitectureModel before = modelWith(
                rel("CP-1", "REALIZES", "BP-1")
        );
        CanonicalArchitectureModel after = modelWith(
                rel("CP-1", "REALIZES", "BP-1"),
                rel("CP-2", "SUPPORTS", "BP-2")
        );
        ModelDiff diff = differ.diff(before, after);
        assertThat(diff.addedRelations()).hasSize(1);
        assertThat(diff.addedRelations().get(0).getSourceId()).isEqualTo("CP-2");
        assertThat(diff.removedRelations()).isEmpty();
    }

    @Test
    void detectsRemovedRelation() {
        CanonicalArchitectureModel before = modelWith(
                rel("CP-1", "REALIZES", "BP-1"),
                rel("CP-2", "SUPPORTS", "BP-2")
        );
        CanonicalArchitectureModel after = modelWith(
                rel("CP-1", "REALIZES", "BP-1")
        );
        ModelDiff diff = differ.diff(before, after);
        assertThat(diff.removedRelations()).hasSize(1);
        assertThat(diff.removedRelations().get(0).getSourceId()).isEqualTo("CP-2");
    }

    @Test
    void detectsChangedRelationStatus() {
        ArchitectureRelation beforeRel = new ArchitectureRelation("CP-1", "REALIZES", "BP-1");
        beforeRel.setStatus("provisional");
        CanonicalArchitectureModel before = new CanonicalArchitectureModel();
        before.getRelations().add(beforeRel);

        ArchitectureRelation afterRel = new ArchitectureRelation("CP-1", "REALIZES", "BP-1");
        afterRel.setStatus("accepted");
        CanonicalArchitectureModel after = new CanonicalArchitectureModel();
        after.getRelations().add(afterRel);

        ModelDiff diff = differ.diff(before, after);
        assertThat(diff.changedRelations()).hasSize(1);
        assertThat(diff.changedRelations().get(0).before().getStatus()).isEqualTo("provisional");
        assertThat(diff.changedRelations().get(0).after().getStatus()).isEqualTo("accepted");
        assertThat(diff.addedRelations()).isEmpty();
        assertThat(diff.removedRelations()).isEmpty();
    }

    @Test
    void detectsChangedRelationConfidence() {
        ArchitectureRelation beforeRel = new ArchitectureRelation("CP-1", "REALIZES", "BP-1");
        beforeRel.setConfidence(0.5);
        CanonicalArchitectureModel before = new CanonicalArchitectureModel();
        before.getRelations().add(beforeRel);

        ArchitectureRelation afterRel = new ArchitectureRelation("CP-1", "REALIZES", "BP-1");
        afterRel.setConfidence(0.9);
        CanonicalArchitectureModel after = new CanonicalArchitectureModel();
        after.getRelations().add(afterRel);

        ModelDiff diff = differ.diff(before, after);
        assertThat(diff.changedRelations()).hasSize(1);
    }

    // ── Complex scenarios ────────────────────────────────────────────

    @Test
    void complexDiffWithMultipleChangeTypes() {
        CanonicalArchitectureModel before = modelWith(
                elem("CP-1", "Capability", "Auth"),
                elem("CP-2", "Capability", "Crypto"),
                elem("CP-3", "Capability", "Removed"),
                rel("CP-1", "REALIZES", "BP-1"),
                rel("CP-3", "SUPPORTS", "BP-3")
        );

        CanonicalArchitectureModel after = modelWith(
                elem("CP-1", "Capability", "Authentication"), // changed title
                elem("CP-2", "Capability", "Crypto"),         // unchanged
                elem("CP-4", "Capability", "New"),            // added
                rel("CP-1", "REALIZES", "BP-1"),              // unchanged
                rel("CP-4", "DEPENDS_ON", "CP-2")             // added
        );

        ModelDiff diff = differ.diff(before, after);

        assertThat(diff.addedElements()).extracting(ArchitectureElement::getId).containsExactly("CP-4");
        assertThat(diff.removedElements()).extracting(ArchitectureElement::getId).containsExactly("CP-3");
        assertThat(diff.changedElements()).hasSize(1);
        assertThat(diff.changedElements().get(0).before().getTitle()).isEqualTo("Auth");
        assertThat(diff.changedElements().get(0).after().getTitle()).isEqualTo("Authentication");

        assertThat(diff.addedRelations()).hasSize(1);
        assertThat(diff.addedRelations().get(0).getSourceId()).isEqualTo("CP-4");
        assertThat(diff.removedRelations()).hasSize(1);
        assertThat(diff.removedRelations().get(0).getSourceId()).isEqualTo("CP-3");
        assertThat(diff.changedRelations()).isEmpty();

        assertThat(diff.isEmpty()).isFalse();
        assertThat(diff.totalChanges()).isEqualTo(5);
    }

    @Test
    void totalChangesCountsAllChangeTypes() {
        CanonicalArchitectureModel before = modelWith(
                elem("A", "T", "X"),
                rel("A", "R", "B")
        );
        CanonicalArchitectureModel after = modelWith(
                elem("A", "T", "Y"),
                elem("C", "T", "Z"),
                rel("C", "R", "A")
        );
        ModelDiff diff = differ.diff(before, after);
        // changed element A, added element C, removed relation A→B, added relation C→A
        assertThat(diff.totalChanges()).isEqualTo(4);
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
