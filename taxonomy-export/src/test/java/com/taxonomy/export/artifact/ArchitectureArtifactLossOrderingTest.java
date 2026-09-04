package com.taxonomy.export.artifact;

import org.junit.jupiter.api.Test;

import static com.taxonomy.export.artifact.ArchitectureArtifactLoss.Disposition.OMITTED;
import static com.taxonomy.export.artifact.ArchitectureArtifactLoss.Disposition.PRESERVED;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureArtifactLossOrderingTest {

    @Test
    void dispositionOrderUsesStableSerializedNameInsteadOfEnumOrdinal() {
        ArchitectureArtifactLoss preserved = loss(PRESERVED);
        ArchitectureArtifactLoss omitted = loss(OMITTED);

        assertThat(preserved.compareTo(omitted)).isPositive();
        assertThat(omitted.compareTo(preserved)).isNegative();
    }

    private static ArchitectureArtifactLoss loss(
            ArchitectureArtifactLoss.Disposition disposition) {
        return new ArchitectureArtifactLoss(
                "same-code",
                "DiagramNode.sameField",
                disposition,
                "Same detail.");
    }
}
