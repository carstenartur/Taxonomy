package com.taxonomy.controller;

import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.service.WorkspaceManager;
import com.taxonomy.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for workspace management.
 *
 * <p>Provides endpoints to query the current user's workspace, list all active
 * workspaces (admin), and manage workspace lifecycle. Each authenticated user
 * automatically gets a personal workspace on first access.
 */
@RestController
@RequestMapping("/api/workspace")
@Tag(name = "Workspace Management")
public class WorkspaceController {

    private final WorkspaceManager workspaceManager;
    private final WorkspaceResolver workspaceResolver;

    public WorkspaceController(WorkspaceManager workspaceManager,
                               WorkspaceResolver workspaceResolver) {
        this.workspaceManager = workspaceManager;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/current")
    @Operation(summary = "Get the current user's workspace",
            description = "Returns workspace metadata including current branch, context, " +
                    "and timestamps for the authenticated user.")
    public ResponseEntity<WorkspaceInfo> getCurrentWorkspace() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(workspaceManager.getWorkspaceInfo(user));
    }

    @GetMapping("/active")
    @Operation(summary = "List all active workspaces",
            description = "Returns all currently active (in-memory) workspaces. " +
                    "Useful for admin monitoring of concurrent users.")
    public ResponseEntity<List<WorkspaceInfo>> listActiveWorkspaces() {
        return ResponseEntity.ok(workspaceManager.listActiveWorkspaces());
    }

    @GetMapping("/stats")
    @Operation(summary = "Workspace statistics",
            description = "Returns basic statistics about active workspaces.")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "activeWorkspaces", workspaceManager.getActiveWorkspaceCount()
        ));
    }

    @PostMapping("/evict")
    @Operation(summary = "Evict a user's workspace from memory",
            description = "Removes the in-memory workspace state for the specified user. " +
                    "The workspace will be recreated on next access. " +
                    "Admin-only operation for maintenance.")
    public ResponseEntity<Map<String, Object>> evictWorkspace(
            @RequestParam String username) {
        workspaceManager.evictWorkspace(username);
        return ResponseEntity.ok(Map.of(
                "evicted", username,
                "success", true
        ));
    }
}
