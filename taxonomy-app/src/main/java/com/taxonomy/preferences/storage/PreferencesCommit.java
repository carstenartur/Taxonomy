package com.taxonomy.preferences.storage;

import java.time.Instant;

/**
 * Immutable DTO representing a single preferences commit in the Git history.
 *
 * @param commitId  the full SHA-1 hex string of the commit object
 * @param author    the author identity string
 * @param timestamp when the commit was created
 * @param message   the commit message
 */
public record PreferencesCommit(
        String commitId,
        String author,
        Instant timestamp,
        String message
) {}
