package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AnalysisSnapshotAdoptionTest {
    private final ArchitectureDslCommands dsl = new ArchitectureDslCommands();
    private final RelationKey relation = new RelationKey("system", "CONTAINS", "component");
    private AdoptAnalysisSnapshot adoption(String fingerprint) {
        return new AdoptAnalysisSnapshot("analysis-123", "Frozen analysis", "Old requirement", fingerprint,
                List.of(new SnapshotElement("system", "System", "Recorded system", "Recorded description"),
                        new SnapshotElement("component", "Component", "Recorded component", "")),
                List.of(relation), Map.of("x-analysis-snapshot-id", "snapshot-1", "x-analysis-repository-id", "repo-a"));
    }
    @Test void createsOneViewWithExactRelationsAndReusesEditedElementsWithoutOverwriting() {
        String before = "element system type System {\n title: \"Edited\";\n description: \"My description\";\n x-owner: \"Alice\";\n}\n";
        var change = dsl.apply(before, adoption("a".repeat(64)));
        assertThat(change.changedIds()).containsExactly("element:component", "relation:system CONTAINS component", "view:analysis-123");
        assertThat(change.dsl()).contains(before, "x-analysis-relation-members: \"system CONTAINS component\"", "x-analysis-graph-fingerprint");
        assertThat(dsl.model(change.dsl()).getRelations().getFirst().getStatus()).isEqualTo("proposed");
        assertThat(dsl.model(change.dsl()).getViews().getFirst().getIncludes()).containsExactly("component", "system");
        assertThat(dsl.apply(change.dsl(), adoption("a".repeat(64))).changes()).isEmpty();
        assertCode(() -> dsl.apply(change.dsl(), adoption("b".repeat(64))), "SNAPSHOT_IDENTITY_CONFLICT");
    }
    @Test void preservesExistingRelationStatusAndEvidence() {
        String before = "element system type System {\n title: \"System\";\n}\nelement component type Component {\n title: \"Component\";\n}\n"
                + "relation system CONTAINS component {\n status: \"accepted\";\n provenance: \"reviewed\";\n x-focus-evidence: \"evidence\";\n}\n";
        assertThat(dsl.apply(before, adoption("a".repeat(64))).dsl()).startsWith(before);
    }
    @Test void adoptionIsInvertibleAndLaterDependenciesPreventUnsafeUndo() {
        String after = dsl.apply("", adoption("a".repeat(64))).dsl();
        String undone = dsl.inverse(after, "", after).dsl();
        assertThat(dsl.model(undone).getElements()).isEmpty();
        assertThat(dsl.model(undone).getViews()).isEmpty();
        assertThat(dsl.inverse(undone, after, undone).dsl()).isEqualTo(after);
        String dependent = dsl.apply(after, new UpsertArchitectureView("later", "Later view", "", List.of("system"), Map.of())).dsl();
        assertCode(() -> dsl.inverse(dependent, "", after), "DEPENDENCIES_EXIST");
    }
    @Test void validatesAllInputBeforeReturningAnyChangedDocument() {
        assertCode(() -> dsl.apply("element system type Component {\n title: \"Collision\";\n}", adoption("a".repeat(64))), "SNAPSHOT_TYPE_CONFLICT");
        var invalid = new AdoptAnalysisSnapshot("analysis-other", "Title", "", "a".repeat(64),
                List.of(new SnapshotElement("system", "System", "System", "")), List.of(relation), Map.of());
        assertCode(() -> dsl.apply("", invalid), "MISSING_SNAPSHOT_ENDPOINT");
        var duplicate = new AdoptAnalysisSnapshot("analysis-other", "Title", "", "a".repeat(64),
                List.of(new SnapshotElement("system", "System", "A", ""), new SnapshotElement("system", "System", "B", "")), List.of(), Map.of());
        assertCode(() -> dsl.apply("", duplicate), "AMBIGUOUS_SNAPSHOT_IDENTITY");
    }
    @Test void changedRetainedSourceProvenanceUnderTheSameIdentityConflicts() {
        var original = adoption("a".repeat(64));
        String after = dsl.apply("",original).dsl();
        var provenance = new java.util.HashMap<>(original.provenance()); provenance.put("x-analysis-commit","b".repeat(40));
        var changed = new AdoptAnalysisSnapshot(original.viewId(),original.title(),original.description(),original.graphFingerprint(),
                original.elements(),original.relations(),provenance);
        assertCode(() -> dsl.apply(after,changed),"SNAPSHOT_IDENTITY_CONFLICT");
    }
    @Test void malformedRetainedValuesProduceAValidationProblemBeforeAnyChange() {
        var malformed = new AdoptAnalysisSnapshot("analysis-invalid","Title","","a".repeat(64),
                List.of(new SnapshotElement("system","System",null,"")),List.of(),Map.of());
        assertCode(() -> dsl.apply("",malformed),"INVALID_VALUE");
    }
    @Test void ordinaryExchangeViewsRemainOutsideNarrowAdoptionInversion() {
        String before = "element system type System {\n title: \"System\";\n}\n";
        String after = dsl.apply(before, new UpsertArchitectureView("ordinary", "Ordinary", "", List.of("system"), Map.of())).dsl();
        assertCode(() -> dsl.inverse(after, before, after), "NOT_UNDOABLE");
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
