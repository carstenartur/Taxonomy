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
    @Test
    void admissionCapturesLanguageOnceAndResumeKeepsTheOriginalAcrossLocaleChanges() {
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        var request = request();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.GERMAN);
            try (var initial = service.begin(request, "owner", workspace, 20)) {
                assertEquals(AnalysisLanguage.GERMAN, initial.analysisLanguage());
            }
            var requests = ArgumentCaptor.forClass(AnalysisRequest.class);
            var signatures = ArgumentCaptor.forClass(String.class);
            verify(store).begin(requests.capture(), eq(workspace), signatures.capture(), anyList());
            var saved = requests.getValue(); var signature = signatures.getValue();
            assertEquals(AnalysisLanguage.GERMAN, saved.getAnalysisLanguage());
            assertNull(request.getAnalysisLanguage(), "Admission does not mutate the caller's DTO");
            when(store.originalRequest(request.getContinuationId(), workspace)).thenReturn(Optional.of(saved));
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
            try (var resumed = service.begin(request, "owner", workspace, 20)) {
                var bound = resumed.bind(new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "owner", workspace));
                assertEquals(AnalysisLanguage.GERMAN, bound.analysisLanguage());
                assertEquals(AnalysisLanguage.GERMAN, bound.withAnalysisReasonLength(AnalysisReasonLength.SHORT)
                        .withContinuationBinding(null).withRetainedAnalysis(null).analysisLanguage());
            }
            verify(store, times(2)).begin(requests.capture(), eq(workspace), signatures.capture(), anyList());
            assertEquals(signature, signatures.getValue());
            request.setAnalysisLanguage(AnalysisLanguage.ENGLISH);
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> service.begin(request, "owner", workspace, 20)).getStatusCode().value());
            verify(store, times(2)).begin(any(), any(), any(), any());
        } finally { org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous); }
        assertNull(com.taxonomy.analysis.service.AnalysisLanguageContext.current());
    }

    @Test
    void explicitLanguageOverridesUiAndDifferentContractsCannotShareIdentity() {
        var request = request(); request.setAnalysisLanguage(AnalysisLanguage.ENGLISH);
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.GERMAN);
            try (var admitted = service.begin(request, "owner", workspace, 20)) {
                assertEquals(AnalysisLanguage.ENGLISH, admitted.analysisLanguage());
            }
            request.setAnalysisLanguage(AnalysisLanguage.GERMAN);
            try (var admitted = service.begin(request, "owner", workspace, 20)) {
                assertEquals(AnalysisLanguage.GERMAN, admitted.analysisLanguage());
            }
            var signatures = ArgumentCaptor.forClass(String.class);
            verify(store, times(2)).begin(any(), eq(workspace), signatures.capture(), anyList());
            assertNotEquals(signatures.getAllValues().get(0), signatures.getAllValues().get(1));
        } finally { org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous); }
    }

    @Test
    void languageJsonRoundTripsPreserveExactWireContractAndLegacyAbsence() {
        var mapper = new ObjectMapper();
        for (var language : AnalysisLanguage.values()) {
            var request = mapper.readValue("{\"businessText\":\"original\",\"analysisLanguage\":\"" + language.value() + "\"}", AnalysisRequest.class);
            assertEquals(language, request.getAnalysisLanguage());
            assertEquals(language.value(), mapper.readTree(mapper.writeValueAsString(request)).path("analysisLanguage").asText());
            var result = new AnalysisResult(); result.setAnalysisLanguage(language);
            assertEquals(language, mapper.readValue(mapper.writeValueAsString(result), AnalysisResult.class).getAnalysisLanguage());
            var command = new AnalyzeRequirementCommand("original", false, 20, "MOCK", "owner", workspace).withAnalysisLanguage(language);
            assertEquals(language, mapper.readValue(mapper.writeValueAsString(command), AnalyzeRequirementCommand.class).analysisLanguage());
        }
        assertNull(mapper.readValue("{}", AnalysisResult.class).getAnalysisLanguage());
        var saved = request();
        when(store.originalRequest(saved.getContinuationId(), workspace)).thenReturn(Optional.of(saved));
        try (var legacy = service.begin(saved, "owner", workspace, 20)) {
            assertNull(legacy.analysisLanguage());
            assertNull(legacy.bind(new AnalyzeRequirementCommand("original", false, 20, "MOCK", "owner", workspace)).analysisLanguage());
        }
        var requests = ArgumentCaptor.forClass(AnalysisRequest.class);
        verify(store).begin(requests.capture(), any(), any(), any());
        assertNull(requests.getValue().getAnalysisLanguage());
    }

}
