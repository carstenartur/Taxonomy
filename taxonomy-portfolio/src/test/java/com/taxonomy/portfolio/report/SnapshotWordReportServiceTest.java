package com.taxonomy.portfolio.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.taxonomy.architecture.decision.DecisionRationaleReport;
import com.taxonomy.diagram.*;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.workbench.*;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class SnapshotWordReportServiceTest {
    static final WorkspaceContext CONTEXT =
            new WorkspaceContext("auditor", "workspace-a", "main", "repository-a");

    static DecisionRationaleReport decision() {
        var m =
                new DecisionRationaleReport.ReportMetadata(
                        Instant.EPOCH,
                        "auditor",
                        "1",
                        "build",
                        "catalogue",
                        "v1",
                        "source",
                        "data-sha",
                        "analysis-sha",
                        "snapshot",
                        2,
                        1,
                        "repository-a",
                        "workspace-a",
                        "main",
                        "commit-a",
                        Instant.EPOCH,
                        false,
                        false,
                        "MOCK",
                        "SUCCESS",
                        "model-a",
                        "snapshot-1",
                        41L,
                        42L,
                        43L,
                        7,
                        Instant.EPOCH,
                        "author",
                        "data-sha",
                        "prompt",
                        true,
                        "UTC",
                        2,
                        2,
                        1,
                        100);
        return new DecisionRationaleReport(
                "Saved title",
                "en",
                "Saved requirement",
                DecisionRationaleReport.ReportStatus.FINAL,
                m,
                new DecisionRationaleReport.ExecutiveSummary(
                        null, List.of(), "Saved conclusion", "Saved method"),
                List.of(),
                List.of(),
                List.of("Saved warning"),
                List.of(),
                List.of(),
                null);
    }

    static Projection projection(String title, String commit) {
        var graph =
                new DiagramModel(
                        title,
                        List.of(
                                new DiagramNode("A", "Saved A", "Capability", 1, true, 0),
                                new DiagramNode("B", "Saved B", "Service", .5, false, 1)),
                        List.of(new DiagramEdge("R1", "A", "B", "serves", .5)),
                        new DiagramLayout("LR", true));
        return new Projection(
                41L,
                "mutable key",
                title,
                42L,
                "mutable requirement key",
                title,
                "Saved requirement",
                "snapshot-1",
                AnalysisStatus.SUCCESS,
                Instant.EPOCH,
                "MOCK",
                "model-a",
                "workspace-a",
                "main",
                commit,
                graph,
                new LayeredDiagramLayoutService().layout(graph),
                Map.of(),
                Map.of(),
                List.of("Saved warning"),
                new SnapshotProvenance(43L, "data-sha", "repository-a"));
    }

    @Test
    void sameFrozenGraphAndCoordinatesSurviveMutableDisplayTitles() {
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        when(decisions.generate(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH))
                .thenReturn(decision());
        when(workbench.load(41L, "snapshot-1", "auditor", CONTEXT))
                .thenReturn(
                        projection("Live title before", "commit-a"),
                        projection("Changed live catalogue title", "commit-a"));
        var service = new SnapshotWordReportService(decisions, workbench);
        var first = service.load(41L, " snapshot-1 ", "auditor", CONTEXT, Locale.ENGLISH);
        var second = service.load(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH);
        assertThat(first.architecture()).isSameAs(first.decision().architecture());
        assertThat(first.architecture()).isEqualTo(second.architecture());
        assertThat(first.architecture().requirement()).isEqualTo("Saved requirement");
        assertThat(first.architecture().evidence().requirementVersionId()).isEqualTo(43L);
        assertThat(first.architecture().evidence().commit()).isEqualTo("commit-a");
        assertThat(first.architecture().elements())
                .extracting(e -> e.id())
                .containsExactly("A", "B");
        assertThat(first.architecture().relations()).extracting(e -> e.id()).containsExactly("R1");
        assertThat(first.architecture().evidence().graphSha256()).matches("[a-f0-9]{64}");
    }

    @Test
    void refusesMixedCommitEvidence() {
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        when(decisions.generate(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH))
                .thenReturn(decision());
        when(workbench.load(41L, "snapshot-1", "auditor", CONTEXT))
                .thenReturn(projection("Live title", "other-commit"));
        assertThatThrownBy(
                        () ->
                                new SnapshotWordReportService(decisions, workbench)
                                        .load(
                                                41L,
                                                "snapshot-1",
                                                "auditor",
                                                CONTEXT,
                                                Locale.ENGLISH))
                .hasMessageContaining("commit");
    }

    @Test
    void explicitSavedViewTitleSurvivesEvenWhenItEqualsTheCurrentFallback() {
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        when(decisions.generate(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH))
                .thenReturn(decision());
        var p = projection("Live title", "commit-a");
        String savedTitle = "mutable key / mutable requirement key — Live title";
        var saved =
                new Projection(
                        p.projectId(),
                        p.projectKey(),
                        p.projectTitle(),
                        p.requirementId(),
                        p.requirementKey(),
                        p.requirementTitle(),
                        p.requirementText(),
                        p.snapshotId(),
                        p.snapshotStatus(),
                        p.snapshotCreatedAt(),
                        p.provider(),
                        p.modelName(),
                        p.workspaceId(),
                        p.branchName(),
                        p.commitSha(),
                        p.diagram(),
                        p.scene(),
                        p.elements(),
                        p.relations(),
                        p.warnings(),
                        p.exportProvenance(),
                        p.policyTitleKey(),
                        savedTitle);
        when(workbench.load(41L, "snapshot-1", "auditor", CONTEXT)).thenReturn(saved);
        var document =
                new SnapshotWordReportService(decisions, workbench)
                        .load(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH)
                        .architecture();
        assertThat(document.diagram().title()).isEqualTo(savedTitle);
        assertThat(document.scene().title()).isEqualTo(savedTitle);
    }
}
