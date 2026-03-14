package com.taxonomy.controller;

import com.taxonomy.TaxonomyApplication;
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
@SpringBootTest(classes = TaxonomyApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "gemini.api.key=",
    "openai.api.key=",
    "deepseek.api.key=",
    "qwen.api.key=",
    "llama.api.key=",
    "mistral.api.key="
})
@WithMockUser(roles = "ADMIN")
class HelpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void helpTocReturnsJsonArray() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThan(0)));
    }

    @Test
    void helpTocContainsUserGuide() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.filename == 'USER_GUIDE')].title").value(hasItem("User Guide")));
    }

    @Test
    void helpTocEntriesHaveRequiredFields() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").isString())
                .andExpect(jsonPath("$[0].title").isString())
                .andExpect(jsonPath("$[0].icon").isString())
                .andExpect(jsonPath("$[0].audience").isString());
    }

    @Test
    void helpUserGuideReturnsHtml() throws Exception {
        mockMvc.perform(get("/help/USER_GUIDE").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<h")));
    }

    @Test
    void helpUserGuideResponseWrappedInHelpDocContentDiv() throws Exception {
        mockMvc.perform(get("/help/USER_GUIDE").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("help-doc-content")));
    }

    @Test
    void helpNonExistentDocReturns404() throws Exception {
        mockMvc.perform(get("/help/NONEXISTENT_DOC").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    @Test
    void helpPathTraversalBlockedWithDotDot() throws Exception {
        // Path traversal via %2F..%2F (encoded slashes) should be rejected
        mockMvc.perform(get("/help/..").accept(MediaType.TEXT_HTML))
                .andExpect(status().isBadRequest());
    }

    @Test
    void helpPathTraversalBlockedWithSlash() throws Exception {
        mockMvc.perform(get("/help/foo%2Fbar").accept(MediaType.TEXT_HTML))
                .andExpect(status().isBadRequest());
    }

    @Test
    void helpInternalDocsNotServed() throws Exception {
        // Internal docs should not be listed in DOCS (they are excluded from the list)
        // Attempting to load a doc not in the DOCS list returns 404
        mockMvc.perform(get("/help/internal").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    @Test
    void helpImageNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/help/images/nonexistent-image.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void helpImagePathTraversalBlocked() throws Exception {
        // Path traversal via slashes should be blocked — returns 400 Bad Request
        // (the SAFE_IMAGE_NAME pattern rejects any image name containing slashes)
        mockMvc.perform(get("/help/images/../../etc/passwd"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Either 400 (blocked by input validation) or 404 (not found) is acceptable
                    org.junit.jupiter.api.Assertions.assertTrue(
                            status == 400 || status == 404,
                            "Expected 400 or 404 for path traversal, got " + status);
                });
    }
}
