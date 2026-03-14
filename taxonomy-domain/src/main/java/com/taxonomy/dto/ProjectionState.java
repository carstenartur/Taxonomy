package com.taxonomy.dto;

import java.time.Instant;

/**
 * Tracks which Git commit the DB projection and search index are built from.
 *
 * <p>Used for diagnostics and staleness detection. When the HEAD commit
 * of a branch moves ahead of the projection or index commit, consumers
 * know they need to re-materialize or re-index.
 *
 * @param projectionCommit     commit SHA the DB projection is built from
 * @param projectionBranch     branch of the last materialization
 * @param projectionTimestamp  when the last projection was built
 * @param indexCommit          commit SHA the search index is built from
 * @param indexTimestamp       when the last index build completed
 * @param projectionStale     true if HEAD has moved past the projection commit
 * @param indexStale           true if the search index is behind HEAD
 */
public record ProjectionState(
    String projectionCommit,
    String projectionBranch,
    Instant projectionTimestamp,
    String indexCommit,
    Instant indexTimestamp,
    boolean projectionStale,
    boolean indexStale
) {}
