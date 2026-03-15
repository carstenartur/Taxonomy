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
 * Integration tests for {@link AboutController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "USER")
class AboutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aboutEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/about"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product").value("Taxonomy Architecture Analyzer"))
                .andExpect(jsonPath("$.license").value("MIT"))
                .andExpect(jsonPath("$.copyright").value("Copyright 2026 Carsten Hammer"))
                .andExpect(jsonPath("$.sourceUrl").value("https://github.com/carstenartur/Taxonomy"))
                .andExpect(jsonPath("$.apiDocsUrl").value("/swagger-ui.html"))
                .andExpect(jsonPath("$.thirdPartyNoticesUrl").value("/THIRD-PARTY-NOTICES.md"))
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.commit").exists())
                .andExpect(jsonPath("$.branch").exists());
    }

    @Test
    void aboutThirdPartyEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/about/third-party"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));
    }
}
