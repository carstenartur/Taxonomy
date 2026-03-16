package com.taxonomy.versioning.controller;

import com.taxonomy.dto.ProjectionState;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for querying the Git repository state.
 *
 * <p>Exposes the current branch, HEAD commit, projection/index freshness,
 * and branch overview. All data comes from {@link RepositoryStateService}
 * which combines JGit repository metadata with per-user projection tracking.
 */
@RestController
@RequestMapping("/api/git")
@Tag(name = "Git Repository State")
public class GitStateController {

    private final RepositoryStateService stateService;
    private final WorkspaceResolver workspaceResolver;

    public GitStateController(RepositoryStateService stateService,
                              WorkspaceResolver workspaceResolver) {
        this.stateService = stateService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/state")
    @Operation(summary = "Full repository state including projection/index staleness",
            description = "Returns the complete repository state snapshot for a branch, " +
                    "including HEAD commit info, all branches, projection/index freshness, " +
                    "and any in-progress operations.")
    public ResponseEntity<RepositoryState> getState(
            @RequestParam(defaultValue = "draft") String branch) {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(stateService.getState(user, branch));
    }

    @GetMapping("/projection")
    @Operation(summary = "Projection and search index freshness",
            description = "Returns which commit the DB projection and search index are " +
                    "built from, and whether they are stale relative to HEAD.")
    public ResponseEntity<ProjectionState> getProjectionState(
            @RequestParam(defaultValue = "draft") String branch) {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(stateService.getProjectionState(user, branch));
    }

    @GetMapping("/branches")
    @Operation(summary = "List all branches with HEAD commit info",
            description = "Returns all Git branches in the repository with their " +
                    "HEAD commit SHA and basic metadata.")
    public ResponseEntity<RepositoryState> listBranches(
            @RequestParam(defaultValue = "draft") String branch) {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(stateService.getState(user, branch));
    }

    @GetMapping("/stale")
    @Operation(summary = "Quick check: is projection/index stale?",
            description = "Lightweight endpoint returning only staleness flags. " +
                    "Useful for periodic polling from the UI.")
    public ResponseEntity<Map<String, Boolean>> isStale(
            @RequestParam(defaultValue = "draft") String branch) {
        String user = workspaceResolver.resolveCurrentUsername();
        ProjectionState ps = stateService.getProjectionState(user, branch);
        return ResponseEntity.ok(Map.of(
                "projectionStale", ps.projectionStale(),
                "indexStale", ps.indexStale()
        ));
    }
}
