package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import com.taxonomy.portfolio.service.CopilotOperationKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void enumeratesAllCanonicalV1KeysForOneOperation() {
        var candidates = CopilotOperationKey.candidatesForOperation(ID);

        assertThat(candidates).hasSize(144).doesNotHaveDuplicates()
                .contains("copilot:v1:" + ID + ":1/1:STANDARD:00",
                        "autopilot:v1:" + ID + ":3/3:EXHAUSTIVE:11")
                .allSatisfy(candidate -> {
                    var parsed = CopilotOperationKey.parse(candidate).orElseThrow();
                    assertThat(parsed.operationId()).isEqualTo(ID);
                    assertThat(candidate).isEqualTo(parsed.value());
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "a%_", "01234567-89ab-cdef-0123-456789abcdef",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void invalidOperationIdsHaveNoCanonicalKeys(String operationId) {
        assertThat(CopilotOperationKey.candidatesForOperation(operationId)).isEmpty();
    }

    @Test
    void rejectsOrdinaryClientIdempotencyKeys() {
        assertThat(CopilotOperationKey.parse("detail:41:1700000000"))
                .isEmpty();
    }
}
