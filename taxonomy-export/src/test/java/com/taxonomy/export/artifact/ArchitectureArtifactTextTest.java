package com.taxonomy.export.artifact;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureArtifactTextTest {

    private static final String C1_CONTROL =
            String.valueOf((char) 0x85);

    @Test
    void rejectsLeadingTrailingAndEmbeddedIsoControlsBeforeNormalization() {
        List<String> unsafeValues = List.of(
                "\nvalue",
                "value\n",
                "\tvalue",
                "value\t",
                C1_CONTROL + "value",
                "value" + C1_CONTROL,
                "va" + C1_CONTROL + "lue");

        for (String unsafe : unsafeValues) {
            assertThatThrownBy(() ->
                    ArchitectureArtifactText.requireSafeText(
                            unsafe,
                            "field"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("field contains ISO control characters")
                    .hasMessageNotContaining(unsafe);
        }
    }

    @Test
    void stripsOrdinaryOuterWhitespaceOnlyAfterTheOriginalValueIsSafe() {
        assertThat(ArchitectureArtifactText.requireSafeText(
                "  stable-value  ",
                "field"))
                .isEqualTo("stable-value");
    }
}
