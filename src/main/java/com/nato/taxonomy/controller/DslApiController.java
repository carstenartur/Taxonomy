package com.nato.taxonomy.controller;

import com.nato.taxonomy.dsl.export.DslMaterializeService;
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
import com.nato.taxonomy.service.HypothesisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for the Architecture DSL subsystem.
 *
 * <p>Endpoints cover DSL parsing, validation, export, materialization,
 * and hypothesis management.
 */
@RestController
@RequestMapping("/api/dsl")
@Tag(name = "Architecture DSL")
public class DslApiController {

    private final TaxDslExportService exportService;
    private final DslMaterializeService materializeService;
    private final ArchitectureDslDocumentRepository documentRepository;
    private final HypothesisService hypothesisService;

    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final DslValidator validator = new DslValidator();

    public DslApiController(TaxDslExportService exportService,
                            DslMaterializeService materializeService,
                            ArchitectureDslDocumentRepository documentRepository,
                            HypothesisService hypothesisService) {
        this.exportService = exportService;
        this.materializeService = materializeService;
        this.documentRepository = documentRepository;
        this.hypothesisService = hypothesisService;
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

    @GetMapping("/current")
    @Operation(summary = "Get current architecture state as structured JSON")
    public ResponseEntity<Map<String, Object>> getCurrentArchitecture() {
        CanonicalArchitectureModel model = exportService.buildCanonicalModel();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elements", model.getElements());
        result.put("relations", model.getRelations());
        result.put("requirements", model.getRequirements());
        result.put("mappings", model.getMappings());
        result.put("views", model.getViews());
        result.put("evidence", model.getEvidence());
        return ResponseEntity.ok(result);
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

    @PostMapping("/materialize")
    @Operation(summary = "Parse, validate, and materialize DSL into the database",
            description = "Relations with status=accepted become TaxonomyRelation entities. " +
                    "Relations with status=proposed/provisional become RelationHypothesis entities.")
    public ResponseEntity<Map<String, Object>> materializeDsl(
            @RequestBody String dslText,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {

        DslMaterializeService.MaterializeResult matResult =
                materializeService.materialize(dslText, path, branch, commitId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", matResult.valid());
        result.put("errors", matResult.errors());
        result.put("warnings", matResult.warnings());
        result.put("relationsCreated", matResult.relationsCreated());
        result.put("hypothesesCreated", matResult.hypothesesCreated());
        result.put("documentId", matResult.documentId());

        if (!matResult.valid()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/hypotheses")
    @Operation(summary = "List relation hypotheses, optionally filtered by status")
    public ResponseEntity<List<RelationHypothesis>> listHypotheses(
            @RequestParam(required = false) HypothesisStatus status) {
        List<RelationHypothesis> result;
        if (status != null) {
            result = hypothesisService.findByStatus(status);
        } else {
            result = hypothesisService.findAll();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/hypotheses/{id}/accept")
    @Operation(summary = "Accept a relation hypothesis",
            description = "Promotes the hypothesis to an accepted TaxonomyRelation in the knowledge graph.")
    public ResponseEntity<Map<String, Object>> acceptHypothesis(@PathVariable Long id) {
        try {
            RelationHypothesis accepted = hypothesisService.accept(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", accepted.getId());
            result.put("status", accepted.getStatus().name());
            result.put("sourceNodeId", accepted.getSourceNodeId());
            result.put("targetNodeId", accepted.getTargetNodeId());
            result.put("relationType", accepted.getRelationType().name());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/hypotheses/{id}/reject")
    @Operation(summary = "Reject a relation hypothesis")
    public ResponseEntity<Map<String, Object>> rejectHypothesis(@PathVariable Long id) {
        try {
            RelationHypothesis rejected = hypothesisService.reject(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rejected.getId());
            result.put("status", rejected.getStatus().name());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/hypotheses/{id}/evidence")
    @Operation(summary = "Get evidence records for a hypothesis")
    public ResponseEntity<?> getHypothesisEvidence(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(hypothesisService.findEvidence(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/documents")
    @Operation(summary = "List stored DSL documents")
    public ResponseEntity<List<ArchitectureDslDocument>> listDocuments() {
        return ResponseEntity.ok(documentRepository.findAll());
    }
}
