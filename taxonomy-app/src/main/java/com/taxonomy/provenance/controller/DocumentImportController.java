package com.taxonomy.provenance.controller;

import com.taxonomy.dto.DocumentParseResult;
import com.taxonomy.dto.RequirementSourceLinkDto;
import com.taxonomy.dto.SourceArtifactDto;
import com.taxonomy.model.LinkType;
import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.model.SourceArtifact;
import com.taxonomy.provenance.model.SourceFragment;
import com.taxonomy.provenance.model.SourceVersion;
import com.taxonomy.provenance.service.DocumentParserService;
import com.taxonomy.provenance.service.SourceProvenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST API for document import and source provenance management.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/documents/upload}  — Upload and parse a PDF/DOCX document</li>
 *   <li>{@code GET  /api/provenance/sources} — List all source artifacts</li>
 *   <li>{@code GET  /api/provenance/links/{requirementId}} — Get provenance links for a requirement</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Document Import & Provenance")
public class DocumentImportController {

    private static final Logger log = LoggerFactory.getLogger(DocumentImportController.class);

    private final DocumentParserService parserService;
    private final SourceProvenanceService provenanceService;

    public DocumentImportController(DocumentParserService parserService,
                                     SourceProvenanceService provenanceService) {
        this.parserService = parserService;
        this.provenanceService = provenanceService;
    }

    /**
     * Uploads and parses a PDF or DOCX document, extracting requirement
     * candidates.  The document is registered as a source artifact for
     * provenance tracking.
     */
    @Operation(summary = "Upload and parse document",
               description = "Uploads a PDF or DOCX document, extracts requirement candidates, " +
                       "and registers the document as a source artifact for provenance tracking.")
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "sourceType", required = false, defaultValue = "REGULATION") String sourceType) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        try {
            // Parse the document
            DocumentParseResult result = parserService.parse(file);

            // Determine source type
            SourceType type;
            try {
                type = SourceType.valueOf(sourceType.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = SourceType.UPLOADED_DOCUMENT;
            }

            // Create source artifact
            String artifactTitle = (title != null && !title.isBlank())
                    ? title : file.getOriginalFilename();
            SourceArtifact artifact = provenanceService.createArtifact(type, artifactTitle);

            // Create source version
            String contentHash = parserService.computeContentHash(file.getBytes());
            SourceVersion version = provenanceService.createVersion(
                    artifact, result.getMimeType(), contentHash);

            result.setSourceArtifactId(artifact.getId());
            result.setSourceVersionId(version.getId());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Document upload failed for file '{}'", file.getOriginalFilename(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Failed to parse document: " + e.getMessage()));
        }
    }

    /**
     * Lists all registered source artifacts.
     */
    @Operation(summary = "List source artifacts",
               description = "Returns all registered source artifacts (documents, requests, etc.)")
    @GetMapping("/provenance/sources")
    public ResponseEntity<List<SourceArtifactDto>> listSources() {
        return ResponseEntity.ok(provenanceService.listAllArtifacts());
    }

    /**
     * Returns provenance links for a specific requirement.
     */
    @Operation(summary = "Get requirement provenance",
               description = "Returns all source links for a given requirement ID")
    @GetMapping("/provenance/links/{requirementId}")
    public ResponseEntity<List<RequirementSourceLinkDto>> getLinks(
            @PathVariable String requirementId) {
        return ResponseEntity.ok(provenanceService.getLinksForRequirement(requirementId));
    }

    /**
     * Links selected requirement candidates from a parsed document to the
     * provenance model.  Called after the user reviews and selects candidates
     * in the GUI.
     */
    @Operation(summary = "Confirm selected candidates",
               description = "Links selected requirement candidates to the provenance model")
    @PostMapping("/documents/confirm-candidates")
    public ResponseEntity<?> confirmCandidates(@RequestBody Map<String, Object> body) {
        try {
            Long artifactId = ((Number) body.get("sourceArtifactId")).longValue();
            Long versionId = ((Number) body.get("sourceVersionId")).longValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) body.get("candidates");

            if (candidates == null || candidates.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No candidates provided"));
            }

            var artifactOpt = provenanceService.findArtifactById(artifactId);
            if (artifactOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Source artifact not found"));
            }
            var versionOpt = provenanceService.findVersionById(versionId);

            int linked = 0;
            for (Map<String, Object> c : candidates) {
                String text = (String) c.get("text");
                String section = (String) c.get("sectionHeading");
                if (text == null || text.isBlank()) continue;

                SourceFragment fragment = provenanceService.createFragment(
                        versionOpt.orElse(null), text, section, null);

                // Create a requirement ID based on artifact + candidate index
                String reqId = "DOC-" + artifactId + "-" + linked;
                provenanceService.linkRequirement(reqId, artifactOpt.get(),
                        versionOpt.orElse(null), fragment, LinkType.EXTRACTED_FROM);
                linked++;
            }

            return ResponseEntity.ok(Map.of(
                    "linked", linked,
                    "message", linked + " requirement candidate(s) linked to source"));
        } catch (Exception e) {
            log.error("Failed to confirm candidates", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Failed to confirm candidates: " + e.getMessage()));
        }
    }
}
