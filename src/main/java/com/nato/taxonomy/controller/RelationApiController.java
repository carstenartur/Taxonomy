package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.TaxonomyRelationDto;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.service.TaxonomyRelationService;
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

    public RelationApiController(TaxonomyRelationService relationService) {
        this.relationService = relationService;
    }

    @Operation(summary = "List relations", description = "Returns all taxonomy relations, optionally filtered by type")
    @GetMapping("/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelations(
            @Parameter(description = "Filter by relation type") @RequestParam(required = false) String type) {
        if (type != null && !type.isBlank()) {
            RelationType relationType;
            try {
                relationType = RelationType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(relationService.getRelationsByType(relationType));
        }
        return ResponseEntity.ok(relationService.getAllRelations());
    }

    @Operation(summary = "Get node relations", description = "Returns all relations for a specific taxonomy node")
    @GetMapping("/node/{code}/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelationsForNode(
            @PathVariable String code) {
        return ResponseEntity.ok(relationService.getRelationsForNode(code));
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
            TaxonomyRelationDto dto = relationService.createRelation(
                    sourceCode, targetCode, relationType, description, provenance);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete relation", description = "Deletes a taxonomy relation by ID")
    @DeleteMapping("/relations/{id}")
    public ResponseEntity<Void> deleteRelation(@PathVariable Long id) {
        relationService.deleteRelation(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Count relations", description = "Returns the total number of taxonomy relations")
    @GetMapping("/relations/count")
    public ResponseEntity<Map<String, Long>> countRelations() {
        return ResponseEntity.ok(Map.of("count", relationService.countRelations()));
    }
}
