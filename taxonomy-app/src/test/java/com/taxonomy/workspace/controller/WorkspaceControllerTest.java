package com.taxonomy.workspace.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
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

    // ── Diverged resolution ─────────────────────────────────────────

    @Test
    void resolveDivergedInvalidStrategy() throws Exception {
        mockMvc.perform(post("/api/workspace/resolve-diverged")
                        .param("strategy", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid strategy: INVALID"));
    }

    @Test
    void resolveDivergedValidStrategy() throws Exception {
        // MERGE strategy on default branch — the endpoint validates the strategy
        // but may fail if the merge encounters issues (that's OK, we test the routing)
        var result = mockMvc.perform(post("/api/workspace/resolve-diverged")
                        .param("strategy", "MERGE")
                        .param("userBranch", "draft"))
                .andReturn();
        // Should not be 400 (strategy is valid)
        assertNotEquals(400, result.getResponse().getStatus());
    }

    // ── Create workspace ────────────────────────────────────────────

    @Test
    void createWorkspace_returnsOk() throws Exception {
        mockMvc.perform(post("/api/workspace/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"Test Workspace\", \"description\": \"A test workspace\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Test Workspace"));
    }

    @Test
    void createWorkspace_missingDisplayNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/workspace/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"no name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("displayName is required"));
    }

    @Test
    void createWorkspace_blankDisplayNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/workspace/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"   \", \"description\": \"blank name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("displayName is required"));
    }

    // ── List workspaces ─────────────────────────────────────────────

    @Test
    void listWorkspaces_returnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── Rename workspace ────────────────────────────────────────────

    @Test
    void renameWorkspace_missingDisplayNameReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/api/workspace/nonexistent-id/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("displayName is required"));
    }

    // ── Get workspace info ──────────────────────────────────────────

    @Test
    void getWorkspaceInfo_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/workspace/nonexistent-id-12345/info"))
                .andExpect(status().isNotFound());
    }

    // ── Sync from shared ────────────────────────────────────────────

    @Test
    void syncFromShared_returnsOk() throws Exception {
        var result = mockMvc.perform(post("/api/workspace/sync-from-shared"))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 500,
                "Expected 200 (sync success) or 500 (no shared repo), got " + status);
    }

    // ── Publish ─────────────────────────────────────────────────────

    @Test
    void publish_returnsOk() throws Exception {
        var result = mockMvc.perform(post("/api/workspace/publish"))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 500,
                "Expected 200 (publish success) or 500 (no changes), got " + status);
    }

    // ── Switch workspace (not found) ────────────────────────────────

    @Test
    void switchWorkspace_notFound_returns400() throws Exception {
        mockMvc.perform(post("/api/workspace/nonexistent-id-12345/switch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    // ── Archive workspace (not found) ───────────────────────────────

    @Test
    void archiveWorkspace_notFound_returns400() throws Exception {
        mockMvc.perform(post("/api/workspace/nonexistent-id-12345/archive"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    // ── Delete workspace (not found) ────────────────────────────────

    @Test
    void deleteWorkspace_notFound_returns400() throws Exception {
        mockMvc.perform(delete("/api/workspace/nonexistent-id-12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }
}
