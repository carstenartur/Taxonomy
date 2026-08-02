package com.taxonomy.dsl.merge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaxDslSemanticMergerTest {

    private final TaxDslSemanticMerger merger = new TaxDslSemanticMerger();

    @Test
    void mergesIndependentRequirementsAddedByDifferentPeople() {
        TaxDslMergeResult result = merger.merge(
                document(""),
                document("""
                        projectRequirement P-001 REQ-A-001 {
                          title: "Alice requirement";
                          text: "Secure voice is required";
                        }
                        """),
                document("""
                        projectRequirement P-001 REQ-B-001 {
                          title: "Bob requirement";
                          text: "Offline operation is required";
                        }
                        """));

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.mergedText())
                .contains("projectRequirement P-001 REQ-A-001")
                .contains("projectRequirement P-001 REQ-B-001");
    }

    @Test
    void mergesDifferentPropertiesOfTheSameProject() {
        String base = document("""
                project P-001 {
                  title: "Initial title";
                  description: "Initial description";
                }
                """);
        String ours = document("""
                project P-001 {
                  title: "Reviewed title";
                  description: "Initial description";
                }
                """);
        String theirs = document("""
                project P-001 {
                  title: "Initial title";
                  description: "Shared target architecture";
                }
                """);

        TaxDslMergeResult result = merger.merge(base, ours, theirs);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.mergedText())
                .contains("title: \"Reviewed title\"")
                .contains("description: \"Shared target architecture\"");
    }

    @Test
    void reportsConflictingRequirementTextEdits() {
        String base = requirement("Original requirement");
        String ours = requirement("Alice revision");
        String theirs = requirement("Bob revision");

        TaxDslMergeResult result = merger.merge(base, ours, theirs);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.conflictIdentifiers())
                .containsExactly("projectRequirement P-001 REQ-001:text");
    }

    @Test
    void reportsDeleteVersusModifyConflict() {
        String base = requirement("Original requirement");
        String ours = document("");
        String theirs = requirement("Changed requirement");

        TaxDslMergeResult result = merger.merge(base, ours, theirs);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.conflicts().getFirst().reason())
                .contains("deleted in ours");
    }

    @Test
    void treatsRepeatedPropertyBlocksAtomicallyWhenBothSidesEdit() {
        String base = document("""
                view OPERATIONS {
                  include: "CP-1000";
                  include: "CR-1000";
                }
                """);
        String ours = document("""
                view OPERATIONS {
                  include: "CP-1001";
                  include: "CR-1000";
                }
                """);
        String theirs = document("""
                view OPERATIONS {
                  include: "CP-1000";
                  include: "CR-1001";
                }
                """);

        TaxDslMergeResult result = merger.merge(base, ours, theirs);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.conflicts().getFirst().reason())
                .contains("repeated property keys");
    }

    private static String requirement(String text) {
        return document("""
                projectRequirement P-001 REQ-001 {
                  title: "Requirement";
                  text: "%s";
                }
                """.formatted(text));
    }

    private static String document(String blocks) {
        return """
                meta {
                  language: "taxdsl";
                  version: "2.1";
                  namespace: "test";
                }

                %s
                """.formatted(blocks);
    }
}
