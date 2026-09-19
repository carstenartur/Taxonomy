package com.taxonomy.catalog.controller;

import com.taxonomy.catalog.service.ArchiMateImportException;
import com.taxonomy.catalog.service.ArchiMateXmlImporter;
import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** REST API for previewing and importing ArchiMate 3.x XML models. */
@RestController
@RequestMapping("/api/import")
@Tag(name = "ArchiMate Import")
public class ArchiMateImportController {

    private static final Logger log = LoggerFactory.getLogger(ArchiMateImportController.class);

    private final ArchiMateXmlImporter importer;
    private final WorkspaceContextResolver contextResolver;

    public ArchiMateImportController(ArchiMateXmlImporter importer,
                                     WorkspaceContextResolver contextResolver) {
        this.importer = importer;
        this.contextResolver = contextResolver;
    }

    @Operation(summary = "Preview ArchiMate XML import",
            description = "Parses, matches, and checks duplicates for the active workspace without writing relations")
    @PostMapping(value = "/preview/archimate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> previewArchiMate(@RequestParam("file") MultipartFile file) {
        return process(file, true);
    }

    @Operation(summary = "Import ArchiMate XML",
            description = "Atomically creates matched taxonomy relations in the active workspace")
    @PostMapping(value = "/archimate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importArchiMate(@RequestParam("file") MultipartFile file) {
        return process(file, false);
    }

    private ResponseEntity<?> process(MultipartFile file, boolean preview) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "EMPTY_FILE",
                    "message", "The uploaded ArchiMate XML file is empty"));
        }

        WorkspaceContext context = contextResolver.resolveCurrentContext();
        try {
            ArchiMateImportResult result = preview
                    ? importer.previewXml(file.getInputStream(), context)
                    : importer.importXml(file.getInputStream(), context);
            return ResponseEntity.ok(result);
        } catch (ArchiMateImportException error) {
            log.warn("ArchiMate {} rejected for file '{}' in workspace '{}': {}",
                    preview ? "preview" : "import", file.getOriginalFilename(),
                    context.workspaceId(), error.getMessage());
            return ResponseEntity.status(422).body(Map.of(
                    "error", "INVALID_ARCHIMATE_XML",
                    "message", error.getMessage()));
        } catch (Exception error) {
            log.error("ArchiMate {} failed for file '{}' in workspace '{}'",
                    preview ? "preview" : "import", file.getOriginalFilename(),
                    context.workspaceId(), error);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "ARCHIMATE_IMPORT_FAILED",
                    "message", "The ArchiMate model could not be processed"));
        }
    }
}
