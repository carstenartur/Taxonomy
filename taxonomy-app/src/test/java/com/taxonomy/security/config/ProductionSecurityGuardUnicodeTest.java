package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ProductionSecurityGuardUnicodeTest {

    @Test
    void rejectsShortPasswordPaddedToMinimumLengthWithSpaces() {
        assertRejected(
                "ShortSecret123   ",
                "",
                "must not start or end");
    }

    @Test
    void rejectsUnicodeSpaceAndFormattingCharactersAtCredentialEdges() {
        assertRejected(
                "\u00a0Strong-Local-Password-2026!",
                "",
                "must not start or end");
        assertRejected(
                "Strong-Local-Password-2026!\u200b",
                "",
                "must not start or end");
    }

    @Test
    void rejectsInvisibleAndLineBreakingCharactersInsidePassword() {
        assertRejected(
                "Strong-Local\nPassword-2026!",
                "",
                "must not contain control");
        assertRejected(
                "Strong-Local\u200bPassword-2026!",
                "",
                "must not contain control");
        assertRejected(
                "Strong-Local\u2028Password-2026!",
                "",
                "must not contain control");
        assertRejected(
                "Strong-Local\u00a0Password-2026!",
                "",
                "non-ASCII separator");
    }

    @Test
    void rejectsWhitespaceOnlyOptionalMachineTokenInsteadOfSilentlyDisablingIt() {
        assertRejected(
                "Strong-Local-Password-2026!",
                "   ",
                "ADMIN_PASSWORD");
    }

    @Test
    void rejectsAsciiWhitespaceAnywhereInHttpMachineToken() {
        assertRejected(
                "Strong local password 2026!",
                "Monitoring token 2026 distinct",
                "HTTP machine token");
        assertRejected(
                "Strong local password 2026!",
                "Monitoring\ttoken-2026-distinct",
                "must not contain control");
    }

    @Test
    void rejectsUnicodeAndNonBearerPunctuationInHttpMachineToken() {
        assertRejected(
                "Strong local password 2026!",
                "Monitoring-token-ä-2026",
                "ASCII letters");
        assertRejected(
                "Strong local password 2026!",
                "Monitoring-token-2026!",
                "ASCII letters");
    }

    @Test
    void measuresMinimumLengthInUnicodeCodePointsNotUtf16Units() {
        assertRejected(
                "😀😀😀😀😀😀😀😀",
                "",
                "at least 16 characters");
    }

    @Test
    void permitsAsciiInternalSpacesInPasswordAndTransportSafeDistinctToken() {
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                "Strong local password 2026!",
                "Distinct-monitoring-token-2026");

        Throwable failure = catchThrowable(() -> guard.run(null));

        assertThat(failure).isNull();
    }

    private static void assertRejected(
            String adminPassword,
            String adminToken,
            String expectedMessage) {
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                adminPassword, adminToken);

        Throwable failure = catchThrowable(() -> guard.run(null));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
        assertThat(failure.getMessage()).doesNotContain(adminPassword);
        if (!adminToken.isBlank()) {
            assertThat(failure.getMessage()).doesNotContain(adminToken);
        }
    }
}
