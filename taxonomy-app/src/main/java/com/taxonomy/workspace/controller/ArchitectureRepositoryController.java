package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST catalog for central architecture repositories and their working copies/forks. */
@RestController
@RequestMapping("/api/repositories")
@Tag(name = "Architecture Repositories")
public class ArchitectureRepositoryController {

    private final SystemRepositoryService repositoryService;
    private final ArchitectureRepositoryProvisioningService provisioningService;
    private final RepositoryWorkspaceService workspaceService;
    private final WorkspaceResolver workspaceResolver;

    public ArchitectureRepositoryController(
            SystemRepositoryService repositoryService,
            ArchitectureRepositoryProvisioningService provisioningService,
            RepositoryWorkspaceService workspaceService,
            WorkspaceResolver workspaceResolver) {
        this.repositoryService = repositoryService;
        this.provisioningService = provisioningService;
        this.workspaceService = workspaceService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping
    @Operation(summary = "List visible central architecture repositories")
    public ResponseEntity<List<Map<String, Object>>> listRepositories() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(repositoryService.listActiveRepositories().stream()
                .filter(repository -> canView(repository, user))
                .map(this::toMap)
                .toList());
    }

    @GetMapping("/{repositoryId}")
    @Operation(summary = "Get one visible central architecture repository")
    public ResponseEntity<Map<String, Object>> getRepository(@PathVariable String repositoryId) {
        String user = workspaceResolver.resolveCurrentUsername();
        SystemRepository repository;
        try {
            repository = repositoryService.getRepository(repositoryId);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        if (!canView(repository, user)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toMap(repository));
    }

    @PostMapping
    @Operation(summary = "Create a new central architecture repository")
    public ResponseEntity<?> createRepository(@RequestBody CreateRepositoryRequest request) {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            SystemRepository repository = provisioningService.createRepository(
                    request.displayName(),
                    request.slug(),
                    request.description(),
                    request.visibility(),
                    user,
                    request.defaultBranch());
            return ResponseEntity.ok(toMap(repository));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Repository provisioning failed",
                    "message", safeMessage(exception)));
        }
    }

    @PostMapping("/{repositoryId}/workspaces")
    @Operation(summary = "Create a personal working copy from a central repository")
    public ResponseEntity<?> createWorkspace(
            @PathVariable String repositoryId,
            @RequestBody CreateWorkspaceRequest request) {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            SystemRepository repository = repositoryService.getRepository(repositoryId);
            if (!canView(repository, user)) {
                return ResponseEntity.notFound().build();
            }
            UserWorkspace workspace = workspaceService.createWorkingCopy(
                    user,
                    repositoryId,
                    request.sourceBranch(),
                    request.displayName(),
                    request.description());
            return ResponseEntity.ok(Map.of(
                    "workspaceId", workspace.getWorkspaceId(),
                    "displayName", workspace.getDisplayName(),
                    "sourceRepositoryId", workspace.getSourceRepositoryId(),
                    "sourceBranch", workspace.getSourceBranch(),
                    "currentBranch", workspace.getCurrentBranch(),
                    "baseCommit", workspace.getBaseCommit(),
                    "currentCommit", workspace.getCurrentCommit(),
                    "provisioningStatus", workspace.getProvisioningStatus().name()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(exception)));
        }
    }

    @PostMapping("/{repositoryId}/forks")
    @Operation(summary = "Create a durable central fork")
    public ResponseEntity<?> createFork(
            @PathVariable String repositoryId,
            @RequestBody CreateForkRequest request) {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            SystemRepository source = repositoryService.getRepository(repositoryId);
            if (!canView(source, user)) {
                return ResponseEntity.notFound().build();
            }
            SystemRepository fork = provisioningService.createFork(
                    repositoryId,
                    request.sourceBranch(),
                    request.displayName(),
                    request.slug(),
                    request.description(),
                    request.visibility(),
                    user);
            return ResponseEntity.ok(toMap(fork));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(exception)));
        } catch (RuntimeException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Fork provisioning failed",
                    "message", safeMessage(exception)));
        }
    }

    private boolean canView(SystemRepository repository, String username) {
        if (repository.getLifecycleState() != RepositoryLifecycleState.ACTIVE) {
            return false;
        }
        if (repository.isPrimaryRepo()) {
            return true;
        }
        RepositoryVisibility visibility = repository.getVisibility();
        if (visibility == RepositoryVisibility.PUBLIC
                || visibility == RepositoryVisibility.ORGANIZATION) {
            return true;
        }
        return username != null && username.equals(repository.getOwnerId());
    }

    private Map<String, Object> toMap(SystemRepository repository) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("repositoryId", repository.getRepositoryId());
        result.put("slug", repository.getSlug());
        result.put("displayName", repository.getDisplayName());
        result.put("description", repository.getDescription());
        result.put("defaultBranch", repository.getDefaultBranch());
        result.put("visibility", repository.getVisibility());
        result.put("lifecycleState", repository.getLifecycleState());
        result.put("ownerType", repository.getOwnerType());
        result.put("ownerId", repository.getOwnerId());
        result.put("primary", repository.isPrimaryRepo());
        result.put("topologyMode", repository.getTopologyMode());
        result.put("upstreamRepositoryId", repository.getUpstreamRepositoryId());
        result.put("upstreamBranch", repository.getUpstreamBranch());
        result.put("forkPointCommit", repository.getForkPointCommit());
        result.put("createdAt", repository.getCreatedAt());
        result.put("updatedAt", repository.getUpdatedAt());
        return result;
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record CreateRepositoryRequest(
            String displayName,
            String slug,
            String description,
            RepositoryVisibility visibility,
            String defaultBranch) {
    }

    public record CreateWorkspaceRequest(
            String displayName,
            String description,
            String sourceBranch) {
    }

    public record CreateForkRequest(
            String displayName,
            String slug,
            String description,
            RepositoryVisibility visibility,
            String sourceBranch) {
    }
}
