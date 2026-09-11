package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.persistence.EditorJournal.RevisionConflict;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.http.GitHttpPrecondition;
import com.taxonomy.portfolio.workbench.ArchitecturePdfRenderer;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Controller
public class ArchitectureEditorController {
    private final ArchitectureEditorService service;
    private final ArchitectureEditorProjection projection;
    private final WorkspaceResolver resolver;
    private final SvgDiagramRenderer svg;
    private final ArchitecturePdfRenderer pdf;

    public ArchitectureEditorController(ArchitectureEditorService service, ArchitectureEditorProjection projection,
                                        WorkspaceResolver resolver, SvgDiagramRenderer svg, ArchitecturePdfRenderer pdf) {
        this.service = service;
        this.projection = projection;
        this.resolver = resolver;
        this.svg = svg;
        this.pdf = pdf;
    }

    @GetMapping("/architecture/editor")
    public String page() { return "architecture-editor"; }

    @GetMapping("/api/architecture/editor")
    @ResponseBody
    public ResponseEntity<ArchitectureEditorProjection.View> read(
            @RequestParam(required = false) String repositoryId,
            @RequestParam(required = false) String workspaceScopeKey,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commit,
            @RequestParam(required = false) Long revision) throws IOException {
        RepositoryContext context = readContext(repositoryId, workspaceScopeKey, branch);
        var document = service.read(context, commit, revision);
        return documentResponse(document).body(projection.project(document, mayEdit()));
    }

    @GetMapping(value = "/api/architecture/editor.svg", produces = "image/svg+xml")
    @ResponseBody
    public ResponseEntity<String> svg(@RequestParam String repositoryId, @RequestParam String workspaceScopeKey,
                                      @RequestParam String branch, @RequestParam(required = false) String commit,
                                      @RequestParam(required = false) Long revision) throws IOException {
        if (commit == null && revision == null) throw new IllegalArgumentException("An exact revision or version is required for export");
        var document = service.read(readContext(repositoryId, workspaceScopeKey, branch), commit, revision);
        var view = projection.project(document, false);
        return documentResponse(document)
                .header("X-Taxonomy-Layout-Source", view.schema().layoutMode())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=architecture.svg")
                .body(svg.render(view.scene()));
    }

    @GetMapping(value = "/api/architecture/editor.pdf", produces = "application/pdf")
    @ResponseBody
    public ResponseEntity<byte[]> pdf(@RequestParam String repositoryId, @RequestParam String workspaceScopeKey,
                                      @RequestParam String branch, @RequestParam(required = false) String commit,
                                      @RequestParam(required = false) Long revision) throws IOException {
        if (commit == null && revision == null) throw new IllegalArgumentException("An exact revision or version is required for export");
        var document = service.read(readContext(repositoryId, workspaceScopeKey, branch), commit, revision);
        var view = projection.project(document, false);
        var provenance = document.context();
        String selected = "GIT_CHECKPOINT".equals(document.source()) ? "Checkpoint " + provenance.commit()
                : "Revision " + provenance.revision() + " / checkpoint " + provenance.commit();
        return documentResponse(document)
                .header("X-Taxonomy-Layout-Source", view.schema().layoutMode())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=architecture.pdf")
                .body(pdf.render(view.scene(), provenance.repositoryId() + " / " + provenance.workspaceScopeKey()
                        + " / " + provenance.branch() + "\n" + selected));
    }

    @PostMapping("/api/architecture/editor/preview")
    @ResponseBody
    public ResponseEntity<Preview> preview(@RequestBody WireCommand body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Command command = command(body, ifMatch, ifNoneMatch);
        Preview result = service.preview(resolver.resolveCurrentRepositoryContext(), command);
        return response(HttpStatus.OK, result.context()).body(result);
    }

    @PostMapping("/api/architecture/editor/commands")
    @ResponseBody
    public ResponseEntity<Accepted> execute(@RequestBody WireCommand body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Accepted result = service.execute(resolver.resolveCurrentRepositoryContext(), command(body, ifMatch, ifNoneMatch));
        return response("READY".equals(result.projectionState()) ? HttpStatus.OK : HttpStatus.ACCEPTED,
                result.context()).body(result);
    }

