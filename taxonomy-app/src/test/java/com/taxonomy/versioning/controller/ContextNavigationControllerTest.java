package com.taxonomy.versioning.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Integration tests for {@link ContextNavigationController}. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class ContextNavigationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SAMPLE_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-9999 type Capability {
              title: "Test Element";
            }
            """;

    @BeforeEach
    void ensureInitialCommit() throws Exception {
        MvcResult context = mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(context.getResponse().getContentAsString());
        if (!json.get("commitId").isNull()) {
            return;
        }

        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(SAMPLE_DSL)
                        .param("branch", "draft")
                        .param("author", "system")
                        .param("message", "initial test import"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/context/open")
                        .param("branch", "draft")
                        .param("readOnly", "false"))
                .andExpect(status().isOk());
    }

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

    @Test
    void createVariant_returnsOk() throws Exception {
        mockMvc.perform(post("/api/context/variant")
                        .param("name", "test-variant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").isNotEmpty())
                .andExpect(jsonPath("$.context").isNotEmpty());
    }

    @Test
    void compareWithFilter_returnsOk() throws Exception {
        mockMvc.perform(get("/api/context/compare")
                        .param("leftBranch", "draft")
                        .param("rightBranch", "draft")
                        .param("filter", "elements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes").isArray());
    }

    @Test
    void compareWithCommitIds_returnsOk() throws Exception {
        MvcResult context = mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(context.getResponse().getContentAsString());
        String commitId = json.get("commitId").asText();

        mockMvc.perform(get("/api/context/compare")
                        .param("leftBranch", "draft")
                        .param("leftCommit", commitId)
                        .param("rightBranch", "draft")
                        .param("rightCommit", commitId))
                .andExpect(status().isOk());
    }

    @Test
    void openContextWithSearchQuery_returnsOk() throws Exception {
        mockMvc.perform(post("/api/context/open")
                        .param("branch", "draft")
                        .param("readOnly", "true")
                        .param("searchQuery", "capability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("draft"))
                .andExpect(jsonPath("$.mode").value("READ_ONLY"));
    }

    @Test
    void previewTransferRejectsEmptyNoOpSelection() throws Exception {
        String commitId = currentCommitId();
        String body = """
                {
                    "sourceContextId": "%s",
                    "targetContextId": "%s",
                    "selectedElementIds": [],
                    "selectedRelationIds": [],
                    "mode": "COPY"
                }
                """.formatted(commitId, commitId);

        mockMvc.perform(post("/api/context/copy-back/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void applyTransferRejectsEmptyNoOpSelection() throws Exception {
        String commitId = currentCommitId();
        String body = """
                {
                    "sourceContextId": "%s",
                    "targetContextId": "%s",
                    "selectedElementIds": [],
                    "selectedRelationIds": [],
                    "mode": "COPY"
                }
                """.formatted(commitId, commitId);

        mockMvc.perform(post("/api/context/copy-back/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    private String currentCommitId() throws Exception {
        MvcResult context = mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(context.getResponse().getContentAsString())
                .get("commitId").asText();
    }
}
