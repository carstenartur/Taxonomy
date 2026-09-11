package com.taxonomy.http;

import java.util.Locale;

/** Parses strong HTTP entity-tag preconditions into an exact Git object ID. */
public final class GitHttpPrecondition {

    private static final int OBJECT_ID_LENGTH = 40;

    private GitHttpPrecondition() {
    }

    /**
     * Returns the expected commit from {@code If-Match}; {@code null} means the
     * caller supplied {@code If-None-Match: *} and expects an absent branch.
     */
    public static String expectedHead(String ifMatch, String ifNoneMatch) {
        String match = normalize(ifMatch);
        String noneMatch = normalize(ifNoneMatch);
        if (match != null && noneMatch != null) {
            throw new InvalidPreconditionException(
                    "If-Match and If-None-Match must not be combined");
        }
        if (match == null && noneMatch == null) {
            throw new PreconditionRequiredException(
                    "Supply If-Match with the exact branch commit or If-None-Match: * for a new branch");
        }
        if (noneMatch != null) {
            if (!"*".equals(noneMatch)) {
                throw new InvalidPreconditionException(
                        "If-None-Match must be exactly * for Git branch creation");
            }
            return null;
        }
        if (match.startsWith("W/")) {
            throw new InvalidPreconditionException(
                    "If-Match must use a strong Git commit ETag");
        }
        if (match.indexOf(',') >= 0 || "*".equals(match)) {
            throw new InvalidPreconditionException(
                    "If-Match must contain exactly one quoted Git commit ID");
        }
        if (match.length() != OBJECT_ID_LENGTH + 2
                || match.charAt(0) != '"'
                || match.charAt(match.length() - 1) != '"') {
            throw new InvalidPreconditionException(
                    "If-Match must be a quoted full Git commit ID");
        }
        return normalizeObjectId(match.substring(1, match.length() - 1));
    }

    public static String etag(String commitId) {
        return '"' + normalizeObjectId(commitId) + '"';
    }

    private static String normalizeObjectId(String value) {
        if (value == null || value.length() != OBJECT_ID_LENGTH) {
            throw new InvalidPreconditionException("Git commit ID must contain exactly 40 hexadecimal characters");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hexadecimal = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F';
            if (!hexadecimal) {
                throw new InvalidPreconditionException("Git commit ID must contain exactly 40 hexadecimal characters");
            }
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static final class PreconditionRequiredException extends IllegalArgumentException {
        public PreconditionRequiredException(String message) {
            super(message);
        }
    }

    public static final class InvalidPreconditionException extends IllegalArgumentException {
        public InvalidPreconditionException(String message) {
            super(message);
        }
    }
}
