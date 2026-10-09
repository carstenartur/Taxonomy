package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST catalog for central architecture repositories and their working copies/forks. */
@ConditionalOnProperty(
        prefix = "taxonomy.features.multi-repository-api",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
@RestController
@RequestMapping("/api/repositories")
@Tag(name = "Architecture Repositories")
public class ArchitectureRepositoryController {

    private final SystemRepositoryService repositoryService;
    private final ArchitectureRepositoryProvisioningService provisioningService;
    private final RepositoryWorkspaceService workspaceService;
    private final RepositoryMembershipService membershipService;
    private final WorkspaceResolver workspaceResolver;

    public ArchitectureRepositoryController(
            SystemRepositoryService repositoryService,
            ArchitectureRepositoryProvisioningService provisioningService,
            RepositoryWorkspaceService workspaceService,
            RepositoryMembershipService membershipService,
            WorkspaceResolver workspaceResolver) {
        this.repositoryService = repositoryService;
        this.provisioningService = provisioningService;
        this.workspaceService = workspaceService;
        this.membershipService = membershipService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping
    @Operation(summary = "List visible central architecture repositories",
            description = "Lists active central repositories visible to the authenticated user. Results are filtered by visibility and membership rather than exposing all repository records.")
    public ResponseEntity<List<Map<String, Object>>> listRepositories() {
        String user = workspaceResolver.resolveCurrentUsername();
        return ResponseEntity.ok(repositoryService.listActiveRepositories().stream()
                .filter(repository -> canView(repository, user))
                .map(this::toMap)
                .toList());
    }

    @GetMapping("/{repositoryId}")
    @Operation(summary = "Get one visible central architecture repository",
            description = "Reads one central architecture repository visible to the authenticated user. Unavailable and non-visible repositories both return 404; knowing a repository identifier does not grant membership.")
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
    @Operation(summary = "Create a new central architecture repository",
            description = "Provisions a central architecture repository with the supplied name, slug, visibility and default branch for the authenticated creator. Invalid metadata is rejected rather than partially creating a usable repository.")
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
    @Operation(summary = "Create a personal working copy from a central repository",
            description = "Creates a working copy from a visible central repository after requiring CONTRIBUTOR permission. The requested source branch is provisioned explicitly; creation does not implicitly switch unrelated users' workspaces.")
    public ResponseEntity<?> createWorkspace(
            @PathVariable String repositoryId,
            @RequestBody CreateWorkspaceRequest request) {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            SystemRepository repository = repositoryService.getRepository(repositoryId);
            if (!canView(repository, user)) {
                return ResponseEntity.notFound().build();
            }
            if (!membershipService.canContribute(repository, user)) {
                return forbidden("Repository CONTRIBUTOR role required");
            }
            UserWorkspace workspace = workspaceService.createWorkingCopy(
                    user,
                    repositoryId,
                    request.sourceBranch(),
                    request.displayName(),
                    request.description());
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("workspaceId", workspace.getWorkspaceId());
            result.put("displayName", workspace.getDisplayName());
            result.put("sourceRepositoryId", workspace.getSourceRepositoryId());
            result.put("sourceBranch", workspace.getSourceBranch());
            result.put("currentBranch", workspace.getCurrentBranch());
            result.put("baseCommit", workspace.getBaseCommit());
            result.put("currentCommit", workspace.getCurrentCommit());
            result.put("provisioningStatus", workspace.getProvisioningStatus().name());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(exception)));
        }
    }

    @PostMapping("/{repositoryId}/forks")
    @Operation(summary = "Create a durable central fork",
            description = "Creates a fork from the selected source repository/branch after checking visibility and CONTRIBUTOR permission. Provisioning records the source authority and does not modify the source repository.")
    public ResponseEntity<?> createFork(
            @PathVariable String repositoryId,
            @RequestBody CreateForkRequest request) {
        String user = workspaceResolver.resolveCurrentUsername();
        try {
            SystemRepository source = repositoryService.getRepository(repositoryId);
            if (!canView(source, user)) {
                return ResponseEntity.notFound().build();
            }
            if (!membershipService.canContribute(source, user)) {
                return forbidden("Repository CONTRIBUTOR role required");
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

    @GetMapping("/{repositoryId}/members")
    @Operation(summary = "List repository memberships",
            description = "Lists membership roles for a visible repository after requiring repository OWNER permission. Ordinary visibility alone is insufficient to inspect or manage memberships.")
    public ResponseEntity<?> listMemberships(@PathVariable String repositoryId) {
        String user = workspaceResolver.resolveCurrentUsername();
        SystemRepository repository = visibleRepository(repositoryId, user);
        if (repository == null) {
            return ResponseEntity.notFound().build();
        }
        if (!membershipService.isOwner(repository, user)) {
            return forbidden("Repository OWNER role required");
        }
        return ResponseEntity.ok(membershipService.listMemberships(repository, user).stream()
                .map(this::toMembershipMap)
                .toList());
    }

    @PutMapping("/{repositoryId}/members/{username}")
    @Operation(summary = "Assign or change a repository membership",
            description = "Assigns or changes the specified repository member's role after requiring repository OWNER permission. Invalid roles or membership transitions are rejected rather than bypassing ownership constraints.")
    public ResponseEntity<?> updateMembership(
            @PathVariable String repositoryId,
            @PathVariable String username,
            @RequestBody UpdateMembershipRequest request) {
        String user = workspaceResolver.resolveCurrentUsername();
        SystemRepository repository = visibleRepository(repositoryId, user);
        if (repository == null) {
            return ResponseEntity.notFound().build();
        }
        if (!membershipService.isOwner(repository, user)) {
            return forbidden("Repository OWNER role required");
        }
        try {
            RepositoryMembership membership = membershipService.assignRole(
                    repository, username, request.role(), user);
            return ResponseEntity.ok(toMembershipMap(membership));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(exception)));
        } catch (AccessDeniedException exception) {
            return forbidden(safeMessage(exception));
        }
    }

    @DeleteMapping("/{repositoryId}/members/{username}")
    @Operation(summary = "Remove a repository membership",
            description = "Removes the specified member after requiring repository OWNER permission and validating membership invariants. Returns 204; a rejected removal leaves existing membership state unchanged.")
    public ResponseEntity<?> removeMembership(
            @PathVariable String repositoryId,
            @PathVariable String username) {
        String user = workspaceResolver.resolveCurrentUsername();
        SystemRepository repository = visibleRepository(repositoryId, user);
        if (repository == null) {
            return ResponseEntity.notFound().build();
        }
        if (!membershipService.isOwner(repository, user)) {
            return forbidden("Repository OWNER role required");
        }
        try {
            membershipService.removeMembership(repository, username, user);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(exception)));
        } catch (AccessDeniedException exception) {
            return forbidden(safeMessage(exception));
        }
    }

    private SystemRepository visibleRepository(String repositoryId, String username) {
        try {
            SystemRepository repository = repositoryService.getRepository(repositoryId);
            return canView(repository, username) ? repository : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean canView(SystemRepository repository, String username) {
        return membershipService.canRead(repository, username);
    }

    private Map<String, Object> toMap(SystemRepository repository) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
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

    private Map<String, Object> toMembershipMap(RepositoryMembership membership) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("username", membership.getUsername());
        result.put("role", membership.getRole());
        result.put("createdAt", membership.getCreatedAt());
        result.put("createdBy", membership.getCreatedBy());
        result.put("updatedAt", membership.getUpdatedAt());
        return result;
    }

    private static ResponseEntity<Map<String, String>> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", message));
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

    public record UpdateMembershipRequest(RepositoryRole role) {
    }
}
