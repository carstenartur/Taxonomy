package com.nato.taxonomy;

import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.repository.RelationProposalRepository;
import com.nato.taxonomy.repository.TaxonomyRelationRepository;
import com.nato.taxonomy.service.RelationQualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the Relation Quality Dashboard:
 * RelationQualityService and QualityApiController.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RelationQualityTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private RelationQualityService qualityService;
    @Autowired private RelationProposalRepository proposalRepository;
    @Autowired private TaxonomyRelationRepository relationRepository;

    @BeforeEach
    void clean() {
        proposalRepository.deleteAll();
        relationRepository.deleteAll();
    }

    @Test
    void metricsEndpointReturnsJson() throws Exception {
        mockMvc.perform(get("/api/relations/metrics").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProposals").exists());
    }

    @Test
    void metricsContainsAcceptanceRate() throws Exception {
        mockMvc.perform(get("/api/relations/metrics").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptanceRate").isNumber());
    }

    @Test
    void byTypeEndpointReturnsArray() throws Exception {
        mockMvc.perform(get("/api/relations/metrics/by-type").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void byProvenanceEndpointReturnsArray() throws Exception {
        mockMvc.perform(get("/api/relations/metrics/by-provenance").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void topRejectedReturnsEmptyInitially() throws Exception {
        mockMvc.perform(get("/api/relations/metrics/top-rejected").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void topRejectedRespectsLimit() throws Exception {
        mockMvc.perform(get("/api/relations/metrics/top-rejected?limit=3")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void metricsInitiallyShowZeroCounts() throws Exception {
        mockMvc.perform(get("/api/relations/metrics").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProposals").value(0))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.pending").value(0));
    }

    @Test
    void feedbackLoopServiceReturnsNeutralWithNoHistory() {
        double weight = qualityService.acceptanceHistoryWeight("BP", "CP", RelationType.RELATED_TO);
        assertThat(weight).isEqualTo(0.5);
    }
}
