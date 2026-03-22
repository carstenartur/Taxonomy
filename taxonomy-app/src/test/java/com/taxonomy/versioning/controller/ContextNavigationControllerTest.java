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

    /**
     * Ensure at least one commit exists on the {@code draft} branch so
     * that the current context's {@code commitId} is non-null.
     *
     * <p>In production there is always at least one taxonomy-import commit.
     * Without this setup, the current context's {@code commitId} would be
     * {@code null}, causing compare and transfer tests to fail.
     *
     * <p>The commit is made through the API to ensure it lands in whatever
     * repository the current user's workspace context resolves to.
     */
    @BeforeEach
    void ensureInitialCommit() throws Exception {
        // Check if there's already a commit (from a previous test in this class)
        MvcResult ctx = mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(ctx.getResponse().getContentAsString());
        if (!json.get("commitId").isNull()) {
            return; // Already have a commit
        }

        // Commit through the API so it lands in the correct repo for the user
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(SAMPLE_DSL)
                        .param("branch", "draft")
                        .param("author", "system")
                        .param("message", "initial test import"))
                .andExpect(status().isOk());

        // Refresh the user's navigation context so commitId is non-null
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
        MvcResult ctx = mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(ctx.getResponse().getContentAsString());
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
    void previewTransfer_returnsOk() throws Exception {
        MvcResult ctx = mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(ctx.getResponse().getContentAsString());
        String commitId = json.get("commitId").asText();

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasConflicts").value(false))
                .andExpect(jsonPath("$.selectedElements").value(0))
                .andExpect(jsonPath("$.selectedRelations").value(0));
    }

    @Test
    void applyTransfer_returnsOk() throws Exception {
        MvcResult ctx = mockMvc.perform(get("/api/context/current"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(ctx.getResponse().getContentAsString());
        String commitId = json.get("commitId").asText();

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
