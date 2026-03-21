package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.ExternalGitSyncService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for external Git repository synchronization.
 *
 * <p>Provides endpoints to fetch from, push to, and fully sync with an
 * external Git remote when the system is configured in
 * {@link RepositoryTopologyMode#EXTERNAL_CANONICAL} mode.
 */
@RestController
@RequestMapping("/api/workspace/external")
@Tag(name = "External Git Sync")
public class ExternalSyncController {

    private final ExternalGitSyncService externalGitSyncService;
    private final SystemRepositoryService systemRepositoryService;
    private final WorkspaceResolver workspaceResolver;

    public ExternalSyncController(ExternalGitSyncService externalGitSyncService,
                                  SystemRepositoryService systemRepositoryService,
                                  WorkspaceResolver workspaceResolver) {
        this.externalGitSyncService = externalGitSyncService;
        this.systemRepositoryService = systemRepositoryService;
        this.workspaceResolver = workspaceResolver;
    }

    @PostMapping("/fetch")
    @Operation(summary = "Fetch from external remote",
            description = "Fetches all branches from the configured external Git remote " +
                    "into the system repository. Requires EXTERNAL_CANONICAL topology mode.")
    public ResponseEntity<Map<String, Object>> fetchFromExternal() {
        try {
            var result = externalGitSyncService.fetchFromExternal();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updates", result.getTrackingRefUpdates().size());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Configuration error",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Fetch failed",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/push")
    @Operation(summary = "Push to external remote",
            description = "Pushes the shared branch to the configured external Git remote.")
    public ResponseEntity<Map<String, Object>> pushToExternal(
            @RequestParam(required = false) String branch) {
        try {
            String targetBranch = branch != null ? branch : systemRepositoryService.getSharedBranch();
            var result = externalGitSyncService.pushToExternal(targetBranch);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("branch", targetBranch);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Configuration error",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Push failed",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/full-sync")
    @Operation(summary = "Full sync with external remote",
            description = "Fetches from the external remote and merges changes into the shared branch.")
    public ResponseEntity<Map<String, Object>> fullSync() {
        try {
            String username = workspaceResolver.resolveCurrentUsername();
            String commitId = externalGitSyncService.fullSync(username);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("commitId", commitId);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Configuration error",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Full sync failed",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/status")
    @Operation(summary = "Get external sync status",
            description = "Returns the current external sync configuration and timestamps.")
    public ResponseEntity<Map<String, Object>> getStatus() {
        var status = externalGitSyncService.getStatus();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("externalEnabled", status.externalEnabled());
        response.put("externalUrl", status.externalUrl());
        response.put("lastFetchAt", status.lastFetchAt());
        response.put("lastPushAt", status.lastPushAt());
        response.put("lastFetchCommit", status.lastFetchCommit());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/configure")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Configure external repository",
            description = "Set the external URL and topology mode for the system repository.")
    public ResponseEntity<Map<String, Object>> configure(
            @RequestParam(required = false) String externalUrl,
            @RequestParam(required = false) String topologyMode) {
        try {
            SystemRepository sysRepo = systemRepositoryService.getPrimaryRepository();

            if (externalUrl != null) {
                sysRepo.setExternalUrl(externalUrl);
            }
            if (topologyMode != null) {
                sysRepo.setTopologyMode(RepositoryTopologyMode.valueOf(topologyMode));
            }

            systemRepositoryService.save(sysRepo);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("topologyMode", sysRepo.getTopologyMode().name());
            response.put("externalUrl", sysRepo.getExternalUrl());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid parameter",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Configuration failed",
                    "message", e.getMessage()
            ));
        }
    }
}
