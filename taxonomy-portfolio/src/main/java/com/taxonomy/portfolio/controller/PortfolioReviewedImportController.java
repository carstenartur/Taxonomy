package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportItem;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportRequest;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportResult;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioReviewedImportService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Atomic adapter for mixed new-requirement and new-version import decisions. */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements")
@Tag(name = "Project Requirement Import Review")
public class PortfolioReviewedImportController {

    private final PortfolioReviewedImportService importService;
    private final ProjectRequirementAnalysisService analysisService;
    private final WorkspaceResolver workspaceResolver;
    private final int maximumItems;
    private final long maximumCharacters;

    public PortfolioReviewedImportController(
            PortfolioReviewedImportService importService,
            ProjectRequirementAnalysisService analysisService,
            WorkspaceResolver workspaceResolver,
            @Value("${taxonomy.portfolio.max-import-requirements:100}") int maximumItems,
            @Value("${taxonomy.portfolio.max-import-characters:500000}") long maximumCharacters) {
        this.importService = importService;
        this.analysisService = analysisService;
        this.workspaceResolver = workspaceResolver;
        this.maximumItems = Math.max(1, maximumItems);
        this.maximumCharacters = Math.max(1L, maximumCharacters);
    }

    @PostMapping("/import-review")
    @Operation(summary = "Atomically apply a reviewed mixed document import")
    public ResponseEntity<ReviewedImportResult> importReviewed(
            @PathVariable Long projectId,
            @RequestBody ReviewedImportRequest request) {
        validate(request);
        String username = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        var persisted = importService.persist(projectId, request.items(), username, context);
        var job = request.analyzeAfterImport() && !persisted.affectedRequirementIds().isEmpty()
                ? analysisService.enqueueProject(
                        projectId,
                        new AnalyzeProjectRequest(
                                persisted.affectedRequirementIds(),
                                false,
                                request.provider(),
                                request.maxArchitectureNodes(),
                                request.idempotencyKey()),
                        username,
                        context)
                : null;
        ReviewedImportResult result = new ReviewedImportResult(
                persisted.newRequirements(), persisted.versionedRequirements(), job);
        if (job != null) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/projects/" + projectId + "/analysis-jobs/" + job.id()))
                    .body(result);
        }
        return ResponseEntity.status(201).body(result);
    }

    private void validate(ReviewedImportRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw PortfolioException.validation("At least one reviewed import item is required");
        }
        if (request.items().size() > maximumItems) {
            throw PortfolioException.validation(
                    "Reviewed import contains more than " + maximumItems + " items");
        }
        long characters = 0L;
        for (ReviewedImportItem item : request.items()) {
            if (item == null) continue;
            characters += length(item.text());
            if (item.source() != null) characters += length(item.source().originalText());
            if (characters > maximumCharacters) {
                throw PortfolioException.validation(
                        "Reviewed import exceeds " + maximumCharacters + " text characters");
            }
        }
    }

    private static int length(String value) {
        return value != null ? value.length() : 0;
    }
}
