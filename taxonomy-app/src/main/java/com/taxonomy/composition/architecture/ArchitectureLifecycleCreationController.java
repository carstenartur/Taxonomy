package com.taxonomy.composition.architecture;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import java.util.*;
import static com.taxonomy.composition.architecture.ArchitectureLifecycleService.problem;

/** Core lifecycle creation is independent of the optional administration catalogue UI. */
@RestController
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
@RequestMapping("/api/architecture/lifecycle/architectures")
public class ArchitectureLifecycleCreationController {
    private final SystemRepositoryService repositories;private final ArchitectureRepositoryProvisioningService provisioner;
    private final RepositoryMembershipService membership;private final WorkspaceResolver resolver;private final UserWorkspaceRepository workspaces;
    public ArchitectureLifecycleCreationController(SystemRepositoryService repositories,ArchitectureRepositoryProvisioningService provisioner,
            RepositoryMembershipService membership,WorkspaceResolver resolver,UserWorkspaceRepository workspaces) {
        this.repositories=repositories;this.provisioner=provisioner;this.membership=membership;this.resolver=resolver;this.workspaces=workspaces;
    }
    public record Create(String displayName,String slug,String description) {
        public Create {
            if(displayName==null || displayName.isBlank() || displayName.length()>255 || slug==null || !slug.matches("[a-z0-9][a-z0-9-]{0,99}"))
                throw new IllegalArgumentException("A bounded name and stable lower-case slug are required");
            if(description!=null && description.length()>4000) throw new IllegalArgumentException("Description is too long");
        }
    }
    public record Architecture(String repositoryId,String displayName,String slug,String defaultBranch,boolean owned) {}
    public record WorkingCopy(String workspaceId,String sourceRepositoryId,String sourceBranch,String currentBranch,
                              String displayName,WorkspaceProvisioningStatus provisioningStatus) {}
    @GetMapping
    @Operation(summary="List readable persistent architectures for requirement intake")
    public ResponseEntity<List<Architecture>> list() {
        String user=resolver.resolveCurrentUsername();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(repositories.listActiveRepositories().stream()
                .filter(repo->membership.canRead(repo,user)).map(repo->view(repo,user)).toList());
    }
    @PostMapping
    @Operation(summary="Create an independent private architecture",description="Uses the existing logical JGit provisioning service. Keep the slug stable across a transport retry and recover the already-created owned architecture before making a working copy.")
    public ResponseEntity<Architecture> create(@RequestBody Create request) {
        String user=resolver.resolveCurrentUsername();
        var existing=repositories.listActiveRepositories().stream().filter(repo->request.slug().equals(repo.getSlug())).findFirst();
        if(existing.isPresent()) {
            var repo=existing.get();
            if(!user.equals(repo.getOwnerId()) || !request.displayName().strip().equals(repo.getDisplayName())
                    || !Objects.equals(request.description(),repo.getDescription())) throw problem("COMMAND_ID_REUSED","This architecture slug already identifies another creation");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(repo,user));
        }
        var created=provisioner.createRepository(request.displayName(),request.slug(),request.description(),RepositoryVisibility.PRIVATE,user,"draft");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(view(created,user));
    }
    @GetMapping("/{repositoryId}/workspaces")
    @Operation(summary="Recover an owned architecture working copy after an interrupted response")
    public ResponseEntity<List<WorkingCopy>> copies(@PathVariable String repositoryId) {
        String user=resolver.resolveCurrentUsername();var repo=repositories.getRepository(repositoryId);
        if(!membership.canRead(repo,user)) throw problem("NOT_FOUND","Architecture unavailable");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspaces.findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc(user).stream()
                .filter(w->!w.isShared() && repositoryId.equals(w.getSourceRepositoryId()))
                .map(w->new WorkingCopy(w.getWorkspaceId(),w.getSourceRepositoryId(),w.getSourceBranch(),w.getCurrentBranch(),w.getDisplayName(),w.getProvisioningStatus())).toList());
    }
    private static Architecture view(SystemRepository repo,String user){return new Architecture(repo.getRepositoryId(),repo.getDisplayName(),repo.getSlug(),repo.getDefaultBranch(),user.equals(repo.getOwnerId()));}
}
