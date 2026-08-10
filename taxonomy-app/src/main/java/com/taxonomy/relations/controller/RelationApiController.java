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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Relations")
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
            description = "Returns relations from the selected repository/workspace, optionally filtered by type")
    @GetMapping("/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelations(
            @Parameter(description = "Filter by relation type")
            @RequestParam(required = false) String type) {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        if (type != null && !type.isBlank()) {
            RelationType relationType;
            try {
                relationType = RelationType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException exception) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(
                    relationService.getRelationsByTypeInContext(relationType, context));
        }
        return ResponseEntity.ok(relationService.getAllRelationsInContext(context));
    }

    @Operation(
            summary = "Get node relations",
            description = "Returns node relations from the selected repository/workspace")
    @GetMapping("/node/{code}/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelationsForNode(
            @PathVariable String code) {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        return ResponseEntity.ok(
                relationService.getRelationsForNodeInContext(code, context));
    }

    @Operation(
            summary = "Create relation",
            description = "Creates a relation in the active workspace or an explicitly authorized central repository")
    @PostMapping("/relations")
    public ResponseEntity<TaxonomyRelationDto> createRelation(
            @RequestBody Map<String, String> body) {
        String sourceCode = body.get("sourceCode");
        String targetCode = body.get("targetCode");
        String relationTypeStr = body.get("relationType");
        String description = body.get("description");
        String provenance = body.get("provenance");

        if (sourceCode == null || sourceCode.isBlank()
                || targetCode == null || targetCode.isBlank()
                || relationTypeStr == null || relationTypeStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        RelationType relationType;
        try {
            relationType = RelationType.valueOf(relationTypeStr.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }

        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            TaxonomyRelationDto dto = relationService.createRelationInContext(
                    sourceCode,
                    targetCode,
                    relationType,
                    description,
                    provenance,
                    context);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete relation", description = "Deletes a relation in the exact active tenant scope")
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
}
