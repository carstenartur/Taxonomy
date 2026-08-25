package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ProductionSecurityGuardTransportTest {

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
    void rejectsControlCharactersInsidePassword() {
        assertRejected(
                "Strong-Local\nPassword-2026!",
                "",
                "must not contain control characters");
    }

    @Test
    void rejectsWhitespaceOnlyOptionalMachineTokenInsteadOfSilentlyDisablingIt() {
        assertRejected(
                "Strong-Local-Password-2026!",
                "   ",
                "ADMIN_PASSWORD");
    }

    @Test
    void rejectsInternalWhitespaceInHttpMachineToken() {
        assertRejected(
                "Strong local password 2026!",
                "Monitoring token 2026 distinct",
                "HTTP machine token");
    }

    @Test
    void measuresMinimumLengthInUnicodeCodePointsNotUtf16Units() {
        assertRejected(
                "😀😀😀😀😀😀😀😀",
                "",
                "at least 16 characters");
    }

    @Test
    void permitsInternalSpacesInPasswordAndTransportSafeDistinctToken() {
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                "Strong local password 2026!",
                "Distinct-monitoring-token-2026!");

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
