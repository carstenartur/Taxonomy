package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.ExternalGitSyncService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** REST API for conflict-safe external Git repository synchronization. */
@RestController
@RequestMapping("/api/workspace/external")
@Tag(name = "External Git Sync")
public class ExternalSyncController {

    private static final Logger log = LoggerFactory.getLogger(ExternalSyncController.class);
    private static final String OPERATION_FAILED_MESSAGE =
            "The external Git operation failed; see the server log for diagnostic details";

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
            description = "Fetches all branches from the configured external Git remote "
                    + "into remote-tracking refs. Requires EXTERNAL_CANONICAL topology mode.")
    public ResponseEntity<Map<String, Object>> fetchFromExternal() {
        try {
            var result = externalGitSyncService.fetchFromExternal();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updates", result.getTrackingRefUpdates().size());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return configurationError(exception);
        } catch (Exception exception) {
            log.error("External Git fetch failed", exception);
            return operationFailure("FETCH_FAILED");
        }
    }

    @PostMapping("/push")
    @Operation(summary = "Push to external remote",
            description = "Pushes the selected local branch and verifies the remote update status.")
    public ResponseEntity<Map<String, Object>> pushToExternal(
            @RequestParam(required = false) String branch) {
        try {
            String targetBranch = branch != null
                    ? branch
                    : systemRepositoryService.getSharedBranch();
            var result = externalGitSyncService.pushToExternal(targetBranch);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("branch", targetBranch);
            response.put("updates", result.getRemoteUpdates().size());
            return ResponseEntity.ok(response);
        } catch (ExternalGitSyncService.ExternalPushRejectedException exception) {
            log.warn("External Git push was rejected: {}", exception.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "error", "PUSH_REJECTED",
                    "message", "The external repository rejected the branch update"));
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return configurationError(exception);
        } catch (Exception exception) {
            log.error("External Git push failed", exception);
            return operationFailure("PUSH_FAILED");
        }
    }

    @PostMapping("/full-sync")
    @Operation(summary = "Synchronize with external remote",
            description = "Fetches the configured shared branch and integrates it using Git "
                    + "ancestry, fast-forward, or a three-way merge. Conflicting local changes "
                    + "are preserved and reported with HTTP 409.")
    public ResponseEntity<Map<String, Object>> fullSync() {
        try {
            String username = workspaceResolver.resolveCurrentUsername();
            String commitId = externalGitSyncService.fullSync(username);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("status", commitId != null ? "INTEGRATED" : "NO_REMOTE_BRANCH");
            response.put("commitId", commitId);
            return ResponseEntity.ok(response);
        } catch (ExternalGitSyncService.ExternalSyncConflictException exception) {
            log.warn("External Git synchronization found a merge conflict");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "error", "MERGE_CONFLICT",
                    "message", "Local and external changes conflict; "
                            + "the local shared branch was not changed"));
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return configurationError(exception);
        } catch (Exception exception) {
            log.error("External Git full synchronization failed", exception);
            return operationFailure("FULL_SYNC_FAILED");
        }
    }

    @GetMapping("/status")
    @Operation(summary = "Get external sync status",
            description = "Returns external synchronization configuration and timestamps; "
                    + "credentials are never included.")
    public ResponseEntity<Map<String, Object>> getStatus() {
        var status = externalGitSyncService.getStatus();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("externalEnabled", status.externalEnabled());
        response.put("externalUrl", status.externalUrl());
        response.put("credentialConfigured", status.credentialConfigured());
        response.put("lastFetchAt", status.lastFetchAt());
        response.put("lastPushAt", status.lastPushAt());
        response.put("lastFetchCommit", status.lastFetchCommit());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/configure")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Configure external repository",
            description = "Sets the external URL and topology mode for the system repository. "
                    + "Credentials are configured separately and are write-only.")
    public ResponseEntity<Map<String, Object>> configure(
            @RequestParam(required = false) String externalUrl,
            @RequestParam(required = false) String topologyMode) {
        try {
            SystemRepository systemRepository = systemRepositoryService.getPrimaryRepository();

            if (externalUrl != null) {
                // Parse and reject embedded HTTP credentials before the value can
                // reach persistence, logs, status responses, or JGit transport.
                String validatedUrl = ExternalGitSyncService
                        .validateExternalUrl(externalUrl)
                        .toPrivateString();
                systemRepository.setExternalUrl(validatedUrl);
            }
            if (topologyMode != null) {
                systemRepository.setTopologyMode(
                        RepositoryTopologyMode.valueOf(topologyMode));
            }

            systemRepositoryService.save(systemRepository);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("topologyMode", systemRepository.getTopologyMode().name());
            response.put("externalUrl", systemRepository.getExternalUrl());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid parameter",
                    "message", exception.getMessage()));
        } catch (Exception exception) {
            log.error("External Git configuration failed", exception);
            return operationFailure("CONFIGURATION_FAILED");
        }
    }

    private static ResponseEntity<Map<String, Object>> configurationError(
            RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Configuration error",
                "message", exception.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> operationFailure(String code) {
        return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", code,
                "message", OPERATION_FAILED_MESSAGE));
    }
}
