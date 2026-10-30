package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Final documents may combine individually valid edits into invalid cross-block references. */
class ArchitectureReconciliationValidationTest {
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();

    // Authored architecture identities, independent of catalogue taxonomy codes.
    private static final String REQUIREMENT = """
            requirement requirement.orders {
              text: "Retain reviewed orders";
              x-project-key: "orders";
              x-requirement-key: "retention";
            }
            """;
    private static final String MODEL = """
            # Preserve the author's document annotation.
            element arch-system type System {
              title: "Order system";
              x-future-property: "Keep # literal { braces }";
            }
            element arch-child type Component {
              title: "Order storage";
            }
            element arch-peer type Component {
              title: "Order archive";
            }
            """ + REQUIREMENT;
    private static final String SOURCE = """
            source source-orders {
              # Preserve source provenance and unknown annotations.
              title: "Order policy";
              x-future-source: "retained";
            }
            """;
    private static final String EDITION = """
            sourceVersion edition-orders {
              source: "source-orders";
              versionLabel: "reviewed";
            }
            """;
    private static final String FRAGMENT = """
            sourceFragment fragment-orders {
              sourceVersion: "edition-orders";
              text: "Keep the approved order record";
            }
            """;
    private static final String LINK = """
            requirementSourceLink source-link {
              requirement: "requirement.orders";
              source: "source-orders";
              sourceVersion: "edition-orders";
              sourceFragment: "fragment-orders";
              x-planning-entry: "reviewed-policy";
            }
            """;
    // Referents may follow their users: validation concerns the complete final state.
    private static final String SOURCE_GRAPH = LINK + FRAGMENT + EDITION + SOURCE;

    @Test
    void rejectsMissingRelationEndpointsAndInvalidRelationSemantics() {
        assertProblem(MODEL + relation("arch-missing", "CONTAINS", "arch-child"),
                "NOT_FOUND", "element:arch-missing");
        assertProblem(MODEL + relation("arch-system", "CONTAINS", "arch-missing"),
                "NOT_FOUND", "element:arch-missing");
        assertProblem(MODEL + relation("arch-child", "CONTAINS", "arch-system"),
                "INCOMPATIBLE_RELATION", "relationType");
        assertProblem(MODEL + relation("arch-child", "RELATED_TO", "arch-child"),
                "SELF_RELATION", "targetId");
        assertProblem(MODEL + relation("arch-system", "INVENTED", "arch-child"),
                "UNKNOWN_RELATION_TYPE", "relationType");
        assertProblem(MODEL + relation("arch-system", "CONTAINS", "arch-child")
                .replace("accepted", "invented"), "INVALID_STATUS", "status");
    }

    @Test
    void requiresBothMappingEndpointsInTheReconciledDocument() {
        String mapping = "mapping requirement.orders -> arch-child {\n  rationale: \"Reviewed\";\n}\n";
        assertValid(MODEL + mapping);
        assertProblem(MODEL.replace(REQUIREMENT, "") + mapping,
                "NOT_FOUND", "requirement:requirement.orders");
        assertProblem(MODEL + mapping.replace("-> arch-child", "-> arch-missing"),
                "NOT_FOUND", "element:arch-missing");
        assertProblem(MODEL + mapping.replace("->", "maps-to"), "INVALID_HEADER", "mapping");
    }

    @Test
    void validatesEveryIncludedViewElementAfterReconciliation() {
        String view = """
                view reviewed-view {
                  include: "arch-system";
                  include: "arch-child";
                }
                """;
        assertValid(MODEL + view);
        assertProblem(MODEL + view.replace("include: \"arch-child\"", "include: \"arch-missing\""),
                "NOT_FOUND", "element:arch-missing");
    }

    @Test
    void rejectsStrandedEvidenceAndRetainedSnapshotRelationMembership() {
        String relation = relation("arch-system", "CONTAINS", "arch-child");
        String evidence = """
                evidence review-note {
                  for-relation: "arch-system CONTAINS arch-child";
                  summary: "Reviewed before reconciliation";
                }
                """;
        String snapshot = """
                view analysis-retained {
                  include: "arch-system";
                  include: "arch-child";
                  x-analysis-relation-members: "arch-system CONTAINS arch-child";
                }
                """;
        assertValid(MODEL + relation + evidence + snapshot);
        assertProblem(MODEL + evidence, "NOT_FOUND", "relation:arch-system CONTAINS arch-child");
        assertProblem(MODEL + snapshot, "NOT_FOUND", "relation:arch-system CONTAINS arch-child");
    }

