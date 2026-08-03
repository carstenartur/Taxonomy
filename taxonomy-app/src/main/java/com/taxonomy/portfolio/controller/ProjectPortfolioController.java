package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ImportRequirementCandidate;
import com.taxonomy.portfolio.dto.PortfolioDtos.ImportRequirementsRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ImportRequirementsResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateRequirementRequest;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
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

/** Project and stable requirement identities with immutable text versions. */
@RestController
@RequestMapping("/api/projects")
@Tag(name = "Project Requirement Portfolio")
public class ProjectPortfolioController {

    private final ProjectPortfolioService projectService;
    private final ProjectRequirementAnalysisService analysisService;
    private final WorkspaceResolver workspaceResolver;
    private final int maximumImportRequirements;
    private final long maximumImportCharacters;

    public ProjectPortfolioController(ProjectPortfolioService projectService,
                                      ProjectRequirementAnalysisService analysisService,
                                      WorkspaceResolver workspaceResolver,
                                      @Value("${taxonomy.portfolio.max-import-requirements:100}")
                                      int maximumImportRequirements,
                                      @Value("${taxonomy.portfolio.max-import-characters:500000}")
                                      long maximumImportCharacters) {
        this.projectService = projectService;
        this.analysisService = analysisService;
        this.workspaceResolver = workspaceResolver;
        this.maximumImportRequirements = Math.max(1, maximumImportRequirements);
        this.maximumImportCharacters = Math.max(1L, maximumImportCharacters);
    }

    @PostMapping
    @Operation(summary = "Create a project")
    public ResponseEntity<ProjectView> createProject(@RequestBody CreateProjectRequest request) {
        RequestScope scope = scope();
        ProjectView project = projectService.createProject(request, scope.username(), scope.context());
        return ResponseEntity.created(URI.create("/api/projects/" + project.id())).body(project);
    }

    @GetMapping
    @Operation(summary = "List projects in the current workspace")
    public List<ProjectView> listProjects() {
        RequestScope scope = scope();
        return projectService.listProjects(scope.username(), scope.context());
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Read one project")
    public ProjectView getProject(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return projectService.getProject(projectId, scope.username(), scope.context());
    }

    @PatchMapping("/{projectId}")
    @Operation(summary = "Update project metadata")
    public ProjectView updateProject(@PathVariable Long projectId,
                                     @RequestBody UpdateProjectRequest request) {
        RequestScope scope = scope();
        return projectService.updateProject(projectId, request, scope.username(), scope.context());
    }

    @PostMapping("/{projectId}/requirements")
    @Operation(summary = "Create one project requirement and its initial immutable version")
    public ResponseEntity<RequirementView> createRequirement(
            @PathVariable Long projectId,
            @RequestBody CreateRequirementRequest request) {
        RequestScope scope = scope();
        RequirementView requirement = projectService.createRequirement(
                projectId, request, scope.username(), scope.context());
        return ResponseEntity.created(URI.create(
                "/api/projects/" + projectId + "/requirements/" + requirement.id()))
                .body(requirement);
    }

    @PostMapping("/{projectId}/requirements/import")
    @Operation(summary = "Import candidates as separate requirements, optionally queue their analysis")
    public ResponseEntity<ImportRequirementsResult> importRequirements(
            @PathVariable Long projectId,
            @RequestBody ImportRequirementsRequest request) {
        validateImportRequest(request);
        RequestScope scope = scope();
        List<RequirementView> requirements = projectService.importRequirements(
                projectId, request.requirements(), scope.username(), scope.context());
        var job = request.analyzeAfterImport()
                ? analysisService.enqueueProject(
                        projectId,
                        new AnalyzeProjectRequest(
                                requirements.stream().map(RequirementView::id).toList(),
                                false,
                                request.provider(),
                                request.maxArchitectureNodes(),
                                request.idempotencyKey()),
                        scope.username(),
                        scope.context())
                : null;
        return ResponseEntity.status(201).body(new ImportRequirementsResult(requirements, job));
    }

    @GetMapping("/{projectId}/requirements")
    @Operation(summary = "List project requirements")
    public List<RequirementView> listRequirements(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return projectService.listRequirements(projectId, scope.username(), scope.context());
    }

    @GetMapping("/{projectId}/requirements/{requirementId}")
    @Operation(summary = "Read one project requirement")
    public RequirementView getRequirement(@PathVariable Long projectId,
                                          @PathVariable Long requirementId) {
        RequestScope scope = scope();
        return projectService.getRequirement(
                projectId, requirementId, scope.username(), scope.context());
    }

    @PatchMapping("/{projectId}/requirements/{requirementId}")
    @Operation(summary = "Update requirement metadata without rewriting historical text")
    public RequirementView updateRequirement(@PathVariable Long projectId,
                                             @PathVariable Long requirementId,
                                             @RequestBody UpdateRequirementRequest request) {
        RequestScope scope = scope();
        return projectService.updateRequirement(
                projectId, requirementId, request, scope.username(), scope.context());
    }

    @PostMapping("/{projectId}/requirements/{requirementId}/versions")
    @Operation(summary = "Create or select an immutable requirement text version")
    public ResponseEntity<RequirementVersionView> addRequirementVersion(
            @PathVariable Long projectId,
            @PathVariable Long requirementId,
            @RequestBody CreateRequirementVersionRequest request) {
        RequestScope scope = scope();
        RequirementVersionView version = projectService.addRequirementVersion(
                projectId, requirementId, request, scope.username(), scope.context());
        return ResponseEntity.created(URI.create(
                "/api/projects/" + projectId + "/requirements/" + requirementId
                        + "/versions/" + version.id()))
                .body(version);
    }

    @GetMapping("/{projectId}/requirements/{requirementId}/versions")
    @Operation(summary = "List immutable requirement versions")
    public List<RequirementVersionView> listRequirementVersions(
            @PathVariable Long projectId,
            @PathVariable Long requirementId) {
        RequestScope scope = scope();
        return projectService.listRequirementVersions(
                projectId, requirementId, scope.username(), scope.context());
    }

    private void validateImportRequest(ImportRequirementsRequest request) {
        if (request == null || request.requirements() == null || request.requirements().isEmpty()) {
            throw PortfolioException.validation("At least one requirement candidate is required");
        }
        if (request.requirements().size() > maximumImportRequirements) {
            throw PortfolioException.validation(
                    "Import contains " + request.requirements().size()
                            + " candidates; maximum is " + maximumImportRequirements);
        }

        long characters = 0L;
        for (ImportRequirementCandidate candidate : request.requirements()) {
            if (candidate == null) continue;
            characters += length(candidate.text());
            if (candidate.source() != null) {
                characters += length(candidate.source().originalText());
            }
            if (characters > maximumImportCharacters) {
                throw PortfolioException.validation(
                        "Import text payload exceeds " + maximumImportCharacters + " characters");
            }
        }
    }

    private static int length(String value) {
        return value != null ? value.length() : 0;
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
