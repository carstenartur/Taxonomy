package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the ADMIN health dashboard warning states to WCAG AA text contrast. */
class AdminHealthStatusContrastContractTest {

    private static final double WCAG_AA_NORMAL_TEXT = 4.5;

    @Test
    void warningStatesUseScopedAccessibleColoursInLightAndDarkThemes()
            throws IOException {
        String stylesheet = resource("/static/css/git-status.css");

        assertThat(stylesheet)
                .contains("#healthOverallBadge.text-warning")
                .contains("#healthAiBadge.text-warning")
                .contains("#healthEmbeddingBadge.text-warning")
                .contains("#healthMemoryBadge.text-warning")
                .contains("""
                        #healthOverallBadge.text-warning,
                        #healthAiBadge.text-warning,
                        #healthEmbeddingBadge.text-warning,
                        #healthMemoryBadge.text-warning {
                            color: #664d03 !important;
                        }
                        """.stripIndent())
                .contains("""
                        [data-bs-theme="dark"] #healthOverallBadge.text-warning,
                        [data-bs-theme="dark"] #healthAiBadge.text-warning,
                        [data-bs-theme="dark"] #healthEmbeddingBadge.text-warning,
                        [data-bs-theme="dark"] #healthMemoryBadge.text-warning {
                            color: #ffda6a !important;
                        }
                        """.stripIndent())
                .contains("""
                        @media (forced-colors: active) {
                            #healthOverallBadge.text-warning,
                            #healthAiBadge.text-warning,
                            #healthEmbeddingBadge.text-warning,
                            #healthMemoryBadge.text-warning {
                                color: CanvasText !important;
                            }
                        }
                        """.stripIndent());

        assertThat(contrastRatio("#664d03", "#ffffff"))
                .isGreaterThanOrEqualTo(WCAG_AA_NORMAL_TEXT);
        assertThat(contrastRatio("#ffda6a", "#1a1a2e"))
                .isGreaterThanOrEqualTo(WCAG_AA_NORMAL_TEXT);
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = AdminHealthStatusContrastContractTest.class
                .getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static double contrastRatio(String first, String second) {
        double firstLuminance = relativeLuminance(first);
        double secondLuminance = relativeLuminance(second);
        double lighter = Math.max(firstLuminance, secondLuminance);
        double darker = Math.min(firstLuminance, secondLuminance);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(String colour) {
        int red = Integer.parseInt(colour.substring(1, 3), 16);
        int green = Integer.parseInt(colour.substring(3, 5), 16);
        int blue = Integer.parseInt(colour.substring(5, 7), 16);
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
