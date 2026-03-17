package com.taxonomy.catalog.controller;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.catalog.service.TaxonomyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slim REST controller that exposes only the core taxonomy tree endpoint.
 *
 * <p>All other endpoints that were previously in this controller have been
 * extracted into dedicated controllers:
 * <ul>
 *   <li>{@code AnalysisApiController} — analysis endpoints</li>
 *   <li>{@code ExportApiController} — diagram and scores export/import</li>
 *   <li>{@code SearchApiController} — full-text, semantic, hybrid, and graph search</li>
 *   <li>{@code AdminApiController} — admin, diagnostics, prompts, AI status</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Taxonomy")
public class ApiController {

    private final TaxonomyService taxonomyService;

    public ApiController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @Operation(summary = "Get full taxonomy tree", description = "Returns the complete taxonomy hierarchy as a nested tree of nodes", tags = {"Taxonomy"})
    @ApiResponse(responseCode = "200", description = "Taxonomy tree returned successfully")
    @GetMapping("/taxonomy")
    public ResponseEntity<List<TaxonomyNodeDto>> getTaxonomy() {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        return ResponseEntity.ok(taxonomyService.getFullTree());
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkInitialized() {
        if (!taxonomyService.isInitialized()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Taxonomy data is still loading. Please wait.");
            body.put("status", taxonomyService.getInitStatus());
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }
}
