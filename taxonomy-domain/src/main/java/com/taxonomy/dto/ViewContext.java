package com.taxonomy.dto;

import java.time.Instant;

/**
 * Metadata attached to API responses that return architecture data.
 *
 * <p>Tells the consumer exactly which Git version the data comes from
 * and whether the projection or search index is stale.
 *
 * @param basedOnCommit              commit SHA the response data is based on
 * @param basedOnBranch              branch the response data is based on
 * @param commitTimestamp            timestamp of the commit
 * @param includesProvisionalRelations whether the data includes provisional (unreviewed) relations
 * @param projectionStale            true if the DB projection is behind HEAD
 * @param indexStale                 true if the search index is behind HEAD
 */
public record ViewContext(
    String basedOnCommit,
    String basedOnBranch,
    Instant commitTimestamp,
    boolean includesProvisionalRelations,
    boolean projectionStale,
    boolean indexStale
) {}
