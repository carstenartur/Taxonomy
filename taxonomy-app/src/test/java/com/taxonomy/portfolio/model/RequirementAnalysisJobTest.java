package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementAnalysisJobTest {

    @Test
    void incompleteCompletionAddsStartedAtWhenACompetingWorkerOwnsTheItems() {
        Instant createdAt = Instant.parse("2026-08-03T08:00:00Z");
        Instant completionAttempt = Instant.parse("2026-08-03T08:01:00Z");
        RequirementAnalysisJob job = job(createdAt);

        job.complete(0, 0, 0, null, completionAttempt);

        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(job.getStartedAt()).isEqualTo(completionAttempt);
        assertThat(job.getCompletedAt()).isNull();
    }

    @Test
    void incompleteCompletionPreservesTheOriginalWorkerStartTime() {
        Instant createdAt = Instant.parse("2026-08-03T08:00:00Z");
        Instant workerStartedAt = Instant.parse("2026-08-03T08:00:30Z");
        RequirementAnalysisJob job = job(createdAt);
        job.markRunning(workerStartedAt);

        job.complete(0, 0, 0, null, Instant.parse("2026-08-03T08:01:00Z"));

        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(job.getStartedAt()).isEqualTo(workerStartedAt);
        assertThat(job.getCompletedAt()).isNull();
    }

    private static RequirementAnalysisJob job(Instant createdAt) {
        return new RequirementAnalysisJob(
                "job-1",
                null,
                "client-key",
                "MOCK",
                25,
                "architect",
                "ws-architect",
                1,
                createdAt);
    }
}