    @Test
    void rejectsContainmentCycleIntroducedByCombiningValidRelations() {
        String forward = relation("arch-child", "CONTAINS", "arch-peer");
        String reverse = relation("arch-peer", "CONTAINS", "arch-child");
        assertValid(MODEL + forward);
        assertValid(MODEL + reverse);
        assertProblem(MODEL + forward + reverse, "CONTAINMENT_CYCLE", null);
    }

    @Test
    void rejectsTwoContainmentParentsIntroducedByCombiningValidRelations() {
        String systemParent = relation("arch-system", "CONTAINS", "arch-child");
        String componentParent = relation("arch-peer", "CONTAINS", "arch-child");
        assertValid(MODEL + systemParent);
        assertValid(MODEL + componentParent);
        assertProblem(MODEL + systemParent + componentParent, "MULTIPLE_PARENTS", "arch-child");
    }

    @Test
    void requiresPackageParentsAndElementPlacementsToResolveInTheFinalHierarchy() {
        String root = """
                package orders-package {
                  title: "Orders";
                  parent: "";
                  position: "0";
                }
                """;
        String placed = MODEL.replace("title: \"Order storage\";", """
                title: "Order storage";
                  package: "orders-package";
                  position: "0";""");
        assertValid(placed + root);
        assertProblem(placed, "PACKAGE_PARENT", "placements");
        assertProblem(MODEL + root.replace("parent: \"\"", "parent: \"missing-package\""),
                "PACKAGE_PARENT", "placements");
    }

    @Test
    void validatesEverySourceGraphEdgeIncludingCrossSourceAndCrossEditionLinks() {
        assertValid(MODEL + SOURCE_GRAPH);
        assertProblem(MODEL + LINK + FRAGMENT + EDITION, "INVALID_REFERENCE", "source");
        assertProblem(MODEL + LINK + FRAGMENT + SOURCE, "INVALID_REFERENCE", "source");
        assertProblem(MODEL + LINK + EDITION + SOURCE, "INVALID_REFERENCE", "source");
        assertProblem(MODEL.replace(REQUIREMENT, "") + SOURCE_GRAPH, "INVALID_REFERENCE", "source");

        String otherSource = SOURCE.replace("source-orders", "source-other");
        String wrongSourceEdition = EDITION.replace("source: \"source-orders\"", "source: \"source-other\"");
        assertProblem(MODEL + SOURCE + otherSource + wrongSourceEdition + FRAGMENT + LINK,
                "INVALID_REFERENCE", "source");

        String otherEdition = EDITION.replace("edition-orders", "edition-other");
        String wrongEditionFragment = FRAGMENT.replace("sourceVersion: \"edition-orders\"", "sourceVersion: \"edition-other\"");
        assertProblem(MODEL + SOURCE + EDITION + otherEdition + wrongEditionFragment + LINK,
                "INVALID_REFERENCE", "source");
    }

    @Test
    void acceptsCommentedUnknownAnnotationsAndValidSourceEdgesWithoutNormalizingTheDocument() {
        String source = (MODEL + SOURCE_GRAPH + """
                futureAnnotation extension-note {
                  x-provider: "Keep unrecognized extension blocks";
                }
                # Keep the author's final annotation without a trailing newline.""").replace("\n", "\r\n");
        assertValid(source);

        // The checked final state remains suitable for a byte-preserving semantic projection.
        assertThat(ArchitectureSemanticPatch.applyProjection(source, source)).isEqualTo(source);
        var model = commands.model(source);
        assertThat(model.findElement("arch-system").orElseThrow().getExtensions())
                .containsEntry("x-future-property", "Keep # literal { braces }");
        assertThat(model.getSources().getFirst().getExtensions()).containsEntry("x-future-source", "retained");
        assertThat(model.getRequirementSourceLinks()).singleElement().satisfies(link -> {
            assertThat(link.getRequirementId()).isEqualTo("requirement.orders");
            assertThat(link.getSourceId()).isEqualTo("source-orders");
            assertThat(link.getSourceVersionId()).isEqualTo("edition-orders");
            assertThat(link.getSourceFragmentId()).isEqualTo("fragment-orders");
        });
    }

    private static String relation(String source, String type, String target) {
        return "relation " + source + " " + type + " " + target + " {\n  status: \"accepted\";\n}\n";
    }

    private void assertValid(String source) {
        assertThatCode(() -> commands.validateReconciledSource(source)).doesNotThrowAnyException();
    }

    private void assertProblem(String source, String code, String field) {
        assertThatThrownBy(() -> commands.validateReconciledSource(source))
                .isInstanceOfSatisfying(CommandProblem.class, problem -> {
                    assertThat(problem.code()).isEqualTo(code);
                    if (field != null) assertThat(problem.field()).isEqualTo(field);
                });
    }
}
