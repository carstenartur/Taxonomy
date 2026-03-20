package com.taxonomy.workspace.controller;

import com.taxonomy.dto.ContextComparison;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.SemanticChange;
import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.versioning.model.ContextHistoryRecord;
import com.taxonomy.versioning.service.ContextCompareService;
import com.taxonomy.versioning.service.ContextHistoryService;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.workspace.service.SyncIntegrationService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceProjectionService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ContextNavigationService contextNavigationService;
    private final SyncIntegrationService syncIntegrationService;
    private final WorkspaceProjectionService workspaceProjectionService;
    private final SystemRepositoryService systemRepositoryService;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkspaceController.class);

    public WorkspaceController(WorkspaceManager workspaceManager,
                               WorkspaceResolver workspaceResolver,
                               ContextCompareService contextCompareService,
                               ContextHistoryService contextHistoryService,
                               ContextNavigationService contextNavigationService,
                               SyncIntegrationService syncIntegrationService,
                               WorkspaceProjectionService workspaceProjectionService,
                               SystemRepositoryService systemRepositoryService) {
        this.workspaceManager = workspaceManager;
        this.workspaceResolver = workspaceResolver;
        this.contextCompareService = contextCompareService;
        this.contextHistoryService = contextHistoryService;
        this.contextNavigationService = contextNavigationService;
        this.syncIntegrationService = syncIntegrationService;
        this.workspaceProjectionService = workspaceProjectionService;
        this.systemRepositoryService = systemRepositoryService;
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

    // ── Multi-Workspace Management ────────────────────────────────

    @GetMapping("/list")
    @Operation(summary = "List all workspaces for the current user",
            description = "Returns all non-archived workspaces for the authenticated user.")
    public ResponseEntity<List<Map<String, Object>>> listWorkspaces() {
        String user = workspaceResolver.resolveCurrentUsername();
        List<UserWorkspace> workspaces = workspaceManager.listUserWorkspaces(user);
        List<Map<String, Object>> result = workspaces.stream()
                .map(this::workspaceToMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/create")
    @Operation(summary = "Create a new workspace",
            description = "Creates a new workspace for the authenticated user.")
    public ResponseEntity<Map<String, Object>> createWorkspace(@RequestBody Map<String, String> body) {
        String user = workspaceResolver.resolveCurrentUsername();
        String displayName = body.get("displayName");
        String description = body.get("description");
        if (displayName == null || displayName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "displayName is required"
            ));
        }
        try {
            UserWorkspace ws = workspaceManager.createWorkspace(user, displayName, description);
            return ResponseEntity.ok(workspaceToMap(ws));
        } catch (Exception e) {
            log.warn("Failed to create workspace for user '{}'", user, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to create workspace",
                    "message", e.getMessage()
            ));
        }
    }

    @PutMapping("/{id}/rename")
    @Operation(summary = "Rename a workspace",
            description = "Changes the display name of a workspace.")
    public ResponseEntity<Map<String, Object>> renameWorkspace(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        String user = workspaceResolver.resolveCurrentUsername();
        String displayName = body.get("displayName");
        if (displayName == null || displayName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "displayName is required"
            ));
        }
        try {
            UserWorkspace ws = workspaceManager.renameWorkspace(user, id, displayName);
            return ResponseEntity.ok(workspaceToMap(ws));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @PutMapping("/{id}/description")
    @Operation(summary = "Update workspace description",
            description = "Updates the description of a workspace.")
    public ResponseEntity<Map<String, Object>> updateDescription(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        String user = workspaceResolver.resolveCurrentUsername();
        String description = body.get("description");
        try {
            UserWorkspace ws = workspaceManager.updateDescription(user, id, description);
            return ResponseEntity.ok(workspaceToMap(ws));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/{id}/switch")
    @Operation(summary = "Switch active workspace",
            description = "Switches the current user's active workspace.")
    public ResponseEntity<Map<String, Object>> switchWorkspace(@PathVariable String id) {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            UserWorkspace ws = workspaceManager.switchWorkspace(user, id);
            return ResponseEntity.ok(workspaceToMap(ws));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive a workspace",
            description = "Soft-deletes a workspace by marking it as archived.")
    public ResponseEntity<Map<String, Object>> archiveWorkspace(@PathVariable String id) {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            UserWorkspace ws = workspaceManager.archiveWorkspace(id, user);
            return ResponseEntity.ok(workspaceToMap(ws));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a workspace",
            description = "Permanently deletes a workspace. Only the owner can delete " +
                    "their own non-shared, non-default workspaces.")
    public ResponseEntity<Map<String, Object>> deleteWorkspace(@PathVariable String id) {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            workspaceManager.deleteWorkspace(id, user);
            return ResponseEntity.ok(Map.of(
                    "deleted", id,
                    "success", true
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}/info")
    @Operation(summary = "Get workspace info by ID",
            description = "Returns workspace metadata for the specified workspace.")
    public ResponseEntity<Map<String, Object>> getWorkspaceInfo(@PathVariable String id) {
        UserWorkspace ws = workspaceManager.getWorkspaceById(id);
        if (ws == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workspaceToMap(ws));
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
            @RequestParam(required = false) String rightCommit,
            @RequestParam(required = false) Set<String> filter) {
        try {
            ContextRef left = readOnlyContextRef(leftBranch, leftCommit);
            ContextRef right = readOnlyContextRef(rightBranch, rightCommit);

            ContextComparison comparison;
            if (leftCommit != null || rightCommit != null) {
                comparison = contextCompareService.compareContexts(left, right);
            } else {
                comparison = contextCompareService.compareBranches(left, right);
            }
            return ResponseEntity.ok(applyFilter(comparison, filter));
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

    // ── Provisioning & Topology ────────────────────────────────────

    @GetMapping("/provisioning-status")
    @Operation(summary = "Get workspace provisioning status",
            description = "Returns the current provisioning state of the user's workspace.")
    public ResponseEntity<Map<String, Object>> getProvisioningStatus() {
        String user = workspaceResolver.resolveCurrentUsername();
        UserWorkspace ws = workspaceManager.findUserWorkspace(user);
        Map<String, Object> result = new LinkedHashMap<>();
        if (ws == null) {
            result.put("status", "NOT_PROVISIONED");
        } else {
            result.put("status", ws.getProvisioningStatus().name());
            result.put("topologyMode", ws.getTopologyMode().name());
            result.put("sourceRepository", ws.getSourceRepositoryId());
            result.put("error", ws.getProvisioningError());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/provision")
    @Operation(summary = "Provision workspace repository",
            description = "Creates the user's personal branch from the shared repository.")
    public ResponseEntity<Map<String, Object>> provisionWorkspace() {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            UserWorkspace ws = workspaceManager.provisionWorkspaceRepository(user);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", ws.getProvisioningStatus().name());
            result.put("branch", ws.getCurrentBranch());
            result.put("baseBranch", ws.getBaseBranch());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Provisioning failed",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/topology")
    @Operation(summary = "Get repository topology",
            description = "Returns the repository topology mode and shared source information.")
    public ResponseEntity<Map<String, Object>> getTopology() {
        SystemRepository sysRepo = systemRepositoryService.getPrimaryRepository();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", sysRepo.getTopologyMode().name());
        result.put("sharedBranch", sysRepo.getDefaultBranch());
        result.put("systemRepositoryId", sysRepo.getRepositoryId());
        result.put("displayName", sysRepo.getDisplayName());
        return ResponseEntity.ok(result);
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

    /**
     * Convert a {@link UserWorkspace} entity to a response map.
     */
    private Map<String, Object> workspaceToMap(UserWorkspace ws) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workspaceId", ws.getWorkspaceId());
        map.put("username", ws.getUsername());
        map.put("displayName", ws.getDisplayName());
        map.put("description", ws.getDescription());
        map.put("currentBranch", ws.getCurrentBranch());
        map.put("baseBranch", ws.getBaseBranch());
        map.put("shared", ws.isShared());
        map.put("archived", ws.isArchived());
        map.put("isDefault", ws.isDefault());
        map.put("provisioningStatus", ws.getProvisioningStatus().name());
        map.put("topologyMode", ws.getTopologyMode().name());
        map.put("createdAt", ws.getCreatedAt() != null ? ws.getCreatedAt().toString() : null);
        map.put("lastAccessedAt", ws.getLastAccessedAt() != null ? ws.getLastAccessedAt().toString() : null);
        return map;
    }

    private ContextComparison applyFilter(ContextComparison comparison, Set<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return comparison;
        }
        List<SemanticChange> filtered = comparison.changes().stream()
                .filter(c -> {
                    if (filter.contains("elements") && "ELEMENT".equals(c.category())) return true;
                    if (filter.contains("relations") && "RELATION".equals(c.category())) return true;
                    return false;
                })
                .toList();
        return new ContextComparison(comparison.left(), comparison.right(),
                comparison.summary(), filtered, comparison.rawDslDiff());
    }

    @GetMapping("/history/origin-stack")
    @Operation(summary = "Get origin stack from current context",
            description = "Returns the chain of origin contexts from the current context back to the root.")
    public ResponseEntity<List<ContextHistoryRecord>> getOriginStack() {
        String user = workspaceResolver.resolveCurrentUsername();
        List<ContextHistoryRecord> history = contextHistoryService.getHistory(user);
        // Filter to only entries that form the origin chain
        // Walk backwards from most recent, following originContextId links
        List<ContextHistoryRecord> originStack = new ArrayList<>();
        if (!history.isEmpty()) {
            // Start with the most recent entry
            ContextHistoryRecord current = history.get(0);
            originStack.add(current);
            // Follow origin chain
            for (ContextHistoryRecord record : history) {
                if (current.getOriginContextId() != null
                        && current.getOriginContextId().equals(record.getToContextId())) {
                    originStack.add(record);
                    current = record;
                }
            }
        }
        return ResponseEntity.ok(originStack);
    }

    @PostMapping("/history/return-to")
    @Operation(summary = "Return to a specific history entry",
            description = "Navigates to a specific context from the navigation history, " +
                    "updating the user's current context and recording the navigation event.")
    public ResponseEntity<Map<String, Object>> returnToHistoryEntry(
            @RequestParam String contextId) {
        String user = workspaceResolver.resolveCurrentUsername();
        List<ContextHistoryRecord> history = contextHistoryService.getHistory(user);
        // Find the target entry
        ContextHistoryRecord target = history.stream()
                .filter(r -> contextId.equals(r.getToContextId()))
                .findFirst()
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        if (target != null) {
            // Perform actual navigation via the context navigation service
            ContextRef newContext = contextNavigationService.switchContext(
                    user, target.getToBranch(), target.getToCommitId());
            result.put("success", true);
            result.put("branch", newContext.branch());
            result.put("commitId", newContext.commitId());
            result.put("contextId", newContext.contextId());
        } else {
            result.put("success", false);
            result.put("error", "History entry not found for contextId: " + contextId);
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/history")
    @Operation(summary = "Clear navigation history",
            description = "Deletes all navigation history entries for the current user.")
    public ResponseEntity<Map<String, Object>> clearHistory() {
        String user = workspaceResolver.resolveCurrentUsername();
        contextHistoryService.clearHistory(user);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "History cleared for user: " + user
        ));
    }
}
