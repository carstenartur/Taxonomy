package com.taxonomy.composition.dsl.controller;

import org.springframework.http.CacheControl;
import com.taxonomy.composition.dsl.service.ProjectDslSource;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import com.taxonomy.dsl.diff.DiffSummary;
import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.diff.SemanticDiffDescriber;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.workspace.storage.DslCommit;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.composition.dsl.service.DslDocumentOperationsFacade;
import com.taxonomy.versioning.controller.DslReadWorkspaceContextResolver;
import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Composes knowledge documents, archive compatibility, and workspace response context. */
@com.taxonomy.shared.features.ConditionalOnFeature({"architecture"})
@RestController
@RequestMapping("/api/dsl")
@Tag(name = "Architecture DSL")
public class DslDocumentApiController {
    private static final Logger log = LoggerFactory.getLogger(DslDocumentApiController.class);
    private final ProjectDslSource projects;
    private final DslDocumentOperationsFacade documents;
    private final DslOperationsFacade dslOps;
    private final WorkspaceResolver workspaceResolver;
    private final DslReadWorkspaceContextResolver readContextResolver;

    public DslDocumentApiController(DslDocumentOperationsFacade documents, DslOperationsFacade dslOps,
                                    WorkspaceResolver workspaceResolver,
                                    DslReadWorkspaceContextResolver readContextResolver,
                                    ProjectDslSource projects) {
        this.projects = projects;
        this.documents = documents;
        this.dslOps = dslOps;
        this.workspaceResolver = workspaceResolver;
        this.readContextResolver = readContextResolver;
    }

    // ── Export & current state ────────────────────────────────────────

    @GetMapping("/export")
    @Operation(summary = "Export the entire current official catalogue as DSL text",
            description = "Serializes the official catalogue's concrete nodes, hierarchy, relationships and recorded generation as DSL. Project source is read from /api/dsl/project.")
    public ResponseEntity<String> exportCurrentArchitecture(
            @RequestParam(defaultValue = "default") String namespace) {
        String dsl = documents.exportAll(namespace);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(dsl);
    }

