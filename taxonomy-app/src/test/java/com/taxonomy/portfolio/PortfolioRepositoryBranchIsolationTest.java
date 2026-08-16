package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PortfolioRepositoryBranchIsolationTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private ArchitectureProjectRepository projectRepository;

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @Test
    void identicalBusinessKeysRemainExactTenantAndBranchLocal() {
        String repositoryA = repository("Tenant A");
        String repositoryB = repository("Tenant B");
        WorkspaceContext centralAMain = context(repositoryA, null, "main");
        WorkspaceContext centralADraft = context(repositoryA, null, "draft");
        WorkspaceContext centralBMain = context(repositoryB, null, "main");
        WorkspaceContext repositoryAMain = context(repositoryA, "workspace-1", "main");
        WorkspaceContext repositoryADraft = context(repositoryA, "workspace-1", "draft");
        WorkspaceContext repositoryBMain = context(repositoryB, "workspace-1", "main");
        WorkspaceContext repositoryASecondWorkspace = context(repositoryA, "workspace-2", "main");

        ProjectView centralA = create(centralAMain, "SAME-KEY");
        ProjectView centralDraft = create(centralADraft, "SAME-KEY");
        ProjectView centralB = create(centralBMain, "SAME-KEY");
        ProjectView aMain = create(repositoryAMain, "SAME-KEY");
        ProjectView aDraft = create(repositoryADraft, "SAME-KEY");
        ProjectView bMain = create(repositoryBMain, "SAME-KEY");
        ProjectView aSecondWorkspace = create(repositoryASecondWorkspace, "SAME-KEY");

        assertThat(List.of(centralA.id(), centralDraft.id(), centralB.id(),
                aMain.id(), aDraft.id(), bMain.id(), aSecondWorkspace.id()))
                .doesNotHaveDuplicates();
        assertThat(projectService.listProjects("another-user", centralAMain))
                .extracting(ProjectView::id).containsExactly(centralA.id());
        assertThat(projectService.listProjects("architect", centralADraft))
                .extracting(ProjectView::id).containsExactly(centralDraft.id());
        assertThat(projectService.listProjects("architect", centralBMain))
                .extracting(ProjectView::id).containsExactly(centralB.id());
        assertThat(projectService.listProjects("architect", repositoryAMain))
                .extracting(ProjectView::id).containsExactly(aMain.id());
        assertThat(projectService.listProjects("architect", repositoryADraft))
                .extracting(ProjectView::id).containsExactly(aDraft.id());
        assertThat(projectService.listProjects("architect", repositoryBMain))
                .extracting(ProjectView::id).containsExactly(bMain.id());
        assertThat(projectService.listProjects("architect", repositoryASecondWorkspace))
                .extracting(ProjectView::id).containsExactly(aSecondWorkspace.id());

        assertThatThrownBy(() -> projectService.getProject(
                aMain.id(), "architect", repositoryBMain))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Project not found");

        var stored = projectRepository.findById(aMain.id()).orElseThrow();
        assertThat(stored.getRepositoryId()).isEqualTo(repositoryA);
        assertThat(stored.getWorkspaceScope()).isEqualTo("WORKSPACE:workspace-1");
        assertThat(stored.getBranchName()).isEqualTo("main");
    }

    private ProjectView create(WorkspaceContext context, String projectKey) {
        return projectService.createProject(
                new CreateProjectRequest(
                        projectKey,
                        "Tenant-local project",
                        "Multi-repository isolation evidence",
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                "architect",
                context);
    }

    private String repository(String displayName) {
        String discriminator = UUID.randomUUID().toString();
        return systemRepositoryService.createCentralRepository(
                displayName,
                "tenant-" + discriminator,
                "Portfolio isolation fixture",
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
