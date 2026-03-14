package com.taxonomy.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full snapshot of the Git repository state, including projection and index freshness.
 *
 * <p>This DTO captures the current state of the DSL Git repository,
 * the database projection, and the search index. Consumers can use it
 * to determine whether the data they see is up-to-date or stale.
 *
 * @param currentBranch        the active branch (e.g. "draft")
 * @param headCommit           40-char SHA of the HEAD commit on that branch
 * @param headTimestamp        timestamp of the HEAD commit
 * @param headAuthor           author of the HEAD commit
 * @param headMessage          commit message of the HEAD commit
 * @param branches             all branch names in the repository
 * @param operationInProgress  whether a multi-step operation is in progress
 * @param operationKind        the kind of operation (null, "merge", "cherry-pick", "revert")
 * @param projectionCommit     commit SHA the DB projection is built from
 * @param projectionBranch     branch of the last materialization
 * @param projectionTimestamp  when the last projection was built
 * @param projectionStale      true if HEAD has moved past the projection commit
 * @param indexCommit          commit SHA the search index is built from
 * @param indexStale           true if the search index is behind HEAD
 * @param totalCommits         number of commits on the current branch
 * @param databaseBacked       true if using HibernateRepository (persistent)
 */
public record RepositoryState(
    String currentBranch,
    String headCommit,
    Instant headTimestamp,
    String headAuthor,
    String headMessage,
    List<String> branches,
    boolean operationInProgress,
    String operationKind,
    String projectionCommit,
    String projectionBranch,
    Instant projectionTimestamp,
    boolean projectionStale,
    String indexCommit,
    boolean indexStale,
    int totalCommits,
    boolean databaseBacked
) {}
