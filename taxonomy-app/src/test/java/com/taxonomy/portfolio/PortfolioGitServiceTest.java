package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@WithMockUser(roles = "ADMIN")
class PortfolioGitServiceTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private PortfolioGitService portfolioGitService;

    @Test
    void projectsRequirementsAndAllTextVersionsRoundTripThroughDsl() {
        WorkspaceContext alice = new WorkspaceContext("alice", "git-roundtrip-alice", "draft");
        WorkspaceContext bob = new WorkspaceContext("bob", "git-roundtrip-bob", "draft");

        var project = projectService.createProject(
                new CreateProjectRequest(
                        "P-GIT-ROUNDTRIP", "Joint architecture", "Collaborative project",
                        null, null, null, null, null),
                "alice", alice);
        var requirement = projectService.createRequirement(
                project.id(),
                new CreateRequirementRequest(
                        "REQ-A-001", "Secure voice", "Initial secure voice requirement",
                        null, 80, null, null, null, "alice", "Initial version", null),
                "alice", alice);
        projectService.addRequirementVersion(
                project.id(), requirement.id(),
                new CreateRequirementVersionRequest(
                        "Reviewed secure voice requirement", "Architecture board review", null),
                "alice", alice);

        String dsl = portfolioGitService.exportPortfolio("alice", alice);

        assertThat(dsl)
                .contains("project P-GIT-ROUNDTRIP")
                .contains("projectRequirement P-GIT-ROUNDTRIP REQ-A-001")
                .contains("requirementVersion P-GIT-ROUNDTRIP REQ-A-001 1")
                .contains("requirementVersion P-GIT-ROUNDTRIP REQ-A-001 2")
                .contains("requirement P-GIT-ROUNDTRIP__REQ-A-001");

        PortfolioGitService.MaterializeResult result =
                portfolioGitService.materialize(dsl, "bob", bob);

        assertThat(result.projectsCreated()).isEqualTo(1);
        assertThat(result.requirementsCreated()).isEqualTo(1);
        assertThat(result.versionsCreated()).isEqualTo(2);
        var bobProjects = projectService.listProjects("bob", bob);
        assertThat(bobProjects).extracting("projectKey")
                .containsExactly("P-GIT-ROUNDTRIP");
        var bobRequirements = projectService.listRequirements(
                bobProjects.getFirst().id(), "bob", bob);
        assertThat(bobRequirements).hasSize(1);
        assertThat(bobRequirements.getFirst().currentVersion().text())
                .isEqualTo("Reviewed secure voice requirement");
    }
}
