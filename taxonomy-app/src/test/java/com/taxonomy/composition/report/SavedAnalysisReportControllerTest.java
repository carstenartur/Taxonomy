package com.taxonomy.composition.report;

import com.taxonomy.analysis.cluster.ClusterAnalysisObservation;
import com.taxonomy.analysis.cluster.ClusterAnalysisView;
import com.taxonomy.analysis.recovery.AnalysisContinuationService;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.architecture.service.*;
import com.taxonomy.dto.*;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.reporting.render.document.ArchitectureReportDocxRenderer;
import com.taxonomy.reporting.render.document.ArchitectureReportTextRenderer;
import com.taxonomy.reporting.render.document.DocxReportRendererExtension;
import com.taxonomy.reporting.render.document.MarkdownReportRendererExtension;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceActorAccess;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void reportRejectsAContinuationRevisionThatAdvancedWithIdenticalScores() throws Exception {
        var request = new AnalysisRequest("Frozen requirement");
        when(continuations.read("versioned", "alice", workspace)).thenReturn(
                revision(7, "Which retention interval?", request),
                revision(8, "Which recovery target?", request));
        var payload = Map.of("analysisReference", Map.of("continuationId", "versioned",
                "workspaceId", "ws-a", "recoveryVersion", 7));
        try (var context = context()) {
            var mvc = mvc(context);
            var body = mvc.perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(payload))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(body).path("nodeAssessments").path("BP").get(0).path("question").asString())
                    .isEqualTo("Which retention interval?");
            mvc.perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(payload))).andExpect(status().isConflict());
        }
        verifyNoInteractions(architecture, gaps, patterns, recommendations, proposals, metadata, cluster);
    }

    @Test
    void reportAcceptsScorelessAssessmentsFromLiveAndOwnedSources() throws Exception {
        var assessment = unresolved("Which retention interval?");
        var live = Map.of("businessText", "Frozen requirement", "scores", Map.of(),
                "nodeAssessments", Map.of("BP", List.of(assessment)));
        var result = new AnalysisResult(Map.of(), List.of());
        result.setNodeAssessments(Map.of("BP", List.of(assessment)));
        var request = new AnalysisRequest("Frozen requirement");
        var recovery = new AnalysisRecoveryView("scoreless", 5, "PARTIAL", 0, 1, "BP", List.of());
        when(continuations.read("scoreless", "alice", workspace)).thenReturn(
                new AnalysisContinuationStore.Snapshot(recovery, result, request));
        var owned = Map.of("analysisReference", Map.of("continuationId", "scoreless",
                "workspaceId", "ws-a", "recoveryVersion", 5));
        // The ad-hoc JSON path also attaches current repository provenance.
        var source = new ViewContext("commit", "branch-a", null, false, false, false);
        try (var context = context()) {
            var repositories = context.getBean(RepositoryStateService.class);
            when(repositories.resolveWorkspaceBranch("alice")).thenReturn("branch-a");
            when(repositories.getViewContext("alice", "branch-a", workspace)).thenReturn(source);
            var mvc = mvc(context);
            for (var payload : List.of(live, owned)) {
                var body = mvc.perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload))).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                var report = mapper.readTree(body);
                assertThat(report.path("scores").isEmpty()).isTrue();
                assertThat(report.path("nodeAssessments").path("BP").get(0).path("question").asString())
                        .isEqualTo("Which retention interval?");
            }
            mvc.perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"businessText\":\"Frozen requirement\",\"scores\":{}}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(architecture, gaps, patterns, recommendations, proposals, metadata, cluster);
    }

    @Test
    void liveEvidenceRequiresTheOriginalRequirementEvenWithAnOpenQuestion() throws Exception {
        var request = mapper.createObjectNode();
        request.set("nodeAssessments", mapper.valueToTree(Map.of("BP", List.of(unresolved("Which retention interval?")))));
        try (var context = context()) {
            var http = mvc(context);
            http.perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(request))).andExpect(status().isBadRequest());
            request.put("businessText", "   ");
            http.perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(request))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(cluster, continuations, architecture, gaps, recommendations, proposals, metadata);
    }

    @Test
    void liveEvidencePreservesTheExactNodeCodeUpTo256Characters() throws Exception {
        String longestCode = "A".repeat(256);
        var claims = List.of(unresolved("Which retention interval?"));
        var request = mapper.createObjectNode();
        request.put("businessText", "Frozen requirement");
        request.putObject("scores");
        try (var context = context()) {
            var http = mvc(context);
            for (String invalidCode : List.of(longestCode + "A", " BP", "BP\u2003")) {
                request.set("nodeAssessments", mapper.valueToTree(Map.of(invalidCode, claims)));
                http.perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request))).andExpect(status().isBadRequest());
            }
            request.set("nodeAssessments", mapper.valueToTree(Map.of(longestCode, claims)));
            var response = http.perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(request))).andExpect(status().isOk()).andReturn().getResponse();
            var recorded = mapper.readTree(response.getContentAsByteArray()).path("nodeAssessments");
            assertThat(recorded.has(longestCode)).isTrue();
            assertThat(recorded.path(longestCode).get(0).path("requirementQuote").asString()).isEqualTo("Frozen requirement");
        }
    }

    @Test
    void markdownRequiresScoresOrRetainedCopilotContent() throws Exception {
        var retainedAssessmentOnly = scorelessReference("scoreless-markdown");
        var copilot = savedResult();
        copilot.setScores(Map.of());
        when(continuations.read("legacy-copilot", "alice", workspace)).thenReturn(
                new AnalysisContinuationStore.Snapshot(null, copilot, new AnalysisRequest("Frozen requirement")));
        var retainedCopilot = Map.of("analysisReference",
                Map.of("continuationId", "legacy-copilot", "workspaceId", "ws-a"));
        var scores = Map.of("BP", 0d);
        when(architecture.build(scores, "Frozen requirement", 20)).thenReturn(new RequirementArchitectureView());
        when(metadata.resolve()).thenReturn(new DiagramViewMetadata("default", "View", "", false, List.of()));
        when(recommendations.rankMatches(scores, "Frozen requirement", 20)).thenReturn(new ArchitectureRecommendation());
        try (var context = context()) {
            when(context.getBean(ReportRendererRegistry.class).getRequired("markdown"))
                    .thenReturn(new MarkdownReportRendererExtension(new ArchitectureReportTextRenderer()));
            var http = mvc(context);
            for (var payload : List.of(scorelessLive(), retainedAssessmentOnly)) {
                http.perform(post("/api/report/markdown").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload))).andExpect(status().isBadRequest());
            }
            http.perform(post("/api/report/markdown").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("businessText", "Frozen requirement", "scores", scores))))
                    .andExpect(status().isOk());
            var retained = http.perform(post("/api/report/markdown").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(retainedCopilot))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(retained).contains("Frozen gap evidence");
        }
    }

    @Test
    void wordKeepsScorelessClaimsAndIdentifiesTheResolvedSource() throws Exception {
        var retained = scorelessReference("scoreless-word");
        var renderer = new ArchitectureReportDocxRenderer();
        try (var context = context()) {
            when(context.getBean(ReportRendererRegistry.class).getRequired("docx"))
                    .thenReturn(new DocxReportRendererExtension(renderer));
            var http = mvc(context);
            var storedWord = http.perform(post("/api/report/docx").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(retained))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(wordText(storedWord)).contains("Which retention interval?", "Report from retained analysis evidence.")
                    .doesNotContain("Ad-hoc report");

            var liveWord = http.perform(post("/api/report/docx").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(scorelessLive()))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(wordText(liveWord)).contains("Which retention interval?", "Ad-hoc report from the supplied live analysis")
                    .doesNotContain("retained analysis evidence");
        }

        var unspecified = new ArchitectureReport();
        unspecified.setBusinessText("Frozen requirement");
        assertThat(wordText(renderer.render(unspecified, "en")))
                .contains("Report from the supplied requirement and analysis evidence.")
                .doesNotContain("Ad-hoc report", "retained analysis evidence");
    }

    private static String wordText(byte[] word) throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(word));
                var extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static Map<String, Object> scorelessLive() {
        return Map.of("businessText", "Frozen requirement", "scores", Map.of(), "language", "en",
                "nodeAssessments", Map.of("BP", List.of(unresolved("Which retention interval?"))));
    }

    private Map<String, Object> scorelessReference(String id) {
        var result = new AnalysisResult(Map.of(), List.of());
        result.setNodeAssessments(Map.of("BP", List.of(unresolved("Which retention interval?"))));
        var recovery = new AnalysisRecoveryView(id, 5, "PARTIAL", 0, 1, "BP", List.of());
        when(continuations.read(id, "alice", workspace)).thenReturn(
                new AnalysisContinuationStore.Snapshot(recovery, result, new AnalysisRequest("Frozen requirement")));
        return Map.of("language", "en", "analysisReference",
                Map.of("continuationId", id, "workspaceId", "ws-a", "recoveryVersion", 5));
    }

    private AnalysisContinuationStore.Snapshot revision(long version, String question, AnalysisRequest request) {
        var result = savedResult();
        result.setNodeAssessments(Map.of("BP", List.of(unresolved(question))));
        var recovery = new AnalysisRecoveryView("versioned", version, "PARTIAL", 1, 1, "BP", List.of());
        return new AnalysisContinuationStore.Snapshot(recovery, result, request);
    }

    private static RequirementNodeAssessment unresolved(String question) {
        return new RequirementNodeAssessment("Frozen requirement", List.of(RequirementNodeAssessment.SubjectKind.PROCESS),
                List.of(RequirementNodeAssessment.RequirementKind.FUNCTIONAL), RequirementNodeAssessment.ContributionType.DIRECT_SUBJECT,
                RequirementNodeAssessment.EvidenceStatus.UNRESOLVED, RequirementNodeAssessment.EvidenceBasis.UNSPECIFIED,
                RequirementNodeAssessment.Necessity.UNDETERMINED, RequirementNodeAssessment.Scope.UNSPECIFIED,
                "", "Need more details.", question);
    }

    @Test
    void durableReferenceExportsRetainedReportsAndIgnoresForgedClientEvidence() throws Exception {
        var saved = savedResult();
        var binding = new AnalysisModelBinding(AnalysisModelBinding.State.CATALOGUE_UNRESOLVED,
                "repo-a", "ws-a", "historical", "b".repeat(40), 17L, "GIT", "c".repeat(64),
                null, "catalogue-definition-5", Set.of("BP"), new AnalysisScope(Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY),
                "brief-3", Map.of(), List.of(new AnalysisModelBinding.Reference("reference", "standard",
                        "standard-edition-2", "paragraph-7", Map.of())), Set.of(), null, "used-generation-9");
        saved.setModelBinding(binding);
        when(cluster.request(OPERATION, "alice", workspace)).thenReturn(new ClusterAnalysisObservation.RecoveryInput(
                OPERATION, "Frozen requirement", "MOCK", null,
                new ClusterAnalysisView.ObservationScope("ws-a", "repo-a", "branch-a", "commit-a"), null, null, 23L));
        when(cluster.result(OPERATION, "alice", workspace)).thenReturn(saved);
        try (var context = context()) {
            String body = mvc(context).perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("analysisReference", reference(),
                            "modelBinding", Map.of("catalogueRevision", "forged"),
                            "scores", Map.of("FORGED", 99), "businessText", "Forged text",
                            "reasons", Map.of("FORGED", "forged rationale")))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var report = mapper.readTree(body);
            assertThat(report.path("businessText").asString()).isEqualTo("Frozen requirement");
            assertThat(report.path("requirementVersionId").asLong()).isEqualTo(23L);
            assertThat(report.path("analysisScope").path("mode").asString()).isEqualTo("FULL");
            assertThat(mapper.treeToValue(report.path("modelBinding"), AnalysisModelBinding.class)).isEqualTo(binding);
            assertThat(report.path("scores").has("FORGED")).isFalse();
            assertThat(report.path("scores").path("BP").asDouble()).isEqualTo(70d);
            assertThat(report.has("nodeReasons")).isFalse();
            assertThat(report.has("languageTag")).isFalse();
            assertThat(report.path("gapAnalysis").path("notes").get(0).asString()).isEqualTo("Frozen gap evidence");
            assertThat(report.path("recommendation").path("meanRelevance").asDouble()).isEqualTo(71d);
            assertThat(report.path("recommendation").has("confidence")).isFalse();
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

    @Test
    void continuationJsonIncludesRecordedReasonsAndContentLanguage() throws Exception {
        var result = savedResult();
        result.setReasons(Map.of("BP", "Erfasste Begründung zur Übergabe"));
        var request = new AnalysisRequest("Gespeicherte Anforderung zur Übergabe");
        request.setAnalysisLanguage(AnalysisLanguage.GERMAN);
        when(continuations.read("continuation", "alice", workspace)).thenReturn(
                new AnalysisContinuationStore.Snapshot(null, result, request, OPERATION));

        try (var context = context()) {
            String body = mvc(context).perform(post("/api/report/json").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("analysisReference", Map.of("continuationId", "continuation",
                            "operationId", OPERATION, "workspaceId", "ws-a"), "analysisLanguage", "en",
                            "reasons", Map.of("BP", "Forged rationale")))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var report = mapper.readTree(body);
            assertThat(report.path("nodeReasons").path("BP").asString()).isEqualTo("Erfasste Begründung zur Übergabe");
            assertThat(report.path("languageTag").asString()).isEqualTo("de");
            assertThat(body).doesNotContain("Forged rationale");
        }
        verifyNoInteractions(architecture, gaps, patterns, recommendations, proposals, metadata, cluster);
    }

    private Map<String, String> reference() { return Map.of("operationId", OPERATION, "workspaceId", "ws-a"); }

    private AnalysisResult savedResult() {
        var result = new AnalysisResult(Map.of("BP", 70d), List.of());
        result.setAnalysisOperationId(OPERATION);
        result.setArchitectureView(new RequirementArchitectureView());
        var retainedGaps = new GapAnalysisView(); retainedGaps.getNotes().add("Frozen gap evidence");
        var recommendation = new ArchitectureRecommendation(); recommendation.setMeanRelevance(71d);
        result.setCopilot(new AnalysisCopilotResult(1, "PARTIAL", retainedGaps,
                new PatternDetectionView(), recommendation, List.of("Some retained evidence is incomplete")));
        return result;
    }

    private AnnotationConfigApplicationContext context() {
        when(workspaces.resolveCurrentUsername()).thenReturn("alice");
        when(workspaces.resolveCurrentContext()).thenReturn(workspace);
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ArchitectureReportService.class, () -> new ArchitectureReportService(
                architecture, gaps, recommendations, new DiagramProjectionService(),
                new MermaidExportService(), proposals, metadata));
        context.registerBean(ReportRendererRegistry.class, () -> mock(ReportRendererRegistry.class));
        context.registerBean(RepositoryStateService.class, () -> mock(RepositoryStateService.class));
        context.registerBean(WorkspaceResolver.class, () -> workspaces);
        context.registerBean(ClusterAnalysisObservation.class, () -> cluster);
        context.registerBean(WorkspaceActorAccess.class, WorkspaceActorAccess::unavailable);
        context.registerBean(AnalysisContinuationService.class, () -> continuations);
        context.registerBean(ReportApiController.class);
        context.refresh();
        // Spring configures the mock during refresh. Verify interactions from the request.
        clearInvocations(continuations);
        return context;
    }

    private MockMvc mvc(AnnotationConfigApplicationContext context) {
        return MockMvcBuilders.standaloneSetup(context.getBean(ReportApiController.class)).build();
    }
}
