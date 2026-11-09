package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.ClearArchitectureElementProperties;
import com.taxonomy.dsl.command.ArchitectureCommand.ConfigureAdmbwDelivery;
import com.taxonomy.dsl.command.ArchitectureCommand.DeleteArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureCommand.DeleteRequirementPlanning;
import com.taxonomy.dsl.command.ArchitectureCommand.ImportRequirementPlanning;
import com.taxonomy.dsl.command.ArchitectureCommand.SetRequirementPlanning;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.planning.PlanningEntry;
import com.taxonomy.dsl.planning.PlanningInformation;
import com.taxonomy.dsl.requirements.RequirementRealization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmbwRequirementProfileTest {
    private static final String REQUIREMENT_ID = "REQ-1";
    private static final String REQUIREMENT = "The service must deliver warnings.";
    private static final String DSL = """
            element operation type Component {
              title: "Operation";
              x-admbw-type: "OperationalConstraint";
            }
            element role type BusinessRole {
              title: "Official operating role";
              x-admbw-type: "OperationalRole";
              x-admbw-catalogue-guid: "{55AEAD63-403A-48FD-A022-174DC3037CE2}";
              x-admbw-catalogue-reference: "Catalogue edition 1";
            }
            element local-role type BusinessRole {
              title: "Unbound operating role";
              x-admbw-type: "OperationalRole";
            }
            element realizer type Component {
              title: "Delivery role";
              x-admbw-type: "ResourceRole";
              x-admbw-catalogue-guid: "{66AEAD63-403A-48FD-A022-174DC3037CE2}";
              x-admbw-catalogue-reference: "EA Parts catalogue edition 1";
            }
            requirement REQ-1 {
              title: "Warnings";
              text: "%s";
              x-version: "2";
              x-content-hash: "%s";
            }
            """.formatted(REQUIREMENT, RequirementRealization.textHash(REQUIREMENT));
    private static final Map<String, String> ASSIGNMENT = Map.of(
            "requirementType", "FunctionalRequirement", "derivedFrom", "operation",
            "constraintSubject", "role", "realizer", "realizer");

    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();

    @ParameterizedTest
    @CsvSource({
            "derivedFrom, missing, DerivedFrom",
            "derivedFrom, role, DerivedFrom",
            "constraintSubject, missing, Satisfy",
            "constraintSubject, realizer, Satisfy",
            "constraintSubject, local-role, Satisfy",
            "realizer, missing, ToBeRealizedBy",
            "realizer, operation, ToBeRealizedBy"
    })
    void rejectsAnInvalidEndpointOnAuthoring(String field, String id, String connector) {
        assertThatThrownBy(() -> store(DSL, assignment(field, id)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(connector);
    }

    @Test
    void rejectsAnOfficialCapabilityEvenWithTheConstraintStereotype() {
        String source = DSL.replace("element operation type Component", "element operation type Capability");
        assertThatThrownBy(() -> store(source, entry(ASSIGNMENT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DerivedFrom");
    }

    @Test
    void offersOnlyModeledElementsThatCanFillEachDirectedSlot() {
        String source = DSL + """
                element architecture type Component {
                  x-admbw-type: "OperationalArchitecture";
                  x-admbw-catalogue-guid: "{65AEAD63-403A-48FD-A022-174DC3037CE2}";
                  x-admbw-catalogue-reference: "Catalogue edition 1";
                }
                element configuration type Component {
                  x-admbw-type: "CapabilityConfiguration";
                }
                element service type CoreService {
                  x-admbw-type: "ResourceRole";
                }
                """;
        var choices = new PlanningInformation().elementChoices(commands.model(source));

        assertThat(choices).containsOnlyKeys("admbw-r7@1");
        assertThat(choices.get("admbw-r7@1")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "derivedFrom", List.of("operation"), "constraintSubject", List.of("architecture", "role"),
                "realizer", List.of("configuration", "realizer")));
    }

    @Test
    void rejectsASecondAuthoredR7EntryOnTheSameRequirement() {
        String reviewed = store(DSL, entry(ASSIGNMENT));
        var second = new PlanningEntry("r7-second", "admbw-r7", "1", "MANUAL", ASSIGNMENT);

        assertThatThrownBy(() -> store(reviewed, second))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one R7 planning entry");
    }

    @Test
    void reconciliationCannotIntroduceTwoReviewedAssignmentsForTheSameRequirement() {
        String reviewed = store(configure(DSL), entry(ASSIGNMENT));
        String duplicate = reviewed.lines().filter(line -> line.stripLeading().startsWith("x-planning.r7."))
                .map(line -> line.replace("x-planning.r7.", "x-planning.r7-second."))
                .collect(java.util.stream.Collectors.joining("\n"));
        int offset = reviewed.indexOf("  x-planning.r7.");
        String incoming = reviewed.substring(0, offset) + duplicate + "\n" + reviewed.substring(offset);
        assertThatThrownBy(() -> commands.validateReconciledSource(reviewed, incoming))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one R7 planning entry");
    }

    @Test
    void retainsDuplicateImportedDraftsUntilAnAuthorResolvesThem() {
        var other = new PlanningEntry("r7-alternative", "admbw-r7", "1", "REQIF", ASSIGNMENT);
        String imported = commands.applyAll(DSL, List.of(
                new ImportRequirementPlanning(REQUIREMENT_ID, entry(ASSIGNMENT)),
                new ImportRequirementPlanning(REQUIREMENT_ID, other))).dsl();

        var planning = new PlanningInformation();
        assertThat(planning.read(imported, REQUIREMENT_ID)).hasSize(2);
        assertThatThrownBy(() -> store(imported, entry(ASSIGNMENT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one R7 planning entry");

        String retained = commands.apply(imported, new DeleteRequirementPlanning(REQUIREMENT_ID, other.id())).dsl();
        assertThat(planning.read(store(retained, entry(ASSIGNMENT)), REQUIREMENT_ID).getFirst().entry().values())
                .doesNotContainKey("imported");
    }

    @Test
    void boundDeliveryRequiresAnEaPartBindingForAResourceRoleRealizer() {
        String unbound = DSL.replace("x-admbw-catalogue-reference: \"EA Parts catalogue edition 1\";", "");
        String delivery = configure(unbound);

        assertThatThrownBy(() -> store(delivery, entry(ASSIGNMENT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ToBeRealizedBy");
        assertThatCode(() -> store(unbound, entry(ASSIGNMENT))).doesNotThrowAnyException();
    }

    @Test
    void boundDeliveryOmitsUnboundResourceRolesFromRealizerChoices() {
        String delivery = configure(DSL + """
                element local-realizer type Component {
                  x-admbw-type: "ResourceRole";
                }
                element configuration type Component {
                  x-admbw-type: "CapabilityConfiguration";
                }
                """);

        assertThat(new PlanningInformation().elementChoices(commands.model(delivery))
                .get("admbw-r7@1").get("realizer")).containsExactly("configuration", "realizer");
    }

    @Test
    void rejectsAReconciledAssignmentWhenItsEndpointIsRetyped() {
        String reviewed = store(DSL, entry(ASSIGNMENT));
        String retyped = reviewed.replace("OperationalConstraint", "ResourceRole");

        assertThatCode(() -> commands.validateReconciledSource(reviewed)).doesNotThrowAnyException();
        assertThatThrownBy(() -> commands.validateReconciledSource(retyped))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        problem -> assertThat(problem.code()).isEqualTo("INVALID_REFERENCE"))
                .hasMessageContaining("DerivedFrom");
    }

    @Test
    void retainsAnImportedAssignmentWithUnresolvedEndpointsForExplicitReview() {
        PlanningEntry unresolved = assignment("derivedFrom", "missing");
        String imported = commands.apply(DSL, new ImportRequirementPlanning(REQUIREMENT_ID, unresolved)).dsl();
        var pending = new AdmbwFffmlvProfile().assess(imported, "WARN", null).requirementDerivation();

        assertThatCode(() -> commands.validateReconciledSource(imported)).doesNotThrowAnyException();
        assertThat(pending.connectors()).isEmpty();
        assertThat(pending.issues()).extracting(AdmbwFffmlvProfile.Issue::code)
                .containsExactly("R7_IMPORTED_REVIEW_REQUIRED");
        assertThatThrownBy(() -> store(imported, unresolved))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DerivedFrom");

        String reviewed = store(imported, entry(ASSIGNMENT));
        assertThat(new PlanningInformation().read(reviewed, REQUIREMENT_ID).getFirst().entry().values())
                .containsEntry("boundVersion", "2").containsEntry("boundHash", RequirementRealization.textHash(REQUIREMENT))
                .doesNotContainKey("imported");
    }

    @ParameterizedTest
    @MethodSource("breakingElementChanges")
    void protectsAReviewedSlotInAConfiguredDelivery(ArchitectureCommand change, String field) {
        String reviewed = configure(store(DSL, entry(ASSIGNMENT)));

        assertThatThrownBy(() -> commands.apply(reviewed, change))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class, error -> {
                    assertThat(error.code()).isEqualTo("ADMBW_R7_ENDPOINT_IN_USE");
                    assertThat(error.field()).isEqualTo(field);
                    assertThat(error.dependencies()).containsExactly("requirement:REQ-1");
                });
    }

    private static Stream<Arguments> breakingElementChanges() {
        return Stream.of(
                Arguments.of(new UpdateArchitectureElement("operation", "Component",
                        Map.of(AdmbwFffmlvProfile.TYPE, "ResourceRole")), "derivedFrom"),
                Arguments.of(new ClearArchitectureElementProperties("role", Set.of(AdmbwFffmlvProfile.CATALOGUE_REFERENCE)),
                        "constraintSubject"),
                Arguments.of(new ClearArchitectureElementProperties("realizer", Set.of(AdmbwFffmlvProfile.CATALOGUE_GUID)),
                        "realizer"),
                Arguments.of(new DeleteArchitectureElement("realizer"), "realizer"));
    }

    @Test
    void allowsChangesThatKeepTheReviewedEndpointEligible() {
        String reviewed = configure(store(DSL, entry(ASSIGNMENT)));
        var change = new UpdateArchitectureElement("realizer", "Component",
                Map.of(AdmbwFffmlvProfile.TYPE, "CapabilityConfiguration"));

        assertThat(commands.apply(reviewed, change).dsl()).contains("x-admbw-type: \"CapabilityConfiguration\";");
    }

    @Test
    void allowsRemovingAnEndpointReferencedOnlyByUnconfiguredOrUnreviewedPlanning() {
        String unconfigured = store(DSL, entry(ASSIGNMENT));
        String stale = configure(unconfigured.replace("x-version: 2;", "x-version: 3;"));
        PlanningEntry exported = new PlanningInformation().read(unconfigured, REQUIREMENT_ID).getFirst().entry();
        String imported = commands.apply(configure(DSL), new ImportRequirementPlanning(REQUIREMENT_ID, exported)).dsl();

        for (String source : List.of(unconfigured, stale, imported)) {
            assertThat(commands.apply(source, new DeleteArchitectureElement("realizer")).dsl())
                    .doesNotContain("element realizer type Component");
        }
    }

    @Test
    void allowsRepairingAReviewedSlotThatWasAlreadyInvalid() {
        String invalid = configure(store(DSL, entry(ASSIGNMENT)))
                .replace("x-admbw-type: \"OperationalConstraint\";", "x-admbw-type: \"ResourceRole\";");
        var repair = new UpdateArchitectureElement("operation", "Component",
                Map.of(AdmbwFffmlvProfile.TYPE, "OperationalConstraint"));

        assertThat(commands.apply(invalid, repair).dsl()).contains("x-admbw-type: \"OperationalConstraint\";");
    }

    @Test
    void undoesAnEndpointEditTogetherWithTheAssignmentThatUsedIt() {
        String before = configure(DSL.replace("x-admbw-type: \"OperationalConstraint\";", ""));
        var declare = new UpdateArchitectureElement("operation", "Component",
                Map.of(AdmbwFffmlvProfile.TYPE, "OperationalConstraint"));
        String reviewed = commands.applyAll(before, List.of(declare,
                new SetRequirementPlanning(REQUIREMENT_ID, entry(ASSIGNMENT)))).dsl();

        assertThatCode(() -> commands.inverse(reviewed, before, reviewed)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUndoThatWouldInvalidateALaterReviewedAssignment() {
        String before = configure(DSL.replace("x-admbw-type: \"OperationalConstraint\";", ""));
        String declared = commands.apply(before, new UpdateArchitectureElement("operation", "Component",
                Map.of(AdmbwFffmlvProfile.TYPE, "OperationalConstraint"))).dsl();
        String reviewed = store(declared, entry(ASSIGNMENT));

        assertThatThrownBy(() -> commands.inverse(reviewed, before, declared))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_R7_ENDPOINT_IN_USE"));
    }

    private String configure(String source) {
        return commands.apply(source, new ConfigureAdmbwDelivery("WARN", null)).dsl();
    }

    private String store(String source, PlanningEntry entry) {
        return commands.apply(source, new SetRequirementPlanning(REQUIREMENT_ID, entry)).dsl();
    }

    private static PlanningEntry assignment(String key, String value) {
        Map<String, String> values = new LinkedHashMap<>(ASSIGNMENT);
        values.put(key, value);
        return entry(values);
    }

    private static PlanningEntry entry(Map<String, String> values) {
        return new PlanningEntry("r7", "admbw-r7", "1", "MANUAL", values);
    }
}
