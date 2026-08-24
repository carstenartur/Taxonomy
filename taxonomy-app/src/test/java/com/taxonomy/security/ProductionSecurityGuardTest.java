package com.taxonomy.security;

import com.taxonomy.security.config.ProductionSecurityGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityGuardTest {

    private static final DefaultApplicationArguments NO_ARGS =
            new DefaultApplicationArguments(new String[0]);
    private static final String STRONG_LOGIN_PASSWORD =
            "correct-horse-battery-staple-2026";
    private static final String STRONG_MACHINE_TOKEN =
            "metrics-machine-token-2026-unique";

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "admin",
            "password",
            "changeme",
            "change-me",
            "replace-with-a-long-random-password",
            "replace-with-a-unique-random-password",
            "replace-with-a-long-random-login-password",
            "REPLACE-WITH-ANOTHER-DOCUMENTED-PLACEHOLDER"
    })
    void rejectsMissingKnownAndDocumentedLoginPlaceholders(String password) {
        assertThatThrownBy(() -> guard(password, "").run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production startup refused");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "admin",
            "password",
            "replace-with-a-different-long-random-machine-token",
            "REPLACE-WITH-ANOTHER-MACHINE-TOKEN"
    })
    void rejectsKnownAndDocumentedMachineTokenPlaceholders(String token) {
        assertThatThrownBy(() -> guard(STRONG_LOGIN_PASSWORD, token).run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    void rejectsShortLoginPassword() {
        assertThatThrownBy(() -> guard("short-secret", "").run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TAXONOMY_ADMIN_PASSWORD")
                .hasMessageContaining("16 characters");
    }

    @Test
    void rejectsShortMachineToken() {
        assertThatThrownBy(() -> guard(
                STRONG_LOGIN_PASSWORD, "short-token").run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD")
                .hasMessageContaining("16 characters");
    }

    @Test
    void rejectsMachineTokenThatReusesTheLoginPassword() {
        assertThatThrownBy(() -> guard(
                STRONG_LOGIN_PASSWORD, STRONG_LOGIN_PASSWORD).run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distinct machine token")
                .hasMessageContaining("TAXONOMY_ADMIN_PASSWORD");
    }

    @Test
    void acceptsStrongLoginPasswordWithoutOptionalMachineToken() {
        assertThatCode(() -> guard(STRONG_LOGIN_PASSWORD, "").run(NO_ARGS))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsDistinctStrongLoginAndMachineCredentials() {
        assertThatCode(() -> guard(
                STRONG_LOGIN_PASSWORD, STRONG_MACHINE_TOKEN).run(NO_ARGS))
                .doesNotThrowAnyException();
    }

    private static ProductionSecurityGuard guard(String loginPassword, String token) {
        return new ProductionSecurityGuard(loginPassword, token);
    }
}
