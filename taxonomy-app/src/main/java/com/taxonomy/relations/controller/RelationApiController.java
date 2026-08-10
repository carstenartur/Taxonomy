package com.taxonomy.relations.controller;

import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST API for taxonomy relations. */
@RestController
@RequestMapping("/api")
@Tag(name = "Relations", description = "Taxonomy relation management")
public class RelationApiController {

    private final TaxonomyRelationService relationService;
    private final WorkspaceResolver workspaceResolver;
    private final SystemRepositoryService repositoryService;
    private final RepositoryMembershipService membershipService;

    public RelationApiController(
            TaxonomyRelationService relationService,
            WorkspaceResolver workspaceResolver,
            SystemRepositoryService repositoryService,
            RepositoryMembershipService membershipService) {
        this.relationService = relationService;
        this.workspaceResolver = workspaceResolver;
        this.repositoryService = repositoryService;
        this.membershipService = membershipService;
    }

    @Operation(
            summary = "List relations",
            description = "Returns relations visible in the selected repository/workspace")
    @GetMapping("/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelations() {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        return ResponseEntity.ok(relationService.getRelationsInContext(context));
    }

    @Operation(
            summary = "List outgoing relations",
            description = "Returns outgoing relations visible in the selected repository/workspace")
    @GetMapping("/relations/from/{sourceId}")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelationsFrom(
            @PathVariable String sourceId) {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        return ResponseEntity.ok(
                relationService.getRelationsFromInContext(sourceId, context));
    }

    @Operation(
            summary = "List incoming relations",
            description = "Returns incoming relations visible in the selected repository/workspace")
    @GetMapping("/relations/to/{targetId}")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelationsTo(
            @PathVariable String targetId) {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        return ResponseEntity.ok(
                relationService.getRelationsToInContext(targetId, context));
    }

    @Operation(summary = "Create relation")
    @PostMapping("/relations")
    public ResponseEntity<TaxonomyRelationDto> createRelation(
            @Valid @RequestBody CreateRelationRequest request) {
        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            TaxonomyRelationDto created = relationService.createRelationInContext(
                    request.sourceNodeId(),
                    request.targetNodeId(),
                    RelationType.valueOf(request.relationType()),
                    request.rationale(),
                    request.createdBy(),
                    context);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Create relations in bulk")
    @PostMapping("/relations/bulk")
    public ResponseEntity<List<TaxonomyRelationDto>> createRelationsBulk(
            @Valid @RequestBody @NotEmpty List<CreateRelationRequest> requests) {
        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            List<TaxonomyRelationDto> created = requests.stream()
                    .map(request -> relationService.createRelationInContext(
                            request.sourceNodeId(),
                            request.targetNodeId(),
                            RelationType.valueOf(request.relationType()),
                            request.rationale(),
                            request.createdBy(),
                            context))
                    .toList();
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete relation")
    @DeleteMapping("/relations/{id}")
    public ResponseEntity<Void> deleteRelation(@PathVariable Long id) {
        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            relationService.deleteRelationInContext(id, context);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Count relations",
            description = "Returns the number of relations visible in the selected repository/workspace")
    @GetMapping("/relations/count")
    public ResponseEntity<Map<String, Long>> countRelations() {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        return ResponseEntity.ok(Map.of(
                "count", relationService.countRelationsInContext(context)));
    }

    /**
     * Workspace contexts are writable by their resolved owner. Central contexts
     * require repository MAINTAINER/OWNER; a global application ADMIN remains an
     * explicit operational compatibility override for the selected repository.
     */
    private RepositoryContext writableContext(RepositoryContext context) {
        if (context.workspaceId() != null) {
            return context;
        }
        SystemRepository repository = repositoryService.getRepository(context.repositoryId());
        if (!isApplicationAdmin()
                && !membershipService.canMaintain(repository, context.username())) {
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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    public record CreateRelationRequest(
            @NotBlank String sourceNodeId,
            @NotBlank String targetNodeId,
            @NotBlank String relationType,
            String rationale,
            String createdBy) {
    }
}
