package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.admbw.AdmbwPreparation.Basis;
import com.taxonomy.dsl.admbw.AdmbwPreparation.Reference;
import com.taxonomy.dsl.admbw.AdmbwPreparation.Selection;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureRelation;
import com.taxonomy.dsl.command.ArchitectureCommand.RelationKey;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureRelation;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.planning.PlanningEntry;
import com.taxonomy.dsl.planning.PlanningInformation;
import com.taxonomy.dsl.requirements.RequirementRealization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AdmbwPreparationTest {
    private static final String PROJECT = "{F0000000-0000-4000-A000-000000000001}";
    private static final String ROOT = "{F0000000-0000-4000-A000-000000000002}";
    private static final String OTHER_ROOT = "{F0000000-0000-4000-A000-000000000003}";
    private static final String CAPABILITY = "{F0000000-0000-4000-A000-000000000004}";
    private static final String SUBJECT = "{F0000000-0000-4000-A000-000000000005}";
    private static final String CAPABILITY_TWO = "{F0000000-0000-4000-A000-000000000006}";
    private static final String SUBJECT_TWO = "{F0000000-0000-4000-A000-000000000007}";
    private static final String DIGEST = "a".repeat(64);
    private static final String TEXT = "Die Warnstelle muss Warnmeldungen für alle Standorte bereitstellen.";
    private static final String QUOTE = "Warnmeldungen für alle Standorte";
    private static final String DRIVER = "Jeder Standort benötigt Zugang zu freigegebenen Warnmeldungen.";
    private static final String TEXT_HASH = RequirementRealization.textHash(TEXT);
    private static final String SOURCE = """
            element CI-1 type CoreService {
              title: "Warnmeldungsdienst";
              description: "Stellt Warnmeldungen bereit.";
              x-editor-status: "active";
              x-local-note: "keep";
            }
            element CI-2 type CoreService {
              title: "Verteildienst";
              description: "Verteilt Warnmeldungen.";
            }
            element CP-1 type Capability {
              title: "Warnungen bereitstellen";
              description: "Eigenständiger C3-Eintrag ohne native Bindung.";
            }
            element BP-1 type Process {
              title: "Warnmeldungen ausgeben";
            }
            requirement REQ-1 {
              title: "Warnmeldungen";
              text: "%s";
              x-version: "2";
              x-content-hash: "%s";
              x-custom-source: "preserve exact source";
            }
            requirement REQ-2 {
              title: "Verteilung";
              text: "Weitere Anforderung.";
              x-version: "1";
              x-content-hash: "%s";
            }
            relation CI-1 SUPPORTS BP-1 {
              status: "accepted";
            }
            mapping REQ-1 -> CI-2 {
              source: "analysis";
              x-project-key: "warn";
            }
            view frozen {
              title: "Eingefrorene Analyse";
              include: CI-1;
              include: BP-1;
              x-analysis-snapshot-id: "snapshot-1";
              x-analysis-status: "PARTIAL";
              x-analysis-relation-members: "CI-1 SUPPORTS BP-1";
            }
            """.formatted(TEXT, TEXT_HASH, RequirementRealization.textHash("Weitere Anforderung."));

    private final AdmbwPreparation planner = new AdmbwPreparation();

    @Test
    void resolvesTheRetainedCatalogueInsteadOfAuthorizingStandardOnlyDirections() {
        var custom = new com.taxonomy.dsl.model.CatalogueDefinition("restricted", false, List.of(
                new com.taxonomy.dsl.model.CatalogueDefinition.Root("Services", "CR", "CoreService"),
                new com.taxonomy.dsl.model.CatalogueDefinition.Root("Capabilities", "CP", "Capability")), List.of());
        var restricted = new AdmbwPreparation(model -> custom);
        assertThatIllegalArgumentException().isThrownBy(() -> restricted.prepare(SOURCE,
                selection("CI-2", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE), basis()))
                .isInstanceOf(ArchitectureDslCommands.CommandProblem.class);
    }

    @Test
    void subsequentPreparationRetainsAssessmentsSavedInTheVersionedPlanningEditor() {
        Selection selection = selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE);
        String initial = planner.prepare(SOURCE, selection, basis()).dsl();
        var planning = new PlanningInformation();
        var entry = planning.read(initial, "REQ-1").getFirst().entry();
        var values = new java.util.HashMap<>(entry.values());
        values.put("criticality", "Prüfung ausstehend");
        values.put("procurementPriority", "Nach Freigabe");
        String reviewed = planning.upsert(initial, "REQ-1",
                new PlanningEntry(entry.id(), entry.profile(), entry.version(), "MANUAL", values), false);
        var prepared = planner.prepare(reviewed, selection, basis());
        assertThat(prepared.dsl()).isEqualTo(reviewed);
        assertThat(prepared.assessment().requirementDerivation().elements()).anySatisfy(element -> {
            assertThat(element.ref()).isEqualTo("requirement:REQ-1");
            assertThat(element.assessedTags()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("Kritikalität", "Prüfung ausstehend", "Priorität Vergabe", "Nach Freigabe"));
        });
    }

    @Test
    void guidedExportRetainsTheReviewedProjectAndRootEvenThroughAnotherExportRoute() {
        var result = planner.prepare(SOURCE, selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE), basis());
        assertThat(AdmbwPreparation.requiresBasisInspection(result.dsl(), result.assessment())).isTrue();
        AdmbwPreparation.validateDeliveryBasis(result.dsl(), result.assessment(), ROOT, basis());
        assertThatIllegalArgumentException().isThrownBy(() -> AdmbwPreparation.validateDeliveryBasis(
                result.dsl(), result.assessment(), OTHER_ROOT, basis()));
        var alteredBytes = new Basis("b".repeat(64), basis().projectGuid(), basis().references());
        assertThatIllegalArgumentException().isThrownBy(() -> AdmbwPreparation.validateDeliveryBasis(
                result.dsl(), result.assessment(), ROOT, alteredBytes)).withMessageContaining("SHA-256");
        var otherProject = new AdmbwFffmlvProfile().assess(result.dsl(), "OTHER", result.viewId());
        assertThatIllegalArgumentException().isThrownBy(() -> AdmbwPreparation.validateDeliveryBasis(
                result.dsl(), otherProject, ROOT, basis()));
    }

    @Test
    void retainedNativePartsCannotChangeOwnerInTheSameRoot() {
        Reference role = new Reference(SUBJECT, "OperationalRole", "Role", "Native role", List.of("Model", "Roles"), ROOT, CAPABILITY_TWO);
        var original = new Basis(DIGEST, PROJECT, List.of(reference(CAPABILITY, "Capability", ROOT), role,
                reference(CAPABILITY_TWO, "Capability", ROOT), reference(SUBJECT_TWO, "OperationalArchitecture", ROOT)));
        var first = planner.prepare(SOURCE, selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE), original);
        var moved = new Basis("b".repeat(64), PROJECT, List.of(reference(CAPABILITY, "Capability", ROOT),
                new Reference(role.guid(), role.stereotype(), role.name(), role.notes(), role.packagePath(), ROOT, SUBJECT_TWO),
                reference(CAPABILITY_TWO, "Capability", ROOT), reference(SUBJECT_TWO, "OperationalArchitecture", ROOT)));
        assertThatIllegalArgumentException().isThrownBy(() -> AdmbwPreparation.validateDeliveryBasis(
                first.dsl(), first.assessment(), ROOT, moved)).withMessageContaining("owner");
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(first.dsl(),
                selection("CI-2", CAPABILITY_TWO, "REQ-2", SUBJECT_TWO, DRIVER, "Weitere Anforderung."), moved))
                .withMessageContaining("owner");
    }

    @Test
    void firstGuidedReviewPinsTheOwnerOfAnUnselectedManualPartAndExportDetectsAnOwnerChange() {
        String manual = SOURCE + """
                element retained-role type Component {
                  title: "Manually reviewed role";
                  description: "Manually recorded notes.";
                  x-admbw-type: OperationalRole;
                  x-admbw-catalogue-guid: "%s";
                  x-admbw-catalogue-reference: "Earlier project authority";
                }
                element retained-constraint type Component {
                  title: "Existing constraint";
                  description: "Existing operational source.";
                  x-admbw-type: OperationalConstraint;
                }
                """.formatted(SUBJECT_TWO);
        manual = new PlanningInformation().upsert(manual, "REQ-2", new PlanningEntry("manual-r7", "admbw-r7", "1",
                "MANUAL", Map.of("requirementType", "FunctionalRequirement", "derivedFrom", "retained-constraint",
                        "constraintSubject", "retained-role")), false);
        var role = new Reference(SUBJECT_TWO, "OperationalRole", "Native role", "Native notes.",
                List.of("Model", "Roles"), ROOT, CAPABILITY_TWO);
        var approvedBasis = new Basis(DIGEST, PROJECT, List.of(reference(CAPABILITY, "Capability", ROOT),
                reference(SUBJECT, "OperationalArchitecture", ROOT), role));
        var choice = selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE);
        var prepared = planner.prepare(manual, choice, approvedBasis);
        var before = ArchitectureSemanticPatch.index(manual).get("element:retained-role");
        var after = ArchitectureSemanticPatch.index(prepared.dsl()).get("element:retained-role");
        assertThat(before.getProperties()).allSatisfy(property ->
                assertThat(after.property(property.key())).isEqualTo(property.value()));
        assertThat(after.property("x-exchange-admbw-native-project")).isEqualTo(PROJECT);
        assertThat(after.property("x-exchange-admbw-native-root")).isEqualTo(ROOT);
        assertThat(after.property("x-exchange-admbw-native-owner")).isEqualTo(CAPABILITY_TWO);
        assertThat(ArchitectureSemanticPatch.between(manual, prepared.dsl())).extracting(ArchitectureSemanticPatch.BlockChange::id)
                .doesNotContain("requirement:REQ-2", "view:frozen");
        assertThat(planner.prepare(prepared.dsl(), choice, approvedBasis).dsl()).isEqualTo(prepared.dsl());
        AdmbwPreparation.validateDeliveryBasis(prepared.dsl(), prepared.assessment(), ROOT, approvedBasis);
        var changedBasis = new Basis("b".repeat(64), PROJECT, List.of(reference(CAPABILITY, "Capability", ROOT),
                reference(SUBJECT, "OperationalArchitecture", ROOT),
                new Reference(role.guid(), role.stereotype(), role.name(), role.notes(), role.packagePath(), ROOT, SUBJECT)));
        assertThatIllegalArgumentException().isThrownBy(() -> AdmbwPreparation.validateDeliveryBasis(
                prepared.dsl(), prepared.assessment(), ROOT, changedBasis)).withMessageContaining("retained-role").withMessageContaining("owner");
    }

    @Test
    void doesNotShowExcerptGuidanceWhenTheQuoteIsTheEntireRequirement() {
        var result = planner.prepare(SOURCE, selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, TEXT), basis());
        var excerpt = planner.prepare(SOURCE, selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE), basis());

        assertThat(result.notes()).noneMatch(note -> note.startsWith("The quote is an excerpt from the requirement."));
        assertThat(result.assessment().requirementDerivation().status())
                .isEqualTo(excerpt.assessment().requirementDerivation().status());
        assertThat(result.assessment().requirementDerivation().issues())
                .isEqualTo(excerpt.assessment().requirementDerivation().issues());
    }

    @Test
    void plansExactVersionedEvidenceAndPreservesTheFrozenScopeAndWholeModelGaps() {
        var result = planner.prepare(SOURCE, selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE), basis());
        var blocks = ArchitectureSemanticPatch.index(result.dsl());
        String capability = nativeId(result, CAPABILITY);
        String subject = nativeId(result, SUBJECT);
        assertThat(result.requirementVersion()).isEqualTo(2);
        assertThat(result.requirementContentHash()).isEqualTo(TEXT_HASH);
        assertThat(result.basisSha256()).isEqualTo(DIGEST);
        assertThat(result.notes()).anyMatch(note -> note.contains("restatement"));
        assertThat(result.notes()).anySatisfy(note -> assertThat(note)
                .startsWith("The quote is an excerpt from the requirement.")
                .contains("supports the entire requirement", "separately versioned requirement"));
        assertThat(capability).isNotEqualTo("CP-1");
        assertThat(blocks.get("element:CP-1").property(AdmbwFffmlvProfile.CATALOGUE_GUID)).isNull();
        assertThat(blocks.get("element:CI-1").property(AdmbwFffmlvProfile.TYPE)).isEqualTo("ServiceSpecification");
        assertThat(blocks.get("element:CI-1").property("x-local-note")).isEqualTo("keep");
        assertThat(blocks.get("relation:CI-1 FULFILLS " + capability).property("status")).isEqualTo("accepted");
        assertThat(blocks.get("mapping:REQ-1 -> CI-1")).isNotNull();

        var requirement = blocks.get("requirement:REQ-1");
        assertThat(requirement.property("text")).isEqualTo(TEXT);
        assertThat(requirement.property("x-version")).isEqualTo("2");
        assertThat(requirement.property("x-content-hash")).isEqualTo(TEXT_HASH);
        assertThat(requirement.property("x-custom-source")).isEqualTo("preserve exact source");
        var assignment = new PlanningInformation().read(result.dsl(), "REQ-1").getFirst().entry().values();
        assertThat(assignment).containsEntry("requirementType", "FunctionalRequirement")
                .containsEntry("constraintSubject", subject).containsEntry("boundVersion", "2")
                .containsEntry("boundHash", TEXT_HASH).doesNotContainKey("realizer");
        var constraint = blocks.get("element:" + assignment.get("derivedFrom"));
        assertThat(constraint.getHeaderTokens()).endsWith("type", "Component");
        assertThat(constraint.property(AdmbwFffmlvProfile.TYPE)).isEqualTo("OperationalConstraint");
        assertThat(constraint.property("description")).contains(DRIVER, QUOTE, "REQ-1", "2", TEXT_HASH);
        assertThat(ArchitectureSemanticPatch.between(SOURCE, result.dsl())).extracting(ArchitectureSemanticPatch.BlockChange::id)
                .doesNotContain("view:frozen", "relation:CI-1 SUPPORTS BP-1", "mapping:REQ-1 -> CI-2", "requirement:REQ-2");
        var coverage = result.assessment().coverage();
        assertThat(coverage.requirements()).isEqualTo(new AdmbwDeliveryCoverage.Counts(2, 1, 1));
        assertThat(coverage.mappings()).isEqualTo(new AdmbwDeliveryCoverage.Counts(2, 0, 2));
        assertThat(coverage.sourceAnalyses()).containsExactly(new AdmbwDeliveryCoverage.SourceAnalysis("frozen", "snapshot-1", "PARTIAL"));
        assertThat(result.assessment().requirementDerivation().issues()).anyMatch(issue -> issue.object().equals("requirement:REQ-2"));
    }

    @Test
    void rerunIsByteIdenticalAndSecondPairRetainsReviewedPairsButExcludesIncidentalCrossPair() {
        var firstChoice = selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE);
        var first = planner.prepare(SOURCE, firstChoice, basis());
        var repeated = planner.prepare(first.dsl(), firstChoice, basis());
        assertThat(repeated.dsl()).isEqualTo(first.dsl());
        assertThat(repeated.viewId()).isEqualTo(first.viewId());
        String firstCapability = nativeId(first, CAPABILITY);
        var crossPair = new RelationKey("CI-2", "FULFILLS", firstCapability);
        String prior = new ArchitectureDslCommands().apply(first.dsl(), new CreateArchitectureRelation(crossPair, "accepted")).dsl();
        var second = planner.prepare(prior,
                selection("CI-2", CAPABILITY_TWO, "REQ-2", SUBJECT, "Geprüfte Warnmeldungen verteilen.", "Weitere Anforderung."),
                basis());
        String secondCapability = nativeId(second, CAPABILITY_TWO);
        assertThat(second.viewId()).isEqualTo(first.viewId());
        assertThat(second.assessment().capabilityToService().connectors()).containsExactlyInAnyOrder(
                new AdmbwFffmlvProfile.Connector("Exhibits", "element:CI-1", "element:" + firstCapability),
                new AdmbwFffmlvProfile.Connector("Exhibits", "element:CI-2", "element:" + secondCapability));
        assertThat(second.assessment().coverage().omissions()).contains(
                new AdmbwDeliveryCoverage.Omission("RELATION", "relation:CI-2 FULFILLS " + firstCapability,
                        "CI-2 FULFILLS " + firstCapability, "RELATION_OUTSIDE_SELECTED_VIEW"));
        assertThat(ArchitectureSemanticPatch.index(second.dsl()).get("view:" + second.viewId()).propertyValues("include"))
                .contains("CI-1", "CI-2", firstCapability, secondCapability);
    }

    @Test
    void preservesAReviewedAssignmentAndRefusesAConflictingDriverOrStaleVersion() {
        var choice = selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE);
        String reviewed = planner.prepare(SOURCE, choice, basis()).dsl();
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(reviewed,
                selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, "Anderer Treiber", QUOTE), basis()));
        String stale = reviewed.replace("x-version: 2;", "x-version: 3;");
        assertThat(ArchitectureSemanticPatch.index(stale).get("requirement:REQ-1").property("x-version")).isEqualTo("3");
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(stale, choice, basis()));
    }

    @Test
    void acceptsOnlyTheSelectedProposedFulfilmentAndPreservesAnExistingRequirementMapping() {
        var choice = selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE);
        String existing = SOURCE + """
                element native-capability type Capability {
                  title: "Native capability";
                  description: "Native capability notes.";
                  x-admbw-catalogue-guid: "%s";
                  x-admbw-catalogue-reference: "Earlier verified binding";
                }
                mapping REQ-1 -> CI-1 {
                  source: "analysis";
                  x-project-key: "prior-mapping";
                }
                """.formatted(CAPABILITY);
        var relation = new RelationKey("CI-1", "FULFILLS", "native-capability");
        existing = new ArchitectureDslCommands().apply(existing,
                new CreateArchitectureRelation(relation, "proposed", Map.of("provenance", "Earlier proposal"))).dsl();
        var result = planner.prepare(existing, choice, basis());
        var blocks = ArchitectureSemanticPatch.index(result.dsl());
        assertThat(blocks.get("relation:" + relation.id()).property("status")).isEqualTo("accepted");
        assertThat(blocks.get("relation:" + relation.id()).property("provenance")).isEqualTo("Earlier proposal");
        assertThat(ArchitectureSemanticPatch.between(existing, result.dsl())).extracting(ArchitectureSemanticPatch.BlockChange::id)
                .doesNotContain("mapping:REQ-1 -> CI-1", "relation:CI-1 SUPPORTS BP-1");
    }

    @Test
    void reinspectsRetainedDeliveryPairsWhenTheBasisChanges() {
        var first = planner.prepare(SOURCE, selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE), basis());
        var choice = selection("CI-2", CAPABILITY_TWO, "REQ-2", SUBJECT,
                "Geprüfte Warnmeldungen verteilen.", "Weitere Anforderung.");
        var current = List.of(reference(CAPABILITY_TWO, "Capability", ROOT), reference(SUBJECT, "OperationalArchitecture", ROOT));
        String newDigest = "b".repeat(64);
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(first.dsl(), choice,
                new Basis(newDigest, PROJECT, current)));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(first.dsl(), choice,
                new Basis(newDigest, PROJECT, List.of(current.get(0), current.get(1),
                        reference(CAPABILITY, "Capability", OTHER_ROOT)))));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(first.dsl(), choice,
                new Basis(newDigest, PROJECT, List.of(current.get(0), current.get(1),
                        reference(CAPABILITY, "OperationalArchitecture", ROOT)))));

        var supported = planner.prepare(first.dsl(), choice, new Basis(newDigest, PROJECT,
                List.of(current.get(0), current.get(1), new Reference(CAPABILITY, "Capability", "Updated native label",
                        "New native notes.", List.of("Model", "References"), ROOT, null))));
        assertThat(supported.basisSha256()).isEqualTo(newDigest);
        assertThat(supported.assessment().capabilityToService().connectors()).hasSize(2);
        String retained = "element:" + nativeId(first, CAPABILITY);
        assertThat(ArchitectureSemanticPatch.between(first.dsl(), supported.dsl())).extracting(ArchitectureSemanticPatch.BlockChange::id)
                .doesNotContain(retained, "requirement:REQ-1", "view:frozen");
        assertThat(ArchitectureSemanticPatch.index(supported.dsl()).get(retained).property(AdmbwFffmlvProfile.CATALOGUE_REFERENCE))
                .contains(DIGEST).doesNotContain(newDigest);
    }

    @Test
    void reinspectsTheNativeSubjectOfEveryDeliveredRequirement() {
        var first = planner.prepare(SOURCE, selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE), basis());
        var choice = selection("CI-2", CAPABILITY_TWO, "REQ-2", SUBJECT_TWO,
                "Geprüfte Warnmeldungen verteilen.", "Weitere Anforderung.");
        var withoutRetainedSubject = new Basis("b".repeat(64), PROJECT, List.of(
                reference(CAPABILITY, "Capability", ROOT), reference(CAPABILITY_TWO, "Capability", ROOT),
                reference(SUBJECT_TWO, "OperationalArchitecture", ROOT)));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(first.dsl(), choice, withoutRetainedSubject));
    }

    @Test
    void preservesExistingNativeBindingsAndReportsConflictingOrAmbiguousBindings() {
        String nativeBlock = """
                element independently-bound type Capability {
                  title: "Reviewed custom title";
                  description: "Existing canonical notes";
                  x-admbw-catalogue-guid: "%s";
                  x-admbw-catalogue-reference: "Retained authority";
                }
                """.formatted(CAPABILITY.toLowerCase(Locale.ROOT));
        var choice = selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE);
        String original = SOURCE + nativeBlock;
        var result = planner.prepare(original, choice, basis());
        assertThat(nativeId(result, CAPABILITY)).isEqualTo("independently-bound");
        var before = ArchitectureSemanticPatch.index(original).get("element:independently-bound");
        var after = ArchitectureSemanticPatch.index(result.dsl()).get("element:independently-bound");
        assertThat(before.getProperties()).allSatisfy(property ->
                assertThat(after.property(property.key())).isEqualTo(property.value()));
        assertThat(after.property("x-exchange-admbw-native-project")).isEqualTo(PROJECT);
        assertThat(after.property("x-exchange-admbw-native-root")).isEqualTo(ROOT);
        assertThat(after.property("x-exchange-admbw-native-owner")).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(
                original + nativeBlock.replace("independently-bound", "duplicate"), choice, basis()));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(
                SOURCE + nativeBlock.replace("type Capability", "type Component"), choice, basis()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "warnmeldungen für alle Standorte", "Warnmeldungen für  alle Standorte"})
    void rejectsBlankOrInexactRequirementsQuotes(String quote) {
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(SOURCE,
                selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, quote), basis()));
    }

    @Test
    void rejectsInvalidRequirementHashNativeHashAndGuidOrWrongRootAndStereotype() {
        Selection choice = selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE);
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(SOURCE.replace(TEXT_HASH, "0".repeat(64)), choice, basis()));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(SOURCE, choice, new Basis("a", PROJECT, basis().references())));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(SOURCE, choice, new Basis(DIGEST, "not-a-guid", basis().references())));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(SOURCE, choice,
                new Basis(DIGEST, PROJECT, List.of(reference(CAPABILITY, "Capability", OTHER_ROOT), reference(SUBJECT, "OperationalArchitecture", ROOT)))));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(SOURCE, choice,
                new Basis(DIGEST, PROJECT, List.of(reference(CAPABILITY, "OperationalArchitecture", ROOT), reference(SUBJECT, "OperationalArchitecture", ROOT)))));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(SOURCE, choice,
                new Basis(DIGEST, PROJECT, List.of(reference(CAPABILITY, "Capability", ROOT), reference(CAPABILITY, "Capability", OTHER_ROOT),
                        reference(SUBJECT, "OperationalArchitecture", ROOT)))));
    }

    @Test
    void doesNotOverwriteRejectedFulfilmentUnownedViewOrSelectedIncompatibleService() {
        var choice = selection("CI-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE);
        var result = planner.prepare(SOURCE, choice, basis());
        String capability = nativeId(result, CAPABILITY);
        String rejected = new ArchitectureDslCommands().apply(result.dsl(),
                new UpdateArchitectureRelation(new RelationKey("CI-1", "FULFILLS", capability), "rejected")).dsl();
        assertThat(rejected).isNotEqualTo(result.dsl());
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(rejected, choice, basis()));
        String collision = SOURCE + "view " + result.viewId() + " {\n  title: \"Unowned\";\n}\n";
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(collision, choice, basis()));
        assertThatIllegalArgumentException().isThrownBy(() -> planner.prepare(SOURCE,
                selection("CP-1", CAPABILITY, "REQ-1", SUBJECT, DRIVER, QUOTE), basis()));
    }

    private static String nativeId(AdmbwPreparation.Prepared result, String guid) {
        return ArchitectureSemanticPatch.index(result.dsl()).values().stream()
                .filter(block -> guid.equalsIgnoreCase(block.property(AdmbwFffmlvProfile.CATALOGUE_GUID)))
                .map(block -> block.getHeaderTokens().getFirst()).findFirst().orElseThrow();
    }

    private static Selection selection(String service, String capability, String requirement, String subject, String driver, String quote) {
        return new Selection("WARN", ROOT, service, capability, requirement, "FunctionalRequirement", subject, driver, quote);
    }

    private static Basis basis() {
        return new Basis(DIGEST, PROJECT, List.of(reference(CAPABILITY, "Capability", ROOT),
                reference(CAPABILITY_TWO, "Capability", ROOT), reference(SUBJECT, "OperationalArchitecture", ROOT)));
    }

    private static Reference reference(String guid, String stereotype, String root) {
        // Intentionally give the native capability the same display name as the C3 capability.
        return new Reference(guid, stereotype, "Warnungen bereitstellen", "Native Katalogreferenz.",
                List.of("Model", "References"), root, null);
    }
}
