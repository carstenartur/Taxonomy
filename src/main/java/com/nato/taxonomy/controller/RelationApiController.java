package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.TaxonomyRelationDto;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.service.TaxonomyRelationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RelationApiController {

    private final TaxonomyRelationService relationService;

    public RelationApiController(TaxonomyRelationService relationService) {
        this.relationService = relationService;
    }

    @GetMapping("/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelations(
            @RequestParam(required = false) String type) {
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

    @GetMapping("/node/{code}/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelationsForNode(
            @PathVariable String code) {
        return ResponseEntity.ok(relationService.getRelationsForNode(code));
    }

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

    @DeleteMapping("/relations/{id}")
    public ResponseEntity<Void> deleteRelation(@PathVariable Long id) {
        relationService.deleteRelation(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/relations/count")
    public ResponseEntity<Map<String, Long>> countRelations() {
        return ResponseEntity.ok(Map.of("count", relationService.countRelations()));
    }
}
