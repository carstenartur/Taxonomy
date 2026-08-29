package com.taxonomy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisMessageBundleContractTest {

    @Test
    void countBearingReviewMessagesRemainPluralNeutral() throws IOException {
        String english = resource("/i18n/messages.properties");
        String german = resource("/i18n/messages_de.properties");

        assertThat(english)
                .contains("analyze.discrepancies=\\u26A0\\uFE0F Scoring discrepancies requiring review: {0}.")
                .contains("analyze.product.coverage.gaps=\\u26A0\\uFE0F Relevant product families without a suitable catalogued product: {0}.")
                .doesNotContain("discrepancy/discrepancies")
                .doesNotContain("family/families");
        assertThat(german)
                .contains("analyze.discrepancies=\\u26A0\\uFE0F Zu pr\\u00FCfende Bewertungsabweichungen: {0}.")
                .contains("analyze.product.coverage.gaps=\\u26A0\\uFE0F Relevante Produktfamilien ohne geeignetes katalogisiertes Produkt: {0}.")
                .doesNotContain("Abweichung(en)")
                .doesNotContain("Produktfamilie(n)");
    }

    private static String resource(String path) throws IOException {
        try (var input = AnalysisMessageBundleContractTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
