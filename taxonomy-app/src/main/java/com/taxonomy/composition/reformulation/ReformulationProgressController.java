package com.taxonomy.composition.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationProgressService;
import com.taxonomy.portfolio.reformulation.ReformulationUsageService;
import com.taxonomy.portfolio.reformulation.ReformulationProgressService.Partial;
import com.taxonomy.portfolio.reformulation.ReformulationProgressService.Progress;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Inspection is GET-only, authenticated and scoped; it cannot change the original or proposal. */
@RestController
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
    public ResponseEntity<ReformulationUsageService.Summary> usage(@PathVariable Long projectId, @PathVariable Long requirementId,
            @PathVariable String proposalId, @PathVariable String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(usage.summary(projectId, requirementId, proposalId, runId,
                resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @GetMapping("/progress")
    public ResponseEntity<Progress> progress(@PathVariable Long projectId, @PathVariable Long requirementId,
            @PathVariable String proposalId, @PathVariable String runId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String after) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.progress(projectId, requirementId, proposalId, runId,
                limit, after, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @GetMapping("/progress/checkpoints/{checkpointId}")
    public ResponseEntity<Partial> partial(@PathVariable Long projectId, @PathVariable Long requirementId,
            @PathVariable String proposalId, @PathVariable String runId, @PathVariable String checkpointId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.partial(projectId, requirementId, proposalId, runId,
                checkpointId, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
}
