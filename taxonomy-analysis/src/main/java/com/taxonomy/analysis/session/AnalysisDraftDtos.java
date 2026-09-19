package com.taxonomy.analysis.session;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** REST contracts for the resumable ad-hoc analysis working draft. */
public final class AnalysisDraftDtos {

    private AnalysisDraftDtos() {
    }

    public record SaveAnalysisDraftRequest(
            JsonNode payload,
            Long expectedVersion) {
    }

    /**
     * Explicitly starts a new ad-hoc analysis lifecycle.
     *
     * <p>The reset is an intentional destructive user command and therefore
     * does not use the caller's optimistic-lock version. Keeping an empty,
     * versioned tombstone prevents another stale tab from silently restoring
     * the discarded requirement text.</p>
     */
    public record ResetAnalysisDraftRequest(
            JsonNode analysisOptions) {
    }

    public record AnalysisDraftView(
            String workspaceId,
            String branch,
            JsonNode payload,
            long version,
            Instant updatedAt) {
    }
}
