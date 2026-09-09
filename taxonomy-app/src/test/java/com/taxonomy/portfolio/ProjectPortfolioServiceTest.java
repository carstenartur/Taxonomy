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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        assertThatThrownBy(() -> projectService.listApprovedRequirements(
                project.id(), "bob-isolated", bob, 0, 10))
                .isInstanceOfSatisfying(PortfolioException.class,
                        failure -> assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
    }

    @Test
    void approvedPagesFilterBeforePagingAndKeepStableRequirementOrder() {
        WorkspaceContext context = context("approved-pages");
        ProjectView project = createProject(context, "P-" + shortId());
        createRequirement(project.id(), context, "REQ-000", "Unapproved", RequirementStatus.DRAFT);
        // Insert out of key order so paging cannot accidentally rely on creation order or IDs alone.
        var last = createRequirement(project.id(), context, "REQ-C", "Approved C");
        var first = createRequirement(project.id(), context, "REQ-A", "Approved A");
        var middle = createRequirement(project.id(), context, "REQ-B", "Approved B");
        ProjectView otherProject = createProject(context, "P-" + shortId());
        createRequirement(otherProject.id(), context, "REQ-001", "Other project's approved requirement");

        var page = projectService.listApprovedRequirements(project.id(), context.username(), context, 0, 2);
        assertThat(page.requirements()).extracting(RequirementView::id).containsExactly(first.id(), middle.id());
        assertThat(page.requirements()).extracting(RequirementView::status).containsOnly(RequirementStatus.APPROVED);
        assertThat(page.requirements()).extracting(value -> value.currentVersion().text())
                .containsExactly("Approved A", "Approved B");
        assertThat(page.hasNext()).isTrue();

        var lastPage = projectService.listApprovedRequirements(project.id(), context.username(), context, 1, 2);
        assertThat(lastPage.requirements()).extracting(RequirementView::id).containsExactly(last.id());
        assertThat(lastPage.hasNext()).isFalse();
        var emptyPage = projectService.listApprovedRequirements(project.id(), context.username(), context, 2, 2);
        assertThat(emptyPage.requirements()).isEmpty();
        assertThat(emptyPage.hasNext()).isFalse();
        assertThat(projectService.listApprovedRequirements(project.id(), context.username(), context, 0, 2)
                .requirements()).extracting(RequirementView::id).containsExactly(first.id(), middle.id());
        assertThat(projectService.listApprovedRequirements(project.id(), context.username(), context, 100000, 100)
                .requirements()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"-1, 1", "100001, 1", "2147483647, 1", "0, -1", "0, 0", "0, 101", "0, 2147483647"})
    void approvedPagesRejectOutOfBoundsRequests(int page, int pageSize) {
        WorkspaceContext context = context("page-bounds");
        ProjectView project = createProject(context, "P-" + shortId());

        assertThatThrownBy(() -> projectService.listApprovedRequirements(
                project.id(), context.username(), context, page, pageSize))
                .isInstanceOfSatisfying(PortfolioException.class,
                        failure -> assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.VALIDATION))
                .hasMessage("Requirement page is outside supported bounds");
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
        return createRequirement(projectId, context, key, text, RequirementStatus.APPROVED);
    }

    private RequirementView createRequirement(Long projectId,
                                              WorkspaceContext context,
                                              String key,
                                              String text,
                                              RequirementStatus status) {
        return projectService.createRequirement(
                projectId,
                new CreateRequirementRequest(
                        key,
                        key + " title",
                        text,
                        status,
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
