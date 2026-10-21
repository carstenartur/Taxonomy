package com.taxonomy.portfolio.report;

import com.taxonomy.reporting.api.decision.DecisionReportScope;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
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

    static DecisionRationaleReport decision() { return decision("en"); }

    static DecisionRationaleReport decision(String language) {
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
                language,
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
        assertThat(first.architecture().title()).isEqualTo("Architecture report");
        assertThat(first.architecture().evidence().snapshotId()).isEqualTo("snapshot-1");
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
    void standaloneFrozenArchitectureWordRetainsTheOriginalNodeRationale() throws Exception {
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        String reason = "Die gespeicherte Begründung bleibt vollständig lesbar. ".repeat(70)
                + "FROZEN-NODE-REASON-END-7319";
        var p = projection("Saved", "commit-a");
        var node = new ElementMetadata("A", "Saved A", "CP", 100, 1, .9, "AI", "CP/A", reason,
                false, null, null, null, null, null, null);
        var source = new Projection(p.projectId(), p.projectKey(), p.projectTitle(), p.requirementId(), p.requirementKey(),
                p.requirementTitle(), p.requirementText(), p.snapshotId(), p.snapshotStatus(), p.snapshotCreatedAt(), p.provider(),
                p.modelName(), p.workspaceId(), p.branchName(), p.commitSha(), p.diagram(), p.scene(),
                Map.of("A", node), p.relations(), p.warnings(), p.exportProvenance());
        when(decisions.generate(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH)).thenReturn(decision());
        when(workbench.load(41L, "snapshot-1", "auditor", CONTEXT)).thenReturn(source);
        var report = new SnapshotWordReportService(decisions, workbench)
                .load(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH).architecture();
        byte[] bytes = new com.taxonomy.reporting.render.document.ArchitectureReportDocxRenderer().render(report);
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document).getText()).contains(reason);
        }
    }

    @Test
    void legacyRelationMappingReasonsRemainVisibleWithinSelectedFrozenGraph() throws Exception {
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        String reason = "The saved mapping explains why this relationship was selected. ".repeat(70)
                + "LEGACY-SELECTION-REASON-END-5831";
        String comment = "The reviewer independently recorded this complete assessment. ".repeat(70)
                + "LEGACY-REVIEW-COMMENT-END-5831";
        String excluded = "OUTSIDE-SELECTED-RELATION-EVIDENCE-5831";
        var p = projection("Saved", "commit-a");
        var nodes = new ArrayList<>(p.diagram().nodes());
        nodes.add(new DiagramNode("C", "Outside C", "Capability", .6, false, 0));
        nodes.add(new DiagramNode("D", "Outside D", "Service", .4, false, 1));
        var graph = new DiagramModel(p.diagram().title(), nodes,
                List.of(p.diagram().edges().getFirst(), new DiagramEdge("R2", "C", "D", "serves", .4)), p.diagram().layout());
        assertThat(graph.edges()).allSatisfy(edge -> assertThat(edge.metadata()).isNull());
        var kept = new RelationMetadata("A", "B", "serves", null, null, .5, .7, reason,
                null, null, null, comment);
        var omitted = new RelationMetadata("C", "D", "serves", null, null, .4, .6, excluded,
                null, null, null, excluded);
        var source = new Projection(p.projectId(), p.projectKey(), p.projectTitle(), p.requirementId(), p.requirementKey(),
                p.requirementTitle(), p.requirementText(), p.snapshotId(), p.snapshotStatus(), p.snapshotCreatedAt(), p.provider(),
                p.modelName(), p.workspaceId(), p.branchName(), p.commitSha(), graph,
                new LayeredDiagramLayoutService().layout(graph), p.elements(),
                Map.of(kept.signature(), kept, omitted.signature(), omitted), p.warnings(), p.exportProvenance());
        var options = new DecisionReportOptions(DecisionReportOptions.Profile.STANDARD, Set.of("A"), null, null, null);
        var base = decision("de");
        var selected = base.withScope(new DecisionReportScope(null, null, List.of(), Set.of("A"), Set.of("A"),
                base.scope().decisionTree(), options, true, true));
        when(decisions.generate(41L, "snapshot-1", "auditor", CONTEXT, Locale.GERMAN, options)).thenReturn(selected);
        when(workbench.loadIfPresent(41L, "snapshot-1", "auditor", CONTEXT)).thenReturn(Optional.of(source));
        var report = new SnapshotWordReportService(decisions, workbench)
                .load(41L, "snapshot-1", "auditor", CONTEXT, Locale.GERMAN, options);
        assertThat(report.architecture().scope()).contains("SHA-256 des Quellgraphen:").doesNotContain("Source graph SHA-256");
        assertThat(report.architecture().diagram().edges()).extracting(DiagramEdge::id).containsExactly("R1");
        assertThat(report.architecture().diagram().edges().getFirst().metadata()).isNull();
        assertThat(report.architecture().relationReasons()).containsOnlyKeys("R1");
        assertThatThrownBy(() -> report.architecture().relationReasons().put("R2", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.architecture().relationReasons().get("R1").put("presenceReason", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(report.architecture().evidence().graphSha256()).isEqualTo(
                com.taxonomy.reporting.api.document.ArchitectureReportDocument.graphSha256(report.architecture().diagram()));
        byte[] bytes = new com.taxonomy.reporting.render.document.ArchitectureReportDocxRenderer().render(report.architecture());
        String word;
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes));
                var extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
            word = extractor.getText();
        }
        String html = new com.taxonomy.reporting.render.decision.DecisionRationaleHtmlRenderer(
                new com.taxonomy.reporting.render.decision.DecisionChapterDiagramRenderer()).render(report.decision());
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(word).contains(reason, comment, "Gespeicherte Auswahlbegründung", "Gespeicherter Prüfkommentar")
                        .doesNotContain(excluded),
                () -> assertThat(html).contains(reason, comment, "Gespeicherte Auswahlbegründung", "Gespeicherter Prüfkommentar")
                        .doesNotContain(excluded));
        String fixtures = System.getProperty("decision.export.fixtures");
        if (fixtures != null) {
            var output = java.nio.file.Path.of(fixtures, "frozen-legacy-relation-reasons.docx");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.write(output, bytes);
        }
    }

    @Test
    void selectedReportNamesCrossingEndpointsAsContextAndCompactDoesNotLoadGraph() {
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        var options = new com.taxonomy.reporting.api.decision.DecisionReportOptions(
                com.taxonomy.reporting.api.decision.DecisionReportOptions.Profile.STANDARD, Set.of("A"), null, null, null);
        var base = decision();
        var selected = base.withScope(new com.taxonomy.reporting.api.decision.DecisionReportScope(null, null,
                List.of(), Set.of("A"), Set.of("A"), base.scope().decisionTree(), options, true, true));
        when(decisions.generate(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, options)).thenReturn(selected);
        when(workbench.loadIfPresent(41L, "snapshot-1", "auditor", CONTEXT)).thenReturn(Optional.of(projection("Saved", "commit-a")));
        var service = new SnapshotWordReportService(decisions, workbench);
        var source = service.load(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, options);
        assertThat(source.architecture().scope()).contains("Boundary context: B", "Source graph SHA-256:");
        assertThat(source.architecture().relations()).hasSize(1);
        var compact = new com.taxonomy.reporting.api.decision.DecisionReportOptions(
                com.taxonomy.reporting.api.decision.DecisionReportOptions.Profile.COMPACT, Set.of("A"), null, null, null);
        when(decisions.generate(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, compact)).thenReturn(selected);
        clearInvocations(workbench);
        assertThat(service.load(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, compact).architecture()).isNull();
        verifyNoInteractions(workbench);
    }

    @Test
    void aValidSelectionWithoutGraphNodesStillExportsItsDecisionEvidence() {
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        var options = new com.taxonomy.reporting.api.decision.DecisionReportOptions(
                com.taxonomy.reporting.api.decision.DecisionReportOptions.Profile.STANDARD, Set.of("CP"), null, null, null);
        var base = decision();
        var selected = base.withScope(new com.taxonomy.reporting.api.decision.DecisionReportScope(null, null,
                List.of(), Set.of("CP"), Set.of("CP"), base.scope().decisionTree(), options, true, true));
        when(decisions.generate(41L,"snapshot-1","auditor",CONTEXT,Locale.ENGLISH,options)).thenReturn(selected);
        when(workbench.loadIfPresent(41L,"snapshot-1","auditor",CONTEXT)).thenReturn(Optional.of(projection("Saved","commit-a")));
        var source = new SnapshotWordReportService(decisions,workbench).load(41L,"snapshot-1","auditor",CONTEXT,Locale.ENGLISH,options);
        assertThat(source.architecture()).isNull();
        assertThat(source.decision().scope().reportRoots()).containsExactly("CP");
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
    void configuredFullExportKeepsDecisionEvidenceWhenNoArchitectureWasSaved() {
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        var options = com.taxonomy.reporting.api.decision.DecisionReportOptions.full();
        when(decisions.generate(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, options)).thenReturn(decision());
        when(workbench.loadIfPresent(41L, "snapshot-1", "auditor", CONTEXT)).thenReturn(Optional.empty());
        var service = new SnapshotWordReportService(decisions, workbench);
        var source = service.loadEvidence(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, options);
        assertThat(source.decision().metadata().analysisSnapshotId()).isEqualTo("snapshot-1");
        assertThat(source.architecture()).isNull();
        assertThat(service.load(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, options).architecture()).isNull();
        verify(workbench, never()).load(any(), any(), any(), any());
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
