package com.taxonomy.portfolio.report;

import com.taxonomy.architecture.decision.DecisionRationaleReport;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReportMetadata;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReportStatus;
import com.taxonomy.architecture.decision.DecisionRationaleReportPlugin;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.DecisionAnalysisInput;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DecisionRationaleSnapshotReportTest {

    private static final WorkspaceContext CONTEXT = new WorkspaceContext(
            "auditor", "workspace-a", "draft", "repository-a");
    private static final Instant SNAPSHOT_TIME = Instant.parse("2026-08-20T10:15:30Z");
    private static final Instant COMMIT_TIME = Instant.parse("2026-08-20T09:00:00Z");

    @AfterEach
    void resetLocaleContext() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void snapshotServiceReplaysFrozenEvidenceAndMatchingHistoricalView() {
        RequirementAnalysisSnapshotRepository repository =
                mock(RequirementAnalysisSnapshotRepository.class);
        PortfolioJsonCodec jsonCodec = mock(PortfolioJsonCodec.class);
        DecisionRationaleReportService reportService =
                mock(DecisionRationaleReportService.class);
        DecisionRationaleSnapshotReportService service =
                new DecisionRationaleSnapshotReportService(repository, jsonCodec, reportService);

        RequirementAnalysisSnapshot snapshot = snapshot(
                "MOCK", "model-a", "taxonomy-sha", "prompt-sha",
                "main", "commit-a", AnalysisStatus.SUCCESS);
        AnalysisResult analysis = reportableAnalysis();
        analysis.setProvider("ANALYSIS_PROVIDER");
        analysis.setViewContext(new ViewContext(
                "commit-a", "main", COMMIT_TIME, true, true, false));
        DecisionRationaleReport expected = report(7);

        when(repository.findByIdAndProjectIdAndScopeKey(
                eq("snapshot-1"), eq(41L), anyString()))
                .thenReturn(Optional.of(snapshot));
        when(jsonCodec.read("analysis-json", AnalysisResult.class)).thenReturn(analysis);
        when(reportService.generate(any(), eq(CONTEXT), any(), eq(Locale.GERMAN)))
                .thenReturn(expected);

        DecisionRationaleReport actual = service.generate(
                41L, "  snapshot-1  ", "auditor", CONTEXT, Locale.GERMAN);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<DecisionAnalysisInput> inputCaptor =
                ArgumentCaptor.forClass(DecisionAnalysisInput.class);
        ArgumentCaptor<ViewContext> viewCaptor = ArgumentCaptor.forClass(ViewContext.class);
        verify(reportService).generate(
                inputCaptor.capture(), eq(CONTEXT), viewCaptor.capture(), eq(Locale.GERMAN));

        DecisionAnalysisInput input = inputCaptor.getValue();
        assertThat(input.businessText()).isEqualTo("immutable requirement");
        assertThat(input.provider()).isEqualTo("MOCK");
        assertThat(input.analysisStatus()).isEqualTo("SUCCESS");
        assertThat(input.scores()).containsEntry("CP", 100);
        assertThat(input.productCoverageGaps())
                .extracting(ProductCoverageGap::productFamilyCode)
                .containsExactly("CP");
        assertThat(input.taxonomyTree()).hasSize(1);
        assertThat(input.snapshotProvenance().snapshotId()).isEqualTo("snapshot-1");
        assertThat(input.snapshotProvenance().requirementVersionNumber()).isEqualTo(7);
        assertThat(input.snapshotProvenance().createdBy()).isEqualTo("analysis-author");
        assertThat(input.snapshotProvenance().modelName()).isEqualTo("model-a");
        assertThat(input.snapshotProvenance().taxonomyFingerprintSha256())
                .isEqualTo("taxonomy-sha");
        assertThat(input.snapshotProvenance().promptFingerprintSha256())
                .isEqualTo("prompt-sha");

        ViewContext historical = viewCaptor.getValue();
        assertThat(historical.basedOnCommit()).isEqualTo("commit-a");
        assertThat(historical.basedOnBranch()).isEqualTo("main");
        assertThat(historical.commitTimestamp()).isEqualTo(COMMIT_TIME);
        assertThat(historical.includesProvisionalRelations()).isTrue();
        assertThat(historical.projectionStale()).isTrue();
        assertThat(historical.indexStale()).isFalse();
    }

    @Test
    void snapshotServiceUsesSafeFallbacksWhenHistoricalContextDoesNotMatch() {
        RequirementAnalysisSnapshotRepository repository =
                mock(RequirementAnalysisSnapshotRepository.class);
        PortfolioJsonCodec jsonCodec = mock(PortfolioJsonCodec.class);
        DecisionRationaleReportService reportService =
                mock(DecisionRationaleReportService.class);
        DecisionRationaleSnapshotReportService service =
                new DecisionRationaleSnapshotReportService(repository, jsonCodec, reportService);

        RequirementAnalysisSnapshot snapshot = snapshot(
                " ", null, null, null, null, null, AnalysisStatus.PARTIAL);
        AnalysisResult analysis = reportableAnalysis();
        analysis.setProvider("ANALYSIS_PROVIDER");
        analysis.setViewContext(new ViewContext(
                "different-commit", "different-branch", COMMIT_TIME,
                false, true, true));
        analysis.setProvisionalRelations(List.of(mock(RelationHypothesisDto.class)));
        when(repository.findByIdAndProjectIdAndScopeKey(
                eq("snapshot-2"), eq(41L), anyString()))
                .thenReturn(Optional.of(snapshot));
        when(jsonCodec.read("analysis-json", AnalysisResult.class)).thenReturn(analysis);
        when(reportService.generate(any(), eq(CONTEXT), any(), eq(Locale.ENGLISH)))
                .thenReturn(report(null));

        service.generate(41L, "snapshot-2", "auditor", CONTEXT, Locale.ENGLISH);

        ArgumentCaptor<DecisionAnalysisInput> inputCaptor =
                ArgumentCaptor.forClass(DecisionAnalysisInput.class);
        ArgumentCaptor<ViewContext> viewCaptor = ArgumentCaptor.forClass(ViewContext.class);
        verify(reportService).generate(
                inputCaptor.capture(), eq(CONTEXT), viewCaptor.capture(), eq(Locale.ENGLISH));
        assertThat(inputCaptor.getValue().provider()).isEqualTo("ANALYSIS_PROVIDER");
        assertThat(inputCaptor.getValue().analysisStatus()).isEqualTo("PARTIAL");
        assertThat(inputCaptor.getValue().snapshotProvenance().modelName()).isNull();

        ViewContext historical = viewCaptor.getValue();
        assertThat(historical.basedOnCommit()).isEqualTo("unknown");
        assertThat(historical.basedOnBranch()).isEqualTo("unknown");
        assertThat(historical.commitTimestamp()).isNull();
        assertThat(historical.includesProvisionalRelations()).isTrue();
        assertThat(historical.projectionStale()).isFalse();
        assertThat(historical.indexStale()).isFalse();
    }

    @Test
    void snapshotServiceRejectsInvalidMissingAndNonReproducibleSnapshots() {
        RequirementAnalysisSnapshotRepository repository =
                mock(RequirementAnalysisSnapshotRepository.class);
        PortfolioJsonCodec jsonCodec = mock(PortfolioJsonCodec.class);
        DecisionRationaleReportService reportService =
                mock(DecisionRationaleReportService.class);
        DecisionRationaleSnapshotReportService service =
                new DecisionRationaleSnapshotReportService(repository, jsonCodec, reportService);

        assertThatThrownBy(() -> service.generate(
                null, "snapshot", "auditor", CONTEXT, Locale.ENGLISH))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> service.generate(
                41L, "  ", "auditor", CONTEXT, Locale.ENGLISH))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("snapshotId");
        verifyNoInteractions(repository, jsonCodec, reportService);

        when(repository.findByIdAndProjectIdAndScopeKey(
                eq("missing"), eq(41L), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generate(
                41L, "missing", "auditor", CONTEXT, Locale.ENGLISH))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("not found");

        RequirementAnalysisSnapshot snapshot = snapshot(
                "MOCK", "model", "taxonomy", "prompt",
                "main", "commit", AnalysisStatus.SUCCESS);
        when(repository.findByIdAndProjectIdAndScopeKey(
                eq("invalid"), eq(41L), anyString()))
                .thenReturn(Optional.of(snapshot));
        when(jsonCodec.read("analysis-json", AnalysisResult.class)).thenReturn(null);
        assertThatThrownBy(() -> service.generate(
                41L, "invalid", "auditor", CONTEXT, Locale.ENGLISH))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("score evidence");

        AnalysisResult noScores = new AnalysisResult();
        noScores.setScores(Map.of());
        when(jsonCodec.read("analysis-json", AnalysisResult.class)).thenReturn(noScores);
        assertThatThrownBy(() -> service.generate(
                41L, "invalid", "auditor", CONTEXT, Locale.ENGLISH))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("score evidence");

        AnalysisResult noTree = new AnalysisResult();
        noTree.setScores(Map.of("CP", 100));
        noTree.setTree(List.of());
        when(jsonCodec.read("analysis-json", AnalysisResult.class)).thenReturn(noTree);
        assertThatThrownBy(() -> service.generate(
                41L, "invalid", "auditor", CONTEXT, Locale.ENGLISH))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("frozen taxonomy-hierarchy");
        verify(reportService, never()).generate(any(), any(), any(), any());
    }

    @Test
    void snapshotControllerListsFormatsAndRendersTrustedDownloadMetadata() {
        DecisionRationaleSnapshotReportService reportService =
                mock(DecisionRationaleSnapshotReportService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        DecisionRationaleReport report = report(7);
        ReportRendererExtension renderer = renderer("json", "application/json", "payload");
        ReportRendererRegistry registry = new ReportRendererRegistry(List.of(renderer));
        DecisionRationaleSnapshotReportController controller =
                new DecisionRationaleSnapshotReportController(
                        reportService, registry, workspaceResolver);

        when(workspaceResolver.resolveCurrentContext()).thenReturn(CONTEXT);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("auditor");
        when(reportService.generate(
                41L, "snapshot-1", "auditor", CONTEXT, Locale.GERMAN))
                .thenReturn(report);

        assertThat(controller.listFormats())
                .extracting(ReportFormatDescriptor::id)
                .containsExactly("json");

        var response = controller.export(41L, "snapshot-1", "json", "de");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"taxonomy-decision-rationale-report-v7.json\"");
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Snapshot-Id"))
                .isEqualTo("snapshot-1");
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Data-SHA256"))
                .isEqualTo("data-sha");
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Analysis-SHA256"))
                .isEqualTo("analysis-sha");
        assertThat(response.getBody())
                .isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
        verify(reportService).generate(
                41L, "snapshot-1", "auditor", CONTEXT, Locale.GERMAN);
    }

    @Test
    void snapshotControllerRejectsUnknownFormatsAndFallsBackToRequestLocale() {
        DecisionRationaleSnapshotReportService reportService =
                mock(DecisionRationaleSnapshotReportService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        ReportRendererExtension renderer = renderer("json", "application/json", "payload");
        ReportRendererRegistry registry = new ReportRendererRegistry(List.of(renderer));
        DecisionRationaleSnapshotReportController controller =
                new DecisionRationaleSnapshotReportController(
                        reportService, registry, workspaceResolver);

        assertThatThrownBy(() -> controller.export(
                41L, "snapshot-1", "pdf", "de"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Unknown decision-report format");
        verifyNoInteractions(reportService, workspaceResolver);

        LocaleContextHolder.setLocale(Locale.GERMAN);
        when(workspaceResolver.resolveCurrentContext()).thenReturn(CONTEXT);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("auditor");
        when(reportService.generate(
                41L, "snapshot-1", "auditor", CONTEXT, Locale.GERMAN))
                .thenReturn(report(null));

        var response = controller.export(41L, "snapshot-1", "json", "%%% ");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"taxonomy-decision-rationale-report-vunknown.json\"");
        verify(reportService).generate(
                41L, "snapshot-1", "auditor", CONTEXT, Locale.GERMAN);

        controller.export(41L, "snapshot-1", "json", null);
        verify(reportService, org.mockito.Mockito.times(2)).generate(
                41L, "snapshot-1", "auditor", CONTEXT, Locale.GERMAN);
    }

    private RequirementAnalysisSnapshot snapshot(
            String provider,
            String model,
            String taxonomyFingerprint,
            String promptFingerprint,
            String branch,
            String commit,
            AnalysisStatus status) {
        RequirementAnalysisSnapshot snapshot = mock(RequirementAnalysisSnapshot.class);
        ProjectRequirementVersion version = mock(ProjectRequirementVersion.class);
        when(snapshot.getId()).thenReturn("snapshot-1");
        when(snapshot.getProjectId()).thenReturn(41L);
        when(snapshot.getRequirementId()).thenReturn(42L);
        when(snapshot.getRequirementVersionId()).thenReturn(43L);
        when(snapshot.getRequirementVersion()).thenReturn(version);
        when(version.getVersionNumber()).thenReturn(7);
        when(version.getText()).thenReturn("immutable requirement");
        when(snapshot.getCreatedAt()).thenReturn(SNAPSHOT_TIME);
        when(snapshot.getCreatedBy()).thenReturn("analysis-author");
        when(snapshot.getModelName()).thenReturn(model);
        when(snapshot.getTaxonomyFingerprint()).thenReturn(taxonomyFingerprint);
        when(snapshot.getPromptFingerprint()).thenReturn(promptFingerprint);
        when(snapshot.getProvider()).thenReturn(provider);
        when(snapshot.getStatus()).thenReturn(status);
        when(snapshot.getBranchName()).thenReturn(branch);
        when(snapshot.getCommitSha()).thenReturn(commit);
        when(snapshot.getAnalysisPayload()).thenReturn("analysis-json");
        return snapshot;
    }

    private AnalysisResult reportableAnalysis() {
        TaxonomyNodeDto root = new TaxonomyNodeDto();
        root.setCode("CP");
        root.setNameEn("Capability");
        root.setNameDe("Fähigkeit");
        root.setLevel(0);
        root.setChildren(List.of());

        AnalysisResult analysis = new AnalysisResult();
        analysis.setScores(Map.of("CP", 100));
        analysis.setReasons(Map.of("CP", "The requirement directly needs this capability."));
        analysis.setProductCoverageGaps(List.of(new ProductCoverageGap(
                "CP", "Capability", 100, List.of("CP-P1"),
                "No suitable product reached the threshold.")));
        analysis.setTree(List.of(root));
        analysis.setStatus("SUCCESS");
        return analysis;
    }

    private DecisionRationaleReport report(Integer requirementVersion) {
        ReportMetadata metadata = new ReportMetadata(
                Instant.parse("2026-08-21T10:00:00Z"),
                "auditor",
                "1.4.0-SNAPSHOT",
                "build-sha",
                "snapshot tree",
                "snapshot data",
                "catalogue-sha",
                "data-sha",
                "analysis-sha",
                "immutable snapshot",
                1,
                1,
                "repository-a",
                "workspace-a",
                "main",
                "commit-a",
                COMMIT_TIME,
                false,
                false,
                "MOCK",
                "SUCCESS",
                "model-a",
                "snapshot-1",
                41L,
                42L,
                43L,
                requirementVersion,
                SNAPSHOT_TIME,
                "analysis-author",
                "data-sha",
                "prompt-sha",
                true,
                "Europe/Berlin",
                1,
                1,
                1,
                100.0);
        return new DecisionRationaleReport(
                "Decision report",
                "en",
                "immutable requirement",
                ReportStatus.FINAL,
                metadata,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private ReportRendererExtension renderer(
            String id, String contentType, String content) {
        ReportFormatDescriptor descriptor = new ReportFormatDescriptor(
                id, "JSON", id, contentType, false);
        return new ReportRendererExtension() {
            @Override
            public String reportTypeId() {
                return DecisionRationaleReportPlugin.REPORT_TYPE_ID;
            }

            @Override
            public Class<?> reportModelType() {
                return DecisionRationaleReport.class;
            }

            @Override
            public ReportFormatDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ReportRenderResult render(ReportRenderContext context) {
                assertThat(context.payloadAs(DecisionRationaleReport.class)).isNotNull();
                return new ReportRenderResult(content.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
