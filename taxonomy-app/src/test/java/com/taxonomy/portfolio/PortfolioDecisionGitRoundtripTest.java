package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.AddProjectSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertProductCandidateRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.LifecycleStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectSolutionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.SolutionType;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProductCatalogService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.SolutionPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@WithMockUser(roles = "ADMIN")
class PortfolioDecisionGitRoundtripTest {

    @Autowired private ProjectPortfolioService projectService;
    @Autowired private SolutionPortfolioService solutionService;
    @Autowired private ProductCatalogService productService;
    @Autowired private PortfolioGitService portfolioGitService;

    @Test
    void solutionProjectDecisionAndSelectedProductRoundTripAcrossWorkspaces() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        WorkspaceContext alice = new WorkspaceContext("alice", "decision-alice-" + suffix, "draft");
        WorkspaceContext bob = new WorkspaceContext("bob", "decision-bob-" + suffix, "draft");
        String projectKey = "P-DEC-" + suffix;
        String solutionKey = "SOL-DEC-" + suffix;
        String productKey = "PRD-DEC-" + suffix;

        var project = projectService.createProject(
                new CreateProjectRequest(
                        projectKey,
                        "Joint decision architecture",
                        "Portable solution and product decision",
                        null, null, null, null, null),
                "alice", alice);
        projectService.createRequirement(
                project.id(),
                new CreateRequirementRequest(
                        "REQ-001",
                        "Portable collaboration",
                        "The architecture decision must be shareable through Git.\n"
                                + "A second line verifies multiline requirement preservation.",
                        null, 80, null, null, ReviewStatus.CONFIRMED,
                        "alice", "Initial requirement", null),
                "alice", alice);

        var solution = solutionService.createSolution(
                new CreateSolutionRequest(
                        solutionKey,
                        "Shared collaboration service",
                        "Reusable implementation decision",
                        SolutionType.SERVICE,
                        OperatingModel.HYBRID,
                        LifecycleStatus.ACTIVE,
                        4,
                        "Architecture Office",
                        null,
                        null,
                        "Reviewed for cross-project reuse",
                        30,
                        Map.of("decision-owner", "architecture-board")),
                "alice", alice);
        var projectSolution = solutionService.addProjectSolution(
                project.id(),
                new AddProjectSolutionRequest(
                        solution.id(),
                        ProjectSolutionStatus.SELECTED,
                        ActionStatus.REUSE,
                        90,
                        "One shared implementation is selected"),
                "alice", alice);

        var product = productService.createProduct(
                new CreateProductRequest(
                        productKey,
                        "Example Vendor",
                        "Collaboration Family",
                        "Collaboration Product",
                        "2026.1",
                        ProductStatus.ACTIVE,
                        null,
                        "Subscription",
                        OperatingModel.HYBRID,
                        "Linux; Kubernetes",
                        "Encryption and audit logging",
                        "Reviewed controls",
                        null,
                        null,
                        null,
                        "Vendor documentation fixture for Git roundtrip",
                        Instant.parse("2026-08-02T18:00:00Z")),
                "alice", alice);
        productService.upsertCandidate(
                project.id(),
                projectSolution.id(),
                new UpsertProductCandidateRequest(
                        product.id(),
                        92,
                        null,
                        "Strong functional fit",
                        "Commercial dependency",
                        "Final price remains open",
                        0.92,
                        ReviewStatus.CONFIRMED,
                        ProductSelectionStatus.SELECTED),
                "alice", alice);

        String dsl = portfolioGitService.exportPortfolio("alice", alice);

        assertThat(dsl)
                .contains("solutionDefinition " + solutionKey)
                .contains("projectSolutionDecision " + projectKey + " " + solutionKey)
                .contains("productDefinition " + productKey)
                .contains("solutionProductDecision " + projectKey + " "
                        + solutionKey + " " + productKey)
                .contains("selectionStatus: \"SELECTED\"")
                .contains("A second line verifies multiline requirement preservation.");

        PortfolioGitService.MaterializeResult result =
                portfolioGitService.materialize(dsl, "bob", bob);

        assertThat(result.warnings()).isEmpty();
        var bobProjects = projectService.listProjects("bob", bob);
        assertThat(bobProjects).extracting("projectKey").contains(projectKey);
        var bobSolutions = solutionService.listSolutions("bob", bob);
        assertThat(bobSolutions).extracting("solutionKey").contains(solutionKey);
        var bobProducts = productService.listProducts("bob", bob);
        assertThat(bobProducts).extracting("productKey").contains(productKey);

        var bobProject = bobProjects.stream()
                .filter(candidate -> projectKey.equals(candidate.projectKey()))
                .findFirst().orElseThrow();
        var bobProjectSolutions = solutionService.listProjectSolutions(
                bobProject.id(), "bob", bob);
        assertThat(bobProjectSolutions).singleElement().satisfies(decision -> {
            assertThat(decision.solution().solutionKey()).isEqualTo(solutionKey);
            assertThat(decision.status()).isEqualTo(ProjectSolutionStatus.SELECTED);
            assertThat(decision.actionStatus()).isEqualTo(ActionStatus.REUSE);
            assertThat(decision.productCandidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.product().productKey()).isEqualTo(productKey);
                assertThat(candidate.reviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
                assertThat(candidate.selectionStatus()).isEqualTo(ProductSelectionStatus.SELECTED);
            });
        });
    }
}
