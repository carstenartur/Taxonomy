package com.taxonomy.portfolio.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Shared validation for persisted portfolio values whose database constraints are not descriptive enough. */
final class PortfolioValueValidator {

    static final int MAX_SOURCE_FRAGMENT_IDS = 1_000;
    static final int MAX_ORIGINAL_TEXT_CHARACTERS = 100_000;
    static final int MAX_EXTENSION_ATTRIBUTES = 100;
    static final int MAX_EXTENSION_KEY_CHARACTERS = 120;
    static final int MAX_EXTENSION_VALUE_CHARACTERS = 1_000;
    static final Duration MAX_VERIFICATION_CLOCK_SKEW = Duration.ofMinutes(5);

    private static final int MONEY_INTEGER_DIGITS = 17;
    private static final int MONEY_SCALE = 2;

    private PortfolioValueValidator() {
    }

    static BigDecimal money(BigDecimal value, String field) {
        if (value == null) return null;
        if (value.signum() < 0) {
            throw PortfolioException.validation(field + " must not be negative");
        }

        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) normalized = normalized.setScale(0);
        if (normalized.scale() > MONEY_SCALE) {
            throw PortfolioException.validation(field + " supports at most " + MONEY_SCALE + " decimal places");
        }
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (integerDigits > MONEY_INTEGER_DIGITS) {
            throw PortfolioException.validation(
                    field + " supports at most " + MONEY_INTEGER_DIGITS + " integer digits");
        }
        return normalized;
    }

    static String currency(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(normalized).getCurrencyCode();
        } catch (IllegalArgumentException failure) {
            throw PortfolioException.validation(field + " must be a registered ISO 4217 currency code");
        }
    }

    static void requireMoneyPair(BigDecimal amount,
                                 String currency,
                                 String amountField,
                                 String currencyField) {
        if ((amount == null) != (currency == null)) {
            throw PortfolioException.validation(
                    amountField + " and " + currencyField + " must be supplied together");
        }
    }

    static Instant verifiedAt(Instant value, Instant now, boolean required) {
        if (value == null) {
            if (required) throw PortfolioException.validation("verifiedAt is required for product claims");
            return null;
        }
        Instant latestPlausible = now.plus(MAX_VERIFICATION_CLOCK_SKEW);
        if (value.isAfter(latestPlausible)) {
            throw PortfolioException.validation(
                    "verifiedAt must not be more than five minutes in the future");
        }
        return value;
    }

    static Integer nonNegativeDays(Integer value, String field) {
        if (value == null) return null;
        if (value < 0 || value > 36_500) {
            throw PortfolioException.validation(field + " must be between 0 and 36500 days");
        }
        return value;
    }

    static Map<String, String> extensionAttributes(Map<String, String> attributes) {
        if (attributes == null) return null;
        if (attributes.size() > MAX_EXTENSION_ATTRIBUTES) {
            throw PortfolioException.validation(
                    "extensionAttributes contains more than " + MAX_EXTENSION_ATTRIBUTES + " entries");
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            String normalizedKey = ProjectPortfolioService.requireText(
                    key, "extensionAttributes key", MAX_EXTENSION_KEY_CHARACTERS);
            String normalizedValue = ProjectPortfolioService.limited(
                    value, MAX_EXTENSION_VALUE_CHARACTERS,
                    "extensionAttributes[" + normalizedKey + "]");
            if (normalizedValue == null) {
                throw PortfolioException.validation(
                        "extensionAttributes[" + normalizedKey + "] must not be null");
            }
            if (normalized.putIfAbsent(normalizedKey, normalizedValue) != null) {
                throw PortfolioException.validation(
                        "extensionAttributes contains duplicate key after normalization: "
                                + normalizedKey);
            }
        });
        return Map.copyOf(normalized);
    }
}
