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
            if(description!=null && description.length()>1000) throw new IllegalArgumentException("Description must not exceed 1000 characters");
        }
    }
    public record Architecture(String repositoryId,String displayName,String slug,String defaultBranch,boolean owned,
                               String description,RepositoryLifecycleState lifecycleState) {}
    public record WorkingCopy(String workspaceId,String sourceRepositoryId,String sourceBranch,String currentBranch,
                              String displayName,WorkspaceProvisioningStatus provisioningStatus) {}
    @GetMapping
    @Operation(summary="List readable persistent architectures for requirement intake",
            description="Lists active architectures readable by the authenticated user and that user's incomplete private creations. Includes lifecycle state so an interrupted creation can be recovered.")
    public ResponseEntity<List<Architecture>> list() {
        String user=resolver.resolveCurrentUsername();
        var readable=new ArrayList<>(repositories.listActiveRepositories().stream().filter(repo->membership.canRead(repo,user)).toList());
        readable.addAll(repositories.listOwnIncompleteCreations(user));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(readable.stream()
                .sorted(Comparator.comparing(SystemRepository::getDisplayName,String.CASE_INSENSITIVE_ORDER)).map(repo->view(repo,user)).toList());
    }
    @PostMapping
    @Operation(summary="Create an independent private architecture",description="Uses the existing logical JGit provisioning service. Keep the slug stable across a transport retry and recover the already-created owned architecture before making a working copy.")
    public ResponseEntity<Architecture> create(@RequestBody Create request) {
        String user=resolver.resolveCurrentUsername();
        var result=provisioner.createOrResumePrivateRepository(request.displayName(),request.slug(),request.description(),user);
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(view(result.repository(),user));
    }
    @GetMapping("/{repositoryId}/workspaces")
    @Operation(summary="Recover an owned architecture working copy after an interrupted response",
            description="Lists the authenticated user's unarchived private working copies of a readable architecture, including source branch, current branch and provisioning status. This read does not create or provision a working copy.")
    public ResponseEntity<List<WorkingCopy>> copies(@PathVariable String repositoryId) {
        String user=resolver.resolveCurrentUsername();var repo=repositories.getRepository(repositoryId);
        if(!membership.canRead(repo,user)) throw problem("NOT_FOUND","Architecture unavailable");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspaces.findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc(user).stream()
                .filter(w->!w.isShared() && repositoryId.equals(w.getSourceRepositoryId()))
                .map(w->new WorkingCopy(w.getWorkspaceId(),w.getSourceRepositoryId(),w.getSourceBranch(),w.getCurrentBranch(),w.getDisplayName(),w.getProvisioningStatus())).toList());
    }
    private static Architecture view(SystemRepository repo,String user){return new Architecture(repo.getRepositoryId(),repo.getDisplayName(),repo.getSlug(),repo.getDefaultBranch(),
            repo.getOwnerType()==RepositoryOwnerType.USER && user.equals(repo.getOwnerId()),repo.getDescription(),repo.getLifecycleState());}
}
