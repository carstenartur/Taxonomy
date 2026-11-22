package com.taxonomy.dsl.admbw;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmbwDeliveryCoverageTest {
    private static final String MODEL = """
            element capability type Capability {
              title: "Training capability";
              description: "Supports the delivery of training.";
              x-admbw-catalogue-guid: "{67AEAD63-403A-48FD-A022-174DC3037CE2}";
              x-admbw-catalogue-reference: "Authored unit fixture";
            }
            element service type COIService {
              title: "Training portal";
              description: "Provides approved training material.";
              x-admbw-type: "ServiceSpecification";
            }
            element process type Process {
              title: "Deliver training";
            }
            element app type UserApplication {
              title: "Learner interface";
            }
            relation service FULFILLS capability {
              status: "accepted";
            }
            relation service SUPPORTS process {
              status: "accepted";
            }
            relation app USES service {
              status: "proposed";
            }
            requirement REQ-1 {
              title: "Training";
              text: "Provide training.";
            }
            mapping REQ-1 -> service {
              source: "manual";
            }
            view selected {
              title: "Reviewed capability view";
              include: capability;
              include: service;
            }
            view analysis {
              title: "Saved analysis";
              include: process;
              include: app;
              x-analysis-snapshot-id: "saved-training";
              x-analysis-status: "PARTIAL";
            }
            """;

    @Test
    void accountsForAllSourceObjectsEvenWhenTheSelectedCapabilityViewHasNoIssues() {
        var report = new AdmbwFffmlvProfile().assess(MODEL, "TRAINING", "selected");
        assertThat(report.capabilityToService().issues()).isEmpty();
        var coverage = report.coverage();
        assertThat(coverage.status()).isEqualTo("PARTIAL");
        assertThat(coverage.elements().total()).isEqualTo(4);
        assertThat(coverage.elements().represented()).isEqualTo(2);
        assertThat(coverage.elements().omitted()).isEqualTo(2);
        assertThat(coverage.relations().total()).isEqualTo(3);
        assertThat(coverage.relations().represented()).isEqualTo(1);
        assertThat(coverage.requirements().total()).isEqualTo(1);
        assertThat(coverage.requirements().represented()).isZero();
        assertThat(coverage.mappings().total()).isEqualTo(1);
        assertThat(coverage.mappings().represented()).isZero();
        assertThat(coverage.omissions()).extracting(AdmbwDeliveryCoverage.Omission::ref)
                .containsExactlyInAnyOrder("element:app", "element:process", "relation:service SUPPORTS process",
                        "relation:app USES service", "requirement:REQ-1", "mapping:REQ-1 -> service");
    }

    @Test
    void distinguishesSelectionOmissionsFromUnsupportedTypesAndUnreviewedRelations() {
        var selected = new AdmbwFffmlvProfile().assess(MODEL, "TRAINING", "selected").coverage();
        assertThat(selected.omissions()).filteredOn(o -> o.ref().equals("element:process"))
                .extracting(AdmbwDeliveryCoverage.Omission::reason).containsExactly("OUTSIDE_SELECTED_C1_S1_VIEW");
        var whole = new AdmbwFffmlvProfile().assess(MODEL, "TRAINING", null).coverage();
        assertThat(whole.omissions()).filteredOn(o -> o.ref().equals("element:process"))
                .extracting(AdmbwDeliveryCoverage.Omission::reason).containsExactly("UNSUPPORTED_ELEMENT_TYPE");
        assertThat(whole.omissions()).filteredOn(o -> o.ref().equals("relation:app USES service"))
                .extracting(AdmbwDeliveryCoverage.Omission::reason).containsExactly("RELATION_NOT_ACCEPTED");
        assertThat(whole.omissions()).filteredOn(o -> o.ref().equals("mapping:REQ-1 -> service"))
                .extracting(AdmbwDeliveryCoverage.Omission::reason).containsExactly("REQUIREMENT_MAPPING_NOT_EXPORTED");
    }

    @Test
    void aSavedViewCanOmitARelationEvenWhenBothEndpointsAreRepresented() {
        String filtered = MODEL.replace("title: \"Reviewed capability view\";",
                "title: \"Reviewed capability view\";\n  x-analysis-relation-members: \"\";");
        var coverage = new AdmbwFffmlvProfile().assess(filtered, "TRAINING", "selected").coverage();
        assertThat(coverage.relations().represented()).isZero();
        assertThat(coverage.omissions()).filteredOn(o -> o.ref().equals("relation:service FULFILLS capability"))
                .extracting(AdmbwDeliveryCoverage.Omission::reason).containsExactly("RELATION_OUTSIDE_SELECTED_VIEW");
    }

    @Test
    void reportsAnAbsentEndpointWithoutCallingTheRelationRepresented() {
        String dangling = MODEL + """
                relation service FULFILLS absent {
                  status: "accepted";
                }
                """;
        var coverage = new AdmbwFffmlvProfile().assess(dangling, "TRAINING", null).coverage();
        assertThat(coverage.relations()).isEqualTo(new AdmbwDeliveryCoverage.Counts(4, 1, 3));
        assertThat(coverage.omissions()).filteredOn(o -> o.ref().equals("relation:service FULFILLS absent"))
                .extracting(AdmbwDeliveryCoverage.Omission::reason).containsExactly("RELATION_ENDPOINT_NOT_FOUND");
    }

    @Test
    void anUnsupportedServiceMappingIsNotDescribedAsAnUnusedR7Role() {
        String misclassified = MODEL.replace("title: \"Deliver training\";",
                "title: \"Deliver training\";\n  x-admbw-type: \"ServiceSpecification\";");
        var coverage = new AdmbwFffmlvProfile().assess(misclassified, "TRAINING", null).coverage();
        assertThat(coverage.omissions()).filteredOn(o -> o.ref().equals("element:process"))
                .extracting(AdmbwDeliveryCoverage.Omission::reason).containsExactly("UNSUPPORTED_ELEMENT_TYPE");
    }

    @Test
    void doesNotClaimInventoryCoverageWhenSourceTextWouldBeSilentlyIgnored() {
        String inlineBlock = MODEL + "element hidden type Process { title: \"Hidden process\"; }\n";
        assertThatThrownBy(() -> new AdmbwFffmlvProfile().assess(inlineBlock, "TRAINING", "selected"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside a DSL block");
        String strayProperty = MODEL + "title: \"A detached requirement\";\n";
        assertThatThrownBy(() -> new AdmbwFffmlvProfile().assess(strayProperty, "TRAINING", "selected"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside a DSL block");
    }

    @Test
    void rejectsAmbiguousMappingHeadersIdentitiesAndProperties() {
        for (String invalid : java.util.List.of(
                MODEL.replace("mapping REQ-1 -> service", "mapping REQ-1 broken service"),
                MODEL.replace("mapping REQ-1 -> service", "mapping REQ-1 ->"),
                MODEL + "mapping REQ-1 -> service {\n  source: \"manual\";\n}\n",
                MODEL.replace("source: \"manual\";", "source: \"manual\";\n  source: \"automatic\";"))) {
            assertThatThrownBy(() -> new AdmbwFffmlvProfile().assess(invalid, "TRAINING", "selected"))
                    .isInstanceOf(com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class);
        }
    }

    @Test
    void inventoriesSourceHeadersSeparatedByTabsLikeOtherWhitespace() {
        String tabSeparated = MODEL.replace("element process type Process", "element\tprocess type Process")
                .replace("mapping REQ-1 -> service", "mapping\tREQ-1 -> service");
        var coverage = new AdmbwFffmlvProfile().assess(tabSeparated, "TRAINING", "selected").coverage();
        assertThat(coverage.elements().total()).isEqualTo(4);
        assertThat(coverage.mappings().total()).isEqualTo(1);
        assertThat(coverage.omissions()).extracting(AdmbwDeliveryCoverage.Omission::ref)
                .contains("element:process", "mapping:REQ-1 -> service");
    }

    @Test
    void rejectsAdditionalSourceAfterAClosingDelimiterInsteadOfIgnoringIt() {
        String attached = MODEL.replaceFirst("\\n}\\n", "\n} element hidden type Process { title: \"Hidden\"; }\n");
        assertThatThrownBy(() -> new AdmbwFffmlvProfile().assess(attached, "TRAINING", "selected"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closing delimiter");
        assertThat(new AdmbwFffmlvProfile().assess(MODEL.replace("\n}\n", "\n} # retained comment\n"),
                "TRAINING", "selected").coverage().elements().total()).isEqualTo(4);
    }

    @Test
    void preservesPartialSourceAnalysisAndExplainsTheExactDeliveryBoundary() {
        var coverage = new AdmbwFffmlvProfile().assess(MODEL, "TRAINING", "selected").coverage();
        assertThat(coverage.sourceAnalyses()).containsExactly(
                new AdmbwDeliveryCoverage.SourceAnalysis("analysis", "saved-training", "PARTIAL"));
        assertThat(coverage.notes()).contains("C1-S1 selection: selected", "R7 scope: all checkpoint requirements",
                "element:process", "relation:service SUPPORTS process", "mapping:REQ-1 -> service",
                "saved-training", "PARTIAL", "not full architecture or ADMBw method conformance");
    }

    @Test
    void completeProjectionStillRequiresMethodReviewAndAnEmptyProjectionIsNotComplete() {
        String onlyCapability = """
                element cap type Capability {
                  title: "Capability";
                  description: "Provides the required capability.";
                  x-admbw-catalogue-guid: "{67AEAD63-403A-48FD-A022-174DC3037CE2}";
                  x-admbw-catalogue-reference: "Authored unit fixture";
                }
                element svc type CoreService {
                  title: "Service";
                  description: "Provides the required service.";
                  x-admbw-type: "ServiceSpecification";
                }
                relation cap REALIZES svc {
                  status: "accepted";
                }
                """;
        var report = new AdmbwFffmlvProfile().assess(onlyCapability, "TEST", null);
        assertThat(report.coverage().status()).isEqualTo("ALL_SOURCE_OBJECTS_REPRESENTED");
        assertThat(report.capabilityToService().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.requirementDerivation().status()).isEqualTo("CHANGES_REQUIRED");
        assertThat(new AdmbwFffmlvProfile().assess("", "TEST", null).coverage().status()).isEqualTo("NO_SOURCE_OBJECTS");
    }
}
