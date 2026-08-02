package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioDtos.AddProjectSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.LinkRequirementSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectSolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProjectSolutionRequest;
import com.taxonomy.portfolio.service.SolutionPortfolioService;
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
@RequestMapping("/api/projects/{projectId}/solutions")
@Tag(name = "Project Solutions")
public class ProjectSolutionController {

    private final SolutionPortfolioService solutionService;
    private final WorkspaceResolver workspaceResolver;

    public ProjectSolutionController(SolutionPortfolioService solutionService,
                                     WorkspaceResolver workspaceResolver) {
        this.solutionService = solutionService;
        this.workspaceResolver = workspaceResolver;
    }

    @PostMapping
    @Operation(summary = "Add or update a reusable solution in a project")
    public ProjectSolutionView add(@PathVariable Long projectId,
                                   @RequestBody AddProjectSolutionRequest request) {
        RequestScope scope = scope();
        return solutionService.addProjectSolution(
                projectId, request, scope.username(), scope.context());
    }

    @GetMapping
    @Operation(summary = "List project solutions with requirement and product coverage")
    public List<ProjectSolutionView> list(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return solutionService.listProjectSolutions(
                projectId, scope.username(), scope.context());
    }

    @PostMapping("/propose-from-taxonomy")
    @Operation(summary = "Propose reusable solutions from confirmed taxonomy coverage")
    public List<ProjectSolutionView> propose(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return solutionService.proposeFromCurrentMappings(
                projectId, scope.username(), scope.context());
    }

    @PatchMapping("/{projectSolutionId}")
    @Operation(summary = "Review and classify a project solution")
    public ProjectSolutionView update(@PathVariable Long projectId,
                                      @PathVariable Long projectSolutionId,
                                      @RequestBody UpdateProjectSolutionRequest request) {
        RequestScope scope = scope();
        return solutionService.updateProjectSolution(
                projectId, projectSolutionId, request, scope.username(), scope.context());
    }

    @PostMapping("/{projectSolutionId}/requirements")
    @Operation(summary = "Link a project solution to one requirement snapshot")
    public ProjectSolutionView linkRequirement(
            @PathVariable Long projectId,
            @PathVariable Long projectSolutionId,
            @RequestBody LinkRequirementSolutionRequest request) {
        RequestScope scope = scope();
        return solutionService.linkRequirement(
                projectId, projectSolutionId, request, scope.username(), scope.context());
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
