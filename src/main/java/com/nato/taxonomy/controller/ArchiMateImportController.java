package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.ArchiMateImportResult;
import com.nato.taxonomy.service.ArchiMateXmlImporter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST API for ArchiMate XML import.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/import/archimate} — Import ArchiMate XML file</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/import")
@Tag(name = "ArchiMate Import")
public class ArchiMateImportController {

    private final ArchiMateXmlImporter importer;

    public ArchiMateImportController(ArchiMateXmlImporter importer) {
        this.importer = importer;
    }

    /**
     * Imports an ArchiMate 3.x Model Exchange Format XML file.
     */
    @Operation(summary = "Import ArchiMate XML",
               description = "Parses an ArchiMate 3.x XML file and creates taxonomy relations from matched elements")
    @PostMapping(value = "/archimate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ArchiMateImportResult> importArchiMate(
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            ArchiMateImportResult result = importer.importXml(file.getInputStream());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ArchiMateImportResult error = new ArchiMateImportResult();
            error.getNotes().add("Import failed: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}
