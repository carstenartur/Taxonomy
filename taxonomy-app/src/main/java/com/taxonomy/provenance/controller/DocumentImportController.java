package com.taxonomy.provenance.controller;

import com.taxonomy.dto.AiExtractedCandidate;
import com.taxonomy.dto.DocumentParseResult;
import com.taxonomy.dto.RegulationArchitectureMatch;
import com.taxonomy.dto.RequirementSourceLinkDto;
import com.taxonomy.dto.SourceArtifactDto;
import com.taxonomy.model.LinkType;
import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.config.DocumentImportLimits;
import com.taxonomy.provenance.model.SourceArtifact;
import com.taxonomy.provenance.model.SourceFragment;
import com.taxonomy.provenance.model.SourceVersion;
import com.taxonomy.provenance.service.DocumentAnalysisService;
import com.taxonomy.provenance.service.DocumentLimitException;
import com.taxonomy.provenance.service.DocumentParserService;
import com.taxonomy.provenance.service.SourceProvenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** REST API for bounded document import and source-provenance management. */
@RestController
@RequestMapping("/api")
@Tag(name = "Document Import & Provenance")
public class DocumentImportController {

    private static final Logger log = LoggerFactory.getLogger(DocumentImportController.class);

    private final DocumentParserService parserService;
    private final SourceProvenanceService provenanceService;
    private final DocumentAnalysisService analysisService;
    private final DocumentImportLimits limits;

    public DocumentImportController(DocumentParserService parserService,
                                    SourceProvenanceService provenanceService,
                                    DocumentAnalysisService analysisService,
                                    DocumentImportLimits limits) {
        this.parserService = parserService;
        this.provenanceService = provenanceService;
        this.analysisService = analysisService;
        this.limits = limits;
    }

