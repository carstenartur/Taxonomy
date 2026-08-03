package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ElementMappingView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RelationMappingView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewElementMappingRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewRelationMappingRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDiff;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** Requirement-separated analysis jobs and immutable snapshot history. */
@RestController
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Project Requirement Analysis")
public class ProjectAnalysisController {

    private final ProjectRequirementAnalysisService analysisService;
    private final PortfolioAnalysisPersistenceService persistenceService;
    private final WorkspaceResolver workspaceResolver;

    public ProjectAnalysisController(ProjectRequirementAnalysisService analysisService,
                                     PortfolioAnalysisPersistenceService persistenceService,
                                     WorkspaceResolver workspaceResolver) {
        this.analysisService = analysisService;
        this.persistenceService = persistenceService;
        this.workspaceResolver = workspaceResolver;
    }

    @PostMapping("/analyses")
    @Operation(summary = "Queue independent analyses for selected or all requirements")
    public ResponseEntity<AnalysisJobView> analyzeProject(@PathVariable Long projectId,
                                                          @RequestBody AnalyzeProjectRequest request) {
        RequestScope scope = scope();
        AnalysisJobView job = analysisService.enqueueProject(
                projectId, request, scope.username(), scope.context());
        return accepted(projectId, job);
    }

    @PostMapping("/requirements/{requirementId}/analyses")
    @Operation(summary = "Queue one requirement analysis and immutable snapshot")
    public ResponseEntity<AnalysisJobView> analyzeRequirement(
            @PathVariable Long projectId,
            @PathVariable Long requirementId,
            @RequestBody(required = false) AnalyzeProjectRequest request) {
        RequestScope scope = scope();
        AnalyzeProjectRequest effective = request != null
                ? request : new AnalyzeProjectRequest(List.of(requirementId), false, null, null, null);
        AnalysisJobView job = analysisService.enqueueRequirement(
                projectId,
                requirementId,
                effective.provider(),
                effective.maxArchitectureNodes(),
                effective.idempotencyKey(),
                scope.username(),
                scope.context());
        return accepted(projectId, job);
    }

    @GetMapping("/analysis-jobs")
    @Operation(summary = "List project analysis jobs")
    public List<AnalysisJobView> listJobs(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return analysisService.listJobs(projectId, scope.username(), scope.context());
    }

    @GetMapping("/analysis-jobs/{jobId}")
    @Operation(summary = "Read analysis job and item status")
    public AnalysisJobView getJob(@PathVariable Long projectId,
                                  @PathVariable String jobId) {
        RequestScope scope = scope();
        return analysisService.getJob(jobId, projectId, scope.username(), scope.context());
    }

    @PostMapping("/analysis-jobs/{jobId}/retry-failed")
    @Operation(summary = "Queue failed or expired requirement analyses for retry")
    public ResponseEntity<AnalysisJobView> retryFailed(@PathVariable Long projectId,
                                                       @PathVariable String jobId) {
        RequestScope scope = scope();
        AnalysisJobView job = analysisService.enqueueRetryFailed(
                jobId, projectId, scope.username(), scope.context());
        return accepted(projectId, job);
    }

    @GetMapping("/requirements/{requirementId}/snapshots")
    @Operation(summary = "List immutable analysis snapshots for a requirement")
    public List<SnapshotSummary> listSnapshots(@PathVariable Long projectId,
                                               @PathVariable Long requirementId) {
        RequestScope scope = scope();
        return analysisService.listSnapshots(
                projectId, requirementId, scope.username(), scope.context());
    }

    @GetMapping("/snapshots/{snapshotId}")
    @Operation(summary = "Replay one immutable analysis snapshot")
    public SnapshotDetail getSnapshot(@PathVariable Long projectId,
                                      @PathVariable String snapshotId) {
        RequestScope scope = scope();
        return analysisService.getSnapshot(projectId, snapshotId, scope.username(), scope.context());
    }

    @GetMapping("/snapshots/diff")
    @Operation(summary = "Compare two analysis snapshots semantically")
    public SnapshotDiff diffSnapshots(@PathVariable Long projectId,
                                      @RequestParam String older,
                                      @RequestParam String newer) {
        RequestScope scope = scope();
        return analysisService.diffSnapshots(
                projectId, older, newer, scope.username(), scope.context());
    }

    @PatchMapping("/analysis-mappings/elements/{mappingId}")
    @Operation(summary = "Review a requirement-to-taxonomy mapping and classify the action")
    public ElementMappingView reviewElementMapping(
            @PathVariable Long projectId,
            @PathVariable Long mappingId,
            @RequestBody ReviewElementMappingRequest request) {
        RequestScope scope = scope();
        return persistenceService.reviewElementMapping(
                projectId, mappingId, request, scope.username(), scope.context());
    }

    @PatchMapping("/analysis-mappings/relations/{mappingId}")
    @Operation(summary = "Review a requirement-derived architecture relation")
    public RelationMappingView reviewRelationMapping(
            @PathVariable Long projectId,
            @PathVariable Long mappingId,
            @RequestBody ReviewRelationMappingRequest request) {
        RequestScope scope = scope();
        return persistenceService.reviewRelationMapping(
                projectId, mappingId, request, scope.username(), scope.context());
    }

    private static ResponseEntity<AnalysisJobView> accepted(Long projectId, AnalysisJobView job) {
        URI location = URI.create(
                "/api/projects/" + projectId + "/analysis-jobs/" + job.id());
        return ResponseEntity.accepted().location(location).body(job);
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
