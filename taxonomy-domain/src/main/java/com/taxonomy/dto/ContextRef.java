package com.taxonomy.dto;

import java.time.Instant;

/**
 * Reference to a specific architecture context.
 *
 * <p>A context identifies a unique point in the version history (branch + commit)
 * together with metadata about how the user arrived there and whether edits
 * are allowed.
 *
 * @param contextId          unique identifier for this context (UUID)
 * @param branch             the Git branch name
 * @param commitId           the commit SHA (null for HEAD of a branch)
 * @param timestamp          when this context was opened
 * @param mode               whether this context allows edits
 * @param originContextId    the context from which we navigated here (null if root)
 * @param originBranch       the branch of the origin context
 * @param originCommitId     the commit of the origin context
 * @param openedFromSearch   the search query that led here (null if not from search)
 * @param matchedElementId   the element ID matched by search (null if not from search)
 * @param dirty              whether local uncommitted changes exist
 */
public record ContextRef(
    String contextId,
    String branch,
    String commitId,
    Instant timestamp,
    ContextMode mode,
    String originContextId,
    String originBranch,
    String originCommitId,
    String openedFromSearch,
    String matchedElementId,
    boolean dirty
) {}
