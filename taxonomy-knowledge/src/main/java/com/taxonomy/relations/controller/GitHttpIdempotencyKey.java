package com.taxonomy.relations.controller;

/** Validates external keys before a Git mutation can reach projection persistence. */
final class GitHttpIdempotencyKey {

    // Leaves room for bulk index/ID suffixes in the 255-character causation columns.
    private static final int MAX_LENGTH = 128;

    private GitHttpIdempotencyKey() {
    }

    static String require(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH
                || normalized.chars().anyMatch(character -> character < '!' || character > '~')) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain between 1 and " + MAX_LENGTH
                            + " visible ASCII characters without whitespace");
        }
        return normalized;
    }
}