    @GetMapping("/current")
    @Operation(summary = "Get selected architecture state as structured JSON",
            description = "Returns collections and provenance parsed from the selected project's saved DSL. An optional commit selects a reachable Git checkpoint; revision selects a retained workspace revision.")
    @ApiResponse(responseCode = "200", description = "Structured architecture and provenance of the selected project revision", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Invalid project revision selection", content = @Content)
    @ApiResponse(responseCode = "404", description = "The selected revision could not be read", content = @Content)
    @ApiResponse(responseCode = "409", description = "The selected workspace context has changed or the revision cannot be opened in it", content = @Content)
    public ResponseEntity<Map<String, Object>> getCurrentArchitecture(
            @Parameter(description = "Expected repository ID of the authenticated workspace") @RequestParam(required = false) String repositoryId,
            @Parameter(description = "Expected branch of the authenticated workspace") @RequestParam(required = false) String branch,
            @Parameter(description = "Reachable Git checkpoint. Omit when selecting a semantic revision.") @RequestParam(required = false) String commit,
            @Parameter(description = "Retained semantic revision. Omit when selecting a Git checkpoint.") @RequestParam(required = false) Long revision) throws IOException {
        var document = projects.read(repositoryId, branch, commit, revision);
        var ast = new TaxDslParser().parse(document.dsl());
        var model = new AstToModelMapper().map(ast);
        var source = document.context();
        var timestamp = document.versions().stream().filter(item -> item.commitId().equals(source.commit()))
                .map(DslCommit::timestamp).findFirst().orElse(null);
        var result = new LinkedHashMap<String, Object>();
        result.put("elements", model.getElements());
        result.put("relations", model.getRelations());
        result.put("requirements", model.getRequirements());
        result.put("mappings", model.getMappings());
        result.put("views", model.getViews());
        result.put("evidence", model.getEvidence());
        result.put("packages", model.getPackages());
        result.put("organizations", model.getOrganizations());
        result.put("sources", model.getSources());
        result.put("sourceVersions", model.getSourceVersions());
        result.put("sourceFragments", model.getSourceFragments());
        result.put("requirementSourceLinks", model.getRequirementSourceLinks());
        result.put("catalogueRevision", model.getCatalogueRevision());
        result.put("comparisonCatalogueRevision", model.getComparisonCatalogueRevision());
        result.put("context", source);
        var workspace = new WorkspaceContext(source.actor(), "__shared__".equals(source.workspaceScopeKey())
                ? null : source.workspaceScopeKey(), source.branch(), source.repositoryId());
        var indexed = dslOps.getViewContext(source.actor(), source.branch(), workspace);
        boolean differentRevision = !Objects.equals(indexed.basedOnCommit(), source.commit())
                || ("WORKSPACE_REVISION".equals(document.source()) && source.revision() != document.checkpointRevision());
        boolean provisional = model.getRelations().stream().anyMatch(r ->
                "provisional".equals(r.getStatus()) || "proposed".equals(r.getStatus()));
        result.put("viewContext", new ViewContext(source.commit(), source.branch(), timestamp, provisional,
                differentRevision || indexed.projectionStale(), differentRevision || indexed.indexStale()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
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
                documents.materialize(dslText, path, branch, commitId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", matResult.valid());
        result.put("errors", matResult.errors());
        result.put("warnings", matResult.warnings());
        result.put("relationsCreated", matResult.relationsCreated());
        result.put("hypothesesCreated", matResult.hypothesesCreated());
        result.put("documentId", matResult.documentId());

        String effectiveBranch = workspaceResolver.resolveCurrentRepositoryContext().branch();
        result.put("viewContext", dslOps.getViewContext(effectiveBranch));

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
                    documents.materializeIncremental(beforeDocId, afterDocId);

            // Derive branch from the after document when available
            String branch = "draft";
            var afterDoc = documents.findDocumentById(afterDocId);
            if (afterDoc.isPresent() && afterDoc.get().getBranch() != null) {
                branch = afterDoc.get().getBranch();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", matResult.valid());
            result.put("warnings", matResult.warnings());
            result.put("relationsCreated", matResult.relationsCreated());
            result.put("hypothesesCreated", matResult.hypothesesCreated());
            result.put("documentId", matResult.documentId());
            result.put("viewContext", dslOps.getViewContext(branch));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/history")
    @Operation(summary = "Get commit history for a branch",
            description = "Returns all DSL commits on the specified branch from the JGit " +
                    "repository (database-backed), newest first.")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "draft") String branch) {

        String username = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext workspaceContext = readContextResolver.resolve(username);
        try {
            List<DslCommit> gitHistory = dslOps.getDslHistory(branch, workspaceContext);
            List<Map<String, Object>> history = new ArrayList<>();
            for (DslCommit c : gitHistory) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("commitId", c.commitId());
                entry.put("branch", branch);
                entry.put("author", c.author());
                entry.put("message", c.message());
                entry.put("timestamp", c.timestamp());
                entry.put("signature", c.signature());
                // Resolve documentId from the materialized document (if it exists)
                entry.put("documentId", documents.findDocumentIdByCommitId(c.commitId()).orElse(null));
                history.add(entry);
            }
            ViewContext viewContext = dslOps.getViewContext(workspaceContext.username(), branch, workspaceContext);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("currentBranch", branch);
            result.put("headCommit", viewContext.basedOnCommit());
            result.put("commits", history);
            result.put("viewContext", viewContext);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to read history for branch '{}'", branch, e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("errorCode", "HISTORY_LOAD_FAILED");
            errorResult.put("commits", List.of());
            errorResult.put("currentBranch", branch);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(errorResult);
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
            ModelDiff diff = documents.diffBetween(beforeId, afterId);

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
            ModelDiff diff = documents.diffBetween(beforeId, afterId);
            DiffSummary summary = DiffSummary.fromDiff(diff);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // ── Documents ────────────────────────────────────────────────────

    @GetMapping("/documents")
    @Operation(summary = "List stored DSL documents",
            description = "Lists the stored materialized DSL document records and their source metadata. This read does not materialize documents or advance repository branches.")
    public ResponseEntity<List<ArchitectureDslDocument>> listDocuments() {
        return ResponseEntity.ok(documents.listDocuments());
    }

}
