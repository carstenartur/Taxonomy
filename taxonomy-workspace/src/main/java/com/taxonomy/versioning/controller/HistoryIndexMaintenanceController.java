package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.versioning.service.HistoryIndexJournal;
import com.taxonomy.versioning.service.HistoryIndexMaintenance;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational maintenance API for the selected repository/workspace commit index.
 *
 * <p>The existing POST endpoint performs incremental indexing. These additional
 * HTTP verbs deliberately reuse the same resource path while delegating tenant
 * resolution to {@link DslOperationsFacade}: PUT atomically rebuilds one branch;
 * DELETE purges only the exact selected repository/workspace projection.</p>
 */
@RestController
@RequestMapping("/api/dsl/history/index")
@Tag(name = "DSL History Index Maintenance")
public class HistoryIndexMaintenanceController {

    private final DslOperationsFacade operations;
    private final HistoryIndexMaintenance maintenance;

    public HistoryIndexMaintenanceController(DslOperationsFacade operations, HistoryIndexMaintenance maintenance) {
        this.operations = operations;
        this.maintenance = maintenance;
    }

    @Operation(
            summary = "Rebuild one history branch",
            description = "Atomically rebuilds the selected repository/workspace branch from authoritative JGit history")
    @PutMapping
    public ResponseEntity<Map<String, Object>> rebuild(
            @RequestParam(defaultValue = "draft") String branch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Causation-Id", required = false) String cause) {
        String normalizedBranch = branch == null ? "" : branch.strip();
        if (normalizedBranch.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "operation", "rebuild",
                    "error", "branch must not be blank"));
        }

        return response(operations.maintainHistory(HistoryIndexJournal.Kind.REBUILD, normalizedBranch, key, cause));
    }

    @Operation(
            summary = "Purge the selected history projection",
            description = "Deletes only the exact selected repository/workspace commit-history projection")
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> purge(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Causation-Id", required = false) String cause) {
        return response(operations.maintainHistory(HistoryIndexJournal.Kind.PURGE, null, key, cause));
    }

    @GetMapping("/operations/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        return ResponseEntity.ok(body(maintenance.get(id)));
    }

    @PostMapping("/operations/{id}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable String id) {
        return response(maintenance.retry(id));
    }

    @DeleteMapping("/operations/{id}")
    public ResponseEntity<Map<String, Object>> archive(@PathVariable String id) {
        return ResponseEntity.ok(body(maintenance.archive(id)));
    }

    static ResponseEntity<Map<String, Object>> response(HistoryIndexJournal.Receipt receipt) {
        int status = switch (receipt.phase()) {
            case "COMPLETED" -> 200;
            case "ADMITTED", "RUNNING", "RETRYABLE" -> 202;
            case "DENIED", "AWAITING_AUTHORIZATION" -> 403;
            default -> 409;
        };
        return ResponseEntity.status(status).body(body(receipt));
    }

    private static Map<String, Object> body(HistoryIndexJournal.Receipt receipt) {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", receipt.kind().name().toLowerCase(java.util.Locale.ROOT));
        result.put("receipt", receipt);
        if (receipt.kind() != HistoryIndexJournal.Kind.PURGE) result.put("branch", receipt.branch());
        if (receipt.count() != null) result.put(receipt.kind() == HistoryIndexJournal.Kind.PURGE ? "purged" : "indexed", receipt.count());
        return result;
    }
}
