package com.taxonomy.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Fails production startup when the initial administrator credential is absent
 * or matches a known placeholder/default value.
 */
@Component
@Profile("production")
public class ProductionSecurityGuard implements ApplicationRunner {

    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "admin",
            "password",
            "changeme",
            "change-me",
            "change-me-to-a-strong-password",
            "replace-with-a-long-random-password");

    private final String adminPassword;

    public ProductionSecurityGuard(
            @Value("${taxonomy.admin-password:}") String adminPassword) {
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        String normalized = adminPassword == null
                ? "" : adminPassword.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || FORBIDDEN_PASSWORDS.contains(normalized)) {
            throw new IllegalStateException(
                    "Production startup refused: configure a unique, strong "
                            + "TAXONOMY_ADMIN_PASSWORD and do not use a documented placeholder.");
        }
        if (adminPassword.length() < 16) {
            throw new IllegalStateException(
                    "Production startup refused: TAXONOMY_ADMIN_PASSWORD must contain at least 16 characters.");
        }
    }
}
