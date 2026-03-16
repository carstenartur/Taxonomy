package com.taxonomy.workspace.controller;

import com.taxonomy.dto.ContextComparison;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.versioning.model.ContextHistoryRecord;
import com.taxonomy.versioning.service.ContextCompareService;
import com.taxonomy.versioning.service.ContextHistoryService;
import com.taxonomy.workspace.service.SyncIntegrationService;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceProjectionService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for workspace management.
 *
 * <p>Provides endpoints to query the current user's workspace, list all active
 * workspaces (admin), and manage workspace lifecycle. Each authenticated user
 * automatically gets a personal workspace on first access.
 *
 * <p>Additional endpoints support context comparison, synchronization with the
 * shared integration repository, navigation history, and projection state.
 */
@RestController
@RequestMapping("/api/workspace")
@Tag(name = "Workspace Management")
public class WorkspaceController {

    private final WorkspaceManager workspaceManager;
    private final WorkspaceResolver workspaceResolver;
    private final ContextCompareService contextCompareService;
    private final ContextHistoryService contextHistoryService;
    private final SyncIntegrationService syncIntegrationService;
    private final WorkspaceProjectionService workspaceProjectionService;

    public WorkspaceController(WorkspaceManager workspaceManager,
                               WorkspaceResolver workspaceResolver,
                               ContextCompareService contextCompareService,
                               ContextHistoryService contextHistoryService,
                               SyncIntegrationService syncIntegrationService,
                               WorkspaceProjectionService workspaceProjectionService) {
        this.workspaceManager = workspaceManager;
        this.workspaceResolver = workspaceResolver;
        this.contextCompareService = contextCompareService;
        this.contextHistoryService = contextHistoryService;
        this.syncIntegrationService = syncIntegrationService;
        this.workspaceProjectionService = workspaceProjectionService;
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

    // ── Compare ─────────────────────────────────────────────────────

    @PostMapping("/compare")
    @Operation(summary = "Compare two branches or contexts",
            description = "Performs a semantic diff between two branches or specific commits. " +
                    "Returns a summary of changes, individual semantic changes, and an " +
                    "optional raw DSL diff.")
    public ResponseEntity<?> compare(
            @RequestParam String leftBranch,
            @RequestParam String rightBranch,
            @RequestParam(required = false) String leftCommit,
            @RequestParam(required = false) String rightCommit) {
        try {
            ContextRef left = readOnlyContextRef(leftBranch, leftCommit);
            ContextRef right = readOnlyContextRef(rightBranch, rightCommit);

            ContextComparison comparison;
            if (leftCommit != null || rightCommit != null) {
                comparison = contextCompareService.compareContexts(left, right);
            } else {
                comparison = contextCompareService.compareBranches(left, right);
            }
            return ResponseEntity.ok(comparison);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Compare failed",
                    "message", e.getMessage()
            ));
        }
    }

    // ── Sync operations ─────────────────────────────────────────────

    @PostMapping("/sync-from-shared")
    @Operation(summary = "Sync from shared repository",
            description = "Merges the shared integration branch into the user's branch, " +
                    "bringing it up to date with the latest team-wide state.")
    public ResponseEntity<Map<String, Object>> syncFromShared(
            @RequestParam(required = false) String userBranch) {
        String user = workspaceResolver.resolveCurrentUsername();
        String branch = resolveBranch(user, userBranch);
        try {
            String mergeCommit = syncIntegrationService.syncFromShared(user, branch);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("branch", branch);
            result.put("mergeCommit", mergeCommit);
            result.put("syncedAt", Instant.now().toString());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Sync from shared failed",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/publish")
    @Operation(summary = "Publish to shared repository",
            description = "Merges the user's branch into the shared integration branch, " +
                    "making local changes available to all team members.")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestParam(required = false) String userBranch) {
        String user = workspaceResolver.resolveCurrentUsername();
        String branch = resolveBranch(user, userBranch);
        try {
            String mergeCommit = syncIntegrationService.publishToShared(user, branch);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("branch", branch);
            result.put("mergeCommit", mergeCommit);
            result.put("publishedAt", Instant.now().toString());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Publish failed",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/sync-state")
    @Operation(summary = "Get current sync state",
            description = "Returns the synchronization status between the user's workspace " +
                    "and the shared repository, including last sync time and " +
                    "unpublished commit count.")
    public ResponseEntity<Map<String, Object>> getSyncState() {
        String user = workspaceResolver.resolveCurrentUsername();
        var state = syncIntegrationService.getSyncState(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", user);
        result.put("syncStatus", state.getSyncStatus());
        result.put("lastSyncedCommitId", state.getLastSyncedCommitId());
        result.put("lastSyncTimestamp", state.getLastSyncTimestamp());
        result.put("lastPublishedCommitId", state.getLastPublishedCommitId());
        result.put("lastPublishTimestamp", state.getLastPublishTimestamp());
        result.put("unpublishedCommitCount", state.getUnpublishedCommitCount());
        return ResponseEntity.ok(result);
    }

    // ── History ─────────────────────────────────────────────────────

    @GetMapping("/history")
    @Operation(summary = "Get navigation history",
            description = "Returns the user's recent context navigation history, " +
                    "ordered newest first (max 50 entries).")
    public ResponseEntity<List<ContextHistoryRecord>> getHistory() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(contextHistoryService.getHistory(user));
    }

    // ── Local changes ───────────────────────────────────────────────

    @GetMapping("/local-changes")
    @Operation(summary = "Get count of local unpublished changes",
            description = "Returns the number of commits on the user's branch that " +
                    "have not been published to the shared repository.")
    public ResponseEntity<Map<String, Object>> getLocalChanges(
            @RequestParam(required = false) String branch) {
        String user = workspaceResolver.resolveCurrentUsername();
        String resolvedBranch = resolveBranch(user, branch);
        try {
            int changeCount = syncIntegrationService.getLocalChanges(user, resolvedBranch);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("branch", resolvedBranch);
            result.put("changeCount", changeCount);
            result.put("hasUnpublishedChanges", changeCount > 0);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Could not count local changes",
                    "message", e.getMessage()
            ));
        }
    }

    // ── Dirty check ─────────────────────────────────────────────────

    @GetMapping("/dirty")
    @Operation(summary = "Check if workspace has unsaved changes",
            description = "Returns whether the user's workspace has unpublished " +
                    "changes relative to the shared repository.")
    public ResponseEntity<Map<String, Object>> isDirty() {
        String user = workspaceResolver.resolveCurrentUsername();
        boolean dirty = syncIntegrationService.isDirty(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", user);
        result.put("dirty", dirty);
        result.put("syncStatus", syncIntegrationService.getSyncState(user).getSyncStatus());
        return ResponseEntity.ok(result);
    }

    // ── Diverged resolution ─────────────────────────────────────────

    @PostMapping("/resolve-diverged")
    @Operation(summary = "Resolve a diverged sync state",
            description = "Resolves a diverged state between user and shared branches. " +
                    "Strategies: MERGE (attempt merge), KEEP_MINE (publish local), " +
                    "TAKE_SHARED (overwrite local with shared).")
    public ResponseEntity<Map<String, Object>> resolveDiverged(
            @RequestParam String strategy,
            @RequestParam(required = false) String userBranch) {
        String user = workspaceResolver.resolveCurrentUsername();
        String branch = resolveBranch(user, userBranch);
        try {
            SyncIntegrationService.DivergedStrategy parsedStrategy =
                    SyncIntegrationService.DivergedStrategy.valueOf(strategy.toUpperCase());
            String message = syncIntegrationService.resolveDiverged(user, branch, parsedStrategy);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("strategy", parsedStrategy.name());
            result.put("branch", branch);
            result.put("message", message);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid strategy: " + strategy,
                    "validStrategies", List.of("MERGE", "KEEP_MINE", "TAKE_SHARED")
            ));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Diverged resolution failed",
                    "message", e.getMessage()
            ));
        }
    }

    // ── Projection ──────────────────────────────────────────────────

    @GetMapping("/projection")
    @Operation(summary = "Get projection state",
            description = "Returns the materialization and index state for the " +
                    "user's workspace, including both in-memory and persisted data.")
    public ResponseEntity<Map<String, Object>> getProjection() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(workspaceProjectionService.getProjectionInfo(user));
    }

    // ── Internal helpers ────────────────────────────────────────────

    /**
     * Resolve the branch name, falling back to the current workspace branch
     * or "draft" as default.
     */
    private String resolveBranch(String username, String explicitBranch) {
        if (explicitBranch != null && !explicitBranch.isBlank()) {
            return explicitBranch;
        }
        WorkspaceInfo info = workspaceManager.getWorkspaceInfo(username);
        return info != null && info.currentBranch() != null
                ? info.currentBranch()
                : "draft";
    }

    /**
     * Create a read-only {@link ContextRef} for comparison operations.
     */
    private ContextRef readOnlyContextRef(String branch, String commitId) {
        return new ContextRef(
                UUID.randomUUID().toString(), branch, commitId,
                Instant.now(), ContextMode.READ_ONLY,
                null, null, null, null, null, false);
    }
}
