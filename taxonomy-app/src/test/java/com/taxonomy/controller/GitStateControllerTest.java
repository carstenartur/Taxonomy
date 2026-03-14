package com.taxonomy.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link GitStateController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class GitStateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getStateReturnsOk() throws Exception {
        mockMvc.perform(get("/api/git/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBranch").value("draft"))
                .andExpect(jsonPath("$.databaseBacked").isBoolean())
                .andExpect(jsonPath("$.branches").isArray())
                .andExpect(jsonPath("$.projectionStale").isBoolean())
                .andExpect(jsonPath("$.indexStale").isBoolean());
    }

    @Test
    void getStateWithCustomBranch() throws Exception {
        mockMvc.perform(get("/api/git/state").param("branch", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBranch").value("nonexistent"))
                .andExpect(jsonPath("$.headCommit").isEmpty())
                .andExpect(jsonPath("$.totalCommits").value(0));
    }

    @Test
    void getProjectionStateReturnsOk() throws Exception {
        mockMvc.perform(get("/api/git/projection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectionStale").isBoolean())
                .andExpect(jsonPath("$.indexStale").isBoolean());
    }

    @Test
    void getBranchesReturnsOk() throws Exception {
        mockMvc.perform(get("/api/git/branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branches").isArray());
    }

    @Test
    void isStaleReturnsOk() throws Exception {
        mockMvc.perform(get("/api/git/stale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectionStale").isBoolean())
                .andExpect(jsonPath("$.indexStale").isBoolean());
    }

    @Test
    void isStaleWithCustomBranch() throws Exception {
        mockMvc.perform(get("/api/git/stale").param("branch", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectionStale").value(false))
                .andExpect(jsonPath("$.indexStale").value(false));
    }
}
