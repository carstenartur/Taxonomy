package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end evidence that requirement identities and versions cannot cross exact tenants. */
@SpringBootTest
@Transactional
class RequirementTenantIsolationTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private ProjectRequirementRepository requirementRepository;

    @Autowired
    private ProjectRequirementVersionRepository versionRepository;

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void identicalRequirementKeysAndContentRemainRepositoryAndBranchLocal() {
        String repositoryA = repository("Requirement tenant A");
        String repositoryB = repository("Requirement tenant B");
        WorkspaceContext aMain = context(repositoryA, null, "main");
        WorkspaceContext aDraft = context(repositoryA, null, "draft");
        WorkspaceContext bMain = context(repositoryB, null, "main");

        ProjectView projectAMain = createProject(aMain);
        ProjectView projectADraft = createProject(aDraft);
        ProjectView projectBMain = createProject(bMain);

        RequirementView requirementAMain = createRequirement(projectAMain.id(), aMain);
        RequirementView requirementADraft = createRequirement(projectADraft.id(), aDraft);
        RequirementView requirementBMain = createRequirement(projectBMain.id(), bMain);

        assertThat(requirementAMain.id())
                .isNotEqualTo(requirementADraft.id())
                .isNotEqualTo(requirementBMain.id());
        assertThat(requirementAMain.currentVersion().id())
                .isNotEqualTo(requirementADraft.currentVersion().id())
                .isNotEqualTo(requirementBMain.currentVersion().id());

        String scopeAMain = PortfolioScope.key("architect", aMain);
        String scopeADraft = PortfolioScope.key("architect", aDraft);
        String scopeBMain = PortfolioScope.key("architect", bMain);

        // Force the first scoped version lookup down the exact-query path. The
        // following wrong-scope lookup then exercises the already-loaded fast path.
        entityManager.flush();
        entityManager.clear();

        assertThat(requirementRepository.findByIdAndProjectIdAndScopeKey(
                requirementAMain.id(), projectAMain.id(), scopeAMain)).isPresent();
        assertThat(requirementRepository.findByIdAndProjectIdAndScopeKey(
                requirementAMain.id(), projectAMain.id(), scopeADraft)).isEmpty();
        assertThat(requirementRepository.findByIdAndProjectIdAndScopeKey(
                requirementAMain.id(), projectAMain.id(), scopeBMain)).isEmpty();
        assertThat(versionRepository.findByIdAndRequirementIdAndScopeKey(
                requirementAMain.currentVersion().id(), requirementAMain.id(), scopeAMain))
                .isPresent();
        assertThat(versionRepository.findByIdAndRequirementIdAndScopeKey(
                requirementAMain.currentVersion().id(), requirementAMain.id(), scopeADraft))
                .isEmpty();

        assertThatThrownBy(() -> projectService.getRequirement(
                projectADraft.id(), requirementAMain.id(), "architect", aDraft))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("was not found in project");
        assertThatThrownBy(() -> projectService.addRequirementVersion(
                projectBMain.id(),
                requirementAMain.id(),
                new CreateRequirementVersionRequest(
                        "Foreign update must fail", "guessed identifier", null),
                "architect",
                bMain))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("was not found in project");

        var secondVersion = projectService.addRequirementVersion(
                projectAMain.id(),
                requirementAMain.id(),
                new CreateRequirementVersionRequest(
                        "Shared requirement text, revision two", "tenant-local revision", null),
                "architect",
                aMain);
        assertThat(secondVersion.versionNumber()).isEqualTo(2);
        assertThat(projectService.listRequirementVersions(
                projectAMain.id(), requirementAMain.id(), "architect", aMain))
                .hasSize(2);
        assertThat(projectService.listRequirementVersions(
                projectADraft.id(), requirementADraft.id(), "architect", aDraft))
                .hasSize(1);
    }

    private ProjectView createProject(WorkspaceContext context) {
        return projectService.createProject(
                new CreateProjectRequest(
                        "SAME-PROJECT",
                        "Exact tenant project",
                        "Repository/branch isolation fixture",
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                "architect",
                context);
    }

    private RequirementView createRequirement(Long projectId, WorkspaceContext context) {
        return projectService.createRequirement(
                projectId,
                new CreateRequirementRequest(
                        "SAME-REQUIREMENT",
                        "Exact tenant requirement",
                        "Shared requirement text",
                        RequirementStatus.APPROVED,
                        50,
                        Criticality.HIGH,
                        RequirementType.FUNCTIONAL,
                        ReviewStatus.CONFIRMED,
                        "architect",
                        "initial tenant-local version",
                        null),
                "architect",
                context);
    }

    private String repository(String displayName) {
        String discriminator = UUID.randomUUID().toString();
        return systemRepositoryService.createCentralRepository(
                displayName,
                "requirement-tenant-" + discriminator,
                "Requirement isolation fixture",
                RepositoryVisibility.PRIVATE,
                "architect",
                "main").getRepositoryId();
    }

    private static WorkspaceContext context(
            String repositoryId,
            String workspaceId,
            String branch) {
        return new WorkspaceContext("architect", workspaceId, branch, repositoryId);
    }
}
