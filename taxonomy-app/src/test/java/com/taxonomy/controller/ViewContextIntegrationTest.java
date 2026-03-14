package com.taxonomy.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests verifying that ViewContext is present and correct
 * in API responses across all controllers that were enhanced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ViewContextIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void materializeResponseViewContextNotNull() throws Exception {
        String dsl = """
                element CP-1023 type Capability {
                  title: "ViewContext IT";
                }
                """;

        mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewContext").exists())
                .andExpect(jsonPath("$.viewContext.basedOnBranch").isString())
                .andExpect(jsonPath("$.viewContext.projectionStale").isBoolean())
                .andExpect(jsonPath("$.viewContext.indexStale").isBoolean());
    }

    @Test
    void currentArchitectureViewContextHasDraftBranch() throws Exception {
        mockMvc.perform(get("/api/dsl/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewContext.basedOnBranch").value("draft"));
    }

    @Test
    void historyEndpointReturnsViewContextField() throws Exception {
        // Seed a commit on the default "draft" branch (always present)
        String dsl = """
                element CP-1023 type Capability {
                  title: "History Seed";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "draft"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/dsl/history").param("branch", "draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewContext").exists())
                .andExpect(jsonPath("$.viewContext.basedOnBranch").value("draft"))
                .andExpect(jsonPath("$.commits").isArray());
    }

    @Test
    void reportJsonContainsViewContext() throws Exception {
        String requestBody = """
                {
                    "scores": {"CP-1023": 80},
                    "businessText": "Test requirement",
                    "minScore": 50
                }
                """;

        mockMvc.perform(post("/api/report/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewContext").exists())
                .andExpect(jsonPath("$.viewContext.basedOnBranch").value("draft"));
    }

    @Test
    void viewContextProjectionNotStaleAfterMaterialization() throws Exception {
        // After materialization, projection should not be stale
        String dsl = """
                element CP-1023 type Capability {
                  title: "Projection Stale Check";
                }
                """;

        mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewContext.projectionStale").value(false));
    }

    @Test
    void gitStateEndpointStillWorks() throws Exception {
        mockMvc.perform(get("/api/git/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBranch").value("draft"))
                .andExpect(jsonPath("$.projectionStale").isBoolean())
                .andExpect(jsonPath("$.indexStale").isBoolean());
    }
}
