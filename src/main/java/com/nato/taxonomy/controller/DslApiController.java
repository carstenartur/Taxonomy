package com.nato.taxonomy.controller;

import com.nato.taxonomy.dsl.diff.ModelDiff;
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
 * versioning (commit/history/diff/branches), and hypothesis management.
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

    // ── Export & current state ────────────────────────────────────────

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

    // ── Parse & validate ─────────────────────────────────────────────

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

    // ── Materialization ──────────────────────────────────────────────

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

    @PostMapping("/materialize-incremental")
    @Operation(summary = "Incrementally materialize only the delta between two DSL versions",
            description = "Computes a diff between the before and after documents, then only " +
                    "creates/updates the changed relations. More efficient than full materialization.")
    public ResponseEntity<Map<String, Object>> materializeIncremental(
            @RequestParam(required = false) Long beforeDocId,
            @RequestParam Long afterDocId) {
        try {
            DslMaterializeService.MaterializeResult matResult =
                    materializeService.materializeIncremental(beforeDocId, afterDocId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", matResult.valid());
            result.put("warnings", matResult.warnings());
            result.put("relationsCreated", matResult.relationsCreated());
            result.put("hypothesesCreated", matResult.hypothesesCreated());
            result.put("documentId", matResult.documentId());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // ── Commit & versioning ──────────────────────────────────────────

    @PostMapping("/commit")
    @Operation(summary = "Commit DSL text as a versioned document",
            description = "Stores the DSL text as a new document version on the specified branch. " +
                    "Does not materialize — use POST /api/dsl/materialize or " +
                    "POST /api/dsl/materialize-incremental for that.")
    public ResponseEntity<Map<String, Object>> commitDsl(
            @RequestBody String dslText,
            @RequestParam(defaultValue = "draft") String branch,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String message) {

        // Validate DSL text before committing
        var doc = parser.parse(dslText != null ? dslText : "");
        CanonicalArchitectureModel model = astMapper.map(doc);
        DslValidationResult validation = validator.validate(model);

        if (!validation.isValid()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", false);
            result.put("errors", validation.getErrors());
            result.put("warnings", validation.getWarnings());
            return ResponseEntity.badRequest().body(result);
        }

        // Generate a commit ID from content hash
        String commitId = UUID.randomUUID().toString().substring(0, 8);

        String namespace = doc.getMeta() != null ? doc.getMeta().namespace() : null;
        String dslVersion = doc.getMeta() != null ? doc.getMeta().version() : null;

        ArchitectureDslDocument document = new ArchitectureDslDocument();
        document.setPath("architecture.taxdsl");
        document.setBranch(branch);
        document.setCommitId(commitId);
        document.setNamespace(namespace);
        document.setDslVersion(dslVersion);
        document.setRawContent(dslText);

        ArchitectureDslDocument saved = documentRepository.save(document);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", saved.getId());
        result.put("commitId", commitId);
        result.put("branch", branch);
        result.put("author", author);
        result.put("message", message);
        result.put("timestamp", saved.getParsedAt());
        result.put("valid", true);
        result.put("warnings", validation.getWarnings());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    @Operation(summary = "Get commit history for a branch",
            description = "Returns all DSL document versions on the specified branch, newest first.")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @RequestParam(defaultValue = "draft") String branch) {

        List<ArchitectureDslDocument> documents = documentRepository.findByBranchOrderByParsedAtDesc(branch);
        List<Map<String, Object>> history = new ArrayList<>();

        for (ArchitectureDslDocument doc : documents) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("documentId", doc.getId());
            entry.put("commitId", doc.getCommitId());
            entry.put("branch", doc.getBranch());
            entry.put("namespace", doc.getNamespace());
            entry.put("dslVersion", doc.getDslVersion());
            entry.put("path", doc.getPath());
            entry.put("timestamp", doc.getParsedAt());
            history.add(entry);
        }

        return ResponseEntity.ok(history);
    }

    @GetMapping("/diff/{beforeId}/{afterId}")
    @Operation(summary = "Compute diff between two DSL document versions",
            description = "Returns the added, removed, and changed elements and relations " +
                    "between two stored DSL documents.")
    public ResponseEntity<?> diffDocuments(
            @PathVariable Long beforeId,
            @PathVariable Long afterId) {
        try {
            ModelDiff diff = materializeService.diffDocuments(beforeId, afterId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalChanges", diff.totalChanges());
            result.put("isEmpty", diff.isEmpty());
            result.put("addedElements", diff.addedElements().size());
            result.put("removedElements", diff.removedElements().size());
            result.put("changedElements", diff.changedElements().size());
            result.put("addedRelations", diff.addedRelations().size());
            result.put("removedRelations", diff.removedRelations().size());
            result.put("changedRelations", diff.changedRelations().size());

            // Include details
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("addedElements", diff.addedElements());
            details.put("removedElements", diff.removedElements());
            details.put("changedElements", diff.changedElements());
            details.put("addedRelations", diff.addedRelations());
            details.put("removedRelations", diff.removedRelations());
            details.put("changedRelations", diff.changedRelations());
            result.put("details", details);

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // ── Branches ─────────────────────────────────────────────────────

    @GetMapping("/branches")
    @Operation(summary = "List all branches with DSL documents")
    public ResponseEntity<List<Map<String, Object>>> listBranches() {
        List<String> branchNames = documentRepository.findDistinctBranches();
        List<Map<String, Object>> branches = new ArrayList<>();

        for (String name : branchNames) {
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", name);

            // Find the latest document on this branch for metadata
            documentRepository.findFirstByBranchOrderByParsedAtDesc(name)
                    .ifPresent(doc -> {
                        branch.put("headCommitId", doc.getCommitId());
                        branch.put("headDocumentId", doc.getId());
                        branch.put("lastUpdated", doc.getParsedAt());
                    });

            branches.add(branch);
        }

        return ResponseEntity.ok(branches);
    }

    @PostMapping("/branches")
    @Operation(summary = "Create a new branch by forking from an existing document",
            description = "Creates a new branch by copying the specified document " +
                    "(or the latest from the source branch) to the new branch.")
    public ResponseEntity<Map<String, Object>> createBranch(
            @RequestParam String name,
            @RequestParam(required = false) Long fromDocumentId,
            @RequestParam(required = false, defaultValue = "draft") String fromBranch) {

        // Find the source document
        ArchitectureDslDocument sourceDoc;
        if (fromDocumentId != null) {
            sourceDoc = documentRepository.findById(fromDocumentId).orElse(null);
        } else {
            sourceDoc = documentRepository.findFirstByBranchOrderByParsedAtDesc(fromBranch).orElse(null);
        }

        if (sourceDoc == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Source document not found");
            return ResponseEntity.badRequest().body(error);
        }

        // Create a new document on the new branch
        String commitId = UUID.randomUUID().toString().substring(0, 8);

        ArchitectureDslDocument newDoc = new ArchitectureDslDocument();
        newDoc.setPath(sourceDoc.getPath());
        newDoc.setBranch(name);
        newDoc.setCommitId(commitId);
        newDoc.setNamespace(sourceDoc.getNamespace());
        newDoc.setDslVersion(sourceDoc.getDslVersion());
        newDoc.setRawContent(sourceDoc.getRawContent());

        ArchitectureDslDocument saved = documentRepository.save(newDoc);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", name);
        result.put("documentId", saved.getId());
        result.put("commitId", commitId);
        result.put("forkedFrom", sourceDoc.getId());
        result.put("timestamp", saved.getParsedAt());
        return ResponseEntity.ok(result);
    }

    // ── Hypothesis management ────────────────────────────────────────

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

    // ── Documents ────────────────────────────────────────────────────

    @GetMapping("/documents")
    @Operation(summary = "List stored DSL documents")
    public ResponseEntity<List<ArchitectureDslDocument>> listDocuments() {
        return ResponseEntity.ok(documentRepository.findAll());
    }
}
