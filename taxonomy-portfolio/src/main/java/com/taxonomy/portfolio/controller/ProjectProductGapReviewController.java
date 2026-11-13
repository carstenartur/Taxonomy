package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.service.ProjectProductGapReviewService;
import com.taxonomy.portfolio.service.ProjectProductGapReviewService.ReviewRequest;
import com.taxonomy.portfolio.service.ProjectProductGapReviewService.ReviewedFamily;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/product-gaps")
public class ProjectProductGapReviewController {
    private final ProjectProductGapReviewService reviews;
    private final WorkspaceResolver workspace;

    public ProjectProductGapReviewController(ProjectProductGapReviewService reviews, WorkspaceResolver workspace) {
        this.reviews = reviews;
        this.workspace = workspace;
    }

    @GetMapping
    @Operation(summary = "Read confirmed project product gaps and their review history",
            description = "Lists frozen product evidence and classifications from the authorized project and repository branch.")
    public List<ReviewedFamily> list(@PathVariable Long projectId) {
        return reviews.list(projectId, workspace.resolveCurrentUsername(), workspace.resolveUnambiguousContextForRead());
    }

    @PostMapping("/{gapKey}/reviews")
    @Operation(summary = "Classify a confirmed product gap",
            description = "Appends a review at the expected project evidence fingerprint and revision. A stale reviewer receives 409.")
    public ReviewedFamily review(@PathVariable Long projectId, @PathVariable String gapKey, @RequestBody ReviewRequest request) {
        return reviews.review(projectId, gapKey, request, workspace.resolveCurrentUsername(), workspace.resolveCurrentContext());
    }
}
