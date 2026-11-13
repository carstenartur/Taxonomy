package com.taxonomy.dto;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for the {@code GET /api/search/graph} endpoint.
 *
 * <p>Contains:
 * <ul>
 *   <li>Matched taxonomy nodes ranked by semantic similarity.</li>
 *   <li>Graph statistics: per-root relation counts and most connected relation types.</li>
 *   <li>A human-readable summary text.</li>
 * </ul>
 */
public class GraphSearchResult {

    public enum VectorSource { BRANCH_GENERATION, GLOBAL_INDEX, NO_INFERENCE }

    /** The relation authority and vector index actually used for this response.
     * Only a published branch index has a generation and a source key. */
    public record Source(String repositoryId, String workspaceId, String branch, String sourceCommit,
                         String relationReadModel, VectorSource vectorSource, String modelKey,
                         String generation, String sourceKey) { }

    private Source source;
    private List<TaxonomyNodeDto> matchedNodes;

    /** Number of relation matches aggregated by taxonomy root code (e.g. "BP" → 12). */
    private Map<String, Long> relationCountByRoot;

    /** Most frequently matched relation types and their counts (e.g. "SUPPORTS" → 5). */
    private Map<String, Long> topRelationTypes;

    /** Human-readable summary (e.g. "Business Processes have the most support relationships (12)"). */
    private String summary;

    public GraphSearchResult() {}

    public GraphSearchResult(List<TaxonomyNodeDto> matchedNodes,
                             Map<String, Long> relationCountByRoot,
                             Map<String, Long> topRelationTypes,
                             String summary) {
        this(matchedNodes, relationCountByRoot, topRelationTypes, summary, null);
    }

    public GraphSearchResult(List<TaxonomyNodeDto> matchedNodes, Map<String, Long> relationCountByRoot,
                             Map<String, Long> topRelationTypes, String summary, Source source) {
        this.source = source;
        this.matchedNodes = matchedNodes;
        this.relationCountByRoot = relationCountByRoot;
        this.topRelationTypes = topRelationTypes;
        this.summary = summary;
    }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public List<TaxonomyNodeDto> getMatchedNodes() { return matchedNodes; }
    public void setMatchedNodes(List<TaxonomyNodeDto> matchedNodes) { this.matchedNodes = matchedNodes; }

    public Map<String, Long> getRelationCountByRoot() { return relationCountByRoot; }
    public void setRelationCountByRoot(Map<String, Long> relationCountByRoot) {
        this.relationCountByRoot = relationCountByRoot;
    }

    public Map<String, Long> getTopRelationTypes() { return topRelationTypes; }
    public void setTopRelationTypes(Map<String, Long> topRelationTypes) {
        this.topRelationTypes = topRelationTypes;
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
