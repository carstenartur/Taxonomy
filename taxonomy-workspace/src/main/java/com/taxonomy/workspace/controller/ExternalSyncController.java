package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.ExternalGitSyncService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
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
    private final RepositoryMembershipService membershipService;

    public ExternalSyncController(ExternalGitSyncService externalGitSyncService,
                                  SystemRepositoryService systemRepositoryService,
                                  WorkspaceResolver workspaceResolver) {
        this(externalGitSyncService, systemRepositoryService, workspaceResolver, null);
    }

    @Autowired
    public ExternalSyncController(ExternalGitSyncService externalGitSyncService,
                                  SystemRepositoryService systemRepositoryService,
                                  WorkspaceResolver workspaceResolver,
                                  RepositoryMembershipService membershipService) {
        this.externalGitSyncService = externalGitSyncService;
        this.systemRepositoryService = systemRepositoryService;
        this.workspaceResolver = workspaceResolver;
        this.membershipService = membershipService;
    }

    public ResponseEntity<Map<String, Object>> fetchFromExternal() {
        return fetchFromExternal(null);
    }

    @PostMapping("/fetch")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch from external remote",
            description = "Fetches all branches from the configured external Git remote "
                    + "into remote-tracking refs. Requires EXTERNAL_CANONICAL topology mode.")
    public ResponseEntity<Map<String, Object>> fetchFromExternal(
            @RequestParam(required = false) String repositoryId) {
        try {
            if (repositoryId != null) {
                requireSelectedAccess(repositoryId, false);
            }
            var result = repositoryId == null ? externalGitSyncService.fetchFromExternal()
                    : externalGitSyncService.fetchFromExternal(repositoryId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updates", result.getTrackingRefUpdates().size());
            addRepositoryId(response, repositoryId);
            return ResponseEntity.ok(response);
        } catch (AccessDeniedException exception) {
            return forbidden();
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return configurationError(exception);
        } catch (Exception exception) {
            log.error("External Git fetch failed", exception);
            return operationFailure("FETCH_FAILED");
        }
    }

    public ResponseEntity<Map<String, Object>> pushToExternal(String branch) {
        return pushToExternal(branch, null);
    }

    @PostMapping("/push")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Push to external remote",
            description = "Pushes the selected local branch and verifies the remote update status.")
    public ResponseEntity<Map<String, Object>> pushToExternal(
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String repositoryId) {
        try {
            SystemRepository selected = repositoryId == null ? null : requireSelectedAccess(repositoryId, false);
            String targetBranch = branch != null ? branch
                    : selected != null ? selected.getDefaultBranch() : systemRepositoryService.getSharedBranch();
            var result = repositoryId == null ? externalGitSyncService.pushToExternal(targetBranch)
                    : externalGitSyncService.pushToExternal(repositoryId, targetBranch);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("branch", targetBranch);
            response.put("updates", result.getRemoteUpdates().size());
            addRepositoryId(response, repositoryId);
            return ResponseEntity.ok(response);
        } catch (ExternalGitSyncService.ExternalPushRejectedException exception) {
            log.warn("External Git push was rejected: {}", exception.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "error", "PUSH_REJECTED",
                    "message", "The external repository rejected the branch update"));
        } catch (AccessDeniedException exception) {
            return forbidden();
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return configurationError(exception);
        } catch (Exception exception) {
            log.error("External Git push failed", exception);
            return operationFailure("PUSH_FAILED");
        }
    }

    public ResponseEntity<Map<String, Object>> fullSync() {
        return fullSync(null);
    }

    @PostMapping("/full-sync")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Synchronize with external remote",
            description = "Fetches the configured shared branch and integrates it using Git "
                    + "ancestry, fast-forward, or a three-way merge. Conflicting local changes "
                    + "are preserved and reported with HTTP 409.")
    public ResponseEntity<Map<String, Object>> fullSync(
            @RequestParam(required = false) String repositoryId) {
        try {
            if (repositoryId != null) {
                requireSelectedAccess(repositoryId, false);
            }
            String username = workspaceResolver.resolveCurrentUsername();
            String commitId = repositoryId == null ? externalGitSyncService.fullSync(username)
                    : externalGitSyncService.fullSync(repositoryId, username);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("status", commitId != null ? "INTEGRATED" : "NO_REMOTE_BRANCH");
            response.put("commitId", commitId);
            addRepositoryId(response, repositoryId);
            return ResponseEntity.ok(response);
        } catch (ExternalGitSyncService.ExternalSyncConflictException exception) {
            log.warn("External Git synchronization found a merge conflict");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "error", "MERGE_CONFLICT",
                    "message", "Local and external changes conflict; "
                            + "the local shared branch was not changed"));
        } catch (AccessDeniedException exception) {
            return forbidden();
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return configurationError(exception);
        } catch (Exception exception) {
            log.error("External Git full synchronization failed", exception);
            return operationFailure("FULL_SYNC_FAILED");
        }
    }

    public ResponseEntity<Map<String, Object>> getStatus() {
        return getStatus(null);
    }

    @GetMapping("/status")
    @Operation(summary = "Get external sync status",
            description = "Returns external synchronization configuration and timestamps; "
                    + "credentials are never included.")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestParam(required = false) String repositoryId) {
        if (repositoryId != null) {
            try {
                SystemRepository selected = systemRepositoryService.getRepository(repositoryId);
                if (membershipService == null || !membershipService.canRead(selected,
                        workspaceResolver.resolveCurrentUsername())) {
                    return notFound();
                }
            } catch (IllegalArgumentException exception) {
                return notFound();
            }
        }
        var status = repositoryId == null ? externalGitSyncService.getStatus()
                : externalGitSyncService.getStatus(repositoryId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("externalEnabled", status.externalEnabled());
        response.put("externalUrl", status.externalUrl());
        response.put("credentialConfigured", status.credentialConfigured());
        response.put("lastFetchAt", status.lastFetchAt());
        response.put("lastPushAt", status.lastPushAt());
        response.put("lastFetchCommit", status.lastFetchCommit());
        addRepositoryId(response, repositoryId);
        return ResponseEntity.ok(response);
    }

    public ResponseEntity<Map<String, Object>> configure(String externalUrl, String topologyMode) {
        return configure(externalUrl, topologyMode, null);
    }

    @PutMapping("/configure")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Configure external repository",
            description = "Sets the external URL and topology mode for the system repository. "
                    + "Credentials are configured separately and are write-only.")
    public ResponseEntity<Map<String, Object>> configure(
            @RequestParam(required = false) String externalUrl,
            @RequestParam(required = false) String topologyMode,
            @RequestParam(required = false) String repositoryId) {
        try {
            SystemRepository systemRepository = repositoryId == null
                    ? systemRepositoryService.getPrimaryRepository() : requireSelectedAccess(repositoryId, true);
            RepositoryTopologyMode selectedMode = topologyMode == null ? null
                    : RepositoryTopologyMode.valueOf(topologyMode);

            if (externalUrl != null) {
                validateExternalUrl(externalUrl);
                systemRepository.setExternalUrl(externalUrl.strip());
            }
            if (selectedMode != null) {
                systemRepository.setTopologyMode(selectedMode);
            }

            systemRepositoryService.save(systemRepository);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("topologyMode", systemRepository.getTopologyMode().name());
            response.put("externalUrl", systemRepository.getExternalUrl());
            addRepositoryId(response, repositoryId);
            return ResponseEntity.ok(response);
        } catch (AccessDeniedException exception) {
            return forbidden();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid parameter",
                    "message", exception.getMessage()));
        } catch (Exception exception) {
            log.error("External Git configuration failed", exception);
            return operationFailure("CONFIGURATION_FAILED");
        }
    }

    private SystemRepository requireSelectedAccess(String repositoryId, boolean ownerRequired) {
        SystemRepository selected = systemRepositoryService.getRepository(repositoryId);
        String username = workspaceResolver.resolveCurrentUsername();
        boolean allowed = membershipService != null && (ownerRequired
                ? membershipService.isOwner(selected, username)
                : membershipService.canMaintain(selected, username));
        if (!allowed) {
            throw new AccessDeniedException("Repository permission required");
        }
        return selected;
    }

    private static void addRepositoryId(Map<String, Object> response, String repositoryId) {
        if (repositoryId != null) {
            response.put("repositoryId", repositoryId);
        }
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "Repository permission required"));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "Repository not found"));
    }

    private static void validateExternalUrl(String externalUrl) {
        if (externalUrl.isBlank()) {
            throw new IllegalArgumentException("External URL is not configured");
        }
        try {
            URIish uri = new URIish(externalUrl.strip());
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean httpUserInfo = ("http".equals(scheme) || "https".equals(scheme))
                    && uri.getUser() != null;
            if (uri.getPass() != null || httpUserInfo) {
                throw new IllegalArgumentException(
                        "External Git URL must not contain credentials or HTTP user information");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("External Git URL is invalid", exception);
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
