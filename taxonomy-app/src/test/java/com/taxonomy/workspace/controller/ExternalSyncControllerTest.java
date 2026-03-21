package com.taxonomy.workspace.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "testuser", roles = "ADMIN")
class ExternalSyncControllerTest {

    private static final String BASE = "/api/workspace/external";

    @Autowired
    private MockMvc mockMvc;

    // ── GET /status ──────────────────────────────────────────────────────

    @Test
    void statusReturnsExpectedFields() throws Exception {
        mockMvc.perform(get(BASE + "/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalEnabled").isBoolean())
                .andExpect(jsonPath("$.externalUrl").hasJsonPath())
                .andExpect(jsonPath("$.lastFetchAt").hasJsonPath())
                .andExpect(jsonPath("$.lastPushAt").hasJsonPath())
                .andExpect(jsonPath("$.lastFetchCommit").hasJsonPath());
    }

    // ── POST /fetch ──────────────────────────────────────────────────────

    @Test
    void fetchReturns400WhenTopologyIsInternal() throws Exception {
        // Default topology is INTERNAL_SHARED → IllegalStateException → 400
        mockMvc.perform(post(BASE + "/fetch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Configuration error"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void fetchReturns400WithMeaningfulMessage() throws Exception {
        mockMvc.perform(post(BASE + "/fetch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── POST /push ───────────────────────────────────────────────────────

    @Test
    void pushReturns400WhenTopologyIsInternal() throws Exception {
        mockMvc.perform(post(BASE + "/push"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Configuration error"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void pushReturns400WhenTopologyIsInternalWithExplicitBranch() throws Exception {
        mockMvc.perform(post(BASE + "/push").param("branch", "main"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Configuration error"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void pushReturns400WithMeaningfulMessage() throws Exception {
        mockMvc.perform(post(BASE + "/push"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── POST /full-sync ──────────────────────────────────────────────────

    @Test
    void fullSyncReturns400WhenTopologyIsInternal() throws Exception {
        mockMvc.perform(post(BASE + "/full-sync"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Configuration error"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void fullSyncReturns400WithMeaningfulMessage() throws Exception {
        mockMvc.perform(post(BASE + "/full-sync"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── PUT /configure ───────────────────────────────────────────────────

    @Test
    void configureWithInvalidTopologyModeReturns400() throws Exception {
        mockMvc.perform(put(BASE + "/configure")
                        .param("topologyMode", "INVALID_MODE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid parameter"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void configureWithValidTopologyModeReturns200() throws Exception {
        mockMvc.perform(put(BASE + "/configure")
                        .param("topologyMode", "EXTERNAL_CANONICAL")
                        .param("externalUrl", "https://example.com/repo.git"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.topologyMode").value("EXTERNAL_CANONICAL"))
                .andExpect(jsonPath("$.externalUrl").value("https://example.com/repo.git"));

        // Reset back to INTERNAL_SHARED to avoid side-effects on other tests
        mockMvc.perform(put(BASE + "/configure")
                        .param("topologyMode", "INTERNAL_SHARED"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "regularuser", roles = "USER")
    void configureIsDeniedForNonAdminUser() throws Exception {
        // @PreAuthorize throws AuthorizationDeniedException, which the
        // GlobalExceptionHandler catch-all converts to 500.  Verify the
        // request is at least rejected (not 2xx).
        mockMvc.perform(put(BASE + "/configure")
                        .param("topologyMode", "EXTERNAL_CANONICAL"))
                .andExpect(status().is5xxServerError());
    }
}
