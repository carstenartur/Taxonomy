package com.taxonomy;

import com.taxonomy.dto.CoverageStatistics;
import com.taxonomy.repository.RequirementCoverageRepository;
import com.taxonomy.service.RequirementCoverageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the Requirement Coverage feature:
 * RequirementCoverageService and CoverageApiController.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequirementCoverageTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private RequirementCoverageService coverageService;
    @Autowired private RequirementCoverageRepository coverageRepository;

    @BeforeEach
    void clean() {
        coverageRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Service tests
    // -------------------------------------------------------------------------

    @Test
    void recordingCoverageStoresEntriesAboveThreshold() {
        Map<String, Integer> scores = Map.of("CP-1010", 85, "BP-1481", 72, "BP-1486", 30);
        coverageService.analyzeCoverage(scores, "REQ-001", "Some requirement text", 50);

        var forCp1 = coverageService.getCoverageForNode("CP-1010");
        var forBp = coverageService.getCoverageForNode("BP-1481");
        var forBp5 = coverageService.getCoverageForNode("BP-1486");

        assertThat(forCp1).hasSize(1);
        assertThat(forCp1.get(0).getScore()).isEqualTo(85);
        assertThat(forBp).hasSize(1);
        assertThat(forBp5).isEmpty(); // below threshold
    }

    @Test
    void recordingCoverageReplacesExistingEntries() {
        coverageService.analyzeCoverage(Map.of("CP-1010", 80), "REQ-001", "text", 50);
        coverageService.analyzeCoverage(Map.of("CP-1010", 90), "REQ-001", "text", 50);

        var entries = coverageService.getCoverageForNode("CP-1010");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getScore()).isEqualTo(90);
    }

    @Test
    void getCoverageForRequirementReturnsAllNodes() {
        coverageService.analyzeCoverage(Map.of("CP-1010", 80, "BP-1481", 70), "REQ-002", "another req", 50);
        var entries = coverageService.getCoverageForRequirement("REQ-002");
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting("requirementId").containsOnly("REQ-002");
    }

    @Test
    void coverageStatisticsReturnsTotalNodes() {
        CoverageStatistics stats = coverageService.getCoverageStatistics();
        assertThat(stats.totalNodes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void coverageStatisticsShowsCoveredNodes() {
        coverageService.analyzeCoverage(Map.of("CP-1010", 80, "BP-1481", 70), "REQ-003", "text", 50);

        CoverageStatistics stats = coverageService.getCoverageStatistics();
        assertThat(stats.coveredNodes()).isGreaterThanOrEqualTo(2);
        assertThat(stats.totalRequirements()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void deleteCoverageRemovesAllEntriesForRequirement() {
        coverageService.analyzeCoverage(Map.of("CP-1010", 80), "REQ-DEL", "text", 50);
        assertThat(coverageService.getCoverageForRequirement("REQ-DEL")).isNotEmpty();

        coverageService.deleteCoverageForRequirement("REQ-DEL");
        assertThat(coverageService.getCoverageForRequirement("REQ-DEL")).isEmpty();
    }

    @Test
    void densityMapContainsCoveredNodes() {
        coverageService.analyzeCoverage(Map.of("CP-1010", 80, "BP-1481", 70), "REQ-004", "text", 50);
        Map<String, Integer> density = coverageService.getRequirementDensityMap();
        assertThat(density).containsKey("CP-1010");
        assertThat(density).containsKey("BP-1481");
    }

    // -------------------------------------------------------------------------
    // API endpoint tests
    // -------------------------------------------------------------------------

    @Test
    void statisticsEndpointReturnsJson() throws Exception {
        mockMvc.perform(get("/api/coverage/statistics").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalNodes").exists())
                .andExpect(jsonPath("$.coveredNodes").exists())
                .andExpect(jsonPath("$.uncoveredNodes").exists())
                .andExpect(jsonPath("$.coveragePercentage").isNumber())
                .andExpect(jsonPath("$.totalRequirements").exists());
    }

    @Test
    void densityEndpointReturnsMap() throws Exception {
        mockMvc.perform(get("/api/coverage/density").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap());
    }

    @Test
    void nodeEndpointReturnsEmptyListInitially() throws Exception {
        mockMvc.perform(get("/api/coverage/node/NONEXISTENT-NODE").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void requirementEndpointReturnsEmptyListInitially() throws Exception {
        mockMvc.perform(get("/api/coverage/requirement/REQ-NONE").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void recordEndpointStoresCoverage() throws Exception {
        String body = "{\"requirementId\":\"REQ-API-01\",\"requirementText\":\"api test\","
                + "\"scores\":{\"CP-1010\":80,\"BP-1481\":40},\"minScore\":50}";

        mockMvc.perform(post("/api/coverage/record")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/coverage/requirement/REQ-API-01").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)) // only CP-1010 passes threshold
                .andExpect(jsonPath("$[0].nodeCode").value("CP-1010"));
    }

    @Test
    void deleteEndpointRemovesCoverage() throws Exception {
        coverageService.analyzeCoverage(Map.of("CP-1010", 80), "REQ-API-DEL", "text", 50);

        mockMvc.perform(delete("/api/coverage/requirement/REQ-API-DEL"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/coverage/requirement/REQ-API-DEL").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
