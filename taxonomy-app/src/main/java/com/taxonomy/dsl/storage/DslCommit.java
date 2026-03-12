package com.taxonomy.dsl.storage;

import java.time.Instant;

/**
 * Immutable DTO representing a single DSL commit in the Git history.
 *
 * @param commitId  the full SHA-1 hex string of the commit object
 * @param author    the author identity (name &lt;email&gt;)
 * @param timestamp when the commit was created
 * @param message   the commit message
 */
public record DslCommit(
        String commitId,
        String author,
        Instant timestamp,
        String message
) {}
