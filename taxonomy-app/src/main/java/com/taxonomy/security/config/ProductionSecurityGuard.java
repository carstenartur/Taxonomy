package com.taxonomy.security.config;

import com.taxonomy.setup.SetupChecks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fails production startup before any bootstrap account can be persisted when
 * the active login configuration or optional machine token is unsafe.
 */
@Component
@Profile("production")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionSecurityGuard implements ApplicationRunner {

    private static final int MINIMUM_SECRET_LENGTH = 16;
    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "admin",
            "password",
            "changeme",
            "change-me");

    private final String adminPassword;
    private final String adminToken;
    private final Environment environment;

    /** Preserve direct construction by focused local-password policy tests. */
    public ProductionSecurityGuard(String adminPassword, String adminToken) {
        this(adminPassword, adminToken, new StandardEnvironment());
    }

    @Autowired
    public ProductionSecurityGuard(
            @Value("${taxonomy.admin-password:}") String adminPassword,
            @Value("${admin.token:}") String adminToken,
            Environment environment) {
        this.adminPassword = adminPassword;
        this.adminToken = adminToken;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (environment.acceptsProfiles(Profiles.of("keycloak"))) {
            // Central login has no local bootstrap credential. Validate its own contract instead.
            var profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
            if (profiles.isEmpty()) { profiles.addAll(Arrays.asList(environment.getDefaultProfiles())); }
            SetupChecks.checkAuthentication(environment::getProperty, profiles).stream()
                    .filter(finding -> finding.status() == SetupChecks.Status.ERROR)
                    .findFirst().ifPresent(finding -> {
                        throw new IllegalStateException("Production startup refused: "
                                + finding.key() + ": " + finding.message());
                    });
        } else {
            requireStrongSecret(adminPassword, "TAXONOMY_ADMIN_PASSWORD");
        }

        if (adminToken != null && !adminToken.isBlank()) {
            requireStrongSecret(adminToken, "ADMIN_PASSWORD");
            if (adminPassword != null && !adminPassword.isBlank() && constantTimeEquals(adminPassword, adminToken)) {
                throw new IllegalStateException(
                        "Production startup refused: ADMIN_PASSWORD must be a distinct "
                                + "machine token and must not reuse TAXONOMY_ADMIN_PASSWORD.");
            }
        }
    }

    private static void requireStrongSecret(
            String value,
            String environmentVariable) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
                || FORBIDDEN_PASSWORDS.contains(normalized)
                || normalized.startsWith("replace-with-")
                || normalized.startsWith("change-me-to-")) {
            throw unsafeSecret(environmentVariable);
        }
        if (value.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "Production startup refused: " + environmentVariable
                            + " must contain at least " + MINIMUM_SECRET_LENGTH
                            + " characters.");
        }
    }

    private static IllegalStateException unsafeSecret(String environmentVariable) {
        return new IllegalStateException(
                "Production startup refused: configure a unique, strong "
                        + environmentVariable
                        + " and do not use a documented placeholder.");
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
