package com.nato.taxonomy.controller;

import com.nato.taxonomy.dsl.diff.ModelDiff;
import com.nato.taxonomy.dsl.export.DslMaterializeService;
import com.nato.taxonomy.dsl.export.TaxDslExportService;
import com.nato.taxonomy.dsl.mapper.AstToModelMapper;
import com.nato.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.nato.taxonomy.dsl.parser.TaxDslParser;
import com.nato.taxonomy.dsl.serializer.TaxDslSerializer;
import com.nato.taxonomy.dsl.storage.DslBranch;
import com.nato.taxonomy.dsl.storage.DslCommit;
import com.nato.taxonomy.dsl.storage.DslGitRepository;
import com.nato.taxonomy.dsl.validation.DslValidationResult;
import com.nato.taxonomy.dsl.validation.DslValidator;
import com.nato.taxonomy.model.ArchitectureDslDocument;
import com.nato.taxonomy.model.HypothesisStatus;
import com.nato.taxonomy.model.RelationHypothesis;
import com.nato.taxonomy.repository.ArchitectureDslDocumentRepository;
import com.nato.taxonomy.service.HypothesisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/**
 * REST API for the Architecture DSL subsystem.
 *
 * <p>Endpoints cover DSL parsing, validation, export, materialization,
 * versioning (commit/history/diff/branches), and hypothesis management.
 *
 * <p>Versioning is backed by a JGit DFS repository ({@link DslGitRepository})
 * which stores DSL documents as Git objects (blobs → trees → commits).
 * The JPA {@link ArchitectureDslDocumentRepository} is used in parallel
 * for queryability and backward compatibility.
 */
@RestController
@RequestMapping("/api/dsl")
@Tag(name = "Architecture DSL")
public class DslApiController {

    private static final Logger log = LoggerFactory.getLogger(DslApiController.class);

    private final TaxDslExportService exportService;
    private final DslMaterializeService materializeService;
    private final ArchitectureDslDocumentRepository documentRepository;
    private final HypothesisService hypothesisService;
    private final DslGitRepository gitRepository;

    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final DslValidator validator = new DslValidator();

    public DslApiController(TaxDslExportService exportService,
                            DslMaterializeService materializeService,
                            ArchitectureDslDocumentRepository documentRepository,
                            HypothesisService hypothesisService,
                            DslGitRepository gitRepository) {
        this.exportService = exportService;
        this.materializeService = materializeService;
        this.documentRepository = documentRepository;
        this.hypothesisService = hypothesisService;
        this.gitRepository = gitRepository;
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
                    "The commit is stored as a Git object in the JGit DFS repository AND " +
                    "as a JPA entity for queryability. " +
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

        // 1. Commit to JGit repository (real Git objects in DFS)
        String gitCommitId;
        try {
            gitCommitId = gitRepository.commitDsl(branch, dslText,
                    author != null ? author : "system",
                    message != null ? message : "DSL commit");
        } catch (IOException e) {
            log.error("JGit commit failed", e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("valid", false);
            error.put("errors", List.of("Git commit failed: " + e.getMessage()));
            return ResponseEntity.internalServerError().body(error);
        }

        // 2. Also persist in JPA for queryability
        String namespace = doc.getMeta() != null ? doc.getMeta().namespace() : null;
        String dslVersion = doc.getMeta() != null ? doc.getMeta().version() : null;

        ArchitectureDslDocument document = new ArchitectureDslDocument();
        document.setPath("architecture.taxdsl");
        document.setBranch(branch);
        document.setCommitId(gitCommitId);
        document.setNamespace(namespace);
        document.setDslVersion(dslVersion);
        document.setRawContent(dslText);

        ArchitectureDslDocument saved = documentRepository.save(document);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", saved.getId());
        result.put("commitId", gitCommitId);
        result.put("branch", branch);
        result.put("author", author);
        result.put("message", message);
        result.put("timestamp", saved.getParsedAt());
        result.put("valid", true);
        result.put("warnings", validation.getWarnings());
        result.put("gitBacked", true);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    @Operation(summary = "Get commit history for a branch",
            description = "Returns all DSL commits on the specified branch from the JGit repository, " +
                    "newest first. Falls back to JPA-based history if Git history is empty.")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @RequestParam(defaultValue = "draft") String branch) {

        // Try JGit history first
        try {
            List<DslCommit> gitHistory = gitRepository.getDslHistory(branch);
            if (!gitHistory.isEmpty()) {
                List<Map<String, Object>> history = new ArrayList<>();
                for (DslCommit c : gitHistory) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("commitId", c.commitId());
                    entry.put("branch", branch);
                    entry.put("author", c.author());
                    entry.put("message", c.message());
                    entry.put("timestamp", c.timestamp());
                    entry.put("gitBacked", true);

                    // Link to JPA document if exists
                    documentRepository.findByBranch(branch).stream()
                            .filter(d -> c.commitId().equals(d.getCommitId()))
                            .findFirst()
                            .ifPresent(d -> entry.put("documentId", d.getId()));

                    history.add(entry);
                }
                return ResponseEntity.ok(history);
            }
        } catch (IOException e) {
            log.warn("JGit history lookup failed for branch '{}': {}", branch, e.getMessage());
        }

        // Fallback to JPA-based history
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
            entry.put("gitBacked", false);
            history.add(entry);
        }

