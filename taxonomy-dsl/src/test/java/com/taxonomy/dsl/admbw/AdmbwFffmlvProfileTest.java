package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.planning.PlanningEntry;
import com.taxonomy.dsl.planning.PlanningInformation;
import com.taxonomy.dsl.requirements.RequirementRealization;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmbwFffmlvProfileTest {

    private static final String CAPABILITY_VIEW = """
            element capability type Capability {
              title: "Warnungen bereitstellen";
              description: "Fähigkeit zur Ausgabe von Warnmeldungen.";
              x-admbw-catalogue-guid: "{67AEAD63-403A-48FD-A022-174DC3037CE2}";
              x-admbw-catalogue-reference: "Standardelementekatalog der zuständigen Stelle, Referenzstand 1";
            }
            element service type CoreService {
              title: "Warnmeldungsdienst";
              description: "Stellt freigegebene Warnmeldungen bereit.";
              x-admbw-type: "ServiceSpecification";
            }
            relation capability REALIZES service {
              status: "accepted";
            }
            view warning {
              title: "Warnung";
              include: capability;
              include: service;
            }
            """;

    private static final String REQUIREMENT = "Der Warnmeldungsdienst muss Warnungen bereitstellen.";
    private static final String DERIVATION = """
            element operation type Component {
              title: "Warnungen verfügbar machen";
              description: "Betriebliche Vorgabe für Warnmeldungen.";
              x-admbw-type: "OperationalConstraint";
            }
            element warning-office type Component {
              title: "Warnstelle";
              description: "Verantwortlich für die Herausgabe der Warnmeldungen.";
              x-admbw-type: "ResourceRole";
            }
            element operational-role type BusinessRole {
              title: "Verantwortliche Stelle";
              description: "Betreibt die öffentliche Warnung.";
              x-admbw-type: "OperationalRole";
              x-admbw-catalogue-guid: "{55AEAD63-403A-48FD-A022-174DC3037CE2}";
              x-admbw-catalogue-reference: "Modellelementekatalog, Fassung 1";
            }
            requirement REQ-1 {
              title: "Warnungen bereitstellen";
              text: "%s";
              x-version: "2";
              x-content-hash: "%s";
            }
            """.formatted(REQUIREMENT, RequirementRealization.textHash(REQUIREMENT));

    @Test
    void assessmentIdentifiesTheExactSharedExternalProfile() {
        var report = assess(CAPABILITY_VIEW, "warning");
        assertThat(report.sourceProfile()).isSameAs(com.taxonomy.sparx.mapping.AdmbwSourceProfile.CURRENT);
        assertThat(report.profile()).isEqualTo(report.sourceProfile().id());
        assertThat(AdmbwFffmlvProfile.ELEMENT_STEREOTYPES).containsExactlyInAnyOrderElementsOf(report.sourceProfile().authoringElementTypes());
    }

    @Test
    void mapsTheReviewedCapabilityRelationInTheOfficialClientSupplierDirection() {
        var view = assess(CAPABILITY_VIEW, "warning").capabilityToService();
        assertThat(view.issues()).isEmpty();
        assertThat(view.name()).isEqualTo("C1-S1 : WARN : Capability to Service Mapping");
        assertThat(view.packagePath()).containsExactly("Service Specifications", "C1-S1", "Einordnung Fähigkeitslage");
        assertThat(view.elements()).anySatisfy(element -> {
            assertThat(element.ref()).isEqualTo("element:capability");
            assertThat(element.stereotype()).isEqualTo("Capability");
            assertThat(element.catalogueGuid()).isEqualTo("{67AEAD63-403A-48FD-A022-174DC3037CE2}");
        });
        assertThat(view.connectors()).containsExactly(
                new AdmbwFffmlvProfile.Connector("Exhibits", "element:service", "element:capability"));
    }

    @Test
    void savesCatalogueBindingThroughTheExistingElementEditorCommand() {
        var commands = new ArchitectureDslCommands();
        String withoutGuid = CAPABILITY_VIEW.replace("x-admbw-catalogue-guid: \"{67AEAD63-403A-48FD-A022-174DC3037CE2}\";", "");
        String edited = commands.apply(withoutGuid, new UpdateArchitectureElement("capability", "Capability",
                Map.of("x-admbw-catalogue-guid", "{67AEAD63-403A-48FD-A022-174DC3037CE2}"))).dsl();
        assertThat(assess(edited, "warning").capabilityToService().issues()).isEmpty();
    }

    @Test
    void doesNotUseProposalsOrC3CodesAsCatalogueProof() {
        var view = assess(CAPABILITY_VIEW.replace("accepted", "proposed")
                .replace("{67AEAD63-403A-48FD-A022-174DC3037CE2}", "CP-1022"), "warning").capabilityToService();
        assertThat(view.connectors()).isEmpty();
        assertThat(view.issues()).extracting(AdmbwFffmlvProfile.Issue::code)
                .contains("CAPABILITY_CATALOGUE_GUID_REQUIRED", "SERVICE_EXHIBITS_REQUIRED");
    }

    @Test
    void rejectsContradictoryRelationshipReviewInTheOriginalCheckpoint() {
        String contradictory = CAPABILITY_VIEW.replace("status: \"accepted\";", """
                status: "accepted";
                status: "rejected";
                """);
        assertThatThrownBy(() -> assess(contradictory, "warning"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("status");
    }

    @Test
    void rejectsMalformedOrRepeatedRelationBlocksBeforeProjectingThem() {
        String triple = "relation capability REALIZES service";
        String nested = CAPABILITY_VIEW.replace(triple + " {", triple + " {\n  unknown nested {")
                .replace("status: \"accepted\";", "status: \"accepted\";\n  }");
        String duplicate = CAPABILITY_VIEW + triple + " {\n  status: \"rejected\";\n}\n";
        String unclosed = CAPABILITY_VIEW + "relation service FULFILLS capability {\n  status: \"accepted\";\n";
        for (String malformed : java.util.List.of(nested, duplicate, unclosed)) {
            assertThatThrownBy(() -> assess(malformed, "warning")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void leavesUnrelatedCommunicationsServicesOutsideTheBoundedView() {
        String communications = """
                element voice type CommunicationsService {
                  title: "Telefonzugang";
                  description: "Ergänzender Kommunikationsweg.";
                }
                """;
        assertThat(assess(CAPABILITY_VIEW + communications, null).capabilityToService().issues()).isEmpty();
        String explicitlyMapped = communications.replace("description:", "x-admbw-type: ServiceSpecification;\n  description:");
        assertThat(assess(CAPABILITY_VIEW + explicitlyMapped, null).capabilityToService().issues())
                .extracting(AdmbwFffmlvProfile.Issue::code).containsExactly("UNSUPPORTED_SERVICE_MAPPING");
    }

    @Test
    void excludesRelationsOutsideTheSavedViewEvenWhenBothEndpointsAreInIt() {
        String filtered = CAPABILITY_VIEW.replace("title: \"Warnung\";", """
                title: "Warnung";
                x-analysis-relation-members: "";
                """);
        assertThat(assess(filtered, "warning").capabilityToService().connectors()).isEmpty();
    }

    @Test
    void attachesExactlyOneTypedSourceAndTheOptionalTypedRealizerToTheVersionedRequirement() {
        var view = assess(plan("operation", "warning-office"), null).requirementDerivation();
        assertThat(view.issues()).isEmpty();
        assertThat(view.elements()).anySatisfy(element -> {
            assertThat(element.ref()).isEqualTo("requirement:REQ-1");
            assertThat(element.stereotype()).isEqualTo("FunctionalRequirement");
            assertThat(element.requirementVersion()).isEqualTo(2);
            assertThat(element.requirementContentHash()).isEqualTo(RequirementRealization.textHash(REQUIREMENT));
        });
        assertThat(view.connectors()).containsExactly(
                new AdmbwFffmlvProfile.Connector("Satisfy", "element:operational-role", "element:operation"),
                new AdmbwFffmlvProfile.Connector("DerivedFrom", "requirement:REQ-1", "element:operation"),
                new AdmbwFffmlvProfile.Connector("ToBeRealizedBy", "requirement:REQ-1", "element:warning-office"));
    }

    @Test
    void requiresTheOfficialCatalogueSubjectForTheOperationalConstraint() {
        String dsl = plan("operation", "warning-office")
                .replace("{55AEAD63-403A-48FD-A022-174DC3037CE2}", "BR-1234");
        var view = assess(dsl, null).requirementDerivation();
        assertThat(view.issues()).extracting(AdmbwFffmlvProfile.Issue::code)
                .containsExactly("SUBJECT_CATALOGUE_GUID_REQUIRED");
        assertThat(view.status()).isEqualTo("CHANGES_REQUIRED");
    }

    @Test
    void willNotReuseAnR7DecisionForANewerRequirementVersion() {
        String replacement = "Der Warnmeldungsdienst muss Warnungen zeitnah bereitstellen.";
        String changed = plan("operation", "warning-office").replace(REQUIREMENT, replacement)
                .replace("x-version: \"2\"", "x-version: \"3\"")
                .replace("x-content-hash: \"" + RequirementRealization.textHash(REQUIREMENT) + "\"",
                        "x-content-hash: \"" + RequirementRealization.textHash(replacement) + "\"");
        var view = assess(changed, null).requirementDerivation();
        assertThat(view.issues()).extracting(AdmbwFffmlvProfile.Issue::code).containsExactly("STALE_R7_PLANNING");
        assertThat(view.connectors()).isEmpty();
    }

    @Test
    void enforcesFffmlvConstraintsBeyondTheGeneralAdmbwMetamodel() {
        var view = assess(plan("service", "service"), null).requirementDerivation();
        assertThat(view.issues()).extracting(AdmbwFffmlvProfile.Issue::code)
                .containsExactly("OPERATIONAL_CONSTRAINT_REQUIRED", "R7_REALIZER_TYPE");
        assertThat(view.connectors()).isEmpty();
    }

    @Test
    void requiresLocalReviewForImportedPlanningEvenIfTheTextAndVersionMatch() {
        var registry = new PlanningInformation();
        String baseline = CAPABILITY_VIEW + DERIVATION;
        String original = plan("operation", "warning-office");
        PlanningEntry exported = registry.read(original, "REQ-1").getFirst().entry();
        String imported = registry.upsert(baseline, "REQ-1", exported, true);
        var pending = assess(imported, null).requirementDerivation();
        assertThat(pending.issues()).extracting(AdmbwFffmlvProfile.Issue::code)
                .containsExactly("R7_IMPORTED_REVIEW_REQUIRED");
        assertThat(pending.connectors()).isEmpty();
        String reviewed = registry.upsert(imported, "REQ-1", planning("operation", "warning-office"), false);
        assertThat(assess(reviewed, null).requirementDerivation().issues()).isEmpty();
    }

    @Test
    void retainsTheOptionalNativeResourceRoleBinding() {
        String guid = "{FFAEAD63-403A-48FD-A022-174DC3037CE2}";
        String dsl = plan("operation", "warning-office").replace("x-admbw-type: \"ResourceRole\";", """
                x-admbw-type: "ResourceRole";
                x-admbw-catalogue-guid: "%s";
                x-admbw-catalogue-reference: "Modellelementekatalog, Fassung 1";
                """.formatted(guid));
        var view = assess(dsl, null).requirementDerivation();
        assertThat(view.issues()).isEmpty();
        assertThat(view.elements()).anySatisfy(element -> {
            assertThat(element.stereotype()).isEqualTo("ResourceRole");
            assertThat(element.catalogueGuid()).isEqualTo(guid);
        });
    }

    @Test
    void preventsTheSameOfficialCapabilityFromBecomingAnOperationalConstraint() {
        String dsl = plan("capability", "warning-office")
                .replace("title: \"Warnungen bereitstellen\";", """
                        title: "Warnungen bereitstellen";
                        x-admbw-type: "OperationalConstraint";
                        """);
        var report = assess(dsl, null);
        assertThat(report.capabilityToService().issues()).extracting(AdmbwFffmlvProfile.Issue::code)
                .contains("INCOMPATIBLE_ADMBW_ROLE");
        assertThat(report.requirementDerivation().issues()).extracting(AdmbwFffmlvProfile.Issue::code)
                .contains("OPERATIONAL_CONSTRAINT_REQUIRED");
    }

    private static String plan(String derivedFrom, String realizer) {
        return new PlanningInformation().upsert(CAPABILITY_VIEW + DERIVATION, "REQ-1", planning(derivedFrom, realizer), false);
    }

    private static PlanningEntry planning(String derivedFrom, String realizer) {
        return new PlanningEntry("r7", "admbw-r7", "1", "MANUAL", Map.of(
                "requirementType", "FunctionalRequirement", "derivedFrom", derivedFrom, "realizer", realizer,
                "constraintSubject", "operational-role"));
    }

    private static AdmbwFffmlvProfile.Report assess(String dsl, String viewId) {
        return new AdmbwFffmlvProfile().assess(dsl, "WARN", viewId);
    }
}
