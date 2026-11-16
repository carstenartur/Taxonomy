package com.taxonomy.relations.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.taxonomy.editor.ArchitectureEditPermission;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.ProposalBranchAdoptionService;
import com.taxonomy.relations.service.ProposalBranchAdoptionService.Preview;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Previews and explicitly adopts one accepted proposal from the repository's default branch. */
@RestController
@RequestMapping("/api/proposals/{proposalId}/adoption")
@Tag(name = "Proposals")
public class ProposalBranchAdoptionApiController {
    private final ProposalBranchAdoptionService adoption;
    private final WorkspaceResolver resolver;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService memberships;
    private final ContextNavigationService navigation;
    private final Optional<ArchitectureEditPermission> editPermission;

    public ProposalBranchAdoptionApiController(ProposalBranchAdoptionService adoption, WorkspaceResolver resolver,
            SystemRepositoryService repositories, RepositoryMembershipService memberships,
            ContextNavigationService navigation, Optional<ArchitectureEditPermission> editPermission) {
        this.adoption = adoption;
        this.resolver = resolver;
        this.repositories = repositories;
        this.memberships = memberships;
        this.navigation = navigation;
        this.editPermission = editPermission;
    }

    @Operation(summary = "Preview a proposal's adoption into the selected branch",
            description = "Reads the accepted proposal on the repository default branch and compares it to the selected target. Returns both exact Git heads, the available source evidence and the caller's current target write capability.")
    @GetMapping
    public ResponseEntity<AdoptionView> preview(@PathVariable Long proposalId,
            @RequestParam(required = false) String commitId) throws IOException {
        RepositoryContext target = selectedTarget(commitId);
        SystemRepository repository = readableRepository(target);
        Preview preview = adoption.preview(proposalId, source(repository, target), target);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AdoptionView(preview, mayWrite(repository, target)));
    }

    @Operation(summary = "Adopt a reviewed proposal into its displayed target",
            description = "Applies the single accepted relation after checking the current source branch and commit, selected repository and workspace, write access, and the target If-Match commit. Idempotency-Key identifies the operation. HTTP 202 reports a committed Git change whose projection still needs recovery.")
    @ApiResponse(responseCode = "200", description = "The relation is present in the selected target",
            content = @Content(schema = @Schema(implementation = AdoptionView.class)))
    @ApiResponse(responseCode = "202", description = "Git committed the relation and its projection requires recovery",
            content = @Content(schema = @Schema(implementation = PendingProjection.class)))
    @ApiResponse(responseCode = "409", description = "The selected context changed or the target has a conflicting relation")
    @ApiResponse(responseCode = "412", description = "The source or target commit has moved since the preview")
    @ApiResponse(responseCode = "428", description = "The target commit precondition is missing")
    @PostMapping
    public ResponseEntity<AdoptionView> adopt(@PathVariable Long proposalId, @RequestBody AdoptionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam(required = false) String commitId) throws IOException {
        RepositoryContext target = selectedTarget(commitId);
        SystemRepository repository = readableRepository(target);
        RepositoryContext source = source(repository, target);
        if (!request.matches(source, target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The source or target selection has changed");
        }
        if (!mayWrite(repository, target)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The selected target is not writable");
        }
        if (ifNoneMatch != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adoption requires an existing target branch");
        }
        String expectedTarget;
        String expectedSource;
        CommandMetadata metadata;
        try {
            expectedTarget = GitHttpPrecondition.expectedHead(ifMatch, null);
            expectedSource = GitHttpPrecondition.commitId(request.sourceCommit());
            metadata = new CommandMetadata(GitHttpIdempotencyKey.require(idempotencyKey));
        } catch (GitHttpPrecondition.PreconditionRequiredException missing) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "Supply the previewed target If-Match");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid commit precondition or Idempotency-Key");
        }
        RepositoryContext writable = target.scope() == RepositoryScope.CENTRAL_READ
                ? RepositoryContext.centralWrite(target.repositoryId(), target.branch(), target.username()) : target;
        Preview applied = adoption.adopt(proposalId, source, expectedSource, writable, expectedTarget, metadata);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AdoptionView(applied, true));
    }

    private RepositoryContext selectedTarget(String historicalCommit) {
        if (historicalCommit != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adoption requires a current branch selection");
        }
        return resolver.resolveUnambiguousRepositoryForRead();
    }

    private SystemRepository readableRepository(RepositoryContext target) {
        SystemRepository repository;
        try {
            repository = repositories.getRepository(target.repositoryId());
        } catch (IllegalArgumentException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!memberships.canRead(repository, target.username())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return repository;
    }

    private static RepositoryContext source(SystemRepository repository, RepositoryContext target) {
        return RepositoryContext.centralRead(repository.getRepositoryId(), repository.getDefaultBranch(), target.username());
    }

    private boolean mayWrite(SystemRepository repository, RepositoryContext target) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!ArchitectureEditPermission.allowed(auth, editPermission)) return false;
        if (target.scope() == RepositoryScope.WORKSPACE) {
            boolean allowed = target.collaborative() ? memberships.canCollaborate(repository, target.username())
                    : memberships.canContribute(repository, target.username());
            return allowed && !navigation.isReadOnlyInScope(new WorkspaceContext(
                    target.username(), target.workspaceId(), target.branch(), target.repositoryId()));
        }
        return memberships.canMaintain(repository, target.username())
                || auth.getAuthorities().stream().anyMatch(role -> "ROLE_ADMIN".equals(role.getAuthority()));
    }

    @ExceptionHandler(ProjectionPendingException.class)
    ResponseEntity<PendingProjection> recoveryPending(ProjectionPendingException pending) {
        String commit = pending.getAuthority().authoritativeCommitId();
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).eTag(GitHttpPrecondition.etag(commit))
                .body(new PendingProjection(commit, "PENDING_RECOVERY"));
    }

    @ExceptionHandler(BranchHeadConflictException.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    void staleCommit() { }

    @ExceptionHandler(ProposalBranchAdoptionService.AdoptionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void relationConflict() { }

    @ExceptionHandler(ProposalBranchAdoptionService.ProposalNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void missingProposal() { }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailableGit() { }

    public record AdoptionRequest(String sourceBranch, String sourceCommit, String targetRepositoryId,
                                 @JsonProperty(value = "targetWorkspaceId", required = true) String targetWorkspaceId,
                                 String targetBranch) {
        boolean matches(RepositoryContext source, RepositoryContext target) {
            return source.branch().equals(sourceBranch) && target.repositoryId().equals(targetRepositoryId)
                    && target.branch().equals(targetBranch) && Objects.equals(target.workspaceId(), targetWorkspaceId);
        }
    }

    public record AdoptionView(long proposalId, String sourceBranch, String sourceCommit, String targetRepositoryId,
                               String targetWorkspaceId, String targetBranch, String targetCommit,
                               ProposalBranchAdoptionService.State state, boolean writable, boolean reviewedOnSourceBranch) {
        AdoptionView(Preview preview, boolean writable) {
            this(preview.proposalId(), preview.sourceBranch(), preview.sourceCommit(), preview.repositoryId(),
                    preview.targetWorkspaceId(), preview.targetBranch(), preview.targetCommit(), preview.state(),
                    writable, preview.reviewedOnSourceBranch());
        }
    }

    public record PendingProjection(String authoritativeCommitId, String projectionStatus) { }
}
