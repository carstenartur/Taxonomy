package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioDtos.ConflictView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectPortfolioView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewConflictRequest;
import com.taxonomy.portfolio.service.PortfolioAggregationService;
import com.taxonomy.portfolio.service.ProjectConflictService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Consolidated Project Portfolio")
public class PortfolioQueryController {

    private final PortfolioAggregationService aggregationService;
    private final ProjectConflictService conflictService;
    private final WorkspaceResolver workspaceResolver;

    public PortfolioQueryController(PortfolioAggregationService aggregationService,
                                    ProjectConflictService conflictService,
                                    WorkspaceResolver workspaceResolver) {
        this.aggregationService = aggregationService;
        this.conflictService = conflictService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/portfolio")
    @Operation(summary = "Build the consolidated requirement/solution/product portfolio",
            description = "Builds the authorized project portfolio view, optionally narrowed by the supplied query filters. The response combines recorded requirements, mappings and coverage; it does not run a new model analysis.")
    public ProjectPortfolioView portfolio(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return aggregationService.build(projectId, scope.username(), scope.context());
    }

    @PostMapping("/conflicts/detect")
    @Operation(summary = "Create deterministic, human-reviewable conflict hypotheses",
            description = "Derives conflict candidates from the project's existing portfolio evidence and stores them for review. Detection is not proof that two requirements are incompatible and does not automatically resolve or accept candidates.")
    public List<ConflictView> detectConflicts(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return conflictService.detect(projectId, scope.username(), scope.context());
    }

    @GetMapping("/conflicts")
    @Operation(summary = "List project conflict hypotheses and decisions",
            description = "Lists retained conflict candidates and their review decisions for the authorized project. An unreviewed candidate is not a confirmed inconsistency.")
    public List<ConflictView> listConflicts(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return conflictService.list(projectId, scope.username(), scope.context());
    }

    @PatchMapping("/conflicts/{conflictId}")
    @Operation(summary = "Confirm, reject or resolve a conflict hypothesis",
            description = "Records the caller's review decision for an identified portfolio conflict in the authorized project and workspace. Confirmation, rejection or resolution is a human decision and does not rewrite historical analysis evidence.")
    public ConflictView reviewConflict(@PathVariable Long projectId,
                                       @PathVariable Long conflictId,
                                       @RequestBody ReviewConflictRequest request) {
        RequestScope scope = scope();
        return conflictService.review(
                projectId, conflictId, request, scope.username(), scope.context());
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
