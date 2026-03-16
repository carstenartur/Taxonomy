package com.taxonomy.catalog.controller;

import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.dto.ProfileInfo;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.catalog.service.importer.FrameworkImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for generic framework import.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/import/profiles}             — List available import profiles</li>
 *   <li>{@code POST /api/import/preview/{profileId}}   — Dry-run import with statistics</li>
 *   <li>{@code POST /api/import/{profileId}}            — Full import into database</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/import")
@Tag(name = "Framework Import")
public class ImportApiController {

    private static final Logger log = LoggerFactory.getLogger(ImportApiController.class);

    private final FrameworkImportService importService;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;

    public ImportApiController(FrameworkImportService importService,
                               RepositoryStateService repositoryStateService,
                               WorkspaceResolver workspaceResolver) {
        this.importService = importService;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
    }

    /**
     * Returns all available import profiles with their supported types and file formats.
     */
    @Operation(summary = "List import profiles",
               description = "Returns all registered import profiles (UAF, APQC, C4, etc.)")
    @GetMapping("/profiles")
    public ResponseEntity<List<ProfileInfo>> listProfiles() {
        return ResponseEntity.ok(importService.getAvailableProfiles());
    }

    /**
     * Dry-run: parses and maps the uploaded file but does not write to the database.
     */
    @Operation(summary = "Preview import",
               description = "Parse and map the file without writing to the database. Returns mapping statistics.")
    @PostMapping(value = "/preview/{profileId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FrameworkImportResult> preview(
            @PathVariable String profileId,
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            FrameworkImportResult result = importService.preview(profileId, file.getInputStream());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Preview failed for profile {} with file {}", profileId, file.getOriginalFilename(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Full import: parses, maps, serializes to DSL, and materializes into the database.
     */
    @Operation(summary = "Import framework model",
               description = "Parse, map, and materialize the file into the database")
    @PostMapping(value = "/{profileId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importFile(
            @PathVariable String profileId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "branch", required = false, defaultValue = "main") String branch) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            FrameworkImportResult result = importService.importFile(profileId, file.getInputStream(), branch);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("result", result);
            response.put("viewContext", repositoryStateService.getViewContext(
                    workspaceResolver.resolveCurrentUsername(), branch));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Import failed for profile {} with file {}", profileId, file.getOriginalFilename(), e);
            return ResponseEntity.badRequest().build();
        }
    }
}
