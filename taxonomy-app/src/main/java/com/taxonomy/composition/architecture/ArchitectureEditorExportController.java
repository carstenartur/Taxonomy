package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureEditorExportPort;
import com.taxonomy.editor.ArchitectureEditorExportPort.ExportDocument;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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
public final class ArchitectureEditorExportController {

    private final ArchitectureEditorExportPort exports;
    private final WorkspaceResolver resolver;
    private final SvgDiagramRenderer svg;
    private final ArchitecturePdfRenderer pdf;

    public ArchitectureEditorExportController(
            ArchitectureEditorExportPort exports,
            WorkspaceResolver resolver,
            SvgDiagramRenderer svg,
            ArchitecturePdfRenderer pdf) {
        this.exports = Objects.requireNonNull(exports, "exports");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.svg = Objects.requireNonNull(svg, "svg");
        this.pdf = Objects.requireNonNull(pdf, "pdf");
    }

    @GetMapping(value = "/api/architecture/editor.svg", produces = "image/svg+xml")
    public ResponseEntity<String> svg(
            @RequestParam String repositoryId,
            @RequestParam String workspaceScopeKey,
            @RequestParam String branch,
            @RequestParam(required = false) String commit,
            @RequestParam(required = false) Long revision) throws IOException {
        requireExactSelection(commit, revision);
        ExportDocument document = exports.read(
                readContext(repositoryId, workspaceScopeKey, branch), commit, revision);
        return documentResponse(document)
                .header("X-Taxonomy-Layout-Source", document.layoutMode())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=architecture.svg")
                .body(svg.render(document.scene()));
    }

    @GetMapping(value = "/api/architecture/editor.pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> pdf(
            @RequestParam String repositoryId,
            @RequestParam String workspaceScopeKey,
            @RequestParam String branch,
            @RequestParam(required = false) String commit,
            @RequestParam(required = false) Long revision) throws IOException {
        requireExactSelection(commit, revision);
        ExportDocument document = exports.read(
                readContext(repositoryId, workspaceScopeKey, branch), commit, revision);
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
