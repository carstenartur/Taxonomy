package com.taxonomy.composition.dsl.controller;

import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.composition.dsl.service.ProjectDslSource;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.model.ProjectCatalogue;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.shared.features.ConditionalOnFeature;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@ConditionalOnFeature({"architecture"})
public class ProjectDslController {
    private static final MediaType PROJECT_DSL = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final String REPOSITORY = "Expected repository ID of the authenticated workspace";
    private static final String BRANCH = "Expected branch of the authenticated workspace";
    private static final String COMMIT = "Git checkpoint reachable from the selected branch. Omit when selecting a semantic revision.";
    private static final String REVISION = "Retained semantic revision of the selected workspace. Omit when selecting a Git checkpoint.";
    private final ProjectDslSource projects;
    private final CatalogueSourceJournal catalogues;

    public ProjectDslController(ProjectDslSource projects, CatalogueSourceJournal catalogues) {
        this.projects = projects;
        this.catalogues = catalogues;
    }

    @PostMapping(value = "/api/dsl/project/compare-current", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Preview an explicit comparison to the current catalogue",
            description = "Records the current generation as a separate comparison in the returned draft. The original basis stays unchanged. Use the normal commit workflow to save the result.")
    @ApiResponse(responseCode = "200", description = "Project DSL with the comparison generation recorded", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Invalid project DSL", content = @Content)
    @ApiResponse(responseCode = "409", description = "No catalogue generation is available for comparison", content = @Content)
    public ResponseEntity<String> compareCurrent(@RequestBody String dsl) {
        var current = catalogues.current();
        if (current == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "No retained catalogue generation");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(PROJECT_DSL)
                .header("X-Taxonomy-Comparison-Catalogue", current.id())
                .body(ProjectCatalogue.compareWith(dsl, UUID.fromString(current.id())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> malformedSelection(IllegalArgumentException error) {
        return ResponseEntity.badRequest().build();
    }

    /** Nullable snapshot means the named input generation is unavailable in this installation. */
    public record Catalogues(String recordedBasis, CatalogueSourceJournal.Snapshot basis,
                             String recordedComparison, CatalogueSourceJournal.Snapshot comparison) {}

    private Catalogues evidence(MetaAst meta) {
        String basis = meta == null ? null : meta.catalogueRevision();
        String compare = meta == null ? null : meta.comparisonCatalogueRevision();
        return new Catalogues(basis, catalogues.findRevision(basis).orElse(null),
                compare, catalogues.findRevision(compare).orElse(null));
    }

    @GetMapping("/api/dsl/project/catalogues")
    @Operation(summary = "Read the historical catalogue evidence for a selected project revision",
            description = "Returns the original and optional comparison generations and their retained import fingerprints. An unknown historical source remains unresolved.")
    @ApiResponse(responseCode = "200", description = "Recorded catalogue generations and their available provenance", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Invalid project revision selection", content = @Content)
    @ApiResponse(responseCode = "404", description = "The selected revision could not be read", content = @Content)
    @ApiResponse(responseCode = "409", description = "The selected workspace context has changed or the revision cannot be opened in it", content = @Content)
    public ResponseEntity<Catalogues> catalogues(
            @Parameter(description = REPOSITORY) @RequestParam(required = false) String repositoryId,
            @Parameter(description = BRANCH) @RequestParam(required = false) String branch,
            @Parameter(description = COMMIT) @RequestParam(required = false) String commit,
            @Parameter(description = REVISION) @RequestParam(required = false) Long revision) throws IOException {
        var document = projects.read(repositoryId, branch, commit, revision);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidence(new TaxDslParser().parse(document.dsl()).getMeta()));
    }

    @GetMapping(value = "/api/dsl/project/catalogue-source", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Download a retained official catalogue input for this exact model revision",
            description = "Retrieves the archived workbook, overlay or relation input bound to the original generation or its explicitly recorded comparison. An unavailable archived source cannot be replaced with a current file.")
    @ApiResponse(responseCode = "200", description = "Retained bytes of the selected catalogue input", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Invalid revision selection or catalogue input kind", content = @Content)
    @ApiResponse(responseCode = "404", description = "The selected revision or its retained catalogue input is unavailable", content = @Content)
    @ApiResponse(responseCode = "409", description = "The selected workspace context has changed or the revision cannot be opened in it", content = @Content)
    public ResponseEntity<byte[]> archivedSource(
            @Parameter(description = REPOSITORY) @RequestParam(required = false) String repositoryId,
            @Parameter(description = BRANCH) @RequestParam(required = false) String branch,
            @Parameter(description = COMMIT) @RequestParam(required = false) String commit,
            @Parameter(description = REVISION) @RequestParam(required = false) Long revision,
            @Parameter(description = "Archived catalogue input to download",
                    schema = @Schema(allowableValues = {"workbook", "overlay", "relations"})) @RequestParam String input,
            @Parameter(description = "Select the explicitly recorded comparison generation when true. Defaults to the project's original generation.")
            @RequestParam(defaultValue = "false") boolean comparison) throws IOException {
        var document = projects.read(repositoryId, branch, commit, revision);
        var bindings = evidence(new TaxDslParser().parse(document.dsl()).getMeta());
        var generation = comparison ? bindings.comparison() : bindings.basis();
        if (generation == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalogue source unresolved");
        var entry = switch (input) {
            case "workbook" -> generation.workbook();
            case "overlay" -> generation.overlay();
            case "relations" -> generation.relations();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown catalogue input");
        };
        if (entry.use() != CatalogueSourceJournal.Use.APPLIED)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalogue input was not applied or retained");
        var bytes = catalogues.retainedWorkbook(entry);
        try (var stream = bytes.openStream()) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("X-Taxonomy-Catalogue-Revision", generation.id())
                    .header("X-Content-Type-Options", "nosniff").eTag(bytes.sha256()).body(stream.readAllBytes());
        }
    }

    @GetMapping(value = "/api/dsl/project", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Read the selected project's original DSL",
            description = "Reads the authorized workspace, a retained semantic revision or a reachable Git checkpoint. The exact source text preserves isolated elements, packages, views, evidence, mappings and extension blocks. The response identifies the recorded catalogue generation when its source remains available.")
    @ApiResponse(responseCode = "200", description = "Original DSL of the selected project revision", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Invalid project revision selection", content = @Content)
    @ApiResponse(responseCode = "404", description = "The selected revision could not be read", content = @Content)
    @ApiResponse(responseCode = "409", description = "The selected workspace context has changed or the revision cannot be opened in it", content = @Content)
    public ResponseEntity<String> read(
            @Parameter(description = REPOSITORY) @RequestParam(required = false) String repositoryId,
            @Parameter(description = BRANCH) @RequestParam(required = false) String branch,
            @Parameter(description = COMMIT) @RequestParam(required = false) String commit,
            @Parameter(description = REVISION) @RequestParam(required = false) Long revision) throws IOException {
        var document = projects.read(repositoryId, branch, commit, revision);
        var meta = new TaxDslParser().parse(document.dsl()).getMeta();
        var basis = catalogues.findRevision(meta == null ? null : meta.catalogueRevision())
                .filter(snapshot -> snapshot.workbook().use() == CatalogueSourceJournal.Use.APPLIED);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(PROJECT_DSL)
                .header("X-Taxonomy-Source", document.source())
                .header("X-Taxonomy-Catalogue-State", basis.isPresent() ? "RETAINED" : "UNRESOLVED");
        basis.ifPresent(source -> response.header("X-Taxonomy-Catalogue-Revision", source.id()));
        if (document.context().commit() != null) response.header("X-Taxonomy-Commit", document.context().commit());
        response.header("X-Taxonomy-Semantic-Revision", Long.toString(document.context().revision()));
        return response.body(document.dsl());
    }
}
