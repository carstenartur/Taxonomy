package com.taxonomy.portfolio.dto;

import com.taxonomy.dto.AnalysisLanguage;
import java.util.List;

/** Read-only suggestions; none of these values authorizes or performs a mutation. */
public final class RequirementIntakeDtos {
    private RequirementIntakeDtos() { }
    public enum Status { COMPLETE, PARTIAL, UNAVAILABLE, FAILED }
    public enum Classification { DUPLICATE, CHANGE, ADDITION, CONFLICT, NONE }
    public record Request(Long projectId, String title, String text, AnalysisLanguage analysisLanguage) { }
    public record Scope(String repositoryId, String workspaceId, String workspaceScopeKey, String branch) { }
    public record Coverage(int scanned, int admitted, boolean truncated) { }
    public record Candidate(String id, String kind, String repositoryId, String workspaceId, String branch,
                            Long projectId, String projectKey, Long requirementId, Long requirementVersionId,
                            String requirementKey, String title, String text, String contentHash,
                            Classification classification, String rationale, List<String> quotes,
                            List<String> actions, String sourceCommit, String sourceVersion) {
        public Candidate { quotes = List.copyOf(quotes); actions = List.copyOf(actions); }
        public Candidate(String id, String kind, String repositoryId, String workspaceId, String branch,
                         Long projectId, String projectKey, Long requirementId, Long requirementVersionId,
                         String requirementKey, String title, String text, String contentHash,
                         Classification classification, String rationale, List<String> quotes, List<String> actions) {
            this(id, kind, repositoryId, workspaceId, branch, projectId, projectKey, requirementId,
                    requirementVersionId, requirementKey, title, text, contentHash, classification, rationale,
                    quotes, actions, null, null);
        }
        public Candidate assessed(Classification classification, String rationale, List<String> quotes) {
            return new Candidate(id, kind, repositoryId, workspaceId, branch, projectId, projectKey,
                    requirementId, requirementVersionId, requirementKey, title, text, contentHash,
                    classification, rationale, quotes, actions, sourceCommit, sourceVersion);
        }
    }
    public record Response(Status status, String code, String inputHash, Scope scope, String provider,
                           Coverage coverage, List<Candidate> candidates, boolean manualReviewRequired) {
        public Response { candidates = List.copyOf(candidates); }
    }
}
