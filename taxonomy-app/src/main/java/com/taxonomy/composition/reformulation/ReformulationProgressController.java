package com.taxonomy.composition.reformulation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.portfolio.reformulation.ReformulationProgressService;
import com.taxonomy.portfolio.reformulation.ReformulationUsageService;
import com.taxonomy.portfolio.reformulation.ReformulationProgressService.Partial;
import com.taxonomy.portfolio.reformulation.ReformulationProgressService.Progress;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Inspection is GET-only, authenticated and scoped; it cannot change the original or proposal. */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio"})
@RestController
@Tag(name = "Reformulation progress")
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/synthesis-runs/{runId}")
public class ReformulationProgressController {
    private final ReformulationProgressService progress;
    private final WorkspaceResolver resolver;
    private final ReformulationUsageService usage;
    public ReformulationProgressController(ReformulationProgressService progress, WorkspaceResolver resolver, ReformulationUsageService usage) {
        this.progress = progress;
        this.usage = usage;
        this.resolver = resolver;
    }
    @GetMapping("/usage")
    @Operation(summary = "Read synthesis provider usage",
            description = "Returns the retained usage summary for the authorized synthesis run. This private read does not execute a provider call, change the proposal or adopt requirement text.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<ReformulationUsageService.Summary> usage(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId, @Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,
            @Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId, @Parameter(description = "Existing synthesis run identifier") @PathVariable String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(usage.summary(projectId, requirementId, proposalId, runId,
                resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @GetMapping("/progress")
    @Operation(summary = "Read paginated synthesis progress",
            description = "Returns bounded progress and checkpoint summaries for the selected synthesis run. Use the returned continuation cursor as after; limit bounds the requested page. No provider call or proposal mutation is performed.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<Progress> progress(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId, @Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,
            @Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId, @Parameter(description = "Existing synthesis run identifier") @PathVariable String runId,
            @Parameter(description = "Maximum progress summaries in the requested page") @RequestParam(defaultValue = "20") int limit, @Parameter(description = "Opaque cursor returned by the preceding progress page") @RequestParam(required = false) String after) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.progress(projectId, requirementId, proposalId, runId,
                limit, after, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @GetMapping("/progress/checkpoints/{checkpointId}")
    @Operation(summary = "Read a retained synthesis checkpoint",
            description = "Returns the stored partial evidence identified by checkpointId within the authorized run. Partial output is not a completed proposal or an accepted requirement.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<Partial> partial(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId, @Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,
            @Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId, @Parameter(description = "Existing synthesis run identifier") @PathVariable String runId, @Parameter(description = "Checkpoint identifier returned in run progress") @PathVariable String checkpointId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.partial(projectId, requirementId, proposalId, runId,
                checkpointId, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
}
