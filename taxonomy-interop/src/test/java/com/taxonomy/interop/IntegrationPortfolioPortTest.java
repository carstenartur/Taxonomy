package com.taxonomy.interop;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrationPortfolioPortTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"DRAFT", "APPROVED", "ARCHIVED", "FUTURE_STATUS"})
    void preservesStatusAndRecognizesOnlyTheRelevantStates(String status) {
        var value = new IntegrationPortfolioPort.RequirementData(1L, "key", "Title", status, null, null, null);
        assertThat(value.status()).isEqualTo(status);
        assertThat(value.archived()).isEqualTo("ARCHIVED".equals(status));
        assertThat(value.approved()).isEqualTo("APPROVED".equals(status));
    }
}
