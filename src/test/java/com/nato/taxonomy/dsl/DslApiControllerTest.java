package com.nato.taxonomy.dsl;

import com.nato.taxonomy.model.HypothesisStatus;
import com.nato.taxonomy.model.RelationHypothesis;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.repository.RelationHypothesisRepository;
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

    @Autowired
    private RelationHypothesisRepository hypothesisRepository;

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
    void listHypothesesReturnsArray() throws Exception {
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

    // --- New endpoints ---

    @Test
    void getCurrentArchitectureReturnsStructuredJson() throws Exception {
        mockMvc.perform(get("/api/dsl/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elements").isArray())
                .andExpect(jsonPath("$.relations").isArray())
                .andExpect(jsonPath("$.requirements").isArray())
                .andExpect(jsonPath("$.mappings").isArray())
                .andExpect(jsonPath("$.views").isArray())
                .andExpect(jsonPath("$.evidence").isArray());
    }

    @Test
    void materializeDslWithInvalidContentReturnsBadRequest() throws Exception {
        String dsl = """
                element CP-1001 type Capability
                  title "First"
                
                element CP-1001 type Capability
                  title "Duplicate"
                """;

        mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void materializeValidDslReturnsSuccess() throws Exception {
        // Use real taxonomy node codes that exist in the test database
        String dsl = """
                meta
                  language "taxdsl"
                  version "1.0"
                  namespace "test"
                
                element CP-1 type Capability
                  title "Test Capability"
                
                element BP-1 type Process
                  title "Test Process"
                """;

        mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.documentId").isNotEmpty());
    }

    @Test
    void acceptHypothesisCreatesRelation() throws Exception {
        // Use root-level codes with a relation type not preloaded from CSV
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("UA");
        h.setTargetNodeId("BP");
        h.setRelationType(RelationType.REALIZES);
        h.setStatus(HypothesisStatus.PROVISIONAL);
        h.setConfidence(0.85);
        h.setAnalysisSessionId("test-session-accept");
        RelationHypothesis saved = hypothesisRepository.save(h);

        mockMvc.perform(post("/api/dsl/hypotheses/" + saved.getId() + "/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.sourceNodeId").value("UA"))
                .andExpect(jsonPath("$.targetNodeId").value("BP"));
    }

    @Test
    void rejectHypothesisUpdatesStatus() throws Exception {
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("BP");
        h.setTargetNodeId("CP");
        h.setRelationType(RelationType.SUPPORTS);
        h.setStatus(HypothesisStatus.PROVISIONAL);
        h.setConfidence(0.50);
        h.setAnalysisSessionId("test-session-2");
        RelationHypothesis saved = hypothesisRepository.save(h);

        mockMvc.perform(post("/api/dsl/hypotheses/" + saved.getId() + "/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void acceptAlreadyAcceptedHypothesisReturnsBadRequest() throws Exception {
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("BP");
        h.setTargetNodeId("CP");
        h.setRelationType(RelationType.DEPENDS_ON);
        h.setStatus(HypothesisStatus.ACCEPTED);
        h.setConfidence(0.60);
        h.setAnalysisSessionId("test-session-3");
        RelationHypothesis saved = hypothesisRepository.save(h);

        mockMvc.perform(post("/api/dsl/hypotheses/" + saved.getId() + "/accept"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void getHypothesisEvidenceReturnsArray() throws Exception {
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("BP");
        h.setTargetNodeId("CP");
        h.setRelationType(RelationType.RELATED_TO);
        h.setStatus(HypothesisStatus.PROVISIONAL);
        h.setConfidence(0.50);
        h.setAnalysisSessionId("test-session-4");
        RelationHypothesis saved = hypothesisRepository.save(h);

        mockMvc.perform(get("/api/dsl/hypotheses/" + saved.getId() + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listHypothesesFilteredByStatus() throws Exception {
        RelationHypothesis h1 = new RelationHypothesis();
        h1.setSourceNodeId("BP");
        h1.setTargetNodeId("CP");
        h1.setRelationType(RelationType.FULFILLS);
        h1.setStatus(HypothesisStatus.PROPOSED);
        h1.setConfidence(0.70);
        h1.setAnalysisSessionId("test-filter");
        hypothesisRepository.save(h1);

        mockMvc.perform(get("/api/dsl/hypotheses").param("status", "PROPOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
