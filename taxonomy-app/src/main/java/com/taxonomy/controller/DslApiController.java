package com.taxonomy.controller;

import com.taxonomy.dsl.diff.DiffSummary;
import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.diff.SemanticDiffDescriber;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dsl.storage.DslBranch;
import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.validation.DslValidationResult;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.model.ArchitectureDslDocument;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationHypothesis;
import com.taxonomy.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.service.CommitIndexService;
import com.taxonomy.service.ConflictDetectionService;
import com.taxonomy.service.HypothesisService;
import com.taxonomy.service.RepositoryStateGuard;
import com.taxonomy.service.RepositoryStateService;
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
 * which stores DSL documents as Git objects (blobs → trees → commits)
 * in the HSQLDB database via the {@code sandbox-jgit-storage-hibernate}
 * pattern. JGit is the <b>single source of truth</b> for versioned DSL content.
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
    private final CommitIndexService commitIndexService;
    private final ConflictDetectionService conflictDetectionService;
    private final RepositoryStateGuard stateGuard;
    private final RepositoryStateService repositoryStateService;

    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final DslValidator validator = new DslValidator();

    public DslApiController(TaxDslExportService exportService,
                            DslMaterializeService materializeService,
                            ArchitectureDslDocumentRepository documentRepository,
                            HypothesisService hypothesisService,
                            DslGitRepository gitRepository,
                            CommitIndexService commitIndexService,
                            ConflictDetectionService conflictDetectionService,
                            RepositoryStateGuard stateGuard,
                            RepositoryStateService repositoryStateService) {
        this.exportService = exportService;
        this.materializeService = materializeService;
        this.documentRepository = documentRepository;
        this.hypothesisService = hypothesisService;
        this.gitRepository = gitRepository;
        this.commitIndexService = commitIndexService;
        this.conflictDetectionService = conflictDetectionService;
        this.stateGuard = stateGuard;
        this.repositoryStateService = repositoryStateService;
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
        result.put("viewContext", repositoryStateService.getViewContext("draft"));
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

        String effectiveBranch = branch != null ? branch : "draft";
        result.put("viewContext", repositoryStateService.getViewContext(effectiveBranch));

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
            result.put("viewContext", repositoryStateService.getViewContext("draft"));
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
            description = "Stores the DSL text as a new commit in the JGit repository " +
                    "(database-backed via HibernateRepository). JGit is the single " +
                    "source of truth. Does not materialize — use POST /api/dsl/materialize " +
                    "or POST /api/dsl/materialize-incremental for that.")
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

        // Commit to JGit repository — the single source of truth
        // All Git objects are stored in the database (git_packs table)
        // via HibernateRepository/HibernateObjDatabase
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitId", gitCommitId);
        result.put("branch", branch);
        result.put("author", author);
        result.put("message", message);
        result.put("valid", true);
        result.put("warnings", validation.getWarnings());
        result.put("databaseBacked", gitRepository.isDatabaseBacked());
        result.put("viewContext", repositoryStateService.getViewContext(branch));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    @Operation(summary = "Get commit history for a branch",
            description = "Returns all DSL commits on the specified branch from the JGit " +
                    "repository (database-backed), newest first.")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "draft") String branch) {

        try {
            List<DslCommit> gitHistory = gitRepository.getDslHistory(branch);
            List<Map<String, Object>> history = new ArrayList<>();
            for (DslCommit c : gitHistory) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("commitId", c.commitId());
                entry.put("branch", branch);
                entry.put("author", c.author());
                entry.put("message", c.message());
                entry.put("timestamp", c.timestamp());
                history.add(entry);
            }
            ViewContext viewContext = repositoryStateService.getViewContext(branch);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("currentBranch", branch);
            result.put("headCommit", viewContext.basedOnCommit());
            result.put("commits", history);
            result.put("viewContext", viewContext);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to read history for branch '{}'", branch, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/diff/{beforeId}/{afterId}")
    @Operation(summary = "Compute semantic diff between two DSL commits",
            description = "Returns the added, removed, and changed elements and relations " +
                    "between two Git commit SHAs.")
    public ResponseEntity<?> diffDocuments(
            @PathVariable String beforeId,
            @PathVariable String afterId) {
        try {
            ModelDiff diff;

            // Try as Git commit SHAs first (40-char hex)
            if (looksLikeGitSha(beforeId) && looksLikeGitSha(afterId)) {
                diff = gitRepository.diffBetween(beforeId, afterId);
            } else {
                // Fall back to JPA document IDs for backward compatibility
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

            // Include structural details
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("addedElements", diff.addedElements());
            details.put("removedElements", diff.removedElements());
            details.put("changedElements", diff.changedElements());
            details.put("addedRelations", diff.addedRelations());
            details.put("removedRelations", diff.removedRelations());
            details.put("changedRelations", diff.changedRelations());
            result.put("details", details);

            // Include semantic changes for better reviewability
            SemanticDiffDescriber describer = new SemanticDiffDescriber();
            result.put("semanticChanges", describer.describe(diff));
            result.put("semanticSummary", describer.summarize(diff));

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/diff/semantic/{beforeId}/{afterId}")
    @Operation(summary = "Compute semantic diff between two DSL commits",
            description = "Returns human-readable semantic change descriptions " +
                    "(e.g. 'Title changed', 'Relation added') together with statistics " +
                    "and before/after values—designed for reviews and change documentation.")
    public ResponseEntity<?> semanticDiff(
            @PathVariable String beforeId,
            @PathVariable String afterId) {
        try {
            ModelDiff diff;
            if (looksLikeGitSha(beforeId) && looksLikeGitSha(afterId)) {
                diff = gitRepository.diffBetween(beforeId, afterId);
            } else {
                diff = materializeService.diffDocuments(Long.valueOf(beforeId), Long.valueOf(afterId));
            }
            DiffSummary summary = DiffSummary.fromDiff(diff);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/diff/text/{beforeId}/{afterId}")
    @Operation(summary = "Compute JGit-native text diff between two commits",
            description = "Returns a unified diff patch produced by JGit DiffFormatter.")
    public ResponseEntity<?> textDiff(
            @PathVariable String beforeId,
            @PathVariable String afterId) {
        try {
            String diff = gitRepository.textDiff(beforeId, afterId);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(diff);
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
    @Operation(summary = "List all branches",
            description = "Returns branches from the JGit repository (database-backed).")
    public ResponseEntity<List<Map<String, Object>>> listBranches() {
        List<Map<String, Object>> branches = new ArrayList<>();

        try {
            for (DslBranch gb : gitRepository.listBranches()) {
                Map<String, Object> branch = new LinkedHashMap<>();
                branch.put("name", gb.name());
                branch.put("headCommitId", gb.headCommitId());
                branch.put("created", gb.created());
                branches.add(branch);
            }
        } catch (IOException e) {
            log.error("Failed to list branches", e);
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.ok(branches);
    }

    @PostMapping("/branches")
    @Operation(summary = "Create a new branch by forking from an existing branch",
            description = "Creates a new Git branch pointing at the HEAD of the source branch.")
    public ResponseEntity<Map<String, Object>> createBranch(
            @RequestParam String name,
            @RequestParam(required = false, defaultValue = "draft") String fromBranch) {

        String gitHeadId;
        try {
            gitHeadId = gitRepository.createBranch(name, fromBranch);
        } catch (IOException e) {
            log.error("Branch creation failed", e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Branch creation failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }

        if (gitHeadId == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Source branch '" + fromBranch + "' not found");
            return ResponseEntity.badRequest().body(error);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", name);
        result.put("commitId", gitHeadId);
        result.put("forkedFrom", fromBranch);
        return ResponseEntity.ok(result);
    }

    // ── Cherry-pick & merge ─────────────────────────────────────────

    @PostMapping("/cherry-pick")
    @Operation(summary = "Cherry-pick a commit onto a target branch",
            description = "Applies the changes from a specific commit to the HEAD of the target branch.")
    public ResponseEntity<Map<String, Object>> cherryPick(
            @RequestParam String commitId,
            @RequestParam(defaultValue = "review") String targetBranch) {
        try {
            String newCommitId = gitRepository.cherryPick(commitId, targetBranch);
            if (newCommitId == null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Cherry-pick failed (conflict or invalid commit)");
                return ResponseEntity.badRequest().body(error);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("commitId", newCommitId);
            result.put("targetBranch", targetBranch);
            result.put("cherryPickedFrom", commitId);
            return ResponseEntity.ok(result);
        } catch (org.eclipse.jgit.errors.MissingObjectException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Cherry-pick failed: commit not found — " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (IOException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Cherry-pick failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PostMapping("/merge")
    @Operation(summary = "Merge one branch into another",
            description = "Performs a three-way merge of the source branch into the target branch.")
    public ResponseEntity<Map<String, Object>> merge(
            @RequestParam String fromBranch,
            @RequestParam(defaultValue = "accepted") String intoBranch) {
        try {
            String mergeCommitId = gitRepository.merge(fromBranch, intoBranch);
            if (mergeCommitId == null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Merge failed (conflict or branches not found)");
                return ResponseEntity.badRequest().body(error);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("commitId", mergeCommitId);
            result.put("fromBranch", fromBranch);
            result.put("intoBranch", intoBranch);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Merge failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    // ── Merge/Cherry-pick preview ───────────────────────────────────

    @GetMapping("/merge/preview")
    @Operation(summary = "Preview a merge: would it conflict?",
            description = "Dry-run merge check. Returns whether the merge would succeed, " +
                    "whether it's a fast-forward, or whether conflicts would occur.")
    public ResponseEntity<ConflictDetectionService.MergePreview> previewMerge(
            @RequestParam String from,
            @RequestParam String into) {
        return ResponseEntity.ok(conflictDetectionService.previewMerge(from, into));
    }

    @GetMapping("/cherry-pick/preview")
    @Operation(summary = "Preview a cherry-pick: would it conflict?",
            description = "Dry-run cherry-pick check. Returns whether the cherry-pick would succeed " +
                    "or whether conflicts would occur.")
    public ResponseEntity<ConflictDetectionService.CherryPickPreview> previewCherryPick(
            @RequestParam String commitId,
            @RequestParam(defaultValue = "review") String targetBranch) {
        return ResponseEntity.ok(conflictDetectionService.previewCherryPick(commitId, targetBranch));
    }

    @GetMapping("/operation/check")
    @Operation(summary = "Check whether a write operation is safe",
            description = "Returns warnings (e.g. stale projection) and blocks (e.g. operation in progress) " +
                    "for a given write operation type.")
    public ResponseEntity<RepositoryStateGuard.OperationCheck> checkOperation(
            @RequestParam(defaultValue = "draft") String branch,
            @RequestParam String operationType) {
        return ResponseEntity.ok(stateGuard.checkWriteOperation(branch, operationType));
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
            result.put("viewContext", repositoryStateService.getViewContext(branch));
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

    @PostMapping("/hypotheses/{id}/apply-session")
    @Operation(summary = "Mark hypothesis as applied for current analysis session only",
            description = "The relationship is used in the current Architecture View and exports " +
                    "but is not permanently persisted as a TaxonomyRelation.")
    public ResponseEntity<Map<String, Object>> applyHypothesisForSession(@PathVariable Long id) {
        try {
            RelationHypothesis hypothesis = hypothesisService.applyForSession(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", hypothesis.getId());
            result.put("appliedInCurrentAnalysis", hypothesis.isAppliedInCurrentAnalysis());
            result.put("status", hypothesis.getStatus().name());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
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

    // ── History Search ──────────────────────────────────────────────

    @PostMapping("/history/index")
    @Operation(summary = "Index commits on a branch for history search",
            description = "Parses and tokenizes all unindexed commits on the given branch.")
    public ResponseEntity<Map<String, Object>> indexHistory(
            @RequestParam(defaultValue = "draft") String branch) {
        int indexed = commitIndexService.indexBranch(branch);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch);
        result.put("indexed", indexed);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history/search")
    @Operation(summary = "Search architecture commit history",
            description = "Full-text search across tokenized DSL changes, commit messages, " +
                    "and affected element/relation IDs using Hibernate Search (Lucene backend). " +
                    "Results are ranked by relevance score.")
    public ResponseEntity<?> searchHistory(
            @RequestParam String query,
            @RequestParam(defaultValue = "50") int maxResults) {
        return ResponseEntity.ok(commitIndexService.search(query, maxResults));
    }

    @GetMapping("/history/element/{elementId}")
    @Operation(summary = "Find commits that affected a specific element",
            description = "Searches affectedElementIds and tokenized DSL text for the given " +
                    "element ID using Hibernate Search.")
    public ResponseEntity<?> findHistoryByElement(@PathVariable String elementId) {
        return ResponseEntity.ok(commitIndexService.findByElement(elementId));
    }

    @GetMapping("/history/relation")
    @Operation(summary = "Find commits that affected a specific relation",
            description = "Searches affectedRelationIds and tokenized DSL text for the given " +
                    "relation key (e.g., 'CP-1023 REALIZES CR-1047') using Hibernate Search.")
    public ResponseEntity<?> findHistoryByRelation(@RequestParam String key) {
        return ResponseEntity.ok(commitIndexService.findByRelation(key));
    }

    @GetMapping("/history/element/{elementId}/aggregation")
    @Operation(summary = "Get aggregated history for an element",
            description = "Returns firstSeen, lastSeen, occurrence count, volatility, " +
                    "and recent commit messages for the given element ID.")
    public ResponseEntity<?> elementHistoryAggregation(@PathVariable String elementId) {
        var aggregation = commitIndexService.aggregateElementHistory(elementId);
        if (aggregation == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("elementId", elementId);
            result.put("message", "No history found for element " + elementId);
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(aggregation);
    }
}
