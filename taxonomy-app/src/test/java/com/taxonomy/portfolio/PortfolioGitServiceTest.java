package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.PortablePortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@WithMockUser(roles = "ADMIN")
class PortfolioGitServiceTest {

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private PortfolioGitService portfolioGitService;

    @Autowired
    private PortablePortfolioGitService portablePortfolioGitService;

    @Test
    void fullHistoryRoundTripPreservesAnOlderCurrentVersionAndExactSource() {
        String repository = systemRepositoryService.getPrimaryRepository().getRepositoryId();
        String suffix = java.util.UUID.randomUUID().toString();
        var source = new WorkspaceContext("alice", "source-" + suffix, "draft", repository);
        var target = new WorkspaceContext("bob", "target-" + suffix, "draft", repository);
        var project = projectService.createProject(new CreateProjectRequest("RESTORE", "Restore selection", null, null, null, null, null, null), "alice", source);
        var requirement = projectService.createRequirement(project.id(), new CreateRequirementRequest("REQ-1", "Exact body", "  First body\n",
                null, null, null, null, null, "alice", "Original reason", null), "alice", source);
        var first = requirement.currentVersion();
        projectService.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest("Second body", "Second reason", null), "alice", source);
        projectService.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest("  First body\n", "Select earlier", null), "alice", source);
        String dsl = portfolioGitService.exportPortfolio("alice", source);
        portfolioGitService.materialize(dsl, "bob", target);
        var restoredProject = projectService.listProjects("bob", target).getFirst();
        var restored = projectService.listRequirements(restoredProject.id(), "bob", target).getFirst();
        assertThat(restored.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(restored.currentVersion().text()).isEqualTo("  First body\n");
        assertThat(restored.currentVersion().contentHash()).isEqualTo(first.contentHash());
        assertThat(restored.currentVersion().createdBy()).isEqualTo("alice");
        assertThat(projectService.listRequirementVersions(restoredProject.id(), restored.id(), "bob", target))
                .extracting(com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView::versionNumber).containsExactly(2, 1);
        assertThat(portfolioGitService.materialize(dsl, "bob", target).versionsCreated()).isZero();
    }

    @Test
    void currentOnlyImportKeepsItsActualVersionNumberWithoutCreatingSpuriousHistory() {
        String repository = systemRepositoryService.getPrimaryRepository().getRepositoryId();
        String suffix = java.util.UUID.randomUUID().toString();
        var source = new WorkspaceContext("alice", "current-source-" + suffix, "draft", repository);
        var target = new WorkspaceContext("bob", "current-target-" + suffix, "draft", repository);
        var project = projectService.createProject(new CreateProjectRequest("CURRENT", "Current only", null, null, null, null, null, null), "alice", source);
        var requirement = projectService.createRequirement(project.id(), new CreateRequirementRequest("REQ-1", "Current", "Old body",
                null, null, null, null, null, "alice", null, null), "alice", source);
        projectService.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest("Current body", "Private reason", null), "alice", source);
        portfolioGitService.materialize(portablePortfolioGitService.exportCurrentState("alice", source), "bob", target);
        var restoredProject = projectService.listProjects("bob", target).getFirst();
        var restored = projectService.listRequirements(restoredProject.id(), "bob", target).getFirst();
        assertThat(restored.currentVersion().versionNumber()).isEqualTo(2);
        assertThat(projectService.listRequirementVersions(restoredProject.id(), restored.id(), "bob", target))
                .extracting(com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView::versionNumber).containsExactly(2);
    }

    @Test
    void importedVersionCollisionDoesNotRewriteTheExistingImmutableBody() {
        String repository = systemRepositoryService.getPrimaryRepository().getRepositoryId();
        var scope = new WorkspaceContext("alice", "collision-" + java.util.UUID.randomUUID(), "draft", repository);
        var project = projectService.createProject(new CreateProjectRequest("COLLISION", "Collision", null, null, null, null, null, null), "alice", scope);
        var requirement = projectService.createRequirement(project.id(), new CreateRequirementRequest("REQ-1", "Exact version", "Saved body",
                null, null, null, null, null, "alice", null, null), "alice", scope);
        assertThatThrownBy(() -> projectService.importRequirementVersion(project.id(), requirement.id(),
                new ProjectPortfolioService.ImportedVersion(1, "Different body", null, null, null, "alice", null), "alice", scope))
                .isInstanceOf(com.taxonomy.portfolio.service.PortfolioException.class).hasMessageContaining("Immutable requirement version 1");
        assertThat(projectService.getRequirement(project.id(), requirement.id(), "alice", scope).currentVersion().text()).isEqualTo("Saved body");
    }

    @Test
    void currentStateExportsCanonicalPortfolioWithoutHistoryOrSourceMutation() {
        String repositoryId = systemRepositoryService.getPrimaryRepository().getRepositoryId();
        var source = new WorkspaceContext("alice", "stand-source", "draft", repositoryId);
        var project = projectService.createProject(new CreateProjectRequest(
                "P-STAND", "Current portfolio", null, null, null, null, null, null), "alice", source);
        var requirement = projectService.createRequirement(project.id(), new CreateRequirementRequest(
                "REQ-STAND", "Current requirement", "Private superseded requirement",
                null, 80, null, null, null, "alice", "Initial version", null), "alice", source);
        projectService.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest(
                "Selected current requirement", "Private adoption reason", null), "alice", source);
        String original = portfolioGitService.exportPortfolio("alice", source);

        String current = portablePortfolioGitService.exportCurrentState("alice", source);

        assertThat(current)
                .contains("projectRequirement P-STAND REQ-STAND", "currentVersionNumber: 2",
                        "requirementVersion P-STAND REQ-STAND 2", "requirement P-STAND__REQ-STAND",
                        "Selected current requirement")
                .doesNotContain("requirementVersion P-STAND REQ-STAND 1", "Private superseded requirement",
                        "Private adoption reason", "originalText:", "changeReason:", "currentVersionId:");
        assertThat(portfolioGitService.exportPortfolio("alice", source)).isEqualTo(original);
    }

    @Test
    void currentStateFollowsAnOlderSavedVersionWithoutChangingTheFullHistory() {
        String repositoryId = systemRepositoryService.getPrimaryRepository().getRepositoryId();
        var source = new WorkspaceContext("alice", "older-current-source", "draft", repositoryId);
        var project = projectService.createProject(new CreateProjectRequest(
                "P-OLDER", "Saved current portfolio", null, null, null, null, null, null), "alice", source);
        var requirement = projectService.createRequirement(project.id(), new CreateRequirementRequest(
                "REQ-OLDER", "Saved requirement", "Chosen older body", null, 80, null, null, null, "alice", "Initial", null), "alice", source);
        projectService.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest(
                "Later private body", "Private later reason", null), "alice", source);
        projectService.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest(
                "Chosen older body", "Select existing version", null), "alice", source);
        String full = portfolioGitService.exportPortfolio("alice", source);

        assertThat(portablePortfolioGitService.exportCurrentState("alice", source))
                .contains("currentVersionNumber: 1", "requirementVersion P-OLDER REQ-OLDER 1", "Chosen older body")
                .doesNotContain("requirementVersion P-OLDER REQ-OLDER 2", "Later private body", "Private later reason");
        assertThat(portfolioGitService.exportPortfolio("alice", source)).isEqualTo(full)
                .contains("requirementVersion P-OLDER REQ-OLDER 2", "Later private body");
    }

    @Test
    void projectsRequirementsAndAllTextVersionsRoundTripThroughDsl() {
        String repositoryId = systemRepositoryService.getPrimaryRepository().getRepositoryId();
        WorkspaceContext alice = new WorkspaceContext("alice", "git-roundtrip-alice", "draft",
                repositoryId);
        WorkspaceContext bob = new WorkspaceContext("bob", "git-roundtrip-bob", "draft",
                repositoryId);

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
                .contains("currentVersionNumber: 2")
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

        // A target database may already contain a later local version with unrelated
        // primary keys. Re-materializing the Git model must restore the portable
        // version number from the DSL rather than a foreign database ID.
        projectService.addRequirementVersion(
                bobProjects.getFirst().id(), bobRequirements.getFirst().id(),
                new CreateRequirementVersionRequest(
                        "Unpublished Bob draft", "Local experiment", null),
                "bob", bob);
        portfolioGitService.materialize(dsl, "bob", bob);
        var restored = projectService.getRequirement(
                bobProjects.getFirst().id(), bobRequirements.getFirst().id(), "bob", bob);
        assertThat(restored.currentVersion().versionNumber()).isEqualTo(2);
        assertThat(restored.currentVersion().text())
                .isEqualTo("Reviewed secure voice requirement");
    }
}
