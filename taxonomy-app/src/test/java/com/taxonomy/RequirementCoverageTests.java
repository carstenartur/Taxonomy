package com.taxonomy;

import com.taxonomy.dto.CoverageStatistics;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.relations.repository.RequirementCoverageRepository;
import com.taxonomy.relations.service.RequirementCoverageService;
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
import org.springframework.security.test.context.support.WithMockUser;


/**
 * Tests for the Requirement Coverage feature:
 * RequirementCoverageService and CoverageApiController.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "coverage-contract-admin", roles = {"USER", "ARCHITECT", "ADMIN"})
class RequirementCoverageTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private RequirementCoverageService coverageService;
    @Autowired private RequirementCoverageRepository coverageRepository;

    @Autowired private WorkspaceManager workspaces;
    @Autowired private com.taxonomy.workspace.storage.DslGitRepositoryFactory git;
    private RepositoryContext context;
    private String commit;

    @BeforeEach
    void clean() throws Exception {
        String actor = "coverage-contract-admin";
        var workspace = workspaces.findUserWorkspace(actor);
        if (workspace == null) workspace = workspaces.createWorkspace(actor, "Coverage", "Coverage recording tests");
        workspaces.switchWorkspace(actor, workspace.getWorkspaceId());
        workspace = workspaces.provisionWorkspaceRepository(actor, workspace.getWorkspaceId());
        context = RepositoryContext.workspace(workspace.getSourceRepositoryId(), workspace.getWorkspaceId(), workspace.getCurrentBranch(), actor);
        commit = git.resolveRepository(context).commitDsl(context.branch(), "# coverage", context.username(), "Coverage fixture");
        coverageRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Service tests
    // -------------------------------------------------------------------------

    @Test
    void recordingCoverageStoresEntriesAboveThreshold() {
        Map<String, Double> scores = Map.of("CP-1010", 85d, "BP-1481", 72d, "BP-1486", 30d);
        coverageService.analyzeCoverage(context, commit, scores, "REQ-001", "Some requirement text", 50);

        var forCp = coverageService.getCoverageForNode(context, commit, "CP-1010");
        var forBp = coverageService.getCoverageForNode(context, commit, "BP-1481");
        var forBpBelow = coverageService.getCoverageForNode(context, commit, "BP-1486");

        assertThat(forCp).hasSize(1);
        assertThat(forCp.get(0).getScore()).isEqualTo(85);
        assertThat(forBp).hasSize(1);
        assertThat(forBpBelow).isEmpty(); // below threshold
    }

    @Test
    void recordingCoverageReplacesExistingEntries() {
        coverageService.analyzeCoverage(context, commit, Map.of("CP-1010", 80d), "REQ-001", "text", 50);
        coverageService.analyzeCoverage(context, commit, Map.of("CP-1010", 90d), "REQ-001", "text", 50);

        var entries = coverageService.getCoverageForNode(context, commit, "CP-1010");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getScore()).isEqualTo(90);
    }

    @Test
    void getCoverageForRequirementReturnsAllNodes() {
        coverageService.analyzeCoverage(context, commit, Map.of("CP-1010", 80d, "BP-1481", 70d), "REQ-002", "another req", 50);
        var entries = coverageService.getCoverageForRequirement(context, commit, "REQ-002");
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting("requirementId").containsOnly("REQ-002");
    }

    @Test
    void coverageStatisticsReturnsTotalNodes() {
        CoverageStatistics stats = coverageService.getCoverageStatistics(context, commit);
        assertThat(stats.totalNodes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void coverageStatisticsShowsCoveredNodes() {
        coverageService.analyzeCoverage(context, commit, Map.of("CP-1010", 80d, "BP-1481", 70d), "REQ-003", "text", 50);

        CoverageStatistics stats = coverageService.getCoverageStatistics(context, commit);
        assertThat(stats.coveredNodes()).isGreaterThanOrEqualTo(2);
        assertThat(stats.totalRequirements()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void deleteCoverageRemovesAllEntriesForRequirement() {
        coverageService.analyzeCoverage(context, commit, Map.of("CP-1010", 80d), "REQ-DEL", "text", 50);
        assertThat(coverageService.getCoverageForRequirement(context, commit, "REQ-DEL")).isNotEmpty();

        coverageService.deleteCoverageForRequirement(context, commit, "REQ-DEL");
        assertThat(coverageService.getCoverageForRequirement(context, commit, "REQ-DEL")).isEmpty();
    }

    @Test
    void densityMapContainsCoveredNodes() {
        coverageService.analyzeCoverage(context, commit, Map.of("CP-1010", 80d, "BP-1481", 70d), "REQ-004", "text", 50);
        Map<String, Integer> density = coverageService.getRequirementDensityMap(context, commit);
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

        mockMvc.perform(post("/api/coverage/record").param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId())
                        .param("branch", context.branch()).param("commitId", commit)
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
        coverageService.analyzeCoverage(context, commit, Map.of("CP-1010", 80d), "REQ-API-DEL", "text", 50);

        mockMvc.perform(delete("/api/coverage/requirement/REQ-API-DEL").param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId())
                        .param("branch", context.branch()).param("commitId", commit))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/coverage/requirement/REQ-API-DEL").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
