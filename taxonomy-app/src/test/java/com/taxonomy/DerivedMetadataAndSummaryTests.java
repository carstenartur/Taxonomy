package com.taxonomy;

import com.taxonomy.dto.ArchitectureSummary;
import com.taxonomy.model.TaxonomyNode;
import com.taxonomy.repository.TaxonomyNodeRepository;
import com.taxonomy.repository.TaxonomyRelationRepository;
import com.taxonomy.service.ArchitectureSummaryService;
import com.taxonomy.service.DerivedMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for derived graph metadata, architecture summary, and related API endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DerivedMetadataAndSummaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DerivedMetadataService metadataService;

    @Autowired
    private ArchitectureSummaryService summaryService;

    @Autowired
    private TaxonomyNodeRepository nodeRepository;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    @BeforeEach
    void cleanRelations() {
        relationRepository.deleteAll();
    }

    // ── DerivedMetadataService ──────────────────────────────────────

    @Test
    void recomputeAllUpdatesNodes() {
        int updated = metadataService.recomputeAll();
        // With no relations, all nodes should be classified as "isolated"
        assertThat(updated).isGreaterThanOrEqualTo(0);

        TaxonomyNode node = nodeRepository.findAll().get(0);
        assertThat(node.getGraphRole()).isEqualTo("isolated");
        assertThat(node.getIncomingRelationCount()).isZero();
        assertThat(node.getOutgoingRelationCount()).isZero();
    }

    @Test
    void classifyRoleReturnsCorrectRoles() {
        assertThat(DerivedMetadataService.classifyRole(0, 0)).isEqualTo("isolated");
        assertThat(DerivedMetadataService.classifyRole(1, 0)).isEqualTo("leaf");
        assertThat(DerivedMetadataService.classifyRole(0, 1)).isEqualTo("leaf");
        assertThat(DerivedMetadataService.classifyRole(1, 1)).isEqualTo("bridge");
        assertThat(DerivedMetadataService.classifyRole(3, 3)).isEqualTo("hub");
        assertThat(DerivedMetadataService.classifyRole(5, 0)).isEqualTo("hub");
    }

    // ── ArchitectureSummaryService ──────────────────────────────────

    @Test
    void buildSummaryReturnsValidResult() {
        ArchitectureSummary summary = summaryService.buildSummary();

        assertThat(summary).isNotNull();
        assertThat(summary.generatedAt()).isNotNull();
        assertThat(summary.totalNodes()).isGreaterThan(0);
        assertThat(summary.nextSteps()).isNotEmpty();
    }

    @Test
    void summaryNextStepsContainGuidance() {
        ArchitectureSummary summary = summaryService.buildSummary();

        assertThat(summary.nextSteps())
                .anyMatch(step -> step.action() != null && !step.action().isBlank());
        assertThat(summary.nextSteps())
                .anyMatch(step -> step.description() != null && !step.description().isBlank());
    }

    // ── API Endpoints ───────────────────────────────────────────────

    @Test
    void summaryEndpointReturnsJson() throws Exception {
        mockMvc.perform(get("/api/architecture/summary"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.totalNodes").isNumber())
                .andExpect(jsonPath("$.nextSteps").isArray());
    }

    @Test
    void metadataRecomputeEndpointWorks() throws Exception {
        mockMvc.perform(post("/api/architecture/metadata/recompute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedNodes").isNumber());
    }

    @Test
    void nodeMetadataEndpointForUnknownNodeReturns404() throws Exception {
        mockMvc.perform(get("/api/architecture/metadata/NONEXISTENT-CODE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nodeMetadataEndpointForKnownNodeReturnsJson() throws Exception {
        // Find any existing node code
        TaxonomyNode anyNode = nodeRepository.findAll().get(0);

        mockMvc.perform(get("/api/architecture/metadata/" + anyNode.getCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCode").value(anyNode.getCode()))
                .andExpect(jsonPath("$.graphRole").exists());
    }
}
