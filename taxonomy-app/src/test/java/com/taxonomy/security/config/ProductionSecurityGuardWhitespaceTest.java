package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityGuardWhitespaceTest {

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
    void rejectsControlCharactersInsideCredential() {
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
    void measuresMinimumLengthInUnicodeCodePointsNotUtf16Units() {
        assertRejected(
                "😀😀😀😀😀😀😀😀",
                "",
                "at least 16 characters");
    }

    @Test
    void permitsInternalSpacesInOtherwiseStrongCredential() {
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                "Strong local password 2026!",
                "Distinct monitoring token 2026!");

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    private static void assertRejected(
            String adminPassword,
            String adminToken,
            String expectedMessage) {
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                adminPassword, adminToken);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage)
                .hasMessageNotContaining(adminPassword)
                .hasMessageNotContaining(adminToken);
    }
}
