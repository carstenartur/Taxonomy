package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisContinuationAuthoritySecurityTest {
    private final WorkspaceContext scope = new WorkspaceContext("alice", "workspace", "draft", "repo");
    private final AnalysisContinuationStore store = mock(AnalysisContinuationStore.class);
    private final TaxonomyService catalogue = mock(TaxonomyService.class);
    private final LlmService llm = mock(LlmService.class);
    private final LlmProviderConfig providers = mock(LlmProviderConfig.class);
    private final WorkspaceManager workspaces = mock(WorkspaceManager.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisContinuationService service = new AnalysisContinuationService(store, catalogue, llm, workspaces, mapper);

    @BeforeEach void setup() {
        var workspace = new UserWorkspace(); workspace.setUsername("alice");
        when(workspaces.getWorkspaceById("workspace")).thenReturn(workspace);
        when(catalogue.getFullTree()).thenReturn(List.of(node("Original catalogue")));
        when(llm.resolveAnalysisReasonLength(null)).thenReturn(AnalysisReasonLength.DETAILED);
        when(llm.recoveryPolicyFingerprint("MOCK")).thenReturn("a".repeat(64));
        when(store.begin(any(), eq(scope), anyString(), anyList())).thenAnswer(call ->
                new AnalysisContinuationStore.Claim(call.<AnalysisRequest>getArgument(0).getContinuationId(), UUID.randomUUID().toString(), null));
        ReflectionTestUtils.setField(service, "providers", providers);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryAdmissionPersistsServerEvidenceAndResumesItWithoutReadingTheNewModel(boolean distributed) {
        var models = mock(com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort.class);
        ReflectionTestUtils.setField(service, "admissionModels", models);
        var first = request();
        var trusted = model("a");
        var snapshots = mock(com.taxonomy.catalog.snapshot.CatalogueSnapshotService.class);
        ReflectionTestUtils.setField(service, "catalogueSnapshots", snapshots);
        var identity = new com.taxonomy.catalog.snapshot.CatalogueSourceIdentity("repo", "workspace", "draft", trusted.commit());
        var root = mock(com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.class);
        when(root.rootCode()).thenReturn("CP");
        when(snapshots.captureRevision(identity, trusted.executionCatalogueRevision(), java.util.Set.of())).thenReturn(List.of(root));
        first.setExpectedCommit(trusted.commit());
        first.setModelBinding(model("f"));
        when(models.capture(scope, trusted.commit(), null, first.getAnalysisScope())).thenReturn(trusted);
        try (var ignored = begin(first, distributed)) { }
        var saved = org.mockito.ArgumentCaptor.forClass(AnalysisRequest.class);
        verify(store).begin(saved.capture(), eq(scope), anyString(), anyList());
        assertThat(saved.getValue().getModelBinding()).isEqualTo(trusted);

        when(store.originalRequest(first.getContinuationId(), scope)).thenReturn(java.util.Optional.of(saved.getValue()));
        var retry = request();
        retry.setContinuationId(first.getContinuationId());
        retry.setModelBinding(model("b"));
        try (var execution = begin(retry, distributed)) {
            var command = new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand("library", false, 23,
                    "MOCK", "alice", scope, null, retry.getAnalysisScope());
            assertThat(execution.bind(command).modelBinding()).isEqualTo(trusted);
        }
        verify(models).capture(scope, trusted.commit(), null, first.getAnalysisScope());
        verifyNoMoreInteractions(models);
        retry.setExpectedCommit("b".repeat(40));
        assertThatThrownBy(() -> begin(retry, distributed)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @org.junit.jupiter.api.Test
    void relationsExtensionRetainsTheNodeAdmissionWhileExecutingItsAuthorizedWiderScope() {
        var scoring = new AnalysisScope(java.util.Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY);
        var full = new AnalysisScope(java.util.Set.of("CP"), AnalysisMode.FULL);
        var origin = model("a", scoring);
        var frozen = request();
        frozen.setAnalysisScope(full);
        frozen.setModelBinding(origin);
        frozen.setMaxArchitectureNodes(23);
        var claim = new AnalysisContinuationStore.Claim(frozen.getContinuationId(), "claim", null);
        try (var execution = service.new Execution(claim, null, frozen, null, scope)) {
            var command = new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand("library", true, 23,
                    "MOCK", "alice", scope, null, full);
            var bound = execution.bind(command);
            assertThat(bound.analysisScope()).isEqualTo(full);
            assertThat(bound.modelBinding()).isEqualTo(origin);
            assertThat(bound.modelBinding().scope()).isEqualTo(scoring);
        }
    }

    @org.junit.jupiter.api.Test
    void claimedPortfolioWorkReadsItsSavedModelAndScopeWithoutAccessingTheCurrentBrowser() {
        var browserAdmission = mock(com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort.class);
        ReflectionTestUtils.setField(service, "admissionModels", browserAdmission);
        var searchScope = new AnalysisScope(java.util.Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY);
        var actual = model("a", searchScope);
        var request = request();
        request.setAnalysisScope(searchScope);
        var snapshots = mock(com.taxonomy.catalog.snapshot.CatalogueSnapshotService.class);
        ReflectionTestUtils.setField(service, "catalogueSnapshots", snapshots);
        var archived = mock(com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.class);
        when(archived.rootCode()).thenReturn("CP");
        when(snapshots.captureRevision(any(), eq(actual.analysisCatalogueRevision()), anySet())).thenReturn(List.of(archived));
        try (var execution = service.beginPortfolio(request, "alice", scope, 23, actual)) {
            var command = new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand(request.getBusinessText(), true, 23,
                    "MOCK", "alice", scope, null, searchScope);
            assertThat(execution.bind(command).modelBinding()).isEqualTo(actual);
        }
        verifyNoInteractions(browserAdmission);
    }

    private static AnalysisModelBinding model(String hex) {
        return model(hex, AnalysisScope.full());
    }

    private static AnalysisModelBinding model(String hex, AnalysisScope modelScope) {
        return new AnalysisModelBinding(AnalysisModelBinding.State.CATALOGUE_UNRESOLVED, "repo", "workspace", "draft",
                hex.repeat(40), 9L, "GIT_CHECKPOINT", hex.repeat(64), null, "standard", java.util.Set.of("CP"),
                modelScope, null, java.util.Map.of(), java.util.List.of(), java.util.Set.of(), null, "workbook-at-start");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryAdmissionRejectsPolicyChangesBetweenCaptureAndExecution(boolean distributed) {
        when(llm.recoveryPolicyFingerprint("MOCK")).thenReturn("a".repeat(64), "b".repeat(64));
        assertThatThrownBy(() -> { try (var ignored = begin(request(), distributed)) { } })
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(store, never()).begin(any(), any(), any(), any());
        verify(llm, never()).analyzeWithBudget(any()); verify(llm, never()).analyzeWithBudget(any(), any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryAdmissionRejectsCatalogueChangesBetweenCaptureAndExecution(boolean distributed) {
        when(catalogue.getFullTree()).thenReturn(List.of(node("Original catalogue")), List.of(node("Changed catalogue")));
        assertThatThrownBy(() -> { try (var ignored = begin(request(), distributed)) { } })
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(store, never()).begin(any(), any(), any(), any());
    }

    @Test void standaloneLocalAdmissionValidatesAuthorityBeforePublishingAClaim() {
        ReflectionTestUtils.setField(service, "providers", null);
        when(llm.recoveryPolicyFingerprint("MOCK")).thenReturn("a".repeat(64), "b".repeat(64));
        assertThatThrownBy(() -> { try (var ignored = service.begin(request(), "alice", scope, 23)) { } })
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(store, never()).begin(any(), any(), any(), any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void suppliedAuthorityCannotReplaceTheServerProviderPolicyOrReasonMode(boolean distributed) {
        var request = request();
        request.setContinuationAuthority("{\"provider\":\"GEMINI\",\"scoringPolicy\":\"client-approved\"}");
        when(llm.recoveryPolicyFingerprint("MOCK")).thenAnswer(call ->
                AnalysisReasonContext.current() == AnalysisReasonLength.DETAILED ? "d".repeat(64) : "s".repeat(64));
        try (var execution = begin(request, distributed)) {
            var bound = execution.bind(new AnalyzeRequirementCommand("Edited text", false, 999, "GEMINI", "alice", scope));
            assertThat(bound.businessText()).isEqualTo("Original requirement");
            assertThat(bound.provider()).isEqualTo("MOCK");
            assertThat(bound.analysisReasonLength()).isEqualTo(AnalysisReasonLength.DETAILED);
        }
        var captured = ArgumentCaptor.forClass(AnalysisRequest.class);
        verify(store).begin(captured.capture(), eq(scope), anyString(), anyList());
        var authority = mapper.readTree(captured.getValue().getContinuationAuthority());
        assertThat(authority.path("provider").asText()).isEqualTo("MOCK");
        assertThat(authority.path("scoringPolicy").asText()).isEqualTo("d".repeat(64));
        assertThat(request.getContinuationAuthority()).contains("client-approved");
        assertThat(AnalysisReasonContext.current()).isNull();
    }

    @ParameterizedTest @MethodSource("malformedRelations")
    void malformedRelationDecisionsFailBeforeReadingAnyRetainedSource(boolean resumable, Long version) {
        var request = request(); request.setResumable(resumable); request.setRelationSourceVersion(version);
        request.setRelationSourceContinuationId(UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.resolveRelationsRequest(request, "alice", scope))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(store, llm, catalogue, providers);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void callerFlagsCannotAuthorizeAnotherOwnersWorkspace(boolean distributed) {
        var workspace = new UserWorkspace(); workspace.setUsername("bob");
        when(workspaces.getWorkspaceById("workspace")).thenReturn(workspace);
        assertThatThrownBy(() -> { try (var ignored = begin(request(), distributed)) { } })
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(store, llm, catalogue, providers);
    }

    private AnalysisContinuationService.Execution begin(AnalysisRequest request, boolean distributed) {
        return distributed ? service.beginDistributed(request, "alice", scope, 23) : service.begin(request, "alice", scope, 23);
    }
    private static Stream<Arguments> malformedRelations() {
        return Stream.of(Arguments.of(false, 7L), Arguments.of(true, null));
    }
    private static AnalysisRequest request() {
        var request = new AnalysisRequest("Original requirement"); request.setProvider("MOCK");
        request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString());
        request.setAnalysisScope(new AnalysisScope(java.util.Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY));
        return request;
    }
    private static TaxonomyNodeDto node(String name) {
        var node = new TaxonomyNodeDto(); node.setCode("CP"); node.setTaxonomyRoot("CP"); node.setNameEn(name); return node;
    }
}
