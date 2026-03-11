package com.nato.taxonomy.dsl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the DSL API endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DslApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportCurrentArchitectureReturnsText() throws Exception {
        mockMvc.perform(get("/api/dsl/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    void exportWithCustomNamespace() throws Exception {
        mockMvc.perform(get("/api/dsl/export").param("namespace", "test.namespace"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("namespace \"test.namespace\"")));
    }

    @Test
    void parseValidDslReturnsJsonResult() throws Exception {
        String dsl = """
                element CP-1001 type Capability
                  title "Test"
                
                relation CP-1001 REALIZES BP-1040
                  status accepted
                """;

        mockMvc.perform(post("/api/dsl/parse")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elements").value(1))
                .andExpect(jsonPath("$.relations").value(1));
    }

    @Test
    void parseEmptyDslReturnsValidResult() throws Exception {
        mockMvc.perform(post("/api/dsl/parse")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.elements").value(0));
    }

    @Test
    void validateValidDslReturnsNoErrors() throws Exception {
        String dsl = """
                element CP-1001 type Capability
                  title "Test"
                """;

        mockMvc.perform(post("/api/dsl/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void validateDslWithDuplicateIdsReturnsErrors() throws Exception {
        String dsl = """
                element CP-1001 type Capability
                  title "First"
                
                element CP-1001 type Capability
                  title "Duplicate"
                """;

        mockMvc.perform(post("/api/dsl/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("Duplicate")));
    }

    @Test
    void listHypothesesReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/dsl/hypotheses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void acceptNonExistentHypothesisReturns404() throws Exception {
        mockMvc.perform(post("/api/dsl/hypotheses/99999/accept"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectNonExistentHypothesisReturns404() throws Exception {
        mockMvc.perform(post("/api/dsl/hypotheses/99999/reject"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listDocumentsReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/dsl/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
