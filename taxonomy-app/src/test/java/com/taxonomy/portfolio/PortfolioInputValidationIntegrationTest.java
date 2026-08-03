package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.LifecycleStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.SolutionType;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProductCatalogService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.SolutionPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PortfolioInputValidationIntegrationTest {

    @Autowired private ProjectPortfolioService projectService;
    @Autowired private ProductCatalogService productService;
    @Autowired private SolutionPortfolioService solutionService;

    @Test
    void rejectsUnregisteredProjectCurrencyAndUnpairedBudget() {
        WorkspaceContext context = context("budget-validation");

        assertThatThrownBy(() -> projectService.createProject(
                projectRequest(new BigDecimal("100.00"), "ZZZ"),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("ISO 4217");
        assertThatThrownBy(() -> projectService.createProject(
                projectRequest(new BigDecimal("100.00"), null),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("must be supplied together");
    }

    @Test
    void rejectsFutureProductVerificationAndNegativeSolutionCost() {
        WorkspaceContext context = context("catalog-validation");

        assertThatThrownBy(() -> productService.createProduct(
                new CreateProductRequest(
                        "PRD-" + shortId(),
                        "Example Manufacturer",
                        null,
                        "Example Product",
                        "1.0",
                        ProductStatus.CANDIDATE,
                        null,
                        null,
                        OperatingModel.SAAS,
                        null,
                        null,
                        null,
                        new BigDecimal("10.00"),
                        "EUR",
                        "per month",
                        "Vendor documentation",
                        Instant.now().plusSeconds(600)),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("five minutes");

        assertThatThrownBy(() -> solutionService.createSolution(
                new CreateSolutionRequest(
                        "SOL-" + shortId(),
                        "Example solution",
                        null,
                        SolutionType.SERVICE,
                        OperatingModel.SAAS,
                        LifecycleStatus.PLANNED,
                        1,
                        null,
                        new BigDecimal("-1.00"),
                        "EUR",
                        null,
                        30,
                        Map.of()),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void boundsRequirementSourceFragmentListsBeforeSerialization() {
        WorkspaceContext context = context("source-validation");
        var project = projectService.createProject(
                projectRequest(null, null), context.username(), context);
        SourceReference source = new SourceReference(
                1L,
                1L,
                Collections.nCopies(1_001, 1L),
                "section",
                1,
                "source text");

        assertThatThrownBy(() -> projectService.createRequirement(
                project.id(),
                new CreateRequirementRequest(
                        "REQ-001",
                        "Sourced requirement",
                        "Requirement text",
                        RequirementStatus.DRAFT,
                        50,
                        Criticality.MEDIUM,
                        RequirementType.FUNCTIONAL,
                        ReviewStatus.PROPOSED,
                        context.username(),
                        null,
                        source),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("more than 1000 entries");
    }

    private static CreateProjectRequest projectRequest(BigDecimal amount, String currency) {
        return new CreateProjectRequest(
                "P-" + shortId(),
                "Validation project",
                null,
                ProjectStatus.ACTIVE,
                null,
                null,
                amount,
                currency);
    }

    private static WorkspaceContext context(String username) {
        return new WorkspaceContext(username, "ws-" + username + "-" + shortId(), "draft");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
