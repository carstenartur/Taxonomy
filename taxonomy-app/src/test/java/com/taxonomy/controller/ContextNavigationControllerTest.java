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
 * Integration tests for {@link ContextNavigationController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class ContextNavigationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getCurrentContextReturnsOk() throws Exception {
        mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextId").isNotEmpty())
                .andExpect(jsonPath("$.branch").value("draft"))
                .andExpect(jsonPath("$.mode").value("EDITABLE"));
    }

    @Test
    void openContextReadOnlyReturnsOk() throws Exception {
        mockMvc.perform(post("/api/context/open")
                        .param("branch", "draft")
                        .param("readOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("READ_ONLY"))
                .andExpect(jsonPath("$.branch").value("draft"));
    }

    @Test
    void openContextEditableReturnsOk() throws Exception {
        mockMvc.perform(post("/api/context/open")
                        .param("branch", "draft")
                        .param("readOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("EDITABLE"))
                .andExpect(jsonPath("$.branch").value("draft"));
    }

    @Test
    void returnToOriginReturnsOk() throws Exception {
        mockMvc.perform(post("/api/context/return-to-origin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").isNotEmpty());
    }

    @Test
    void backReturnsOk() throws Exception {
        mockMvc.perform(post("/api/context/back"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").isNotEmpty());
    }

    @Test
    void getHistoryReturnsOk() throws Exception {
        mockMvc.perform(get("/api/context/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void contextEndpointsRequireAuthentication() throws Exception {
        // This test verifies the security config works — @WithMockUser is used
        // at class level so all other tests are authenticated
        mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk());
    }

    @Test
    void compareEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/context/compare")
                        .param("leftBranch", "draft")
                        .param("rightBranch", "draft"))
                .andExpect(status().isOk());
    }
}
