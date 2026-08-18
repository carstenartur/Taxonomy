package com.taxonomy.portfolio;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.CopilotResultSelector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotResultSelectorTest {

    private final CopilotResultSelector selector = new CopilotResultSelector();

    @Test
    void prefersSuccessfulBroadResultOverPartialOrSparsePasses() {
        SnapshotDetail partial = snapshot(
                "partial", AnalysisStatus.PARTIAL, Map.of("CP", 90, "BP", 0), 0);
        SnapshotDetail sparse = snapshot(
                "sparse", AnalysisStatus.SUCCESS, Map.of("CP", 95), 0);
        SnapshotDetail broad = snapshot(
                "broad", AnalysisStatus.SUCCESS, Map.of("CP", 80, "BP", 60, "CR", 40), 1);

        assertThat(selector.select(List.of(partial, sparse, broad)))
                .get()
                .extracting(detail -> detail.summary().id())
                .isEqualTo("broad");
    }

    @Test
    void returnsEmptyWhenNoPassProducedUsableScores() {
        SnapshotDetail failed = snapshot(
                "failed", AnalysisStatus.FAILED, Map.of(), 0);

        assertThat(selector.select(List.of(failed))).isEmpty();
    }

    private static SnapshotDetail snapshot(
            String id,
            AnalysisStatus status,
            Map<String, Integer> scores,
            int warnings) {
        AnalysisResult analysis = new AnalysisResult();
        analysis.setScores(scores);
        analysis.setStatus(status.name());
        analysis.setWarnings(java.util.Collections.nCopies(warnings, "warning"));
        SnapshotSummary summary = new SnapshotSummary(
                id,
                41L,
                7L,
                "REQ-001",
                9L,
                3,
                "job-1",
                status,
                "CUSTOM_OPENAI",
                "model",
                "taxonomy",
                "prompt",
                "workspace",
                "draft",
                "commit",
                Instant.now(),
                10L,
                warnings,
                null);
        return new SnapshotDetail(
                summary,
                analysis,
                null,
                null,
                null,
                List.of(),
                List.of());
    }
}
