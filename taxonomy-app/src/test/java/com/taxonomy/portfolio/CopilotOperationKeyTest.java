package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import com.taxonomy.portfolio.service.CopilotOperationKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotOperationKeyTest {

    private static final String ID = "a".repeat(64);

    @Test
    void roundTripsAllPersistentOperationMetadata() {
        CopilotOperationKey key = new CopilotOperationKey(
                true,
                ID,
                2,
                3,
                AnalysisAutomationProfile.EXHAUSTIVE,
                true,
                false);

        assertThat(CopilotOperationKey.parse(key.value()))
                .contains(key);
        assertThat(key.value()).hasSizeLessThan(160);
    }

    @Test
    void rejectsOrdinaryClientIdempotencyKeys() {
        assertThat(CopilotOperationKey.parse("detail:41:1700000000"))
                .isEmpty();
    }
}
