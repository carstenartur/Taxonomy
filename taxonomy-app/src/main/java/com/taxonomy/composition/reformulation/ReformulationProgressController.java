package com.taxonomy.composition.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationProgressService;
import com.taxonomy.portfolio.reformulation.ReformulationProgressService.Partial;
import com.taxonomy.portfolio.reformulation.ReformulationProgressService.Progress;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Inspection is GET-only, authenticated and scoped; it cannot change the original or proposal. */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/synthesis-runs/{runId}/progress")
public class ReformulationProgressController {
    private final ReformulationProgressService progress;
    private final WorkspaceResolver resolver;
    public ReformulationProgressController(ReformulationProgressService progress, WorkspaceResolver resolver) {
        this.progress = progress;
        this.resolver = resolver;
    }
    @GetMapping
    public ResponseEntity<Progress> progress(@PathVariable Long projectId, @PathVariable Long requirementId,
            @PathVariable String proposalId, @PathVariable String runId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String after) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.progress(projectId, requirementId, proposalId, runId,
                limit, after, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @GetMapping("/checkpoints/{checkpointId}")
    public ResponseEntity<Partial> partial(@PathVariable Long projectId, @PathVariable Long requirementId,
            @PathVariable String proposalId, @PathVariable String runId, @PathVariable String checkpointId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.partial(projectId, requirementId, proposalId, runId,
                checkpointId, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
}
