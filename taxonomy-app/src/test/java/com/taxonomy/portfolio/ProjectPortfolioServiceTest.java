package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProjectPortfolioServiceTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Test
    void storesThreeRequirementsSeparatelyAndVersionsTextImmutably() {
        WorkspaceContext context = context("alice");
        ProjectView project = createProject(context, "P-" + shortId());

        RequirementView first = createRequirement(project.id(), context, "REQ-001", "Cloud service required");
        createRequirement(project.id(), context, "REQ-002", "Data must stay in Germany");
        createRequirement(project.id(), context, "REQ-003", "Offline operation is required");

        List<RequirementView> requirements = projectService.listRequirements(
                project.id(), "alice", context);
        assertThat(requirements)
                .extracting(RequirementView::requirementKey)
                .containsExactly("REQ-001", "REQ-002", "REQ-003");
        assertThat(requirements)
                .extracting(requirement -> requirement.currentVersion().text())
                .containsExactly(
                        "Cloud service required",
                        "Data must stay in Germany",
                        "Offline operation is required");

        var secondVersion = projectService.addRequirementVersion(
                project.id(),
                first.id(),
                new CreateRequirementVersionRequest(
                        "Cloud service required with EU-only storage",
                        "Clarified data residency",
                        null),
                "alice",
                context);
        assertThat(secondVersion.versionNumber()).isEqualTo(2);

        var history = projectService.listRequirementVersions(
                project.id(), first.id(), "alice", context);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).text()).isEqualTo("Cloud service required with EU-only storage");
        assertThat(history.get(1).text()).isEqualTo("Cloud service required");

        // Re-submitting an identical text is idempotent and does not create version 3.
        var duplicate = projectService.addRequirementVersion(
                project.id(),
                first.id(),
                new CreateRequirementVersionRequest(
                        "Cloud service required with EU-only storage", "duplicate", null),
                "alice",
                context);
        assertThat(duplicate.id()).isEqualTo(secondVersion.id());
        assertThat(projectService.listRequirementVersions(
                project.id(), first.id(), "alice", context)).hasSize(2);
    }

    @Test
    void preservesPerRequirementSourceProvenance() {
        WorkspaceContext context = context("provenance-user");
        ProjectView project = createProject(context, "P-" + shortId());
        SourceReference source = new SourceReference(
                11L, 12L, List.of(101L, 102L), "§ 4.2", 17, "Original paragraph");

        RequirementView requirement = projectService.createRequirement(
                project.id(),
                new CreateRequirementRequest(
                        "REQ-SOURCE",
                        "Sourced requirement",
                        "The service shall retain an audit trail.",
                        RequirementStatus.APPROVED,
                        80,
                        Criticality.HIGH,
                        RequirementType.LEGAL,
                        ReviewStatus.CONFIRMED,
                        "provenance-user",
                        "Imported from regulation",
                        source),
                "provenance-user",
                context);

        assertThat(requirement.currentVersion().source()).isEqualTo(source);
        assertThat(requirement.currentVersion().contentHash()).hasSize(64);
    }

    @Test
    void preventsCrossWorkspaceProjectAccess() {
        WorkspaceContext alice = context("alice-isolated");
        WorkspaceContext bob = context("bob-isolated");
        ProjectView project = createProject(alice, "P-" + shortId());

        assertThatThrownBy(() -> projectService.getProject(project.id(), "bob-isolated", bob))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Project not found");
        assertThat(projectService.listProjects("bob-isolated", bob)).isEmpty();
    }

    private ProjectView createProject(WorkspaceContext context, String key) {
        return projectService.createProject(
                new CreateProjectRequest(
                        key,
                        "Portfolio test project",
                        "Project used by integration tests",
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                context.username(),
                context);
    }

    private RequirementView createRequirement(Long projectId,
                                              WorkspaceContext context,
                                              String key,
                                              String text) {
        return projectService.createRequirement(
                projectId,
                new CreateRequirementRequest(
                        key,
                        key + " title",
                        text,
                        RequirementStatus.APPROVED,
                        50,
                        Criticality.MEDIUM,
                        RequirementType.FUNCTIONAL,
                        ReviewStatus.CONFIRMED,
                        context.username(),
                        "Initial version",
                        null),
                context.username(),
                context);
    }

    private WorkspaceContext context(String username) {
        return new WorkspaceContext(username, "ws-" + username + "-" + shortId(), "draft");
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