        return ResponseEntity.ok(history);
    }

    @GetMapping("/diff/{beforeId}/{afterId}")
    @Operation(summary = "Compute diff between two DSL document versions",
            description = "Returns the added, removed, and changed elements and relations. " +
                    "Accepts either JPA document IDs (numeric) or Git commit SHAs (hex strings).")
    public ResponseEntity<?> diffDocuments(
            @PathVariable String beforeId,
            @PathVariable String afterId) {
        try {
            ModelDiff diff;

            // Try as Git commit SHAs first (40-char hex)
            if (looksLikeGitSha(beforeId) && looksLikeGitSha(afterId)) {
                try {
                    diff = gitRepository.diffBetween(beforeId, afterId);
                } catch (IOException e) {
                    log.warn("JGit diff failed, falling back to JPA: {}", e.getMessage());
                    diff = materializeService.diffDocuments(Long.valueOf(beforeId), Long.valueOf(afterId));
                }
            } else {
                // Fall back to JPA document IDs
                diff = materializeService.diffDocuments(Long.valueOf(beforeId), Long.valueOf(afterId));
            }

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
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    private static boolean looksLikeGitSha(String s) {
        return s != null && s.length() == 40 && s.matches("[0-9a-f]+");
    }

    // ── Branches ─────────────────────────────────────────────────────

    @GetMapping("/branches")
    @Operation(summary = "List all branches with DSL documents",
            description = "Returns branches from the JGit repository and merges with JPA-tracked branches.")
    public ResponseEntity<List<Map<String, Object>>> listBranches() {
        List<Map<String, Object>> branches = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. JGit branches (primary source)
        try {
            for (DslBranch gb : gitRepository.listBranches()) {
                Map<String, Object> branch = new LinkedHashMap<>();
                branch.put("name", gb.name());
                branch.put("headCommitId", gb.headCommitId());
                branch.put("created", gb.created());
                branch.put("gitBacked", true);
                branches.add(branch);
                seen.add(gb.name());
            }
        } catch (IOException e) {
            log.warn("Failed to list JGit branches: {}", e.getMessage());
        }

        // 2. JPA branches (fallback / additional)
        for (String name : documentRepository.findDistinctBranches()) {
            if (seen.contains(name)) continue;
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", name);
            documentRepository.findFirstByBranchOrderByParsedAtDesc(name)
                    .ifPresent(doc -> {
                        branch.put("headCommitId", doc.getCommitId());
                        branch.put("headDocumentId", doc.getId());
                        branch.put("lastUpdated", doc.getParsedAt());
                    });
            branch.put("gitBacked", false);
            branches.add(branch);
        }

        return ResponseEntity.ok(branches);
    }

    @PostMapping("/branches")
    @Operation(summary = "Create a new branch by forking from an existing branch",
            description = "Creates a new Git branch pointing at the HEAD of the source branch. " +
                    "Also creates a JPA document copy for queryability.")
    public ResponseEntity<Map<String, Object>> createBranch(
            @RequestParam String name,
            @RequestParam(required = false) Long fromDocumentId,
            @RequestParam(required = false, defaultValue = "draft") String fromBranch) {

        // When an explicit document ID is provided, it must exist
        if (fromDocumentId != null) {
            ArchitectureDslDocument sourceDoc = documentRepository.findById(fromDocumentId).orElse(null);
            if (sourceDoc == null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Source document not found: " + fromDocumentId);
                return ResponseEntity.badRequest().body(error);
            }

            // Fork from the source document's branch in Git
            String gitHeadId = null;
            try {
                gitHeadId = gitRepository.createBranch(name, sourceDoc.getBranch());
            } catch (IOException e) {
                log.warn("JGit branch creation failed: {}", e.getMessage());
            }

            String commitId = gitHeadId != null ? gitHeadId : UUID.randomUUID().toString().substring(0, 8);

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
            result.put("gitBacked", gitHeadId != null);
            return ResponseEntity.ok(result);
        }

        // Fork from branch name
        // 1. Try creating Git branch
        String gitHeadId = null;
        try {
            gitHeadId = gitRepository.createBranch(name, fromBranch);
        } catch (IOException e) {
            log.warn("JGit branch creation failed: {}", e.getMessage());
        }

        // 2. Also create JPA copy from latest doc on source branch
        ArchitectureDslDocument sourceDoc = documentRepository
                .findFirstByBranchOrderByParsedAtDesc(fromBranch).orElse(null);

        if (sourceDoc == null && gitHeadId == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Source branch/document not found");
            return ResponseEntity.badRequest().body(error);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", name);

        if (sourceDoc != null) {
            String commitId = gitHeadId != null ? gitHeadId : UUID.randomUUID().toString().substring(0, 8);

            ArchitectureDslDocument newDoc = new ArchitectureDslDocument();
            newDoc.setPath(sourceDoc.getPath());
            newDoc.setBranch(name);
            newDoc.setCommitId(commitId);
            newDoc.setNamespace(sourceDoc.getNamespace());
            newDoc.setDslVersion(sourceDoc.getDslVersion());
            newDoc.setRawContent(sourceDoc.getRawContent());

            ArchitectureDslDocument saved = documentRepository.save(newDoc);
            result.put("documentId", saved.getId());
            result.put("commitId", commitId);
            result.put("forkedFrom", sourceDoc.getId());
            result.put("timestamp", saved.getParsedAt());
        } else {
            result.put("commitId", gitHeadId);
        }

        result.put("gitBacked", gitHeadId != null);
        return ResponseEntity.ok(result);
    }

    // ── Git-backed read operations ──────────────────────────────────

    @GetMapping("/git/head")
    @Operation(summary = "Read DSL text from the HEAD of a Git branch",
            description = "Reads the DSL text directly from the JGit repository.")
    public ResponseEntity<Map<String, Object>> getGitHead(
            @RequestParam(defaultValue = "draft") String branch) {
        try {
            String dslText = gitRepository.getDslAtHead(branch);
            if (dslText == null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Branch '" + branch + "' not found or empty");
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("branch", branch);
            result.put("dslText", dslText);
            result.put("length", dslText.length());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Git read failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping("/git/commit/{commitId}")
    @Operation(summary = "Read DSL text from a specific Git commit",
            description = "Reads the architecture.taxdsl file from the given commit SHA.")
    public ResponseEntity<Map<String, Object>> getGitCommit(@PathVariable String commitId) {
        try {
            String dslText = gitRepository.getDslAtCommit(commitId);
            if (dslText == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("commitId", commitId);
            result.put("dslText", dslText);
            result.put("length", dslText.length());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Git read failed: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
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
