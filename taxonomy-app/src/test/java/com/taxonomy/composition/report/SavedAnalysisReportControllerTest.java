package com.taxonomy.composition.report;

import com.taxonomy.analysis.cluster.ClusterAnalysisObservation;
import com.taxonomy.analysis.cluster.ClusterAnalysisView;
import com.taxonomy.analysis.recovery.AnalysisContinuationService;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.architecture.service.*;
import com.taxonomy.dto.*;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP report boundary reads owned saved evidence; it never accepts client report authority. */
class SavedAnalysisReportControllerTest {
    private static final String OPERATION = "10000000-0000-4000-8000-000000000001";
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkspaceContext workspace = new WorkspaceContext("alice", "ws-a", "branch-a", "repo-a");
    private final ClusterAnalysisObservation cluster = mock(ClusterAnalysisObservation.class);
    private final AnalysisContinuationService continuations = mock(AnalysisContinuationService.class);
    private final WorkspaceResolver workspaces = mock(WorkspaceResolver.class);
    private final RequirementArchitectureViewService architecture = mock(RequirementArchitectureViewService.class);
    private final ArchitectureGapService gaps = mock(ArchitectureGapService.class);
    private final ArchitecturePatternService patterns = mock(ArchitecturePatternService.class);
    private final ArchitectureRecommendationService recommendations = mock(ArchitectureRecommendationService.class);
    private final RelationProposalService proposals = mock(RelationProposalService.class);
    private final ArchitectureReportMetadataPort metadata = mock(ArchitectureReportMetadataPort.class);

