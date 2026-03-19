package com.taxonomy.relations.controller;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Relations")
public class RelationApiController {

    private final TaxonomyRelationService relationService;
    private final WorkspaceContextResolver contextResolver;

    public RelationApiController(TaxonomyRelationService relationService,
                                  WorkspaceContextResolver contextResolver) {
        this.relationService = relationService;
        this.contextResolver = contextResolver;
    }

    @Operation(summary = "List relations", description = "Returns all taxonomy relations, optionally filtered by type")
    @GetMapping("/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelations(
            @Parameter(description = "Filter by relation type") @RequestParam(required = false) String type) {
        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        if (type != null && !type.isBlank()) {
            RelationType relationType;
            try {
                relationType = RelationType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(relationService.getRelationsByType(relationType, ctx.workspaceId()));
        }
        return ResponseEntity.ok(relationService.getAllRelations(ctx.workspaceId()));
    }

    @Operation(summary = "Get node relations", description = "Returns all relations for a specific taxonomy node")
    @GetMapping("/node/{code}/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelationsForNode(
            @PathVariable String code) {
        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        return ResponseEntity.ok(relationService.getRelationsForNode(code, ctx.workspaceId()));
    }

    @Operation(summary = "Create relation", description = "Creates a new taxonomy relation between two nodes")
    @PostMapping("/relations")
    public ResponseEntity<TaxonomyRelationDto> createRelation(
            @RequestBody Map<String, String> body) {
        String sourceCode = body.get("sourceCode");
        String targetCode = body.get("targetCode");
        String relationTypeStr = body.get("relationType");
        String description = body.get("description");
        String provenance = body.get("provenance");

        if (sourceCode == null || sourceCode.isBlank() ||
                targetCode == null || targetCode.isBlank() ||
                relationTypeStr == null || relationTypeStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        RelationType relationType;
        try {
            relationType = RelationType.valueOf(relationTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        try {
            WorkspaceContext ctx = contextResolver.resolveCurrentContext();
            TaxonomyRelationDto dto = relationService.createRelation(
                    sourceCode, targetCode, relationType, description, provenance,
                    ctx.workspaceId(), ctx.username());
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete relation", description = "Deletes a taxonomy relation by ID")
    @DeleteMapping("/relations/{id}")
    public ResponseEntity<Void> deleteRelation(@PathVariable Long id) {
        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        try {
            relationService.deleteRelation(id, ctx.workspaceId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Count relations", description = "Returns the total number of taxonomy relations visible in the current workspace")
    @GetMapping("/relations/count")
    public ResponseEntity<Map<String, Long>> countRelations() {
        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        return ResponseEntity.ok(Map.of("count", relationService.countRelations(ctx.workspaceId())));
    }
}
