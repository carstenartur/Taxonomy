package com.taxonomy.dsl.storage;

import java.time.Instant;

/**
 * Immutable DTO representing a Git branch for DSL documents.
 *
 * @param name         the branch name (e.g. "draft", "review", "accepted")
 * @param headCommitId the commit SHA at the tip of the branch, or {@code null} if empty
 * @param created      approximate creation time (first commit timestamp)
 */
public record DslBranch(
        String name,
        String headCommitId,
        Instant created
) {}
