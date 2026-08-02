package com.taxonomy.dsl.merge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioDecisionSemanticMergeTest {

    private final TaxDslSemanticMerger merger = new TaxDslSemanticMerger();

    @Test
    void mergesIndependentPropertiesOfTheSameProjectSolutionDecision() {
        String base = """
                projectSolutionDecision P-001 SOL-001 {
                  status: "PROPOSED";
                  actionStatus: "UNDECIDED";
                  priority: 50;
                }
                """;
        String ours = """
                projectSolutionDecision P-001 SOL-001 {
                  status: "PROPOSED";
                  actionStatus: "REUSE";
                  priority: 50;
                }
                """;
        String theirs = """
                projectSolutionDecision P-001 SOL-001 {
                  status: "PROPOSED";
                  actionStatus: "UNDECIDED";
                  priority: 90;
                }
                """;

        TaxDslMergeResult result = merger.merge(base, ours, theirs);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.mergedText())
                .contains("actionStatus: \"REUSE\"")
                .contains("priority: 90");
    }

    @Test
    void reportsContradictoryProductSelectionAsPropertyConflict() {
        String base = """
                solutionProductDecision P-001 SOL-001 PRD-001 {
                  reviewStatus: "CONFIRMED";
                  selectionStatus: "CANDIDATE";
                }
                """;
        String ours = """
                solutionProductDecision P-001 SOL-001 PRD-001 {
                  reviewStatus: "CONFIRMED";
                  selectionStatus: "SELECTED";
                }
                """;
        String theirs = """
                solutionProductDecision P-001 SOL-001 PRD-001 {
                  reviewStatus: "CONFIRMED";
                  selectionStatus: "REJECTED";
                }
                """;

        TaxDslMergeResult result = merger.merge(base, ours, theirs);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.blockIdentity())
                    .isEqualTo("solutionProductDecision P-001 SOL-001 PRD-001");
            assertThat(conflict.property()).isEqualTo("selectionStatus");
            assertThat(conflict.ours()).isEqualTo("SELECTED");
            assertThat(conflict.theirs()).isEqualTo("REJECTED");
        });
    }
}