    @Operation(summary = "Upload and parse document",
            description = "Uploads a bounded PDF or DOCX document, extracts requirement candidates, "
                    + "and registers the document as a source artifact")
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "sourceType", required = false,
                    defaultValue = "REGULATION") String sourceType) {
        requireUsableUpload(file);
        try {
            DocumentParseResult result = parserService.parse(file);
            SourceType type = parseSourceType(sourceType);
            String artifactTitle = title != null && !title.isBlank()
                    ? title : safeFileName(file);
            SourceArtifact artifact = provenanceService.createArtifact(type, artifactTitle);
            String contentHash = parserService.computeContentHash(file);
            SourceVersion version = provenanceService.createVersion(
                    artifact, result.getMimeType(), contentHash);
            result.setSourceArtifactId(artifact.getId());
            result.setSourceVersionId(version.getId());
            return ResponseEntity.ok(result);
        } catch (DocumentLimitException error) {
            throw error;
        } catch (IOException error) {
            log.warn("Document upload rejected for file '{}': {}",
                    safeFileName(file), error.getMessage());
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "DOCUMENT_PARSE_FAILED",
                    "message", "The document could not be parsed as PDF or DOCX"));
        } catch (RuntimeException error) {
            log.error("Document upload failed for file '{}'", safeFileName(file), error);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "DOCUMENT_IMPORT_FAILED",
                    "message", "The document could not be registered"));
        }
    }

    @Operation(summary = "AI-assisted extraction",
            description = "Parses a bounded document and sends at most the configured LLM character limit")
    @PostMapping(value = "/documents/extract-ai", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> extractWithAi(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceType", defaultValue = "REGULATION") String sourceType) {
        requireUsableUpload(file);
        try {
            DocumentParseResult parseResult = parserService.parse(file);
            BoundedText fullText = buildBoundedText(parseResult);
            List<AiExtractedCandidate> candidates =
                    analysisService.extractWithAi(fullText.value(), sourceType);
            return ResponseEntity.ok(Map.of(
                    "fileName", safeResultFileName(parseResult),
                    "totalPages", parseResult.getTotalPages(),
                    "inputTruncated", fullText.truncated(),
                    "inputCharacters", fullText.value().length(),
                    "ruleBased", parseResult.getCandidates() != null
                            ? parseResult.getCandidates() : List.of(),
                    "aiCandidates", candidates));
        } catch (DocumentLimitException error) {
            throw error;
        } catch (IOException error) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "DOCUMENT_PARSE_FAILED",
                    "message", "The document could not be parsed as PDF or DOCX"));
        } catch (RuntimeException error) {
            log.error("AI extraction failed for file '{}'", safeFileName(file), error);
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "AI_EXTRACTION_FAILED",
                    "message", "AI-assisted extraction could not be completed"));
        }
    }

    @Operation(summary = "Direct regulation-to-architecture mapping",
            description = "Maps bounded document text directly to architecture taxonomy nodes")
    @PostMapping(value = "/documents/map-regulation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> mapRegulation(@RequestParam("file") MultipartFile file) {
        requireUsableUpload(file);
        try {
            DocumentParseResult parseResult = parserService.parse(file);
            BoundedText fullText = buildBoundedText(parseResult);
            List<RegulationArchitectureMatch> matches =
                    analysisService.mapRegulationToArchitecture(fullText.value());
            return ResponseEntity.ok(Map.of(
                    "fileName", safeResultFileName(parseResult),
                    "totalPages", parseResult.getTotalPages(),
                    "inputTruncated", fullText.truncated(),
                    "inputCharacters", fullText.value().length(),
                    "matches", matches));
        } catch (DocumentLimitException error) {
            throw error;
        } catch (IOException error) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "DOCUMENT_PARSE_FAILED",
                    "message", "The document could not be parsed as PDF or DOCX"));
        } catch (RuntimeException error) {
            log.error("Regulation mapping failed for file '{}'", safeFileName(file), error);
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "REGULATION_MAPPING_FAILED",
                    "message", "Regulation mapping could not be completed"));
        }
    }

    @Operation(summary = "List source artifacts")
    @GetMapping("/provenance/sources")
    public ResponseEntity<List<SourceArtifactDto>> listSources() {
        return ResponseEntity.ok(provenanceService.listAllArtifacts());
    }

    @Operation(summary = "Get requirement provenance")
    @GetMapping("/provenance/links/{requirementId}")
    public ResponseEntity<List<RequirementSourceLinkDto>> getLinks(
            @PathVariable String requirementId) {
        return ResponseEntity.ok(provenanceService.getLinksForRequirement(requirementId));
    }

    @Operation(summary = "Confirm selected candidates")
    @PostMapping("/documents/confirm-candidates")
    public ResponseEntity<?> confirmCandidates(@RequestBody Map<String, Object> body) {
        try {
            Number artifactNumber = (Number) body.get("sourceArtifactId");
            Number versionNumber = (Number) body.get("sourceVersionId");
            if (artifactNumber == null || versionNumber == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "SOURCE_IDENTIFIERS_REQUIRED"));
            }
            Long artifactId = artifactNumber.longValue();
            Long versionId = versionNumber.longValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) body.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "NO_CANDIDATES"));
            }
            if (candidates.size() > limits.getMaxCandidates()) {
                throw new DocumentLimitException("CANDIDATE_LIMIT_EXCEEDED",
                        "Candidate count exceeds the configured limit of "
                                + limits.getMaxCandidates());
            }

            var artifact = provenanceService.findArtifactById(artifactId);
            var version = provenanceService.findVersionById(versionId);
            if (artifact.isEmpty() || version.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "SOURCE_NOT_FOUND"));
            }

            int linked = 0;
            for (Map<String, Object> candidate : candidates) {
                String text = candidate.get("text") instanceof String value ? value : null;
                String section = candidate.get("sectionHeading") instanceof String value
                        ? value : null;
                if (text == null || text.isBlank()) continue;
                if (text.length() > 2000) {
                    throw new DocumentLimitException("CANDIDATE_TEXT_TOO_LARGE",
                            "A requirement candidate exceeds 2000 characters");
                }
                SourceFragment fragment = provenanceService.createFragment(
                        version.get(), text, section, null);
                String requirementId = "DOC-" + artifactId + "-" + linked;
                provenanceService.linkRequirement(requirementId, artifact.get(),
                        version.get(), fragment, LinkType.EXTRACTED_FROM);
                linked++;
            }
            return ResponseEntity.ok(Map.of(
                    "linked", linked,
                    "message", linked + " requirement candidate(s) linked to source"));
        } catch (DocumentLimitException error) {
            throw error;
        } catch (RuntimeException error) {
            log.error("Failed to confirm document candidates", error);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CANDIDATE_CONFIRMATION_FAILED"));
        }
    }

    private void requireUsableUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentLimitException("EMPTY_FILE", "The uploaded file is empty");
        }
        if (file.getSize() > limits.getMaxUploadBytes()) {
            throw new DocumentLimitException("UPLOAD_TOO_LARGE",
                    "File exceeds the configured upload limit of "
                            + limits.getMaxUploadBytes() + " bytes");
        }
    }

    private SourceType parseSourceType(String sourceType) {
        try {
            return SourceType.valueOf(sourceType.toUpperCase());
        } catch (IllegalArgumentException error) {
            return SourceType.UPLOADED_DOCUMENT;
        }
    }

    private BoundedText buildBoundedText(DocumentParseResult result) {
        StringBuilder text = new StringBuilder(Math.min(
                limits.getMaxLlmCharacters(), 32 * 1024));
        boolean truncated = false;
        if (result.getCandidates() == null || result.getCandidates().isEmpty()) {
            String preview = result.getRawTextPreview() != null ? result.getRawTextPreview() : "";
            return appendBounded(text, preview, false);
        }
        for (var candidate : result.getCandidates()) {
            StringBuilder block = new StringBuilder();
            if (candidate.getSectionHeading() != null
                    && !candidate.getSectionHeading().isBlank()) {
                block.append(candidate.getSectionHeading()).append(":\n");
            }
            block.append(candidate.getText()).append("\n\n");
            BoundedText appendResult = appendBounded(text, block.toString(), truncated);
            truncated = appendResult.truncated();
            if (truncated) break;
        }
        return new BoundedText(text.toString(), truncated);
    }

    private BoundedText appendBounded(StringBuilder target, String value, boolean alreadyTruncated) {
        int remaining = limits.getMaxLlmCharacters() - target.length();
        if (remaining <= 0) return new BoundedText(target.toString(), true);
        if (value.length() > remaining) {
            target.append(value, 0, remaining);
            return new BoundedText(target.toString(), true);
        }
        target.append(value);
        return new BoundedText(target.toString(), alreadyTruncated);
    }

    private static String safeFileName(MultipartFile file) {
        return file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
    }

    private static String safeResultFileName(DocumentParseResult result) {
        return result.getFileName() != null ? result.getFileName() : "";
    }

    private record BoundedText(String value, boolean truncated) {
    }
}