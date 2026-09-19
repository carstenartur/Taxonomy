package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.SolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertTaxonomyCoverageRequest;
import com.taxonomy.portfolio.service.SolutionPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/solutions")
@Tag(name = "Solution Catalogue")
public class SolutionCatalogController {

    private final SolutionPortfolioService solutionService;
    private final WorkspaceResolver workspaceResolver;

    public SolutionCatalogController(SolutionPortfolioService solutionService,
                                     WorkspaceResolver workspaceResolver) {
        this.solutionService = solutionService;
        this.workspaceResolver = workspaceResolver;
    }

    @PostMapping
    @Operation(summary = "Create a reusable workspace solution")
    public ResponseEntity<SolutionView> create(@RequestBody CreateSolutionRequest request) {
        RequestScope scope = scope();
        SolutionView solution = solutionService.createSolution(
                request, scope.username(), scope.context());
        return ResponseEntity.created(URI.create("/api/solutions/" + solution.id())).body(solution);
    }

    @GetMapping
    @Operation(summary = "List reusable solutions in the current workspace")
    public List<SolutionView> list() {
        RequestScope scope = scope();
        return solutionService.listSolutions(scope.username(), scope.context());
    }

    @GetMapping("/{solutionId}")
    @Operation(summary = "Read one reusable solution")
    public SolutionView get(@PathVariable Long solutionId) {
        RequestScope scope = scope();
        return solutionService.getSolution(solutionId, scope.username(), scope.context());
    }

    @PatchMapping("/{solutionId}")
    @Operation(summary = "Update reusable solution metadata")
    public SolutionView update(@PathVariable Long solutionId,
                               @RequestBody UpdateSolutionRequest request) {
        RequestScope scope = scope();
        return solutionService.updateSolution(
                solutionId, request, scope.username(), scope.context());
    }

    @PostMapping("/{solutionId}/taxonomy-coverage")
    @Operation(summary = "Create or update evidence-backed taxonomy coverage")
    public SolutionView upsertCoverage(@PathVariable Long solutionId,
                                       @RequestBody UpsertTaxonomyCoverageRequest request) {
        RequestScope scope = scope();
        return solutionService.upsertTaxonomyCoverage(
                solutionId, request, scope.username(), scope.context());
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
