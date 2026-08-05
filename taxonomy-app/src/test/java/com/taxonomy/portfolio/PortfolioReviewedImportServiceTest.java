package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ImportDecision;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportItem;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioReviewedImportService;
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
class PortfolioReviewedImportServiceTest {

    @Autowired
    private PortfolioReviewedImportService reviewedImportService;

    @Autowired
    private ProjectPortfolioService projectService;

    @Test
    void appliesNewRequirementAndNewVersionInOneReviewedImport() {
        WorkspaceContext context = context();
        ProjectView project = project(context);
        var existing = requirement(project.id(), context, "REQ-001", "Original requirement");
        SourceReference source = new SourceReference(
                41L, 42L, List.of(), "§ 5", 7, "Original source paragraph");

        var result = reviewedImportService.persist(
                project.id(),
                List.of(
                        new ReviewedImportItem(
                                ImportDecision.NEW_REQUIREMENT, null,
                                "REQ-002", "New requirement", "New independent text",
                                RequirementType.LEGAL, 70, Criticality.HIGH, source),
                        new ReviewedImportItem(
                                ImportDecision.NEW_VERSION, existing.id(),
                                null, "Existing requirement", "Clarified existing requirement",
                                RequirementType.FUNCTIONAL, 50, Criticality.MEDIUM, source)),
                context.username(), context);

        assertThat(result.newRequirements()).hasSize(1);
        assertThat(result.versionedRequirements()).hasSize(1);
        assertThat(projectService.listRequirements(project.id(), context.username(), context))
                .extracting(view -> view.requirementKey())
                .containsExactly("REQ-001", "REQ-002");
        assertThat(projectService.listRequirementVersions(
                project.id(), existing.id(), context.username(), context))
                .extracting(version -> version.text())
                .containsExactly("Clarified existing requirement", "Original requirement");
    }

    @Test
    void rollsBackEarlierItemsWhenLaterReviewDecisionIsInvalid() {
        WorkspaceContext context = context();
        ProjectView project = project(context);
        requirement(project.id(), context, "REQ-001", "Original requirement");

        assertThatThrownBy(() -> reviewedImportService.persist(
                project.id(),
                List.of(
                        new ReviewedImportItem(
                                ImportDecision.NEW_REQUIREMENT, null,
                                "REQ-002", "Would be rolled back", "Valid text",
                                RequirementType.FUNCTIONAL, 50, Criticality.MEDIUM, null),
                        new ReviewedImportItem(
                                ImportDecision.NEW_VERSION, 999999L,
                                null, "Missing target", "Invalid target text",
                                RequirementType.FUNCTIONAL, 50, Criticality.MEDIUM, null)),
                context.username(), context))
                .isInstanceOf(PortfolioException.class);

        assertThat(projectService.listRequirements(project.id(), context.username(), context))
                .extracting(view -> view.requirementKey())
                .containsExactly("REQ-001");
    }

    @Test
    void rejectsDuplicateNewKeysBeforeTransactionCanPartiallyPersist() {
        WorkspaceContext context = context();
        ProjectView project = project(context);

        assertThatThrownBy(() -> reviewedImportService.persist(
                project.id(),
                List.of(
                        new ReviewedImportItem(
                                ImportDecision.NEW_REQUIREMENT, null,
                                "REQ-DUP", "First", "First text",
                                RequirementType.FUNCTIONAL, 50, Criticality.MEDIUM, null),
                        new ReviewedImportItem(
                                ImportDecision.NEW_REQUIREMENT, null,
                                "req-dup", "Second", "Second text",
                                RequirementType.FUNCTIONAL, 50, Criticality.MEDIUM, null)),
                context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("duplicate requirement key");

        assertThat(projectService.listRequirements(project.id(), context.username(), context))
                .isEmpty();
    }

    private ProjectView project(WorkspaceContext context) {
        return projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(), "Import review", null,
                        ProjectStatus.ACTIVE, null, null, null, null),
                context.username(), context);
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView requirement(
            Long projectId, WorkspaceContext context, String key, String text) {
        return projectService.createRequirement(
                projectId,
                new CreateRequirementRequest(
                        key, key + " title", text, RequirementStatus.DRAFT,
                        50, Criticality.MEDIUM, RequirementType.FUNCTIONAL,
                        ReviewStatus.PROPOSED, context.username(), "Initial", null),
                context.username(), context);
    }

    private WorkspaceContext context() {
        String user = "import-" + shortId();
        return new WorkspaceContext(user, "ws-" + user, "draft");
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
