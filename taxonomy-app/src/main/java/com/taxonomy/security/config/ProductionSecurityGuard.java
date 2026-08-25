package com.taxonomy.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/**
 * Fails production startup before any bootstrap account can be persisted when
 * the initial administrator credential or optional machine token is unsafe.
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

    public ProductionSecurityGuard(
            @Value("${taxonomy.admin-password:}") String adminPassword,
            @Value("${admin.token:}") String adminToken) {
        this.adminPassword = adminPassword;
        this.adminToken = adminToken;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String canonicalAdminPassword = requireStrongSecret(
                adminPassword,
                "TAXONOMY_ADMIN_PASSWORD",
                false);

        if (adminToken != null && !adminToken.isEmpty()) {
            String canonicalAdminToken = requireStrongSecret(
                    adminToken,
                    "ADMIN_PASSWORD",
                    true);
            if (constantTimeEquals(canonicalAdminPassword, canonicalAdminToken)) {
                throw new IllegalStateException(
                        "Production startup refused: ADMIN_PASSWORD must be a distinct "
                                + "machine token and must not reuse TAXONOMY_ADMIN_PASSWORD.");
            }
        }
    }

    private static String requireStrongSecret(
            String value,
            String environmentVariable,
            boolean transportToken) {
        if (value == null || value.isEmpty()) {
            throw unsafeSecret(environmentVariable);
        }
        if (hasUnsafeEdgeCharacter(value)) {
            throw new IllegalStateException(
                    "Production startup refused: " + environmentVariable
                            + " must not start or end with whitespace, control, "
                            + "or formatting characters.");
        }
        if (value.codePoints().anyMatch(
                codePoint -> isInvisibleOrLineBreaking(codePoint)
                        || isNonAsciiSeparator(codePoint))) {
            throw new IllegalStateException(
                    "Production startup refused: " + environmentVariable
                            + " must not contain control, formatting, line-breaking, "
                            + "or non-ASCII separator characters.");
        }
        if (transportToken && value.codePoints().anyMatch(
                codePoint -> codePoint == ' ' || Character.isWhitespace(codePoint))) {
            throw new IllegalStateException(
                    "Production startup refused: ADMIN_PASSWORD must not contain "
                            + "whitespace because it is used as an HTTP machine token.");
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_PASSWORDS.contains(normalized)
                || normalized.startsWith("replace-with-")
                || normalized.startsWith("change-me-to-")) {
            throw unsafeSecret(environmentVariable);
        }
        if (value.codePointCount(0, value.length()) < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "Production startup refused: " + environmentVariable
                            + " must contain at least " + MINIMUM_SECRET_LENGTH
                            + " characters.");
        }
        return value;
    }

    private static boolean hasUnsafeEdgeCharacter(String value) {
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        return first == ' '
                || last == ' '
                || Character.isWhitespace(first)
                || Character.isWhitespace(last)
                || Character.isSpaceChar(first)
                || Character.isSpaceChar(last)
                || isInvisibleOrLineBreaking(first)
                || isInvisibleOrLineBreaking(last);
    }

    private static boolean isInvisibleOrLineBreaking(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static boolean isNonAsciiSeparator(int codePoint) {
        return codePoint != ' ' && Character.getType(codePoint) == Character.SPACE_SEPARATOR;
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
