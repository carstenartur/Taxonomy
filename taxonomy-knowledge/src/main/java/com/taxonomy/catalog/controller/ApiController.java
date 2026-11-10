package com.taxonomy.catalog.controller;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.catalog.service.TaxonomyService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST reads of the taxonomy tree and applied classification evidence.
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
    private final MessageSource messageSource;

    public ApiController(TaxonomyService taxonomyService, MessageSource messageSource) {
        this.taxonomyService = taxonomyService;
        this.messageSource = messageSource;
    }

    @Operation(summary = "Get full taxonomy tree", description = "Returns the complete taxonomy hierarchy as a nested tree of nodes", tags = {"Taxonomy"})
    @ApiResponse(responseCode = "200", description = "Taxonomy tree returned successfully")
    @GetMapping("/taxonomy")
    public ResponseEntity<List<TaxonomyNodeDto>> getTaxonomy() {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        return ResponseEntity.ok(taxonomyService.getFullTree());
    }

    @Operation(summary = "Read provisional Information Product mappings",
            description = "Technical administration access. Filters applied provisional mappings by code or title. "
                    + "The source parent is the retained workbook reference when available. The result is review evidence for the versioned Git overlay.")
    @ApiResponse(responseCode = "200", description = "Provisional mappings and their applied source identity")
    @ApiResponse(responseCode = "400", description = "Filter exceeds the allowed length")
    @ApiResponse(responseCode = "403", description = "Technical administration access required")
    @ApiResponse(responseCode = "503", description = "Catalogue evidence unavailable")
    @GetMapping("/admin/catalogue/provisional-information-products")
    public ResponseEntity<TaxonomyService.ProvisionalProductQueue> provisionalInformationProducts(
            @RequestParam(name = "q", defaultValue = "") String filter) {
        var noStore = CacheControl.noStore();
        if (filter.length() > 160) return ResponseEntity.badRequest().cacheControl(noStore).build();
        if (!taxonomyService.isInitialized())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(noStore).build();
        try {
            return ResponseEntity.ok().cacheControl(noStore).body(taxonomyService.provisionalInformationProducts(filter));
        } catch (IOException | IllegalStateException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(noStore).build();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkInitialized() {
        if (!taxonomyService.isInitialized()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", messageSource.getMessage("error.loading", null,
                    "Taxonomy data is still loading. Please wait.", LocaleContextHolder.getLocale()));
            body.put("status", taxonomyService.getInitStatus());
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }
}
