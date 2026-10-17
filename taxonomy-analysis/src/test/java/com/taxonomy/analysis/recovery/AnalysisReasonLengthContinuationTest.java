package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.AnalysisReasonContext;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisReasonLengthContinuationTest {
    private final WorkspaceContext workspace = new WorkspaceContext("owner", "workspace", "draft");
    private final AnalysisContinuationStore store = mock(AnalysisContinuationStore.class);
    private final LlmService llm = mock(LlmService.class);
    private final AnalysisContinuationService service;

    AnalysisReasonLengthContinuationTest() {
        var catalogue = mock(TaxonomyService.class);
        when(catalogue.getFullTree()).thenReturn(List.of());
        var workspaces = mock(WorkspaceManager.class);
        var owned = mock(UserWorkspace.class);
        when(owned.getUsername()).thenReturn("owner");
        when(workspaces.getWorkspaceById("workspace")).thenReturn(owned);
        when(llm.recoveryPolicyFingerprint("MOCK")).thenReturn("provider-policy");
        when(store.begin(any(), eq(workspace), anyString(), anyList())).thenReturn(
                new AnalysisContinuationStore.Claim("retained", null, new AnalysisResult()));
        service = new AnalysisContinuationService(store, catalogue, llm, workspaces, new ObjectMapper());
    }

    @Test
    void newRunCapturesThePreferenceAndResumeUsesTheSavedModeAfterPreferencesChange() {
        var request = request();
        when(llm.resolveAnalysisReasonLength(null)).thenReturn(AnalysisReasonLength.DETAILED);
        try (var initial = service.begin(request, "owner", workspace, 20)) {
            assertEquals(AnalysisReasonLength.DETAILED, initial.analysisReasonLength());
        }
        var requests = ArgumentCaptor.forClass(AnalysisRequest.class);
        var signatures = ArgumentCaptor.forClass(String.class);
        verify(store).begin(requests.capture(), eq(workspace), signatures.capture(), anyList());
        var saved = requests.getValue();
        var signature = signatures.getValue();
        assertEquals(AnalysisReasonLength.DETAILED, saved.getAnalysisReasonLength());
        assertNull(request.getAnalysisReasonLength(), "Admission freezes its own copy, not the caller's DTO");

        when(store.originalRequest(request.getContinuationId(), workspace)).thenReturn(Optional.of(saved));
        when(llm.resolveAnalysisReasonLength(null)).thenReturn(AnalysisReasonLength.SHORT);
        request.setContinuationAction("CONTINUE");
        try (var resumed = service.begin(request, "owner", workspace, 20)) {
            var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "owner", workspace);
            assertEquals(AnalysisReasonLength.DETAILED, resumed.bind(command).analysisReasonLength());
        }
        verify(store, times(2)).begin(requests.capture(), eq(workspace), signatures.capture(), anyList());
        assertEquals(signature, signatures.getValue());
        assertEquals(AnalysisReasonLength.DETAILED, requests.getValue().getAnalysisReasonLength());
        verify(llm, times(1)).resolveAnalysisReasonLength(null);
        assertNull(AnalysisReasonContext.current());
    }

    @Test
    void aResumeCannotReplaceTheOriginalModeAndLegacyWorkDoesNotReadLivePreferences() {
        var request = request();
        var saved = request(); saved.setAnalysisReasonLength(AnalysisReasonLength.DETAILED);
        when(store.originalRequest(request.getContinuationId(), workspace)).thenReturn(Optional.of(saved));
        request.setAnalysisReasonLength(AnalysisReasonLength.SHORT);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.begin(request, "owner", workspace, 20)).getStatusCode().value());
        verify(store, never()).begin(any(), any(), any(), any());

        request.setAnalysisReasonLength(null);
        saved.setAnalysisReasonLength(null);
        when(llm.resolveAnalysisReasonLength(null)).thenReturn(AnalysisReasonLength.DETAILED);
        try (var legacy = service.begin(request, "owner", workspace, 20)) {
            assertEquals(AnalysisReasonLength.STANDARD, legacy.analysisReasonLength());
        }
        verify(llm, never()).resolveAnalysisReasonLength(null);
        assertNull(saved.getAnalysisReasonLength(), "Historical provenance stays absent");
        assertNull(AnalysisReasonContext.current());
    }

    private static AnalysisRequest request() {
        var request = new AnalysisRequest("requirement");
        request.setProvider("MOCK"); request.setResumable(true);
        request.setContinuationId(UUID.randomUUID().toString());
        return request;
    }
}
