package com.taxonomy.portfolio.reformulation;

import java.time.Instant;

/** List projection: no frozen baseline, prompt, source text or revision payload is loaded. */
public record ProposalSummary(String id, Long sourceVersionId, String snapshotId,
        String creator, Instant createdAt, long currentRevision) {
}
