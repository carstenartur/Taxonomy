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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** REST API for workspace-isolated architecture context navigation. */
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

    private WorkspaceContext resolveContext() {
        return workspaceResolver.resolveCurrentContext();
    }

    @GetMapping("/current")
    @Operation(summary = "Get the current architecture context")
    public ResponseEntity<ContextRef> getCurrentContext() {
        return ResponseEntity.ok(navigationService.getCurrentContext(
                workspaceResolver.resolveCurrentUsername()));
    }

    @PostMapping("/open")
    @Operation(summary = "Open a context (read-only or editable)")
    public ResponseEntity<ContextRef> openContext(
            @RequestParam(defaultValue = "draft") String branch,
            @RequestParam(required = false) String commitId,
            @RequestParam(defaultValue = "true") boolean readOnly,
            @RequestParam(required = false) String searchQuery,
            @RequestParam(required = false) String elementId) {
        String user = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = resolveContext();
        if (readOnly) {
            return ResponseEntity.ok(navigationService.openReadOnly(
                    user, branch, commitId, context, searchQuery, elementId));
        }
        return ResponseEntity.ok(navigationService.switchContext(
                user, branch, commitId, context));
    }

    @PostMapping("/return-to-origin")
    @Operation(summary = "Return to the origin context")
    public ResponseEntity<ContextRef> returnToOrigin() {
        return ResponseEntity.ok(navigationService.returnToOrigin(
                workspaceResolver.resolveCurrentUsername()));
    }

    @PostMapping("/back")
    @Operation(summary = "Go one step back in navigation history")
    public ResponseEntity<ContextRef> back() {
        return ResponseEntity.ok(navigationService.back(
                workspaceResolver.resolveCurrentUsername()));
    }

    @GetMapping("/history")
    @Operation(summary = "Get the navigation history")
    public ResponseEntity<List<ContextHistoryEntry>> getHistory() {
        return ResponseEntity.ok(navigationService.getHistory(
                workspaceResolver.resolveCurrentUsername()));
    }

    @PostMapping("/variant")
    @Operation(summary = "Create a new branch variant from the current context")
    public ResponseEntity<Map<String, Object>> createVariant(@RequestParam String name) {
        try {
            String user = workspaceResolver.resolveCurrentUsername();
            ContextRef variant = navigationService.createVariantFromCurrent(
                    user, name, resolveContext());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("context", variant);
            result.put("branch", variant.branch());
            return ResponseEntity.ok(result);
        } catch (IOException error) {
            log.error("Failed to create variant '{}'", name, error);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to create variant"));
        }
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare two architecture contexts")
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
            ContextComparison comparison = leftCommit != null || rightCommit != null
                    ? compareService.compareContexts(left, right, resolveContext())
                    : compareService.compareBranches(left, right, resolveContext());
            return ResponseEntity.ok(applyFilter(comparison, filter));
        } catch (IOException error) {
            log.error("Compare failed", error);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/copy-back/preview")
    @Operation(summary = "Preview a selective transfer")
    public ResponseEntity<Map<String, Object>> previewTransfer(
            @RequestBody TransferSelection selection) {
        try {
            List<TransferConflict> conflicts = transferService.previewTransfer(
                    selection, resolveContext());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conflicts", conflicts);
            result.put("hasConflicts", !conflicts.isEmpty());
            result.put("selectedElements", selection.selectedElementIds().size());
            result.put("selectedRelations", selection.selectedRelationIds().size());
            result.put("expectedTargetHead", selection.targetContextId());
            result.put("mode", selection.mode());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        } catch (IOException error) {
            log.error("Transfer preview failed", error);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Transfer preview failed"));
        }
    }

    @PostMapping("/copy-back/apply")
    @Operation(summary = "Apply a selective transfer")
    public ResponseEntity<Map<String, Object>> applyTransfer(
            @RequestBody TransferSelection selection) {
        try {
            String previousHead = selection.targetContextId();
            String commitId = transferService.applyTransfer(selection);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("commitId", commitId);
            result.put("previousHead", previousHead);
            result.put("mode", selection.mode());
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (java.util.ConcurrentModificationException error) {
            return ResponseEntity.status(409).body(Map.of("error", error.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        } catch (IOException error) {
            log.error("Transfer failed", error);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Transfer failed"));
        }
    }

    private ContextComparison applyFilter(ContextComparison comparison, Set<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return comparison;
        }
        List<SemanticChange> filtered = comparison.changes().stream()
                .filter(change -> (filter.contains("elements") && "ELEMENT".equals(change.category()))
                        || (filter.contains("relations") && "RELATION".equals(change.category())))
                .toList();
        return new ContextComparison(comparison.left(), comparison.right(),
                comparison.summary(), filtered, comparison.rawDslDiff());
    }
}
