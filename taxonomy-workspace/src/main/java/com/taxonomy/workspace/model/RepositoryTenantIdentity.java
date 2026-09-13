package com.taxonomy.workspace.model;

import java.util.Objects;

/**
 * Exact, reversible identity of one repository/workspace/branch tenant.
 *
 * <p>The length-prefixed representation is collision-free even when repository,
 * workspace or branch identifiers contain separators. Lengths are Unicode code
 * point counts so Java encoding matches PostgreSQL {@code char_length} during
 * migration backfills.</p>
 */
public record RepositoryTenantIdentity(
        String repositoryId,
        String workspaceScope,
        String branch
) {
    public static final String PREFIX = "v2|";
    public static final String CENTRAL_SCOPE = "CENTRAL";
    public static final String WORKSPACE_SCOPE_PREFIX = "WORKSPACE:";
    public static final int MAX_SCOPE_KEY_LENGTH = 1024;

    public RepositoryTenantIdentity {
        repositoryId = requireText(repositoryId, "repositoryId", 255);
        workspaceScope = requireText(workspaceScope, "workspaceScope", 320);
        if (!CENTRAL_SCOPE.equals(workspaceScope)
                && (!workspaceScope.startsWith(WORKSPACE_SCOPE_PREFIX)
                || workspaceScope.length() == WORKSPACE_SCOPE_PREFIX.length())) {
            throw new IllegalArgumentException(
                    "workspaceScope must be CENTRAL or WORKSPACE:<workspaceId>");
        }
        branch = requireText(branch, "branch", 255);
    }

    public String scopeKey() {
        StringBuilder key = new StringBuilder(PREFIX);
        append(key, 'r', repositoryId);
        key.append('|');
        append(key, 's', workspaceScope);
        key.append('|');
        append(key, 'b', branch);
        if (key.codePointCount(0, key.length()) > MAX_SCOPE_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Encoded repository tenant scope exceeds "
                            + MAX_SCOPE_KEY_LENGTH + " characters");
        }
        return key.toString();
    }

    public static boolean isEncoded(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static RepositoryTenantIdentity parse(String scopeKey) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        if (!scopeKey.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "Repository tenant scope key is not a v2 identity");
        }
        Component repository = component(scopeKey, PREFIX.length(), 'r');
        requireSeparator(scopeKey, repository.nextIndex());
        Component scope = component(scopeKey, repository.nextIndex() + 1, 's');
        requireSeparator(scopeKey, scope.nextIndex());
        Component branch = component(scopeKey, scope.nextIndex() + 1, 'b');
        if (branch.nextIndex() != scopeKey.length()) {
            throw new IllegalArgumentException(
                    "Repository tenant scope key contains trailing data");
        }
        return new RepositoryTenantIdentity(
                repository.value(), scope.value(), branch.value());
    }

    private static void append(StringBuilder target, char label, String value) {
        target.append(label)
                .append(value.codePointCount(0, value.length()))
                .append(':')
                .append(value);
    }

    private static Component component(String value, int start, char expectedLabel) {
        if (start >= value.length() || value.charAt(start) != expectedLabel) {
            throw new IllegalArgumentException(
                    "Repository tenant scope key is missing component " + expectedLabel);
        }
        int colon = value.indexOf(':', start + 1);
        if (colon < 0) {
            throw new IllegalArgumentException(
                    "Repository tenant scope key component " + expectedLabel
                            + " has no length delimiter");
        }
        int codePoints;
        try {
            codePoints = Integer.parseInt(value.substring(start + 1, colon));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Repository tenant scope key component " + expectedLabel
                            + " has an invalid length",
                    error);
        }
        if (codePoints < 1) {
            throw new IllegalArgumentException(
                    "Repository tenant scope key component " + expectedLabel + " is empty");
        }
        int valueStart = colon + 1;
        final int valueEnd;
        try {
            valueEnd = value.offsetByCodePoints(valueStart, codePoints);
        } catch (IndexOutOfBoundsException error) {
            throw new IllegalArgumentException(
                    "Repository tenant scope key component " + expectedLabel + " is truncated",
                    error);
        }
        return new Component(value.substring(valueStart, valueEnd), valueEnd);
    }

    private static void requireSeparator(String value, int index) {
        if (index >= value.length() || value.charAt(index) != '|') {
            throw new IllegalArgumentException(
                    "Repository tenant scope key has an invalid component boundary");
        }
    }

    private static String requireText(String value, String label, int maximumCodePoints) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(
                    label + " exceeds " + maximumCodePoints + " characters");
        }
        return normalized;
    }

    private record Component(String value, int nextIndex) {
    }
}
