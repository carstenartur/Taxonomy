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

    public record AnalysisDraftView(
            String workspaceId,
            String branch,
            JsonNode payload,
            long version,
            Instant updatedAt) {
    }
}
