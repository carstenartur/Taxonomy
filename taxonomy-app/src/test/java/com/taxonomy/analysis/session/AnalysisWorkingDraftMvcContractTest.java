package com.taxonomy.analysis.session;

import com.taxonomy.analysis.session.AnalysisDraftDtos.AnalysisDraftView;
import com.taxonomy.analysis.session.AnalysisDraftDtos.ResetAnalysisDraftRequest;
import com.taxonomy.analysis.session.AnalysisDraftDtos.SaveAnalysisDraftRequest;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                "scores": {"BP-1000": 82},
                "architectureView": null,
                "relationSearchReport": {"totalCalls": 3, "searchExhausted": true}
              },
              "expectedVersion": 6
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private AnalysisWorkingDraftService service;

    @Autowired
    private UserWorkspaceRepository workspaceRepository;

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private WorkspaceResolver workspaceResolver;

    @BeforeEach
    void configureUserAndResponse() {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("analyst");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.workspace(
                        "repository-a", "workspace-b", "feature/b", "analyst"));
        doAnswer(invocation -> {
            SaveAnalysisDraftRequest request = invocation.getArgument(2);
            return new AnalysisDraftView(
                    "workspace-a",
                    "feature/architecture",
                    request.payload(),
                    7L,
                    Instant.parse("2026-08-20T12:00:00Z"));
        }).when(service).save(eq("analyst"), eq("workspace-a"), any());
        doAnswer(invocation -> {
            ResetAnalysisDraftRequest request = invocation.getArgument(2);
            var payload = tools.jackson.databind.json.JsonMapper.builder().build()
                    .createObjectNode()
                    .put("schemaVersion", 1)
                    .put("draftState", "EMPTY")
                    .put("businessText", "");
            if (request != null && request.analysisOptions() != null) {
                payload.set("analysisOptions", request.analysisOptions());
            }
            return new AnalysisDraftView(
                    "workspace-a",
                    "feature/architecture",
                    payload,
                    8L,
                    Instant.parse("2026-08-20T12:05:00Z"));
        }).when(service).reset(eq("analyst"), eq("workspace-a"), any());
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
                .andExpect(jsonPath("$.payload.scores['BP-1000']").value(82))
                .andExpect(jsonPath("$.payload.architectureView").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.payload.relationSearchReport.totalCalls").value(3))
                .andExpect(jsonPath("$.payload.relationSearchReport.searchExhausted").value(true));

        ArgumentCaptor<SaveAnalysisDraftRequest> request =
                ArgumentCaptor.forClass(SaveAnalysisDraftRequest.class);
        verify(service).save(eq("analyst"), eq("workspace-a"), request.capture());
        assertThat(request.getValue().expectedVersion()).isEqualTo(6L);
        assertThat(request.getValue().payload().path("schemaVersion").asInt())
                .isEqualTo(1);
        assertThat(request.getValue().payload().path("scores").path("BP-1000").asInt())
                .isEqualTo(82);
        assertThat(request.getValue().payload().path("architectureView").isNull()).isTrue();
        assertThat(request.getValue().payload().path("relationSearchReport").path("totalCalls").asInt())
                .isEqualTo(3);
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void absentDraftResponseIsExplicitlyNoStore() throws Exception {
        doReturn(Optional.empty()).when(service).read("analyst", "workspace-a");

        mockMvc.perform(get("/api/analysis-drafts/{workspaceId}", "workspace-a"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(service).read("analyst", "workspace-a");
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void deleteResponseIsExplicitlyNoStoreAndUsesExpectedRevision() throws Exception {
        doNothing().when(service).delete("analyst", "workspace-a", 6L);

        mockMvc.perform(delete("/api/analysis-drafts/{workspaceId}", "workspace-a")
                        .with(csrf())
                        .param("expectedVersion", "6"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(service).delete("analyst", "workspace-a", 6L);
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void resetStartsANewVersionedEmptyLifecycleWithoutExpectedRevision() throws Exception {
        mockMvc.perform(post("/api/analysis-drafts/{workspaceId}/reset", "workspace-a")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "analysisOptions": {
                                    "provider": "OPENAI",
                                    "interactiveMode": true
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"8\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.payload.draftState").value("EMPTY"))
                .andExpect(jsonPath("$.payload.businessText").value(""))
                .andExpect(jsonPath("$.payload.analysisOptions.provider").value("OPENAI"));

        ArgumentCaptor<ResetAnalysisDraftRequest> request =
                ArgumentCaptor.forClass(ResetAnalysisDraftRequest.class);
        verify(service).reset(eq("analyst"), eq("workspace-a"), request.capture());
        assertThat(request.getValue().analysisOptions().path("interactiveMode").asBoolean())
                .isTrue();
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void optimisticDraftConflictRemainsHttp409AcrossGlobalExceptionHandling() throws Exception {
        doThrow(new AnalysisDraftConflictException(
                "Analysis draft version conflict: expected 6, current 7"))
                .when(service).save(eq("analyst"), eq("workspace-a"), any());

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
        doThrow(new AnalysisDraftValidationException(
                "Analysis draft payload must be a JSON object"))
                .when(service).save(eq("analyst"), eq("workspace-a"), any());

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

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void resetCannotAddressAWorkspaceDifferentFromTheExplicitPin() throws Exception {
        mockMvc.perform(post("/api/analysis-drafts/{workspaceId}/reset", "workspace-a")
                        .with(csrf())
                        .header(WorkspaceContextResolver.WORKSPACE_HEADER, "workspace-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).reset(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "analyst", roles = "USER")
    void revisionObservationCannotAddressAnotherPinnedWorkspace() throws Exception {
        mockMvc.perform(get("/api/analysis-drafts/{workspaceId}/status", "workspace-a")
                        .header(WorkspaceContextResolver.WORKSPACE_HEADER, "workspace-b"))
                .andExpect(status().isForbidden());
        verify(service, never()).status(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "standard", "detailed"})
    @WithMockUser(username = "analyst", roles = "USER")
    void persistedDraftRetainsExplicitReasonLengthAcrossPutAndGet(String reasonLength) throws Exception {
        ObjectNode payload = recordedAnalysisPayload().put("analysisReasonLength", reasonLength);

        JsonNode restored = persistAndReloadDraft(payload);

        assertThat(restored.path("analysisReasonLength").asText()).isEqualTo(reasonLength);
        assertThat(restored.path("reasons").path("BP-1000").asText())
                .isEqualTo("The supplied requirement calls for resilient communications.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @WithMockUser(username = "analyst", roles = "USER")
    void persistedLegacyDraftKeepsUnknownReasonLengthAcrossPutAndGet(boolean explicitlyNull) throws Exception {
        ObjectNode payload = recordedAnalysisPayload();
        if (explicitlyNull) {
            payload.putNull("analysisReasonLength");
        }

        JsonNode restored = persistAndReloadDraft(payload);

        assertThat(restored.has("analysisReasonLength")).isEqualTo(explicitlyNull);
        if (explicitlyNull) {
            assertThat(restored.path("analysisReasonLength").isNull()).isTrue();
        } else {
            assertThat(restored.path("analysisReasonLength").isMissingNode()).isTrue();
        }
        assertThat(restored.path("reasons").path("BP-1000").asText())
                .isEqualTo("The supplied requirement calls for resilient communications.");
    }

    private ObjectNode recordedAnalysisPayload() {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("businessText", "Provide resilient communications");
        payload.set("scores", objectMapper.createObjectNode().put("BP-1000", 82));
        payload.set("reasons", objectMapper.createObjectNode().put(
                "BP-1000", "The supplied requirement calls for resilient communications."));
        return payload;
    }

    /** The unique workspace bypasses the boundary stubs and uses the real service and database. */
    private JsonNode persistAndReloadDraft(ObjectNode payload) throws Exception {
        String workspaceId = "draft-reasons-" + UUID.randomUUID();
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setUsername("analyst");
        workspace.setDisplayName(workspaceId);
        workspace.setCreatedAt(Instant.now());
        workspace.setCurrentBranch("feature/reason-length");
        workspace.setSourceRepositoryId(systemRepositoryService.getPrimaryRepository().getRepositoryId());
        workspace = workspaceRepository.saveAndFlush(workspace);
        try {
            mockMvc.perform(get("/api/analysis-drafts/{workspaceId}/status", workspaceId))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
            ObjectNode request = objectMapper.createObjectNode();
            request.set("payload", payload);
            request.putNull("expectedVersion");
            var saved = mockMvc.perform(put("/api/analysis-drafts/{workspaceId}", workspaceId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andReturn().getResponse();

            String storedJson = jdbc.queryForObject(
                    "select payload_json from analysis_working_draft where workspace_id = ? and username = ?",
                    String.class, workspaceId, "analyst");
            assertThat(objectMapper.readTree(storedJson)).isEqualTo(payload);

            var restored = mockMvc.perform(get("/api/analysis-drafts/{workspaceId}", workspaceId))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, saved.getHeader(HttpHeaders.ETAG)))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andReturn().getResponse();
            JsonNode restoredPayload = objectMapper.readTree(restored.getContentAsString()).path("payload");
            assertThat(restoredPayload).isEqualTo(payload);
            // Revision observation must not deserialize or transfer the potentially
            // large analysis envelope. Use deliberately invalid JSON to prove it.
            jdbc.update("update analysis_working_draft set payload_json = ? where workspace_id = ? and username = ?",
                    "not a JSON analysis payload", workspaceId, "analyst");
            var metadata = mockMvc.perform(get("/api/analysis-drafts/{workspaceId}/status", workspaceId))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                    .andExpect(jsonPath("$.branch").value("feature/reason-length"))
                    .andExpect(jsonPath("$.payload").doesNotExist())
                    .andReturn().getResponse();
            JsonNode expected = objectMapper.readTree(restored.getContentAsString());
            JsonNode observed = objectMapper.readTree(metadata.getContentAsString());
            assertThat(observed.path("version")).isEqualTo(expected.path("version"));
            assertThat(observed.path("updatedAt")).isEqualTo(expected.path("updatedAt"));
            return restoredPayload;
        } finally {
            jdbc.update("delete from analysis_working_draft where workspace_id = ? and username = ?",
                    workspaceId, "analyst");
            workspaceRepository.delete(workspace);
        }
    }
}
