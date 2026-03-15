package com.taxonomy.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link WorkspaceController}.
 *
 * <p>Verifies all workspace management endpoints including the new
 * compare, sync, history, projection, and dirty-state endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "testuser", roles = "ADMIN")
class WorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Basic workspace endpoints ───────────────────────────────────

    @Test
    void getCurrentWorkspaceReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.currentBranch").isString())
                .andExpect(jsonPath("$.shared").isBoolean());
    }

    @Test
    void listActiveWorkspacesReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getStatsReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeWorkspaces").isNumber());
    }

    @Test
    void evictWorkspaceReturnsOk() throws Exception {
        mockMvc.perform(post("/api/workspace/evict").param("username", "testuser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.evicted").value("testuser"));
    }

    // ── Compare endpoint ────────────────────────────────────────────

    @Test
    void compareWithSameBranchReturnsOk() throws Exception {
        mockMvc.perform(post("/api/workspace/compare")
                        .param("leftBranch", "draft")
                        .param("rightBranch", "draft"))
                .andExpect(status().isOk());
    }

    @Test
    void compareWithNonexistentBranchReturnsOk() throws Exception {
        // Compare between non-existent branches should not throw 500
        mockMvc.perform(post("/api/workspace/compare")
                        .param("leftBranch", "draft")
                        .param("rightBranch", "nonexistent"))
                .andExpect(status().isOk());
    }

    // ── Sync state endpoint ─────────────────────────────────────────

    @Test
    void getSyncStateReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/sync-state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.syncStatus").isString());
    }

    // ── History endpoint ────────────────────────────────────────────

    @Test
    void getHistoryReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── Local changes endpoint ──────────────────────────────────────

    @Test
    void getLocalChangesReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/local-changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeCount").isNumber())
                .andExpect(jsonPath("$.branch").isString());
    }

    @Test
    void getLocalChangesWithBranchParam() throws Exception {
        mockMvc.perform(get("/api/workspace/local-changes").param("branch", "draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeCount").isNumber())
                .andExpect(jsonPath("$.branch").value("draft"));
    }

    // ── Dirty check endpoint ────────────────────────────────────────

    @Test
    void isDirtyReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/dirty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.dirty").isBoolean())
                .andExpect(jsonPath("$.syncStatus").isString());
    }

    // ── Projection endpoint ─────────────────────────────────────────

    @Test
    void getProjectionReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/projection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap());
    }
}
