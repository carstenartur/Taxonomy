package com.taxonomy.catalog.controller;

import com.taxonomy.catalog.service.ArchiMateImportException;
import com.taxonomy.catalog.service.ArchiMateXmlImporter;
import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.RepositoryScope;
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
    private final WorkspaceResolver contextResolver;

    public ArchiMateImportController(ArchiMateXmlImporter importer,
                                     WorkspaceResolver contextResolver) {
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

    @Operation(summary = "Preview bounded Archi 5.10 return", description = "Requires the strict original XML and the content-preserving returned XML; declares every accepted transformation and import loss")
    @PostMapping(value = "/preview/archimate-return", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> previewReturn(@RequestParam("original") MultipartFile original,
                                           @RequestParam("file") MultipartFile returned) {
        return processReturn(original, returned, null, true);
    }

    @Operation(summary = "Apply reviewed bounded Archi return", description = "Requires the preview fingerprint for these exact files and active workspace; creates exact catalogue relations through the existing writer")
    @PostMapping(value = "/archimate-return", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importReturn(@RequestParam("original") MultipartFile original,
                                          @RequestParam("file") MultipartFile returned,
                                          @RequestParam("reviewedFingerprint") String reviewedFingerprint) {
        return processReturn(original, returned, reviewedFingerprint, false);
    }

    private ResponseEntity<?> processReturn(MultipartFile original, MultipartFile returned, String fingerprint, boolean preview) {
        boolean writable = contextResolver.resolveCurrentRepositoryContext().scope() == RepositoryScope.WORKSPACE;
        if (!preview && !writable) return workingCopyRequired();
        WorkspaceContext context = contextResolver.resolveCurrentContext();
        try {
            byte[] source = boundedBytes(original), result = boundedBytes(returned);
            ArchiMateImportResult imported = preview ? importer.previewReturn(source, result, context)
                    : importer.importReturn(source, result, fingerprint, context);
            imported.setWritableContext(writable);
            return ResponseEntity.ok(imported);
        } catch (ArchiMateImportException | IllegalArgumentException error) {
            return ResponseEntity.unprocessableContent().body(Map.of("error", "INVALID_ARCHIMATE_RETURN", "message", error.getMessage()));
        } catch (java.io.IOException error) {
            return ResponseEntity.badRequest().body(Map.of("error", "ARCHIMATE_RETURN_READ_FAILED", "message", "The Archi return files could not be read"));
        }
    }

    private static ResponseEntity<?> workingCopyRequired() {
        return ResponseEntity.status(403).body(Map.of("error", "WORKING_COPY_REQUIRED",
                "message", "Open an editable working copy before importing relations; central contexts are read-only"));
    }

    private static byte[] boundedBytes(MultipartFile file) throws java.io.IOException {
        int max = com.taxonomy.exchange.ExchangeXml.MAX_BYTES;
        if (file.isEmpty() || file.getSize() > max) throw new ArchiMateImportException("Each Archi return XML file must contain 1.." + max + " bytes");
        try (var input = file.getInputStream()) { return input.readNBytes(max + 1); }
    }

    private ResponseEntity<?> process(MultipartFile file, boolean preview) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "EMPTY_FILE",
                    "message", "The uploaded ArchiMate XML file is empty"));
        }

        boolean writable = contextResolver.resolveCurrentRepositoryContext().scope() == RepositoryScope.WORKSPACE;
        if (!preview && !writable) return workingCopyRequired();
        WorkspaceContext context = contextResolver.resolveCurrentContext();
        try {
            ArchiMateImportResult result = preview
                    ? importer.previewXml(file.getInputStream(), context)
                    : importer.importXml(file.getInputStream(), context);
            result.setWritableContext(writable);
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
