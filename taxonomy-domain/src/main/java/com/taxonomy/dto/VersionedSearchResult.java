package com.taxonomy.dto;

import java.time.Instant;
import java.util.List;

/**
 * A search result enriched with version-context metadata.
 *
 * <p>Extends a regular commit search hit with information about how the
 * matched commit relates to the caller's current working context
 * (branch, lineage, recency).
 *
 * @param commitId                the commit SHA of the match
 * @param branch                  the branch the commit belongs to
 * @param timestamp               when the commit was created
 * @param matchedElementId        the element ID matched (if applicable)
 * @param matchedText             excerpt of matched text
 * @param relevanceScore          Lucene relevance score
 * @param onCurrentLineage        whether the commit is an ancestor of the current branch HEAD
 * @param latestOnCurrentBranch   whether this is the newest match on the caller's branch
 * @param latestOverall           whether this is the newest match across all branches
 * @param contextOpenActions      suggested actions the UI can offer (e.g. OPEN_READ_ONLY, SWITCH)
 */
public record VersionedSearchResult(
    String commitId,
    String branch,
    Instant timestamp,
    String matchedElementId,
    String matchedText,
    float relevanceScore,
    boolean onCurrentLineage,
    boolean latestOnCurrentBranch,
    boolean latestOverall,
    List<String> contextOpenActions
) {}
