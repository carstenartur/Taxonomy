package com.taxonomy.composition.architecture;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.dsl.admbw.AdmbwFffmlvProfile;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureEditorExportPort;
import com.taxonomy.editor.ArchitectureEditorExportPort.ExportDocument;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.extension.api.integration.AdmbwQeaExport;
import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.extension.api.plugin.ExtensionUnavailableException;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spring composition for immutable architecture exports.
 *
 * <p>Workspace/editor owns exact document state and the neutral render-ready scene;
 * this controller owns the cross-boundary HTTP/export orchestration.</p>
 */
@RestController
@Tag(name = "Architecture editor exports")
public final class ArchitectureEditorExportController {

    private final ArchitectureEditorExportPort exports;
    private final WorkspaceResolver resolver;
    private final SvgDiagramRenderer svg;
    private final ArchitecturePdfRenderer pdf;
    private final ExtensionCatalog catalog;

    public ArchitectureEditorExportController(
            ArchitectureEditorExportPort exports,
            WorkspaceResolver resolver,
            SvgDiagramRenderer svg,
            ArchitecturePdfRenderer pdf,
            ExtensionCatalog catalog) {
        this.exports = Objects.requireNonNull(exports, "exports");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.svg = Objects.requireNonNull(svg, "svg");
        this.pdf = Objects.requireNonNull(pdf, "pdf");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public record AdmbwAssessment(String repositoryId, String workspaceScopeKey, String branch, String commit,
                                  AdmbwFffmlvProfile.Report assessment, List<AdmbwFffmlvProfile.Issue> qeaIssues) { }

    @GetMapping(value = "/api/architecture/editor.admbw.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Prepare ADMBw C1-S1 and R7 from an exact architecture checkpoint",
            description = "Exports typed authoring data, structural findings and manual reviews for the public FFFmLV 2.0 and ADMBw 2026.05 subset. Requires a reachable Git checkpoint in the authenticated selected context. Optional viewId scopes C1-S1 only. Native EA delivery and acceptance require separate verification.")
    public ResponseEntity<AdmbwAssessment> admbw(
            @RequestParam String repositoryId,
            @RequestParam String workspaceScopeKey,
            @RequestParam String branch,
            @RequestParam String commit,
            @RequestParam String projectCode,
            @RequestParam(required = false) String viewId) throws IOException {
        var assessment = assessAdmbw(repositoryId, workspaceScopeKey, branch, commit, projectCode, viewId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=admbw-assessment.json")
                .header("X-Content-Type-Options", "nosniff")
                .body(assessment);
    }

    @PostMapping(value = "/api/architecture/editor.admbw.qea",
            consumes = {AdmbwQeaExport.MEDIA_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Operation(summary = "Download a native ADMBw QEA copy",
            description = "Requires the Sparx plugin, a structurally checked Git checkpoint in the selected context, and the GUID of a model root in the supplied QEA basis. The output adds C1-S1 and R7 to a private copy for manual review.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = AdmbwQeaExport.MEDIA_TYPE, schema = @Schema(type = "string", format = "binary"))))
    @ApiResponse(responseCode = "200", description = "Native QEA copy for manual review",
            content = @Content(mediaType = AdmbwQeaExport.MEDIA_TYPE, schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> admbwQea(
            @RequestParam String repositoryId,
            @RequestParam String workspaceScopeKey,
            @RequestParam String branch,
            @RequestParam String commit,
            @RequestParam String projectCode,
            @RequestParam(required = false) String viewId,
            @RequestParam String rootPackageGuid,
            HttpServletRequest request) throws IOException {
        var checked = assessAdmbw(repositoryId, workspaceScopeKey, branch, commit, projectCode, viewId);
        var report = checked.assessment();
        var views = List.of(report.capabilityToService(), report.requirementDerivation());
        var issues = views.stream().flatMap(view -> view.issues().stream())
                .map(AdmbwFffmlvProfile.Issue::code).distinct().toList();
        if (!issues.isEmpty()) throw new CommandProblem("ADMBW_CHANGES_REQUIRED", "commit",
                "Resolve the structural findings from the ADMBw assessment before creating a QEA copy", issues);

        if (!checked.qeaIssues().isEmpty()) throw new CommandProblem("ADMBW_QEA_PART_OWNER", "commit",
                "Bind each R7 ResourceRole to an existing owned EA Part before native delivery",
                checked.qeaIssues().stream().map(AdmbwFffmlvProfile.Issue::object).toList());

        try (var lease = catalog.acquire(AdmbwQeaExport.KEY, IntegrationConnectorExtension.class)) {
            if (!(lease.extension() instanceof AdmbwQeaExport writer)) throw admbwExporterUnavailable();
            var delivery = new AdmbwQeaExport.Delivery(report.projectCode(), checked.commit(),
                    views.stream().map(ArchitectureEditorExportController::nativeView).toList());
            var file = writer.exportAdmbw(delivery, rootPackageGuid, readQeaBasis(request));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.parseMediaType(file.mediaType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Taxonomy-Source", "GIT_CHECKPOINT")
                    .header("X-Taxonomy-Commit", checked.commit())
                    .body(file.content());
        } catch (ExtensionUnavailableException unavailable) {
            throw admbwExporterUnavailable();
        }
    }

    private AdmbwAssessment assessAdmbw(String repositoryId, String workspaceScopeKey, String branch,
                                        String commit, String projectCode, String viewId) throws IOException {
        var context = readContext(repositoryId, workspaceScopeKey, branch);
        var checkpoint = exports.readCheckpoint(context, commit);
        var assessment = new AdmbwFffmlvProfile().assess(checkpoint.dsl(), projectCode, viewId);
        var qeaIssues = assessment.requirementDerivation().elements().stream()
                .filter(e -> e.stereotype().equals("ResourceRole") && e.catalogueGuid() == null)
                .map(e -> new AdmbwFffmlvProfile.Issue("ADMBW_QEA_PART_OWNER", e.ref(),
                        "Bind this ResourceRole to an existing owned EA Part before native delivery."))
                .toList();
        return new AdmbwAssessment(repositoryId, workspaceScopeKey, branch, checkpoint.commit(), assessment, qeaIssues);
    }

    private static AdmbwQeaExport.View nativeView(AdmbwFffmlvProfile.View view) {
        return new AdmbwQeaExport.View(view.viewpoint(), view.name(), view.packagePath(),
                view.elements().stream().map(e -> new AdmbwQeaExport.Element(e.ref(), e.stereotype(), e.name(), e.notes(),
                        e.catalogueGuid(), e.requirementVersion(), e.requirementContentHash())).toList(),
                view.connectors().stream().map(c -> new AdmbwQeaExport.Connector(c.stereotype(), c.client(), c.supplier())).toList());
    }

    private static byte[] readQeaBasis(HttpServletRequest request) {
        int maxBytes = AdmbwQeaExport.MAX_BASIS_BYTES;
        if (request.getContentLengthLong() <= maxBytes) {
            try {
                byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
                if (bytes.length > 0 && bytes.length <= maxBytes) return bytes;
            } catch (IOException interrupted) {
                throw new CommandProblem("ADMBW_QEA_BASIS", "basis", "The QEA upload could not be read completely", List.of());
            }
        }
        throw new CommandProblem("ADMBW_QEA_BASIS", "basis", "Supply a QEA file of up to 32 MiB", List.of());
    }

    private static CommandProblem admbwExporterUnavailable() {
        return new CommandProblem("ADMBW_QEA_UNAVAILABLE", "basis", "Install or update the Sparx plugin to enable native ADMBw export", List.of());
    }

    @GetMapping(value = "/api/architecture/editor.svg", produces = "image/svg+xml")
    @Operation(summary = "Export an exact architecture revision as SVG",
            description = "Requires an exact commit or semantic revision plus repositoryId, workspaceScopeKey and branch matching the authenticated selected context. Exports the full stored scene, not the viewport. Returns a no-store SVG attachment with source, layout and ETag headers; does not change the editor.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<String> svg(
            @Parameter(description = "Repository identity; must match the authenticated selected context") @RequestParam String repositoryId,
            @Parameter(description = "Workspace overlay scope key for the selected authenticated workspace") @RequestParam String workspaceScopeKey,
            @Parameter(description = "Exact branch of the selected authenticated repository context") @RequestParam String branch,
            @Parameter(description = "Exact retained architecture Git commit; required when no semantic revision is supplied") @RequestParam(required = false) String commit,
            @Parameter(description = "Exact retained revision number or commit, as required by this endpoint") @RequestParam(required = false) Long revision,
            @Parameter(description = "Server layout: stable (default) or reduce-crossings") @RequestParam(required = false) String layout,
            @Parameter(description = "Optional retained workspace revision supplying optimized ordering") @RequestParam(required = false) Long layoutRevision,
            @Parameter(description = "Optional canonical architecture view identity") @RequestParam(required = false) String viewId) throws IOException {
        var strategy = com.taxonomy.diagram.DiagramLayout.Strategy.fromId(layout);
        requireExactSelection(commit, revision);
        var context = readContext(repositoryId, workspaceScopeKey, branch);
        ExportDocument document = viewId == null ? exports.read(context, commit, revision, strategy, layoutRevision)
                : exports.read(context, commit, revision, strategy, layoutRevision, viewId);
        return documentResponse(document)
                .header("X-Taxonomy-Layout-Source", document.layoutMode())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=architecture.svg")
                .body(svg.render(document.scene()));
    }

    @GetMapping(value = "/api/architecture/editor.pdf", produces = "application/pdf")
    @Operation(summary = "Export an exact architecture revision as PDF",
            description = "Requires an exact commit or semantic revision and the authenticated repository/workspace/branch context. Renders the immutable scene with provenance as a PDF attachment; neither the current model nor editor selection is changed.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> pdf(
            @Parameter(description = "Repository identity; must match the authenticated selected context") @RequestParam String repositoryId,
            @Parameter(description = "Workspace overlay scope key for the selected authenticated workspace") @RequestParam String workspaceScopeKey,
            @Parameter(description = "Exact branch of the selected authenticated repository context") @RequestParam String branch,
            @Parameter(description = "Exact retained architecture Git commit; required when no semantic revision is supplied") @RequestParam(required = false) String commit,
            @Parameter(description = "Exact retained revision number or commit, as required by this endpoint") @RequestParam(required = false) Long revision,
            @Parameter(description = "Server layout: stable (default) or reduce-crossings") @RequestParam(required = false) String layout,
            @Parameter(description = "Optional retained workspace revision supplying optimized ordering") @RequestParam(required = false) Long layoutRevision,
            @Parameter(description = "Optional canonical architecture view identity") @RequestParam(required = false) String viewId) throws IOException {
        var strategy = com.taxonomy.diagram.DiagramLayout.Strategy.fromId(layout);
        requireExactSelection(commit, revision);
        var context = readContext(repositoryId, workspaceScopeKey, branch);
        ExportDocument document = viewId == null ? exports.read(context, commit, revision, strategy, layoutRevision)
                : exports.read(context, commit, revision, strategy, layoutRevision, viewId);
        String selected = "GIT_CHECKPOINT".equals(document.source())
                ? "Checkpoint " + document.commit()
                : "Revision " + document.revision() + " / checkpoint " + document.commit();
        String provenance = document.repositoryId() + " / " + document.workspaceScopeKey()
                + " / " + document.branch() + "\n" + selected;
        return documentResponse(document)
                .header("X-Taxonomy-Layout-Source", document.layoutMode())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=architecture.pdf")
                .body(pdf.render(document.scene(), provenance));
    }

    public ResponseEntity<String> svg(String repositoryId, String workspaceScopeKey, String branch,
            String commit, Long revision, String layout, Long layoutRevision) throws IOException {
        return svg(repositoryId, workspaceScopeKey, branch, commit, revision, layout, layoutRevision, null);
    }

    public ResponseEntity<byte[]> pdf(String repositoryId, String workspaceScopeKey, String branch,
            String commit, Long revision, String layout, Long layoutRevision) throws IOException {
        return pdf(repositoryId, workspaceScopeKey, branch, commit, revision, layout, layoutRevision, null);
    }

    public ResponseEntity<String> svg(String repositoryId, String workspaceScopeKey, String branch,
            String commit, Long revision) throws IOException {
        return svg(repositoryId, workspaceScopeKey, branch, commit, revision, null, null);
    }

    public ResponseEntity<byte[]> pdf(String repositoryId, String workspaceScopeKey, String branch,
            String commit, Long revision) throws IOException {
        return pdf(repositoryId, workspaceScopeKey, branch, commit, revision, null, null);
    }

    private RepositoryContext readContext(
            String repositoryId,
            String workspaceScopeKey,
            String branch) {
        RepositoryContext context = resolver.resolveCurrentRepositoryContext();
        String selectedScope = WorkspaceOverlayScope.keyFor(context.workspaceId());
        if (!context.repositoryId().equals(repositoryId)
                || !selectedScope.equals(workspaceScopeKey)
                || !context.branch().equals(branch)) {
            throw new CommandProblem(
                    "NOT_FOUND",
                    "context",
                    "This exact context is not selected or accessible",
                    List.of());
        }
        return context;
    }

    private static void requireExactSelection(String commit, Long revision) {
        if (commit == null && revision == null) {
            throw new IllegalArgumentException(
                    "An exact revision or version is required for export");
        }
    }

    private static ResponseEntity.BodyBuilder documentResponse(ExportDocument document) {
        if ("GIT_CHECKPOINT".equals(document.source())) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .eTag(commitEtag(document.commit()))
                    .header("X-Taxonomy-Source", document.source());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, revisionEtag(document.revision()))
                .header("X-Taxonomy-Semantic-Revision", Long.toString(document.revision()))
                .header("X-Taxonomy-Source", document.source());
    }

    private static String revisionEtag(long revision) {
        return "\"workspace-revision-" + revision + "\"";
    }

    private static String commitEtag(String commitId) {
        return '"' + ObjectId.fromString(commitId).name() + '"';
    }

    @ExceptionHandler(CommandProblem.class)
    public ResponseEntity<Map<String, Object>> problem(CommandProblem error) {
        int status = switch (error.code()) {
            case "NOT_FOUND" -> 404;
            case "READ_ONLY" -> 403;
            case "ADMBW_QEA_UNAVAILABLE" -> 503;
            case "CONTEXT_CHANGED", "UNDO_CONFLICT", "ALREADY_INVERTED", "COMMAND_ID_REUSED",
                    "DEPENDENCIES_EXIST", "CHECKPOINT_PENDING", "CHECKPOINT_CONFLICT", "VERSION_CHANGED" -> 409;
            default -> 422;
        };
        return ResponseEntity.status(status).body(Map.of(
                "code", error.code(),
                "field", error.field(),
                "detail", error.getMessage(),
                "dependencies", error.dependencies()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_COMMAND",
                "detail", error.getMessage() == null ? "Invalid command" : error.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "GIT_UNAVAILABLE",
                "detail", "Git storage is unavailable; refresh before retrying the same command identity"));
    }
}
