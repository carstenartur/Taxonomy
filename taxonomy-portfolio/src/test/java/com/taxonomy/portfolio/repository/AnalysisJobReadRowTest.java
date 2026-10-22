package com.taxonomy.portfolio.repository;

import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisJobReadRowTest {
    @Test
    void legacyConstructorPreservesEveryFieldWithoutInventingAnAnalysisLanguage() {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        Instant started = created.plusSeconds(1);
        Instant completed = created.plusSeconds(5);
        Instant itemStarted = created.plusSeconds(2);
        Instant itemCompleted = created.plusSeconds(4);

        var legacy = new AnalysisJobReadRow(
                "job-id", 11L, AnalysisStatus.PARTIAL, "idempotency-key",
                "provider", 17, "requester", "workspace",
                created, started, completed, 10, 5, 3, 2, "job-error",
                "plugin-id", "plugin-version", "plugin-sha", "config-revision", AnalysisReasonLength.DETAILED,
                21L, 22L, "requirement-key", 23L, 4, AnalysisStatus.FAILED,
                "snapshot-id", 6, itemStarted, itemCompleted, "item-error");
        var expected = new AnalysisJobReadRow(
                "job-id", 11L, AnalysisStatus.PARTIAL, "idempotency-key",
                "provider", 17, "requester", "workspace",
                created, started, completed, 10, 5, 3, 2, "job-error",
                "plugin-id", "plugin-version", "plugin-sha", "config-revision", AnalysisReasonLength.DETAILED,
                null, 21L, 22L, "requirement-key", 23L, 4, AnalysisStatus.FAILED,
                "snapshot-id", 6, itemStarted, itemCompleted, "item-error");

        // Record equality checks all components, including distinct adjacent IDs, counts and timestamps.
        assertEquals(expected, legacy);
        assertNull(legacy.analysisLanguage(), "An old projection has no recorded analysis language");
    }

    @Test
    void legacyConstructorPreservesNullMetadataAndTheAbsentItemOfALeftJoin() {
        Instant created = Instant.parse("2026-01-02T10:00:00Z");
        var legacy = new AnalysisJobReadRow(
                "empty-job", 31L, AnalysisStatus.PENDING, null, null, 24, "requester", "workspace",
                created, null, null, 0, 0, 0, 0, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        var expected = new AnalysisJobReadRow(
                "empty-job", 31L, AnalysisStatus.PENDING, null, null, 24, "requester", "workspace",
                created, null, null, 0, 0, 0, 0, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(expected, legacy);
        assertNull(legacy.analysisLanguage(), "Missing historical language must remain unknown");
        assertNull(legacy.itemId(), "A job without an item must not acquire one");
    }
}
