package com.taxonomy.security;

import com.taxonomy.security.config.ProductionSecurityGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityGuardTest {

    private static final DefaultApplicationArguments NO_ARGS =
            new DefaultApplicationArguments(new String[0]);

    @Test
    void rejectsMissingDefaultPlaceholderAndShortPasswords() {
        assertThatThrownBy(() -> new ProductionSecurityGuard("").run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production startup refused");
        assertThatThrownBy(() -> new ProductionSecurityGuard("admin").run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductionSecurityGuard(
                "replace-with-a-long-random-password").run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductionSecurityGuard("short-secret").run(NO_ARGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16 characters");
    }

    @Test
    void acceptsUniqueLongPassword() {
        assertThatCode(() -> new ProductionSecurityGuard(
                "correct-horse-battery-staple-2026").run(NO_ARGS))
                .doesNotThrowAnyException();
    }
}
