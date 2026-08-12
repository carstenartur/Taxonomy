package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.ReadOnlyRepositoryContextException;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Git-authoritative relation mutation API.
 *
 * <p>Existing branches require one strong {@code If-Match} commit ETag. Branch
 * creation requires {@code If-None-Match: *}. The authoritative commit is
 * returned as the response ETag; the database projection can never precede it.</p>
 */
@RestController
@RequestMapping("/api/architecture/relations")
@Tag(name = "Git-authoritative relations")
public class GitRelationCommandApiController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final GitAuthoritativeRelationMutationService mutationService;
    private final WorkspaceResolver workspaceResolver;
    private final SystemRepositoryService repositoryService;
    private final RepositoryMembershipService membershipService;

    public GitRelationCommandApiController(
            GitAuthoritativeRelationMutationService mutationService,
            WorkspaceResolver workspaceResolver,
            SystemRepositoryService repositoryService,
            RepositoryMembershipService membershipService) {
        this.mutationService = mutationService;
        this.workspaceResolver = workspaceResolver;
        this.repositoryService = repositoryService;
        this.membershipService = membershipService;
    }

    @Operation(summary = "Add or update one relation through an exact Git commit")
    @PutMapping("/{sourceCode}/{relationType}/{targetCode}")
    public ResponseEntity<MutationResponse> upsert(
            @PathVariable String sourceCode,
            @PathVariable String relationType,
            @PathVariable String targetCode,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = true)
            String causationId,
            @RequestBody(required = false) MutationBody body) {
        try {
            RepositoryContext context = writableContext(
                    workspaceResolver.resolveCurrentRepositoryContext());
            if (context == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String expectedHead = GitHttpPrecondition.expectedHead(
                    ifMatch, ifNoneMatch);
            RelationIdentity identity = identity(
                    sourceCode, relationType, targetCode);
            MutationBody payload = body == null ? MutationBody.EMPTY : body;
            MutationResult result = mutationService.upsert(
                    context,
                    expectedHead,
                    new RelationDefinition(
                            identity,
                            payload.status(),
                            payload.confidence(),
                            payload.provenance(),
                            payload.extensions()),
                    new CommandMetadata(causationId, payload.rationale()));
            HttpStatus status = result.authority().changeKind() == ChangeKind.ADDED
                    ? HttpStatus.CREATED : HttpStatus.OK;
            return authoritative(status, result);
        } catch (GitHttpPrecondition.PreconditionRequiredException error) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        } catch (BranchHeadConflictException error) {
            return preconditionFailed(error);
        } catch (ProjectionPendingException error) {
            return projectionPending(error.getAuthority());
        } catch (ReadOnlyRepositoryContextException error) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IOException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @Operation(summary = "Remove one relation through an exact Git commit")
    @DeleteMapping("/{sourceCode}/{relationType}/{targetCode}")
    public ResponseEntity<MutationResponse> remove(
            @PathVariable String sourceCode,
            @PathVariable String relationType,
            @PathVariable String targetCode,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = true)
            String causationId) {
        try {
            RepositoryContext context = writableContext(
                    workspaceResolver.resolveCurrentRepositoryContext());
            if (context == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String expectedHead = GitHttpPrecondition.expectedHead(
                    ifMatch, ifNoneMatch);
            MutationResult result = mutationService.remove(
                    context,
                    expectedHead,
                    identity(sourceCode, relationType, targetCode),
                    new CommandMetadata(causationId));
            return authoritative(HttpStatus.OK, result);
        } catch (GitHttpPrecondition.PreconditionRequiredException error) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        } catch (BranchHeadConflictException error) {
            return preconditionFailed(error);
        } catch (ProjectionPendingException error) {
            return projectionPending(error.getAuthority());
        } catch (ReadOnlyRepositoryContextException error) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IOException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private static RelationIdentity identity(
            String sourceCode,
            String relationType,
            String targetCode) {
        RelationType type = RelationType.valueOf(
                relationType.strip().toUpperCase(Locale.ROOT));
        return new RelationIdentity(sourceCode, type.name(), targetCode);
    }

    private static ResponseEntity<MutationResponse> authoritative(
            HttpStatus status,
            MutationResult result) {
        CommandResult authority = result.authority();
        return ResponseEntity.status(status)
                .header(HttpHeaders.ETAG,
                        GitHttpPrecondition.etag(
                                authority.authoritativeCommitId()))
                .body(MutationResponse.projected(result));
    }

    private static ResponseEntity<MutationResponse> projectionPending(
            CommandResult authority) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.ETAG,
                        GitHttpPrecondition.etag(
                                authority.authoritativeCommitId()))
                .body(MutationResponse.pending(authority));
    }

    private static ResponseEntity<MutationResponse> preconditionFailed(
            BranchHeadConflictException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                HttpStatus.PRECONDITION_FAILED);
        if (error.getActualHeadCommit() != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            error.getActualHeadCommit()));
        }
        return response.body(MutationResponse.conflict(error));
    }

    /** Preserve isolated write scopes; authorize central reads before upgrading. */
    private RepositoryContext writableContext(RepositoryContext context) {
        if (context.scope() == RepositoryScope.WORKSPACE
                || context.scope() == RepositoryScope.FORK) {
            return context;
        }
        SystemRepository repository = repositoryService.getRepository(
                context.repositoryId());
        if (!isApplicationAdmin()
                && !membershipService.canMaintain(
                        repository, context.username())) {
            return null;
        }
        return new RepositoryContext(
                context.repositoryId(),
                null,
                context.branch(),
                context.username(),
                RepositoryScope.CENTRAL_WRITE);
    }

    private static boolean isApplicationAdmin() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(
                        authority.getAuthority()));
    }

    public record MutationBody(
            String status,
            Double confidence,
            String provenance,
            Map<String, String> extensions,
            String rationale) {
        private static final MutationBody EMPTY = new MutationBody(
                null, null, null, Map.of(), null);

        public MutationBody {
            extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
        }
    }

    public record MutationResponse(
            String repositoryId,
            String workspaceId,
            String branch,
            String scope,
            String previousHeadCommit,
            String authoritativeCommitId,
            String changeKind,
            boolean commitCreated,
            String causationId,
            String projectionStatus,
            String projectionOutcome,
            Boolean relationPresent,
            String expectedHeadCommit,
            String actualHeadCommit) {

        static MutationResponse projected(MutationResult result) {
            CommandResult authority = result.authority();
            return new MutationResponse(
                    authority.repositoryId(),
                    authority.workspaceId(),
                    authority.branch(),
                    authority.scope().name(),
                    authority.previousHeadCommit(),
                    authority.authoritativeCommitId(),
                    authority.changeKind().name(),
                    authority.commitCreated(),
                    authority.causationId(),
                    "PROJECTED",
                    result.projection().outcome().name(),
                    result.projection().relationPresent(),
                    null,
                    null);
        }

        static MutationResponse pending(CommandResult authority) {
            return new MutationResponse(
                    authority.repositoryId(),
                    authority.workspaceId(),
                    authority.branch(),
                    authority.scope().name(),
                    authority.previousHeadCommit(),
                    authority.authoritativeCommitId(),
                    authority.changeKind().name(),
                    authority.commitCreated(),
                    authority.causationId(),
                    "PENDING_REBUILD",
                    null,
                    null,
                    null,
                    null);
        }

        static MutationResponse conflict(BranchHeadConflictException error) {
            return new MutationResponse(
                    null,
                    null,
                    error.getBranch(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    "PRECONDITION_FAILED",
                    null,
                    null,
                    error.getExpectedHeadCommit(),
                    error.getActualHeadCommit());
        }
    }
}
