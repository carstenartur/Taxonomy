package com.taxonomy.relations.controller;

import org.eclipse.jgit.lib.ObjectId;

/** Parses strong HTTP entity-tag preconditions into an exact Git branch head. */
final class GitHttpPrecondition {

    private GitHttpPrecondition() {
    }

    /**
     * Returns the expected commit from {@code If-Match}; {@code null} means the
     * caller supplied {@code If-None-Match: *} and expects an absent branch.
     */
    static String expectedHead(String ifMatch, String ifNoneMatch) {
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
        if (match.length() != 42
                || match.charAt(0) != '"'
                || match.charAt(match.length() - 1) != '"') {
            throw new InvalidPreconditionException(
                    "If-Match must be a quoted full Git commit ID");
        }
        String commit = match.substring(1, match.length() - 1);
        try {
            return ObjectId.fromString(commit).name();
        } catch (IllegalArgumentException error) {
            throw new InvalidPreconditionException(
                    "If-Match must be a quoted full Git commit ID", error);
        }
    }

    static String etag(String commitId) {
        return '"' + ObjectId.fromString(commitId).name() + '"';
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static final class PreconditionRequiredException extends IllegalArgumentException {
        PreconditionRequiredException(String message) {
            super(message);
        }
    }

    static final class InvalidPreconditionException extends IllegalArgumentException {
        InvalidPreconditionException(String message) {
            super(message);
        }

        InvalidPreconditionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
