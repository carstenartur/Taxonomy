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
    @Autowired private com.taxonomy.portfolio.service.PortfolioReportService reports;
    @Autowired private com.taxonomy.portfolio.repository.ProjectSolutionRepository projectSolutions;
    @Autowired private com.taxonomy.portfolio.repository.RequirementSolutionLinkRepository requirementLinks;


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
        var requirement = projectService.createRequirement(
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

        var coverage = new com.taxonomy.portfolio.dto.PortfolioDtos.UpsertTaxonomyCoverageRequest(
                "CP-1010", 80, "Reviewed taxonomy evidence", ReviewStatus.CONFIRMED);
        solutionService.upsertTaxonomyCoverage(solution.id(), coverage, "alice", alice);
        productService.upsertTaxonomyCoverage(product.id(), coverage, "alice", alice);
        var decisionEntity = projectSolutions.findByIdAndProjectId(projectSolution.id(), project.id()).orElseThrow();
        var requirementEntity = projectService.requireRequirement(project.id(), requirement.id(), "alice", alice);
        requirementLinks.save(new com.taxonomy.portfolio.model.RequirementSolutionLink(
                decisionEntity, requirementEntity, "source-snapshot-evidence", 80,
                com.taxonomy.portfolio.model.PortfolioTypes.RequirementSolutionRole.USES,
                ReviewStatus.CONFIRMED, "Reviewed source link", "alice", Instant.now()));

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
        var repeated = portfolioGitService.materialize(dsl, "bob", bob);
        assertThat(repeated.warnings()).isEmpty();
        assertThat(solutionService.listSolutions("bob", bob)).hasSize(1);
        assertThat(productService.listProducts("bob", bob)).hasSize(1);
        assertThat(solutionService.listSolutions("bob", bob).getFirst().taxonomyCoverage())
                .singleElement().satisfies(item -> assertThat(item.coveragePercent()).isEqualTo(80));

        for (var format : com.taxonomy.portfolio.service.PortfolioReportService.Format.values()) {
            var rendered = reports.render(project.id(), null, format, "products", "alice", alice);
            assertThat(rendered.bytes()).isNotEmpty();
            assertThat(rendered.contentType()).isEqualTo(format.contentType());
            if (format != com.taxonomy.portfolio.service.PortfolioReportService.Format.DOCX
                    && format != com.taxonomy.portfolio.service.PortfolioReportService.Format.CSV) {
                assertThat(new String(rendered.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .contains(solutionKey, productKey, "REQ-001");
            }
        }
        var focused = reports.render(project.id(), requirement.id(),
                com.taxonomy.portfolio.service.PortfolioReportService.Format.MARKDOWN,
                "solutions", "alice", alice);
        assertThat(new String(focused.bytes(), java.nio.charset.StandardCharsets.UTF_8)).contains(solutionKey, productKey);
        for (String matrix : new String[]{"solution", "solutions", "requirement-solution", "product", "products", "solution-product"}) {
            assertThat(new String(reports.render(project.id(), null,
                    com.taxonomy.portfolio.service.PortfolioReportService.Format.CSV,
                    matrix, "alice", alice).bytes(), java.nio.charset.StandardCharsets.UTF_8)).startsWith("row");
        }

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
