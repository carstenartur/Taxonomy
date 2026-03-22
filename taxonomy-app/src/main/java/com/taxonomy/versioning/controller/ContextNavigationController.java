package com.taxonomy.versioning.controller;

import com.taxonomy.dto.ContextComparison;
import com.taxonomy.dto.ContextHistoryEntry;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.SemanticChange;
import com.taxonomy.dto.TransferConflict;
import com.taxonomy.dto.TransferSelection;
import com.taxonomy.versioning.service.ContextCompareService;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.versioning.service.SelectiveTransferService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for architecture context navigation.
 *
 * <p>Provides browser-like navigation through architecture versions:
 * open read-only snapshots, switch branches, compare contexts, and
 * selectively transfer elements between versions.
 *
 * <p>All navigation state is isolated per authenticated user via the
 * workspace manager. Each user has their own context, history, and
 * navigation trail.
 */
@RestController
@RequestMapping("/api/context")
@Tag(name = "Context Navigation")
public class ContextNavigationController {

    private static final Logger log = LoggerFactory.getLogger(ContextNavigationController.class);

    private final ContextNavigationService navigationService;
    private final ContextCompareService compareService;
    private final SelectiveTransferService transferService;
    private final WorkspaceResolver workspaceResolver;

    public ContextNavigationController(ContextNavigationService navigationService,
                                       ContextCompareService compareService,
                                       SelectiveTransferService transferService,
                                       WorkspaceResolver workspaceResolver) {
        this.navigationService = navigationService;
        this.compareService = compareService;
        this.transferService = transferService;
        this.workspaceResolver = workspaceResolver;
    }

    /**
     * Resolve the current workspace context from the authenticated user.
     * Falls back to {@link WorkspaceContext#SHARED} if resolution fails.
     */
    private WorkspaceContext resolveContext() {
        try {
            return workspaceResolver.resolveCurrentContext();
        } catch (Exception e) {
            return WorkspaceContext.SHARED;
        }
    }

    // ── Phase 1: Context Navigation ─────────────────────────────────

    @GetMapping("/current")
    @Operation(summary = "Get the current architecture context",
            description = "Returns the active context including branch, commit, mode, and origin info.")
    public ResponseEntity<ContextRef> getCurrentContext() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(navigationService.getCurrentContext(user));
    }

    @PostMapping("/open")
    @Operation(summary = "Open a context (read-only or editable)",
            description = "Opens a specific branch/commit as a new context. " +
                    "If readOnly is true, write operations will be blocked.")
    public ResponseEntity<ContextRef> openContext(
            @RequestParam(defaultValue = "draft") String branch,
            @RequestParam(required = false) String commitId,
            @RequestParam(defaultValue = "true") boolean readOnly,
            @RequestParam(required = false) String searchQuery,
            @RequestParam(required = false) String elementId) {
        String user = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext ctx = resolveContext();
        if (readOnly) {
            return ResponseEntity.ok(
                    navigationService.openReadOnly(user, branch, commitId, ctx, searchQuery, elementId));
        } else {
            return ResponseEntity.ok(
                    navigationService.switchContext(user, branch, commitId, ctx));
        }
    }

    @PostMapping("/return-to-origin")
    @Operation(summary = "Return to the origin context",
            description = "Navigates back to the context from which the current context was opened.")
    public ResponseEntity<ContextRef> returnToOrigin() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(navigationService.returnToOrigin(user));
    }

    @PostMapping("/back")
    @Operation(summary = "Go one step back in navigation history",
            description = "Like the browser back button — returns to the previous context.")
    public ResponseEntity<ContextRef> back() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(navigationService.back(user));
    }

    @GetMapping("/history")
    @Operation(summary = "Get the navigation history",
            description = "Returns the list of context navigations (newest last).")
    public ResponseEntity<List<ContextHistoryEntry>> getHistory() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(navigationService.getHistory(user));
    }

    @PostMapping("/variant")
    @Operation(summary = "Create a new branch variant from the current context",
            description = "Creates a new Git branch from the current context and switches to it.")
    public ResponseEntity<Map<String, Object>> createVariant(
            @RequestParam String name) {
        try {
            String user = workspaceResolver.resolveCurrentUsername();
            ContextRef variant = navigationService.createVariantFromCurrent(user, name, resolveContext());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("context", variant);
            result.put("branch", variant.branch());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("Failed to create variant '{}'", name, e);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to create variant: " + e.getMessage()));
        }
    }

    // ── Phase 3: Compare ────────────────────────────────────────────

    @GetMapping("/compare")
    @Operation(summary = "Compare two architecture contexts",
            description = "Returns a semantic diff between two contexts identified by " +
                    "branch/commit pairs. Includes summary counts, individual changes, " +
                    "and optional raw DSL diff.")
    public ResponseEntity<ContextComparison> compare(
            @RequestParam String leftBranch,
            @RequestParam(required = false) String leftCommit,
            @RequestParam String rightBranch,
            @RequestParam(required = false) String rightCommit,
            @RequestParam(required = false) Set<String> filter) {
        try {
            ContextRef left = new ContextRef(
                    null, leftBranch, leftCommit, null, null,
                    null, null, null, null, null, false);
            ContextRef right = new ContextRef(
                    null, rightBranch, rightCommit, null, null,
                    null, null, null, null, null, false);

            ContextComparison comparison;
            if (leftCommit != null || rightCommit != null) {
                comparison = compareService.compareContexts(left, right, resolveContext());
            } else {
                comparison = compareService.compareBranches(left, right, resolveContext());
            }
            return ResponseEntity.ok(applyFilter(comparison, filter));
        } catch (IOException e) {
            log.error("Compare failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Phase 4: Selective Transfer ─────────────────────────────────

    @PostMapping("/copy-back/preview")
    @Operation(summary = "Preview a selective transfer",
            description = "Shows what would happen if the selected elements and relations " +
                    "were transferred from source to target context, including conflicts.")
    public ResponseEntity<Map<String, Object>> previewTransfer(
            @RequestBody TransferSelection selection) {
        try {
            List<TransferConflict> conflicts = transferService.previewTransfer(selection, resolveContext());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conflicts", conflicts);
            result.put("hasConflicts", !conflicts.isEmpty());
            result.put("selectedElements", selection.selectedElementIds().size());
            result.put("selectedRelations", selection.selectedRelationIds().size());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("Transfer preview failed", e);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Transfer preview failed: " + e.getMessage()));
        }
    }

    @PostMapping("/copy-back/apply")
    @Operation(summary = "Apply a selective transfer",
            description = "Transfers the selected elements and relations from the source " +
                    "context into the target context, creating a new commit.")
    public ResponseEntity<Map<String, Object>> applyTransfer(
            @RequestBody TransferSelection selection) {
        try {
            String commitId = transferService.applyTransfer(selection);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("commitId", commitId);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("Transfer failed", e);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Transfer failed: " + e.getMessage()));
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

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
}
