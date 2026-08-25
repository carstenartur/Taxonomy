package com.taxonomy.analysis.session;

import com.taxonomy.analysis.session.AnalysisDraftDtos.AnalysisDraftView;
import com.taxonomy.analysis.session.AnalysisDraftDtos.SaveAnalysisDraftRequest;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the draft DTO through Spring Boot 4's canonical Jackson 3 MVC boundary. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "embedding.enabled=false",
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key="
})
class AnalysisWorkingDraftMvcContractTest {

    private static final String VALID_SAVE_REQUEST = """
            {
              "payload": {
                "schemaVersion": 1,
                "businessText": "Provide resilient communications",
                "scores": {"BP-1000": 82}
              },
              "expectedVersion": 6
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisWorkingDraftService service;

    @MockitoBean
    private WorkspaceResolver workspaceResolver;

    @BeforeEach
    void configureUserAndResponse() {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("analyst");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.workspace(
                        "repository-a", "workspace-b", "feature/b", "analyst"));
        when(service.save(eq("analyst"), eq("workspace-a"), any()))
                .thenAnswer(invocation -> {
                    SaveAnalysisDraftRequest request = invocation.getArgument(2);
                    return new AnalysisDraftView(
                            "workspace-a",
                            "feature/architecture",
                            request.payload(),
                            7L,
                            Instant.parse("2026-08-20T12:00:00Z"));
                });
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void putBindsAndReturnsStructuredJacksonThreePayload() throws Exception {
        mockMvc.perform(put("/api/analysis-drafts/{workspaceId}", "workspace-a")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SAVE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"7\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.workspaceId").value("workspace-a"))
                .andExpect(jsonPath("$.branch").value("feature/architecture"))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.payload.businessText")
                        .value("Provide resilient communications"))
                .andExpect(jsonPath("$.payload.scores['BP-1000']").value(82));

        ArgumentCaptor<SaveAnalysisDraftRequest> request =
                ArgumentCaptor.forClass(SaveAnalysisDraftRequest.class);
        verify(service).save(eq("analyst"), eq("workspace-a"), request.capture());
        assertThat(request.getValue().expectedVersion()).isEqualTo(6L);
        assertThat(request.getValue().payload().path("schemaVersion").asInt())
                .isEqualTo(1);
        assertThat(request.getValue().payload().path("scores").path("BP-1000").asInt())
                .isEqualTo(82);
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void absentDraftResponseIsExplicitlyNoStore() throws Exception {
        when(service.read("analyst", "workspace-a")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analysis-drafts/{workspaceId}", "workspace-a"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(service).read("analyst", "workspace-a");
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void deleteResponseIsExplicitlyNoStoreAndUsesExpectedRevision() throws Exception {
        mockMvc.perform(delete("/api/analysis-drafts/{workspaceId}", "workspace-a")
                        .with(csrf())
                        .param("expectedVersion", "6"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(service).delete("analyst", "workspace-a", 6L);
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void optimisticDraftConflictRemainsHttp409AcrossGlobalExceptionHandling() throws Exception {
        when(service.save(eq("analyst"), eq("workspace-a"), any()))
                .thenThrow(new AnalysisDraftConflictException(
                        "Analysis draft version conflict: expected 6, current 7"));

        mockMvc.perform(put("/api/analysis-drafts/{workspaceId}", "workspace-a")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SAVE_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message")
                        .value("Analysis draft version conflict: expected 6, current 7"));
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void invalidDraftRemainsHttp400AcrossGlobalExceptionHandling() throws Exception {
        when(service.save(eq("analyst"), eq("workspace-a"), any()))
                .thenThrow(new AnalysisDraftValidationException(
                        "Analysis draft payload must be a JSON object"));

        mockMvc.perform(put("/api/analysis-drafts/{workspaceId}", "workspace-a")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SAVE_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("Analysis draft payload must be a JSON object"));
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void explicitWorkspacePinCannotAddressAnotherDraftPath() throws Exception {
        mockMvc.perform(put("/api/analysis-drafts/{workspaceId}", "workspace-a")
                        .with(csrf())
                        .header(WorkspaceContextResolver.WORKSPACE_HEADER, "workspace-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "payload": {"businessText": "must not be saved"},
                                  "expectedVersion": null
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(service, never()).save(any(), any(), any());
    }
}
