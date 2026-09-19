package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.ProjectPortfolioController;
import com.taxonomy.portfolio.dto.PortfolioDtos.ImportRequirementCandidate;
import com.taxonomy.portfolio.dto.PortfolioDtos.ImportRequirementsRequest;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProjectPortfolioControllerLimitTest {

    @Test
    void rejectsTooManyCandidatesBeforeResolvingWorkspaceOrPersisting() {
        ProjectPortfolioService projectService = mock(ProjectPortfolioService.class);
        ProjectRequirementAnalysisService analysisService = mock(ProjectRequirementAnalysisService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        ProjectPortfolioController controller = new ProjectPortfolioController(
                projectService, analysisService, workspaceResolver, 2, 1_000);

        ImportRequirementsRequest request = new ImportRequirementsRequest(
                List.of(candidate("REQ-001", "one"),
                        candidate("REQ-002", "two"),
                        candidate("REQ-003", "three")),
                false,
                null,
                null,
                null);

        assertThatThrownBy(() -> controller.importRequirements(1L, request))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("maximum is 2");
        verifyNoInteractions(projectService, analysisService, workspaceResolver);
    }

    @Test
    void rejectsCombinedRequirementAndSourcePayloadAboveLimit() {
        ProjectPortfolioService projectService = mock(ProjectPortfolioService.class);
        ProjectRequirementAnalysisService analysisService = mock(ProjectRequirementAnalysisService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        ProjectPortfolioController controller = new ProjectPortfolioController(
                projectService, analysisService, workspaceResolver, 10, 5);

        ImportRequirementsRequest request = new ImportRequirementsRequest(
                List.of(candidate("REQ-001", "123456")),
                false,
                null,
                null,
                null);

        assertThatThrownBy(() -> controller.importRequirements(1L, request))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("exceeds 5 characters");
        verifyNoInteractions(projectService, analysisService, workspaceResolver);
    }

    private static ImportRequirementCandidate candidate(String key, String text) {
        return new ImportRequirementCandidate(
                key,
                key + " title",
                text,
                null,
                50,
                null,
                null);
    }
}
