package com.taxonomy.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DiagramTextWrapperTest {

    @Test
    void preservesTheSecondVisibleLine() {
        assertThat(DiagramTextWrapper.wrap(
                "Alpha bravo charlie", 11, 2, "Fallback label"))
                .containsExactly("Alpha bravo", "charlie");
    }

    @Test
    void marksTruncationOnTheLastAllowedLine() {
        assertThat(DiagramTextWrapper.wrap(
                "Alpha bravo charlie delta", 11, 2, "Fallback label"))
                .containsExactly("Alpha bravo", "charlie...");
    }

    @Test
    void hardWrapsLongTokensWithoutDroppingTheTailIndicator() {
        assertThat(DiagramTextWrapper.wrap(
                "ABCDEFGHIJK secondary", 5, 2, "Fallback label"))
                .containsExactly("ABCDE", "FG...");
    }

    @Test
    void wrapsTheFallbackForBlankLabels() {
        assertThat(DiagramTextWrapper.wrap(" ", 10, 2, "Fallback label"))
                .containsExactly("Fallback", "label");
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DiagramTextWrapper.wrap("label", 0, 2, "fallback"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DiagramTextWrapper.wrap("label", 10, 0, "fallback"));
    }
}
