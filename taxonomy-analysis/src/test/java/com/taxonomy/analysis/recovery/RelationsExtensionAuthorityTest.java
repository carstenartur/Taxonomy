package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelationsExtensionAuthorityTest {
    final AnalysisContinuationStore store = mock(AnalysisContinuationStore.class);
    final TaxonomyService catalogue = mock(TaxonomyService.class);
    final LlmService llm = mock(LlmService.class);
    final WorkspaceManager workspaces = mock(WorkspaceManager.class);
    final WorkspaceViewContextReadPort views = mock(WorkspaceViewContextReadPort.class);
    final LlmProviderConfig providers = mock(LlmProviderConfig.class);
    final ObjectMapper mapper = new ObjectMapper();
    final WorkspaceContext scope = new WorkspaceContext("alice", "ws", "draft", "repo");
    final ViewContext view = new ViewContext("commit", "draft", null, false, false, false);
    final TaxonomyNodeDto node = new TaxonomyNodeDto();
    AnalysisContinuationService service;
    AnalysisRequest source;
    AnalysisResult evidence;

    @BeforeEach void setup() {
        var workspace = new UserWorkspace(); workspace.setUsername("alice");
        when(workspaces.getWorkspaceById("ws")).thenReturn(workspace);
        node.setCode("CP"); node.setTaxonomyRoot("CP"); node.setNameEn("Original catalogue");
        when(catalogue.getFullTree()).thenReturn(List.of(node));
        when(llm.recoveryPolicyFingerprint("MOCK")).thenReturn("a".repeat(64));
        when(views.getViewContext("alice", "draft", scope)).thenReturn(view);
        service = new AnalysisContinuationService(store, catalogue, llm, workspaces, mapper);
        ReflectionTestUtils.setField(service, "providers", providers); ReflectionTestUtils.setField(service, "views", views);
        source = new AnalysisRequest("Original requirement"); source.setProvider("MOCK"); source.setContinuationId(UUID.randomUUID().toString());
        source.setAnalysisScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY)); source.setResumable(true);
        when(store.begin(any(), eq(scope), anyString(), anyList())).thenAnswer(call -> {
            source = mapper.readValue(mapper.writeValueAsString(call.getArgument(0)), AnalysisRequest.class);
            return new AnalysisContinuationStore.Claim(source.getContinuationId(), UUID.randomUUID().toString(), null);
        });
        try (var execution = service.begin(source, "alice", scope, 23)) { }
        evidence = new AnalysisResult(Map.of("CP", 72.125), List.of(node)); evidence.setProvider("MOCK"); evidence.setAnalysisProviderId("MOCK");
        evidence.setStatus("SUCCESS"); evidence.setViewContext(view); evidence.setAnalysisScope(source.getAnalysisScope());
        evidence.setAnalysisCoverage(AnalysisCoverage.derive(List.of(node), evidence.getRawScores(), evidence.getScores(), Map.of(), null));
        when(store.relationInput(any(), eq(scope))).thenAnswer(call -> new AnalysisContinuationStore.Snapshot(
                new AnalysisRecoveryView(source.getContinuationId(), 7, "COMPLETED", 1, 1, null, List.of()), evidence, source));
    }
    AnalysisRequest decision() {
        var request = new AnalysisRequest(); request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString());
        request.setRelationSourceContinuationId(source.getContinuationId()); request.setRelationSourceVersion(7L); return request;
    }
    @Test void extensionUsesSavedProviderRequirementScopeAndLimitDespiteCurrentPreferences() {
        var request = decision(); request.setProvider("GEMINI"); request.setMaxArchitectureNodes(9999);
        var resolved = service.resolveRelationsRequest(request, "alice", scope);
        assertThat(resolved.getBusinessText()).isEqualTo("Original requirement");
        assertThat(resolved.getProvider()).isEqualTo("MOCK"); assertThat(resolved.getMaxArchitectureNodes()).isEqualTo(23);
        assertThat(resolved.getAnalysisScope()).isEqualTo(new AnalysisScope(Set.of("CP"), AnalysisMode.FULL));
        assertThat(resolved.isIncludeArchitectureView()).isTrue();
        verify(store).verifySourceSignature(eq(source.getContinuationId()), eq(scope), anyString());
        verify(llm, never()).analyzeWithBudget(any()); verify(llm, never()).analyzeWithBudget(any(), any());
    }
    @Test void extensionKeepsTheCapturedReasonPolicyWhenLivePreferencesAndRequestedLengthChange() {
        when(llm.resolveAnalysisReasonLength(null)).thenReturn(AnalysisReasonLength.DETAILED);
        when(llm.recoveryPolicyFingerprint("MOCK")).thenAnswer(call ->
                AnalysisReasonContext.current() == AnalysisReasonLength.DETAILED ? "d".repeat(64) : "s".repeat(64));
        source.setContinuationId(UUID.randomUUID().toString());
        source.setAnalysisReasonLength(null);
        try (var execution = service.begin(source, "alice", scope, 23)) { }
        assertThat(mapper.readTree(source.getContinuationAuthority()).get("scoringPolicy").asText()).isEqualTo("d".repeat(64));

        when(llm.resolveAnalysisReasonLength(null)).thenReturn(AnalysisReasonLength.SHORT);
        var request = decision(); request.setAnalysisReasonLength(AnalysisReasonLength.SHORT); request.setProvider("GEMINI");
        var resolved = service.resolveRelationsRequest(request, "alice", scope);
        assertThat(resolved.getAnalysisReasonLength()).isEqualTo(AnalysisReasonLength.DETAILED);
        assertThat(resolved.getProvider()).isEqualTo("MOCK");
        try (var execution = service.begin(resolved, "alice", scope, 23)) {
            var command = new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand("Original requirement", true, 23,
                    "MOCK", "alice", scope);
            assertThat(execution.bind(command).analysisReasonLength()).isEqualTo(AnalysisReasonLength.DETAILED);
        }
        assertThat(AnalysisReasonContext.current()).isNull();
    }
    @Test void changedProviderPolicyRejectsBeforeAnyRelationExecution() {
        when(llm.recoveryPolicyFingerprint("MOCK")).thenReturn("b".repeat(64));
        assertThatThrownBy(() -> service.resolveRelationsRequest(decision(), "alice", scope))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }
    @Test void changedCatalogueRejectsEvenWhenNodeIdentitiesStillMatch() {
        node.setNameEn("Changed catalogue label");
        assertThatThrownBy(() -> service.resolveRelationsRequest(decision(), "alice", scope))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }
    @Test void changedExactSourceCommitRejectsBeforeRelationExecution() {
        when(views.getViewContext("alice", "draft", scope)).thenReturn(new ViewContext("other", "draft", null, false, false, false));
        assertThatThrownBy(() -> service.resolveRelationsRequest(decision(), "alice", scope))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("source changed");
    }
    @Test void editedRequirementIsRejectedWithoutReinterpretingIt() {
        var request = decision(); request.setBusinessText("Edited requirement");
        assertThatThrownBy(() -> service.resolveRelationsRequest(request, "alice", scope))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Requirement text differs");
    }
    @Test void olderDurableEvidenceWithExactSignatureAndExplicitProviderRemainsUsable() {
        source.setContinuationAuthority(null);
        assertThat(service.resolveRelationsRequest(decision(), "alice", scope).getProvider()).isEqualTo("MOCK");
        verify(store).verifySourceSignature(eq(source.getContinuationId()), eq(scope), anyString());
    }
}
