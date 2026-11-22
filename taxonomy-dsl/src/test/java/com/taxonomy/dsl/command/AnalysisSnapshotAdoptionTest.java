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

    @Test void recordsDescriptionProvenanceOnlyForNewlyCreatedElements() {
        String before = """
                element system type System {
                  title: "Existing";
                  description: "Authored";
                }
                """;
        var command = new AdoptAnalysisSnapshot("analysis-source", "Frozen analysis", "", "a".repeat(64),
                List.of(new SnapshotElement("system", "System", "Recorded", "Display reason"),
                        new SnapshotElement("component", "Component", "Recorded", "Generated display reason")),
                List.of(), Map.of());
        var after = dsl.apply(before, command).dsl();
        var model = dsl.model(after);
        String field = "x-analysis-generated-description-sha256";
        assertThat(model.findElement("system").orElseThrow().getExtensions()).doesNotContainKey(field);
        assertThat(model.findElement("system").orElseThrow().getDescription()).isEqualTo("Authored");
        assertThat(model.findElement("component").orElseThrow().getExtensions()).containsEntry(field,
                "78cccaebee466e7b1156347e731fdcc1221c67d5acd7554d70553eb53445572b");
        var undo = dsl.inverse(after, before, after).dsl();
        assertThat(undo).isEqualTo(before);
    }
    @Test void newElementsCarryTheFrozenUnitAndExistingLocalAssignmentsStayInPlace() {
        String before = """
                organization local {
                  title: "Local team";
                }
                element system type System {
                  title: "Existing system";
                  x-responsible-organization-id: "local";
                }
                element component type Component {
                  title: "Existing component";
                }
                """;
        var unit = new SnapshotResponsibility("operations", "Operations", "BP");
        var command = new AdoptAnalysisSnapshot("analysis-owned", "Retained analysis", "", "a".repeat(64),
                List.of(new SnapshotElement("system", "System", "Captured system", "", unit),
                        new SnapshotElement("component", "Component", "Captured component", "", unit),
                        new SnapshotElement("new-component", "Component", "New component", "", unit)),
                List.of(relation), Map.of());
        String after = dsl.apply(before, command).dsl();
        var adopted = dsl.model(after);
        assertThat(adopted.getOrganizations()).extracting(com.taxonomy.dsl.model.ArchitectureOrganization::title)
                .containsExactly("Local team");
        String property = com.taxonomy.dsl.model.ArchitectureOrganization.RESPONSIBLE_ID;
        assertThat(adopted.getElements()).filteredOn(element -> element.getId().equals("system"))
                .singleElement().satisfies(element -> assertThat(element.getExtensions()).containsEntry(property, "local"));
        assertThat(adopted.getElements()).filteredOn(element -> !element.getId().equals("system"))
                .hasSize(2).allSatisfy(element -> assertThat(element.getExtensions())
                        .containsEntry("x-captured-responsibility-unit", "operations")
                        .containsEntry("x-captured-responsibility-name", "Operations")
                        .containsEntry("x-captured-responsibility-source", "BP"));
        var later = new AdoptAnalysisSnapshot("analysis-later", "Later analysis", "", "b".repeat(64),
                List.of(new SnapshotElement("component", "Component", "Later component", "",
                        new SnapshotResponsibility("new-team", "New team", "BP-2"))), List.of(), Map.of());
        assertThat(dsl.model(dsl.apply(after, later).dsl()).getElements()).filteredOn(element -> element.getId().equals("component"))
                .singleElement().satisfies(element -> assertThat(element.getExtensions())
                        .containsEntry("x-captured-responsibility-unit", "operations"));
        assertThat(dsl.inverse(after, before, after).dsl()).isEqualTo(before);
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
    private AdoptAnalysisSnapshot withEvidence(AdoptAnalysisSnapshot legacy, SnapshotEvidence... evidence) {
        return new AdoptAnalysisSnapshot(legacy.viewId(), legacy.title(), legacy.description(), legacy.graphFingerprint(),
                legacy.elements(), legacy.relations(), legacy.provenance(), List.of(evidence));
    }

    @Test void newSourceEvidenceIsAdditiveToAReviewedRelationAndOnlyItsOwnEvidenceIsInverted() {
        var legacy = adoption("a".repeat(64));
        String before = """
                element system type System {
                  title: "System";
                }
                element component type Component {
                  title: "Component";
                }
                relation system CONTAINS component {
                  status: accepted;
                  provenance: "Human review";
                  confidence: 0.8;
                }
                """;
        var first = new SnapshotEvidence(relation, "LLM_SUPPORTED", 0d, Map.of("summary", "First interpretation"));
        var second = new SnapshotEvidence(relation, "LLM_SUPPORTED", 0d, Map.of("summary", "Alternative interpretation"));
        var adoption = withEvidence(legacy, first, second);
        String after = dsl.apply(before, adoption).dsl();
        assertThat(after).startsWith(before);
        assertThat(dsl.model(after).getEvidence()).hasSize(2);
        assertThat(dsl.model(after).getRelations().getFirst().getConfidence()).isEqualTo(0.8d);
        assertThat(dsl.apply(after, withEvidence(legacy, second, first)).changes()).isEmpty();
        String unrelated = "evidence review {\n  for-relation: \"system CONTAINS component\";\n  summary: \"Reviewer note\";\n}\n";
        String undone = dsl.inverse(after + unrelated, before, after).dsl();
        assertThat(undone).isEqualTo(before + unrelated);
        String redone = dsl.inverse(undone, after + unrelated, undone).dsl();
        assertThat(dsl.model(redone).getEvidence()).extracting(e -> e.getSummary())
                .containsExactlyInAnyOrder("First interpretation", "Alternative interpretation", "Reviewer note");
        assertCode(() -> dsl.inverse(after + unrelated, after, after + unrelated), "NOT_UNDOABLE");
        String newlyCreated = dsl.apply("", adoption).dsl();
        assertCode(() -> dsl.inverse(newlyCreated + unrelated, "", newlyCreated), "DEPENDENCIES_EXIST");
    }

    @Test void exactEvidenceWhitespaceSurvivesSerializationAndOwnedViewCannotBeDetached() {
        var legacy = adoption("a".repeat(64));
        var observation = new SnapshotEvidence(relation, null, null, Map.of("summary", " 42 "));
        var adopted = dsl.apply("", withEvidence(legacy, observation)).dsl();
        assertThat(dsl.model(adopted).getEvidence().getFirst().getSummary()).isEqualTo(" 42 ");
        assertCode(() -> dsl.apply(adopted, new DeleteArchitectureView(legacy.viewId())), "DEPENDENCIES_EXIST");
        String undone = dsl.inverse(adopted, "", adopted).dsl();
        assertThat(dsl.model(undone).getEvidence()).isEmpty();
    }

    @Test void rejectsMalformedUtf16InWitnessTextOriginAndSourceProvenance() {
        var legacy = adoption("a".repeat(64));
        for (char unpaired : new char[]{(char) 0xd800, (char) 0xdc00}) {
            String malformed = "proof" + unpaired;
            for (String key : List.of("summary", "x-analysis-candidate-quote", "x-analysis-assessed-quote"))
                assertCode(() -> new SnapshotEvidence(relation, null, null, Map.of(key, malformed)), "INVALID_SNAPSHOT_EVIDENCE");
            assertCode(() -> new SnapshotEvidence(relation, malformed, null, Map.of()), "INVALID_SNAPSHOT_EVIDENCE");
            for (String key : List.of("x-analysis-snapshot-id", "x-analysis-model", "x-analysis-repository-id")) {
                var provenance = new java.util.HashMap<>(legacy.provenance());
                provenance.put(key, malformed);
                assertCode(() -> new AdoptAnalysisSnapshot(legacy.viewId(), legacy.title(), legacy.description(),
                        legacy.graphFingerprint(), legacy.elements(), legacy.relations(), provenance, List.of()),
                        "INVALID_SNAPSHOT_EVIDENCE");
            }
        }
    }

    @Test void reopensAnUnmarkedLegacyViewWithoutInventingEvidenceOrRewritingHistory() {
        var legacy = adoption("a".repeat(64));
        String old = dsl.apply("", legacy).dsl();
        var enriched = withEvidence(legacy, new SnapshotEvidence(relation, "LLM_SUPPORTED", null, Map.of("summary", "Later projection")));
        assertThat(dsl.apply(old, enriched).dsl()).isEqualTo(old);
        assertThat(dsl.model(old).getEvidence()).isEmpty();
        var different = adoption("b".repeat(64));
        assertCode(() -> dsl.apply(old, withEvidence(different)), "SNAPSHOT_IDENTITY_CONFLICT");
    }

    @Test void rejectsUnboundConflictingAndOversizedEvidenceBeforeWriting() {
        var legacy = adoption("a".repeat(64));
        var reverse = new RelationKey("component", "CONTAINS", "system");
        assertCode(() -> dsl.apply("", withEvidence(legacy, new SnapshotEvidence(reverse, "origin", null,
                Map.of("summary", "Wrong direction")))), "INVALID_SNAPSHOT_EVIDENCE");
        assertCode(() -> dsl.apply("", withEvidence(legacy, new SnapshotEvidence(relation, "origin", Double.NaN,
                Map.of()))), "INVALID_SNAPSHOT_EVIDENCE");
        assertCode(() -> dsl.apply("", withEvidence(legacy, new SnapshotEvidence(relation, "origin", null,
                Map.of("summary", "x".repeat(8001))))), "INVALID_SNAPSHOT_EVIDENCE");
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
