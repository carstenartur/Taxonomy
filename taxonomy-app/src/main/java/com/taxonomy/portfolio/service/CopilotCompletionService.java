package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos.ProductView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectSolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SolutionProductCandidateView;
import com.taxonomy.portfolio.dto.PortfolioDtos.TaxonomyCoverageView;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertProductCandidateRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic post-analysis enrichment. It creates proposals only: no solution
 * or product is selected, no procurement is authorized, and no review is confirmed.
 */
@Service
public class CopilotCompletionService {

    private final SolutionPortfolioService solutionService;
    private final ProductCatalogService productService;
    private final int minimumCoverage;
    private final double minimumConfidence;

    public CopilotCompletionService(
            SolutionPortfolioService solutionService,
            ProductCatalogService productService,
            @Value("${taxonomy.ai.product-proposals.minimum-coverage:25}") int minimumCoverage,
            @Value("${taxonomy.ai.product-proposals.minimum-confidence:0.25}")
            double minimumConfidence) {
        this.solutionService = solutionService;
        this.productService = productService;
        this.minimumCoverage = Math.max(0, Math.min(100, minimumCoverage));
        this.minimumConfidence = Math.max(0.0, Math.min(1.0, minimumConfidence));
    }

    public CompletionSummary enrich(
            Long projectId,
            String username,
            WorkspaceContext context,
            boolean proposeSolutions,
            boolean proposeProducts) {
        List<ProjectSolutionView> projectSolutions = proposeSolutions
                ? solutionService.proposeFromCurrentMappings(projectId, username, context)
                : solutionService.listProjectSolutions(projectId, username, context);
        if (proposeSolutions) {
            // Include pre-existing project solutions as well as newly proposed ones.
            projectSolutions = solutionService.listProjectSolutions(projectId, username, context);
        }

        int productsCreated = proposeProducts
                ? proposeProducts(projectId, projectSolutions, username, context)
                : 0;
        return new CompletionSummary(
                projectSolutions.size(),
                productsCreated,
                "Every generated solution and product remains PROPOSED/CANDIDATE until human review.");
    }

    private int proposeProducts(
            Long projectId,
            List<ProjectSolutionView> projectSolutions,
            String username,
            WorkspaceContext context) {
        List<ProductView> products = productService.listProducts(username, context);
        int created = 0;
        for (ProjectSolutionView projectSolution : projectSolutions) {
            Map<String, Integer> solutionCoverage = confirmedCoverage(
                    projectSolution.solution().taxonomyCoverage());
            if (solutionCoverage.isEmpty()) continue;

            Set<Long> existingProducts = projectSolution.productCandidates().stream()
                    .map(SolutionProductCandidateView::product)
                    .map(ProductView::id)
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new));

            for (ProductView product : products) {
                if (existingProducts.contains(product.id()) || unsupported(product.productStatus())) {
                    continue;
                }
                Map<String, Integer> productCoverage = confirmedCoverage(product.taxonomyCoverage());
                Set<String> intersection = new LinkedHashSet<>(solutionCoverage.keySet());
                intersection.retainAll(productCoverage.keySet());
                if (intersection.isEmpty()) continue;

                int coverage = (int) Math.round(intersection.stream()
                        .mapToInt(code -> Math.min(
                                solutionCoverage.get(code), productCoverage.get(code)))
                        .average()
                        .orElse(0.0));
                double confidence = intersection.size()
                        / (double) Math.max(1, solutionCoverage.size());
                if (coverage < minimumCoverage || confidence < minimumConfidence) {
                    continue;
                }

                List<String> nodes = new ArrayList<>(intersection);
                nodes.sort(String::compareTo);
                String joinedNodes = String.join(", ", nodes);
                String strengths = "Confirmed catalogue coverage overlaps on " + joinedNodes;
                String openEvidence = "Verify product edition, support status, deployment fit, "
                        + "security/compliance claims and current source evidence before selection. "
                        + "Catalogue source: " + product.sourceReference();
                productService.upsertCandidate(
                        projectId,
                        projectSolution.id(),
                        new UpsertProductCandidateRequest(
                                product.id(),
                                coverage,
                                null,
                                strengths,
                                null,
                                openEvidence,
                                confidence,
                                ReviewStatus.PROPOSED,
                                ProductSelectionStatus.CANDIDATE),
                        username,
                        context);
                existingProducts.add(product.id());
                created++;
            }
        }
        return created;
    }

    private static Map<String, Integer> confirmedCoverage(
            List<TaxonomyCoverageView> coverage) {
        Map<String, Integer> result = new HashMap<>();
        if (coverage == null) return result;
        for (TaxonomyCoverageView mapping : coverage) {
            if (mapping != null
                    && mapping.reviewStatus() == ReviewStatus.CONFIRMED
                    && mapping.nodeCode() != null
                    && !mapping.nodeCode().isBlank()) {
                result.merge(mapping.nodeCode(), mapping.coveragePercent(), Math::max);
            }
        }
        return result;
    }

    private static boolean unsupported(ProductStatus status) {
        return status == ProductStatus.END_OF_SUPPORT
                || status == ProductStatus.WITHDRAWN;
    }

    public record CompletionSummary(
            int projectSolutionCount,
            int productCandidatesCreated,
            String reviewBoundary) {
    }
}
