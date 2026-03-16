package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link HelpController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.ai.gemini.api-key=",
    "spring.ai.openai.api-key=",
    "spring.ai.deepseek.api-key=",
    "spring.ai.qwen.api-key=",
    "spring.ai.llama.api-key=",
    "spring.ai.mistral.api-key="
})
@WithMockUser
class HelpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tocReturnsJsonArray() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()", greaterThan(0)));
    }

    @Test
    void tocContainsUserGuide() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.filename == 'USER_GUIDE')].title").value("User Guide"));
    }

    @Test
    void tocContainsExpectedFields() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").exists())
                .andExpect(jsonPath("$[0].title").exists())
                .andExpect(jsonPath("$[0].icon").exists())
                .andExpect(jsonPath("$[0].audience").exists());
    }

    @Test
    void tocContainsTwelveEntries() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(HelpController.DOCS.size()));
    }

    @Test
    void docRenderingReturnsHtml() throws Exception {
        mockMvc.perform(get("/help/USER_GUIDE").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<div class=\"help-doc-content\">")));
    }

    @Test
    void docRenderingContainsMarkdownContent() throws Exception {
        mockMvc.perform(get("/help/USER_GUIDE").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h")));
    }

    @Test
    void unknownDocReturns404() throws Exception {
        mockMvc.perform(get("/help/NONEXISTENT_DOC").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    @Test
    void pathTraversalReturns400() throws Exception {
        mockMvc.perform(get("/help/..%2F..%2Fetc%2Fpasswd").accept(MediaType.TEXT_HTML))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void invalidDocNameWithSlashReturns400() throws Exception {
        mockMvc.perform(get("/help/foo%2Fbar"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void imageEndpointReturnsNotFoundForMissing() throws Exception {
        mockMvc.perform(get("/help/images/nonexistent-image.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void imagePathTraversalReturns400() throws Exception {
        // path segment with ".." should be rejected
        mockMvc.perform(get("/help/images/..%2Fsecret.txt"))
                .andExpect(status().is4xxClientError());
    }
}
