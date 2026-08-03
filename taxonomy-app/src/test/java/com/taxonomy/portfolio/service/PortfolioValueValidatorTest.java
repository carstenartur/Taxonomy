package com.taxonomy.portfolio.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioValueValidatorTest {

    @Test
    void normalizesDatabaseCompatibleMoneyAndRejectsInvalidValues() {
        assertThat(PortfolioValueValidator.money(new BigDecimal("123.4500"), "amount"))
                .isEqualByComparingTo("123.45");
        assertThat(PortfolioValueValidator.money(BigDecimal.ZERO, "amount"))
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThatThrownBy(() -> PortfolioValueValidator.money(
                new BigDecimal("-0.01"), "amount"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> PortfolioValueValidator.money(
                new BigDecimal("1.001"), "amount"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("at most 2 decimal places");
        assertThatThrownBy(() -> PortfolioValueValidator.money(
                new BigDecimal("100000000000000000"), "amount"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("17 integer digits");
    }

    @Test
    void acceptsRegisteredIsoCurrencyCodesOnly() {
        assertThat(PortfolioValueValidator.currency(" eur ", "currency")).isEqualTo("EUR");
        assertThatThrownBy(() -> PortfolioValueValidator.currency("AAA", "currency"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("ISO 4217");
        assertThatThrownBy(() -> PortfolioValueValidator.requireMoneyPair(
                BigDecimal.ONE, null, "amount", "currency"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("must be supplied together");
    }

    @Test
    void rejectsVerificationDatesBeyondClockSkew() {
        Instant now = Instant.parse("2026-08-03T06:00:00Z");
        assertThat(PortfolioValueValidator.verifiedAt(now.plusSeconds(300), now, true))
                .isEqualTo(now.plusSeconds(300));
        assertThatThrownBy(() -> PortfolioValueValidator.verifiedAt(
                now.plusSeconds(301), now, true))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("five minutes");
    }

    @Test
    void boundsExtensionAttributeCountKeysAndValues() {
        Map<String, String> normalized = PortfolioValueValidator.extensionAttributes(
                Map.of(" owner ", " architecture "));
        assertThat(normalized).containsEntry("owner", "architecture");

        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index <= PortfolioValueValidator.MAX_EXTENSION_ATTRIBUTES; index++) {
            tooMany.put("key-" + index, "value");
        }
        assertThatThrownBy(() -> PortfolioValueValidator.extensionAttributes(tooMany))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("more than 100 entries");
        assertThatThrownBy(() -> PortfolioValueValidator.extensionAttributes(
                Map.of("key", "x".repeat(PortfolioValueValidator.MAX_EXTENSION_VALUE_CHARACTERS + 1))))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("exceeds 1000 characters");
    }
}
