package com.taxonomy.extension.api.llm;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Locale;
import static org.assertj.core.api.Assertions.*;

class LlmTransportContractTest {
    @Test void identityIsCanonicalIndependentlyOfDefaultLocale() {
        Locale prior = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(new ProviderId(" independent.plugin-1 ")).isEqualTo(new ProviderId("INDEPENDENT.PLUGIN-1"));
        } finally { Locale.setDefault(prior); }
    }
    @Test void rejectsNonPortableOrUnboundedIdentities() {
        for (String invalid : new String[]{null, "", "1provider", "a/b", "a b", "x".repeat(129)}) {
            assertThatThrownBy(() -> new ProviderId(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void failureCarriesOnlyTypedMetadataAndAnOptionalNonNegativeDelay() {
        var failure = new LlmTransportException(LlmTransportException.FailureKind.RATE_LIMIT,
                "Redacted provider failure", Duration.ofMillis(1500));
        assertThat(failure.kind()).isEqualTo(LlmTransportException.FailureKind.RATE_LIMIT);
        assertThat(failure.retryAfter()).contains(Duration.ofMillis(1500));
        assertThat(failure).hasNoCause();
        assertThatThrownBy(() -> new LlmTransportException(failure.kind(), "invalid", Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
