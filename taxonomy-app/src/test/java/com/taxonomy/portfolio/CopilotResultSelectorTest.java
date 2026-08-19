package com.taxonomy.portfolio;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.CopilotResultSelector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotResultSelectorTest {

    private final CopilotResultSelector selector = new CopilotResultSelector();

    @Test
    void prefersSuccessfulBroadResultOverPartialOrSparsePasses() {
        SnapshotDetail partial = snapshot(
                "partial", AnalysisStatus.PARTIAL, Map.of("CP", 90, "BP", 0),
                0, 0, 0, 10L);
        SnapshotDetail sparse = snapshot(
                "sparse", AnalysisStatus.SUCCESS, Map.of("CP", 95),
                0, 0, 0, 10L);
        SnapshotDetail broad = snapshot(
                "broad", AnalysisStatus.SUCCESS, Map.of("CP", 80, "BP", 60, "CR", 40),
                1, 0, 0, 10L);

        assertThat(selector.select(List.of(partial, sparse, broad)))
                .get()
                .extracting(detail -> detail.summary().id())
                .isEqualTo("broad");
    }

    @Test
    void returnsEmptyWhenNoPassProducedUsableScores() {
        SnapshotDetail failed = snapshot(
                "failed", AnalysisStatus.FAILED, Map.of(),
                0, 0, 0, 10L);

        assertThat(selector.select(List.of(failed))).isEmpty();
        assertThat(selector.select(null)).isEmpty();
    }

    @Test
    void usesEveryDeterministicTieBreakerInOrder() {
        assertPreferred(
                snapshot("sum-low", AnalysisStatus.SUCCESS, Map.of("A", 3, "B", 1),
                        0, 1, 1, 10L),
                snapshot("sum-high", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        0, 1, 1, 10L),
                "sum-high");
        assertPreferred(
                snapshot("elements-low", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        0, 1, 1, 10L),
                snapshot("elements-high", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        0, 2, 1, 10L),
                "elements-high");
        assertPreferred(
                snapshot("relations-low", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        0, 2, 1, 10L),
                snapshot("relations-high", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        0, 2, 2, 10L),
                "relations-high");
        assertPreferred(
                snapshot("warnings-high", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        2, 2, 2, 10L),
                snapshot("warnings-low", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        1, 2, 2, 10L),
                "warnings-low");
        assertPreferred(
                snapshot("duration-high", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        1, 2, 2, 20L),
                snapshot("duration-low", AnalysisStatus.SUCCESS, Map.of("A", 4, "B", 1),
                        1, 2, 2, 10L),
                "duration-low");
    }

    private void assertPreferred(
            SnapshotDetail first,
            SnapshotDetail second,
            String expectedId) {
        assertThat(selector.select(List.of(first, second)))
                .get()
                .extracting(detail -> detail.summary().id())
                .isEqualTo(expectedId);
    }

    private static SnapshotDetail snapshot(
            String id,
            AnalysisStatus status,
            Map<String, Integer> scores,
            int warnings,
            int elements,
            int relations,
            long durationMs) {
        AnalysisResult analysis = new AnalysisResult();
        analysis.setScores(scores);
        analysis.setStatus(status.name());
        analysis.setWarnings(Collections.nCopies(warnings, "warning"));
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
                durationMs,
                warnings,
                null);
        return new SnapshotDetail(
                summary,
                analysis,
                null,
                null,
                null,
                Collections.nCopies(elements, null),
                Collections.nCopies(relations, null));
    }
}