    @Test
    void durableReferenceExportsRetainedReportsAndIgnoresForgedClientEvidence() throws Exception {
        var saved = savedResult();
        when(cluster.request(OPERATION, "alice", workspace)).thenReturn(new ClusterAnalysisObservation.RecoveryInput(
                OPERATION, "Frozen requirement", "MOCK", null,
                new ClusterAnalysisView.ObservationScope("ws-a", "repo-a", "branch-a", "commit-a")));
        when(cluster.result(OPERATION, "alice", workspace)).thenReturn(saved);
        try (var context = context()) {
            String body = mvc(context).perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("analysisReference", reference(),
                            "scores", Map.of("FORGED", 99), "businessText", "Forged text",
                            "reasons", Map.of("FORGED", "forged rationale")))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var report = mapper.readTree(body);
            assertThat(report.path("businessText").asString()).isEqualTo("Frozen requirement");
            assertThat(report.path("scores").has("FORGED")).isFalse();
            assertThat(report.path("scores").path("BP").asDouble()).isEqualTo(70d);
            assertThat(report.path("gapAnalysis").path("notes").get(0).asString()).isEqualTo("Frozen gap evidence");
            assertThat(report.path("recommendation").path("confidence").asDouble()).isEqualTo(71d);
            assertThat(report.path("copilot").path("state").asString()).isEqualTo("PARTIAL");
            assertThat(report.path("pendingProposals").isEmpty()).isTrue();
        }
        verifyNoInteractions(architecture, gaps, patterns, recommendations, proposals, metadata, continuations);
    }

    @Test
    void workspacePinCannotReadAnotherWorkspaceOrFallBackToClientScores() throws Exception {
        try (var context = context()) {
            mvc(context).perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("analysisReference",
                            Map.of("operationId", OPERATION, "workspaceId", "foreign"), "scores", Map.of("BP", 99)))))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(cluster, continuations, architecture, gaps, patterns, recommendations, proposals, metadata);
    }

    @Test
    void unavailableOwnedReferenceDoesNotRecomputeFromClientPayload() throws Exception {
        when(cluster.request(OPERATION, "alice", workspace)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        try (var context = context()) {
            mvc(context).perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("analysisReference", reference(), "scores", Map.of("BP", 99)))))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(architecture, gaps, patterns, recommendations, proposals, metadata);
    }

    @Test
    void continuationReferenceRejectsConflictingOperationAlias() throws Exception {
        var request = new AnalysisRequest("Frozen requirement");
        when(continuations.read("saved-continuation", "alice", workspace)).thenReturn(
                new AnalysisContinuationStore.Snapshot(null, savedResult(), request, "different-operation"));
        try (var context = context()) {
            mvc(context).perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("analysisReference",
                            Map.of("operationId", OPERATION, "continuationId", "saved-continuation", "workspaceId", "ws-a")))))
                    .andExpect(status().isConflict());
        }
        verifyNoInteractions(architecture, gaps, patterns, recommendations, proposals, metadata, cluster);
    }

    @Test
    void absentHistoricalCopilotIsUnavailableRatherThanACompletedEmptyOrRecomputedReport() throws Exception {
        var result = savedResult(); result.setCopilot(null);
        when(continuations.read("historical", "alice", workspace)).thenReturn(
                new AnalysisContinuationStore.Snapshot(null, result, new AnalysisRequest("Original requirement"), OPERATION));
        try (var context = context()) {
            mvc(context).perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("analysisReference",
                            Map.of("continuationId", "historical", "workspaceId", "ws-a")))))
                    .andExpect(status().isConflict());
        }
        verifyNoInteractions(architecture, gaps, patterns, recommendations, proposals, metadata, cluster);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"markdown", "html", "docx"})
    void continuationReportsUseSavedLanguageReasonsAndProvenanceInEveryRenderer(String format) throws Exception {
        var result = savedResult();
        result.setReasons(Map.of("BP", "Original rationale"));
        result.setViewContext(new ViewContext("frozen-commit", "frozen-branch", null, false, false, false));
        var request = new AnalysisRequest("Frozen requirement"); request.setAnalysisLanguage(AnalysisLanguage.GERMAN);
        when(continuations.read("continuation", "alice", workspace)).thenReturn(
                new AnalysisContinuationStore.Snapshot(null, result, request, OPERATION));
        try (var context = context()) {
            var renderer = mock(com.taxonomy.extension.api.report.ReportRendererExtension.class);
            when(context.getBean(ReportRendererRegistry.class).getRequired(format)).thenReturn(renderer);
            when(renderer.descriptor()).thenReturn(new com.taxonomy.extension.api.report.ReportFormatDescriptor(
                    format, format, format, "application/octet-stream", false));
            when(renderer.render(any())).thenReturn(new com.taxonomy.extension.api.report.ReportRenderResult(new byte[]{1}));
            mvc(context).perform(post("/api/report/" + format).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("analysisReference", Map.of("continuationId", "continuation",
                            "operationId", OPERATION, "workspaceId", "ws-a"), "analysisLanguage", "en",
                            "reasons", Map.of("BP", "Forged rationale")))))
                    .andExpect(status().isOk());
            var rendered = org.mockito.ArgumentCaptor.forClass(com.taxonomy.extension.api.report.ReportRenderContext.class);
            verify(renderer).render(rendered.capture());
            assertThat(rendered.getValue().options()).containsEntry("language", "de")
                    .containsEntry("nodeReasons", Map.of("BP", "Original rationale"));
            var report = rendered.getValue().report();
            assertThat(report.getViewContext().basedOnCommit()).isEqualTo("frozen-commit");
            assertThat(report.getCopilot()).isSameAs(result.getCopilot());
            verifyNoInteractions(context.getBean(RepositoryStateService.class));
        }
        verifyNoInteractions(architecture, gaps, patterns, recommendations, proposals, metadata, cluster);
    }

    private Map<String, String> reference() { return Map.of("operationId", OPERATION, "workspaceId", "ws-a"); }

    private AnalysisResult savedResult() {
        var result = new AnalysisResult(Map.of("BP", 70d), List.of());
        result.setAnalysisOperationId(OPERATION);
        result.setArchitectureView(new RequirementArchitectureView());
        var retainedGaps = new GapAnalysisView(); retainedGaps.getNotes().add("Frozen gap evidence");
        var recommendation = new ArchitectureRecommendation(); recommendation.setConfidence(71d);
        result.setCopilot(new AnalysisCopilotResult(1, "PARTIAL", retainedGaps,
                new PatternDetectionView(), recommendation, List.of("Some retained evidence is incomplete")));
        return result;
    }

    private AnnotationConfigApplicationContext context() {
        when(workspaces.resolveCurrentUsername()).thenReturn("alice");
        when(workspaces.resolveCurrentContext()).thenReturn(workspace);
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ArchitectureReportService.class, () -> new ArchitectureReportService(
                architecture, gaps, patterns, recommendations, new DiagramProjectionService(),
                new MermaidExportService(), proposals, metadata));
        context.registerBean(ReportRendererRegistry.class, () -> mock(ReportRendererRegistry.class));
        context.registerBean(RepositoryStateService.class, () -> mock(RepositoryStateService.class));
        context.registerBean(WorkspaceResolver.class, () -> workspaces);
        context.registerBean(ClusterAnalysisObservation.class, () -> cluster);
        context.registerBean(AnalysisContinuationService.class, () -> continuations);
        context.registerBean(ReportApiController.class);
        context.refresh();
        return context;
    }

    private MockMvc mvc(AnnotationConfigApplicationContext context) {
        return MockMvcBuilders.standaloneSetup(context.getBean(ReportApiController.class)).build();
    }
}