    @PostMapping("/api/architecture/editor/rebuild")
    @ResponseBody
    public ResponseEntity<Map<String, String>> rebuild(@RequestBody Context context,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws IOException {
        requireRevision(context, ifMatch, null);
        return response(HttpStatus.OK, context).body(Map.of("projectionState",
                service.rebuild(resolver.resolveCurrentRepositoryContext(), context)));
    }

    /** A bounded wire union is converted immediately to typed application/domain commands. No full graph payload exists. */
    public record WireCommand(Context context, Metadata metadata, String kind, String id, String type,
                              Map<String, String> properties, String sourceId, String relationType, String targetId,
                              String status, String parentId, String targetOperationId) {
        public WireCommand {
            if (context == null || metadata == null || kind == null) throw new IllegalArgumentException("Context, metadata and kind are required");
            if (properties != null && properties.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Property values must be strings");
            }
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    private static Command command(WireCommand body, String ifMatch, String ifNoneMatch) {
        requireRevision(body.context(), ifMatch, ifNoneMatch);
        Operation operation = switch (body.kind()) {
            case "CREATE_ELEMENT" -> new SemanticCommand(new CreateArchitectureElement("arch-" + body.metadata().commandId(), body.type(), body.properties()));
            case "UPDATE_ELEMENT" -> new SemanticCommand(new UpdateArchitectureElement(body.id(), body.type(), body.properties()));
            case "DELETE_ELEMENT" -> new SemanticCommand(new DeleteArchitectureElement(body.id()));
            case "CREATE_RELATION" -> new SemanticCommand(new CreateArchitectureRelation(relation(body), body.status()));
            case "UPDATE_RELATION" -> new SemanticCommand(new UpdateArchitectureRelation(relation(body), body.status()));
            case "DELETE_RELATION" -> new SemanticCommand(new DeleteArchitectureRelation(relation(body)));
            case "MOVE_ELEMENT" -> new SemanticCommand(new MoveOrGroupElement(body.id(), body.parentId()));
            case "UNDO" -> new UndoArchitectureCommand(requireTarget(body.targetOperationId()));
            case "REDO" -> new RedoArchitectureCommand(requireTarget(body.targetOperationId()));
            default -> throw new IllegalArgumentException("Unsupported semantic command");
        };
        return new Command(body.context(), body.metadata(), operation);
    }

    private static RelationKey relation(WireCommand body) {
        if (body.sourceId() == null || body.relationType() == null || body.targetId() == null) throw new IllegalArgumentException("Relation identity is required");
        return new RelationKey(body.sourceId(), body.relationType(), body.targetId());
    }

    private static UUID requireTarget(String target) {
        if (target == null) throw new IllegalArgumentException("Target operation ID is required");
        return UUID.fromString(target);
    }

    private RepositoryContext readContext(String repositoryId, String workspaceScopeKey, String branch) {
        RepositoryContext context = resolver.resolveCurrentRepositoryContext();
        Context current = Context.of(context, null);
        if ((repositoryId != null && !repositoryId.equals(current.repositoryId()))
                || (workspaceScopeKey != null && !workspaceScopeKey.equals(current.workspaceScopeKey()))
                || (branch != null && !branch.equals(current.branch()))) {
            throw new CommandProblem("NOT_FOUND", "context", "This exact context is not selected or accessible", List.of());
        }
        return context;
    }

    private static boolean mayEdit() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> List.of("ROLE_ARCHITECT", "ROLE_ADMIN").contains(authority.getAuthority()));
    }

    private static ResponseEntity.BodyBuilder documentResponse(ArchitectureEditorService.Document document) {
        if ("GIT_CHECKPOINT".equals(document.source())) {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .eTag(GitHttpPrecondition.etag(document.context().commit()))
                    .header("X-Taxonomy-Source", document.source());
        }
        return response(HttpStatus.OK, document.context()).header("X-Taxonomy-Source", document.source());
    }

    @PostMapping("/api/architecture/editor/versions/recover")
    @ResponseBody
    public ResponseEntity<Void> recoverVersion() throws IOException {
        service.reconcileVersion(resolver.resolveCurrentRepositoryContext());
        return ResponseEntity.noContent().build();
    }

    private static String etag(Context context) { return "\"workspace-revision-" + context.revision() + "\""; }

    private static void requireRevision(Context context, String ifMatch, String ifNoneMatch) {
        if (ifMatch == null) throw new GitHttpPrecondition.PreconditionRequiredException("An exact semantic revision If-Match header is required");
        if (ifNoneMatch != null || !etag(context).equals(ifMatch)) {
            throw new IllegalArgumentException("HTTP precondition must match the semantic workspace revision");
        }
    }

    private static ResponseEntity.BodyBuilder response(HttpStatus status, Context context) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, etag(context))
                .header("X-Taxonomy-Semantic-Revision", Long.toString(context.revision()));
    }

    @PostMapping("/api/architecture/editor/checkpoints")
    @ResponseBody
    public ResponseEntity<CheckpointAccepted> checkpoint(@RequestBody CreateCheckpointCommand command,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws IOException {
        requireRevision(command.context(), ifMatch, null);
        var result = service.checkpoint(resolver.resolveCurrentRepositoryContext(), command);
        return response(HttpStatus.OK, result.context()).body(result);
    }

    @PostMapping("/api/architecture/editor/checkpoints/resume")
    @ResponseBody
    public ResponseEntity<CheckpointAccepted> resumeCheckpoint() throws IOException {
        var result = service.resumeCheckpoint(resolver.resolveCurrentRepositoryContext());
        return response(HttpStatus.OK, result.context()).body(result);
    }

    @ExceptionHandler(RevisionConflict.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> conflict(RevisionConflict error) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(Map.of(
                "code", "REVISION_MOVED", "detail", "The workspace changed. Refresh, compare and preview again.",
                "expectedRevision", error.expected(), "currentRevision", error.actual()));
    }

    @ExceptionHandler(GitHttpPrecondition.PreconditionRequiredException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> missingPrecondition(Exception error) {
        return ResponseEntity.status(428).body(Map.of("code", "PRECONDITION_REQUIRED", "detail", error.getMessage()));
    }

    @ExceptionHandler(CommandProblem.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> problem(CommandProblem error) {
        int status = switch (error.code()) {
            case "NOT_FOUND" -> 404;
            case "READ_ONLY" -> 403;
            case "CONTEXT_CHANGED", "UNDO_CONFLICT", "ALREADY_INVERTED", "COMMAND_ID_REUSED", "DEPENDENCIES_EXIST", "CHECKPOINT_PENDING", "CHECKPOINT_CONFLICT", "VERSION_CHANGED" -> 409;
            default -> 422;
        };
        return ResponseEntity.status(status).body(Map.of("code", error.code(), "field", error.field(),
                "detail", error.getMessage(), "dependencies", error.dependencies()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_COMMAND", "detail", error.getMessage() == null ? "Invalid command" : error.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code", "GIT_UNAVAILABLE", "detail", "Git storage is unavailable; refresh before retrying the same command identity"));
    }
}
