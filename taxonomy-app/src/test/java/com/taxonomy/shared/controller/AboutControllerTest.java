package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void aboutEndpointReturnsBuildAndLegalResourceLinks() throws Exception {
        mockMvc.perform(get("/api/about"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product").value("Taxonomy Architecture Analyzer"))
                .andExpect(jsonPath("$.license").value("MIT"))
                .andExpect(jsonPath("$.copyright").value("Copyright 2026 Carsten Hammer"))
                .andExpect(jsonPath("$.sourceUrl").value("https://github.com/carstenartur/Taxonomy"))
                .andExpect(jsonPath("$.apiDocsUrl").value("/swagger-ui.html"))
                .andExpect(jsonPath("$.projectLicenseUrl").value("/api/about/license"))
                .andExpect(jsonPath("$.noticeUrl").value("/api/about/notice"))
                .andExpect(jsonPath("$.thirdPartyNoticesUrl").value("/api/about/third-party"))
                .andExpect(jsonPath("$.runtimeThirdPartyLicensesUrl").value("/api/about/runtime-licenses"))
                .andExpect(jsonPath("$.sbomJsonUrl").value("/api/about/sbom.json"))
                .andExpect(jsonPath("$.sbomXmlUrl").value("/api/about/sbom.xml"))
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.commit").exists())
                .andExpect(jsonPath("$.branch").exists());
    }

    @Test
    void projectLicenseEndpointReturnsPackagedMitText() throws Exception {
        mockMvc.perform(get("/api/about/license"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("MIT License")));
    }

    @Test
    void noticeEndpointReturnsPackagedProductNotice() throws Exception {
        mockMvc.perform(get("/api/about/notice"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("Taxonomy Architecture Analyzer")));
    }

    @Test
    void curatedThirdPartyEndpointReturnsNotices() throws Exception {
        mockMvc.perform(get("/api/about/third-party"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string(containsString("Third-Party Notices")));
    }

    @Test
    void runtimeLicenseEndpointReturnsBuildGeneratedDependencyReport() throws Exception {
        mockMvc.perform(get("/api/about/runtime-licenses"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("third-party")));
    }
}
