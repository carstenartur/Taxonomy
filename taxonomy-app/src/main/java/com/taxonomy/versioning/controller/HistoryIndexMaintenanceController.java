package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.DslOperationsFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public HistoryIndexMaintenanceController(DslOperationsFacade operations) {
        this.operations = operations;
    }

    @Operation(
            summary = "Rebuild one history branch",
            description = "Atomically rebuilds the selected repository/workspace branch from authoritative JGit history")
    @PutMapping
    public ResponseEntity<Map<String, Object>> rebuild(
            @RequestParam(defaultValue = "draft") String branch) {
        String normalizedBranch = branch.strip();
        int indexed = operations.rebuildHistoryBranch(normalizedBranch);
        return ResponseEntity.ok(Map.of(
                "operation", "rebuild",
                "branch", normalizedBranch,
                "indexed", indexed));
    }

    @Operation(
            summary = "Purge the selected history projection",
            description = "Deletes only the exact selected repository/workspace commit-history projection")
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> purge() {
        int purged = operations.purgeHistoryIndex();
        return ResponseEntity.ok(Map.of(
                "operation", "purge",
                "purged", purged));
    }
}
