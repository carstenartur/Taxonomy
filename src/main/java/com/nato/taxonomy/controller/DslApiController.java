package com.nato.taxonomy.controller;

import com.nato.taxonomy.dsl.export.TaxDslExportService;
import com.nato.taxonomy.dsl.mapper.AstToModelMapper;
import com.nato.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.nato.taxonomy.dsl.parser.TaxDslParser;
import com.nato.taxonomy.dsl.serializer.TaxDslSerializer;
import com.nato.taxonomy.dsl.validation.DslValidationResult;
import com.nato.taxonomy.dsl.validation.DslValidator;
import com.nato.taxonomy.model.ArchitectureDslDocument;
import com.nato.taxonomy.model.HypothesisStatus;
import com.nato.taxonomy.model.RelationHypothesis;
import com.nato.taxonomy.repository.ArchitectureDslDocumentRepository;
import com.nato.taxonomy.repository.RelationHypothesisRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for the Architecture DSL subsystem.
 *
 * <p>Endpoints cover DSL parsing, validation, export, and hypothesis management.
 */
@RestController
@RequestMapping("/api/dsl")
@Tag(name = "Architecture DSL")
public class DslApiController {

    private final TaxDslExportService exportService;
    private final ArchitectureDslDocumentRepository documentRepository;
    private final RelationHypothesisRepository hypothesisRepository;

    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final DslValidator validator = new DslValidator();

    public DslApiController(TaxDslExportService exportService,
                            ArchitectureDslDocumentRepository documentRepository,
                            RelationHypothesisRepository hypothesisRepository) {
        this.exportService = exportService;
        this.documentRepository = documentRepository;
        this.hypothesisRepository = hypothesisRepository;
    }

    @GetMapping("/export")
    @Operation(summary = "Export current architecture as DSL text")
    public ResponseEntity<String> exportCurrentArchitecture(
            @RequestParam(defaultValue = "default") String namespace) {
        String dsl = exportService.exportAll(namespace);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(dsl);
    }

    @PostMapping("/parse")
    @Operation(summary = "Parse DSL text and return the canonical model as JSON")
    public ResponseEntity<Map<String, Object>> parseDsl(@RequestBody(required = false) String dslText) {
        var doc = parser.parse(dslText != null ? dslText : "");
        CanonicalArchitectureModel model = astMapper.map(doc);
        DslValidationResult validation = validator.validate(model);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", validation.isValid());
        result.put("errors", validation.getErrors());
        result.put("warnings", validation.getWarnings());
        result.put("elements", model.getElements().size());
        result.put("relations", model.getRelations().size());
        result.put("requirements", model.getRequirements().size());
        result.put("mappings", model.getMappings().size());
        result.put("views", model.getViews().size());
        result.put("evidence", model.getEvidence().size());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate DSL text and return errors/warnings")
    public ResponseEntity<Map<String, Object>> validateDsl(@RequestBody(required = false) String dslText) {
        var doc = parser.parse(dslText != null ? dslText : "");
        CanonicalArchitectureModel model = astMapper.map(doc);
        DslValidationResult validation = validator.validate(model);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", validation.isValid());
        result.put("errors", validation.getErrors());
        result.put("warnings", validation.getWarnings());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/hypotheses")
    @Operation(summary = "List relation hypotheses, optionally filtered by status")
    public ResponseEntity<List<RelationHypothesis>> listHypotheses(
            @RequestParam(required = false) HypothesisStatus status) {
        List<RelationHypothesis> result;
        if (status != null) {
            result = hypothesisRepository.findByStatus(status);
        } else {
            result = hypothesisRepository.findAll();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/hypotheses/{id}/accept")
    @Operation(summary = "Accept a relation hypothesis")
    public ResponseEntity<Map<String, Object>> acceptHypothesis(@PathVariable Long id) {
        return hypothesisRepository.findById(id)
                .map(h -> {
                    h.setStatus(HypothesisStatus.ACCEPTED);
                    hypothesisRepository.save(h);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", h.getId());
                    result.put("status", h.getStatus().name());
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/hypotheses/{id}/reject")
    @Operation(summary = "Reject a relation hypothesis")
    public ResponseEntity<Map<String, Object>> rejectHypothesis(@PathVariable Long id) {
        return hypothesisRepository.findById(id)
                .map(h -> {
                    h.setStatus(HypothesisStatus.REJECTED);
                    hypothesisRepository.save(h);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", h.getId());
                    result.put("status", h.getStatus().name());
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/documents")
    @Operation(summary = "List stored DSL documents")
    public ResponseEntity<List<ArchitectureDslDocument>> listDocuments() {
        return ResponseEntity.ok(documentRepository.findAll());
    }
}
