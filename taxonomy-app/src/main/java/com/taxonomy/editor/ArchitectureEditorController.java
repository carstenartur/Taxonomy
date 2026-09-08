package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.portfolio.workbench.ArchitecturePdfRenderer;
import com.taxonomy.relations.controller.GitHttpPrecondition;
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
            @RequestParam(required = false) String commit) throws IOException {
        RepositoryContext context = readContext(repositoryId, workspaceScopeKey, branch);
        var document = service.read(context, commit);
        return response(HttpStatus.OK, document.context().commit()).body(projection.project(document, mayEdit()));
    }

    @GetMapping(value = "/api/architecture/editor.svg", produces = "image/svg+xml")
    @ResponseBody
    public ResponseEntity<String> svg(@RequestParam String repositoryId, @RequestParam String workspaceScopeKey,
                                      @RequestParam String branch, @RequestParam String commit) throws IOException {
        var document = service.read(readContext(repositoryId, workspaceScopeKey, branch), commit);
        var view = projection.project(document, false);
        return response(HttpStatus.OK, document.context().commit())
                .header("X-Taxonomy-Layout-Source", view.schema().layoutMode())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=architecture.svg")
                .body(svg.render(view.scene()));
    }

    @GetMapping(value = "/api/architecture/editor.pdf", produces = "application/pdf")
    @ResponseBody
    public ResponseEntity<byte[]> pdf(@RequestParam String repositoryId, @RequestParam String workspaceScopeKey,
                                      @RequestParam String branch, @RequestParam String commit) throws IOException {
        var document = service.read(readContext(repositoryId, workspaceScopeKey, branch), commit);
        var view = projection.project(document, false);
        var provenance = document.context();
        return response(HttpStatus.OK, document.context().commit())
                .header("X-Taxonomy-Layout-Source", view.schema().layoutMode())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=architecture.pdf")
                .body(pdf.render(view.scene(), provenance.repositoryId() + " / " + provenance.workspaceScopeKey()
                        + " / " + provenance.branch() + "\n" + provenance.commit()));
    }

    @PostMapping("/api/architecture/editor/preview")
    @ResponseBody
    public ResponseEntity<Preview> preview(@RequestBody WireCommand body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Command command = command(body, ifMatch, ifNoneMatch);
        Preview result = service.preview(resolver.resolveCurrentRepositoryContext(), command);
        return response(HttpStatus.OK, result.context().commit()).body(result);
    }

    @PostMapping("/api/architecture/editor/commands")
    @ResponseBody
    public ResponseEntity<Accepted> execute(@RequestBody WireCommand body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Accepted result = service.execute(resolver.resolveCurrentRepositoryContext(), command(body, ifMatch, ifNoneMatch));
        return response("READY".equals(result.projectionState()) ? HttpStatus.OK : HttpStatus.ACCEPTED,
                result.context().commit()).body(result);
    }

    @PostMapping("/api/architecture/editor/rebuild")
    @ResponseBody
    public ResponseEntity<Map<String, String>> rebuild(@RequestBody Context context,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws IOException {
        if (!Objects.equals(context.commit(), GitHttpPrecondition.expectedHead(ifMatch, null))) {
            throw new IllegalArgumentException("HTTP precondition must match the editor context");
        }
        return response(HttpStatus.OK, context.commit()).body(Map.of("projectionState",
                service.rebuild(resolver.resolveCurrentRepositoryContext(), context)));
    }

    /** A bounded wire union is converted immediately to typed application/domain commands. No full graph payload exists. */
    public record WireCommand(Context context, Metadata metadata, String kind, String id, String type,
                              Map<String, String> properties, String sourceId, String relationType, String targetId,
                              String status, String parentId, String targetCommit) {
        public WireCommand {
            if (context == null || metadata == null || kind == null) throw new IllegalArgumentException("Context, metadata and kind are required");
            if (properties != null && properties.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Property values must be strings");
            }
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    private static Command command(WireCommand body, String ifMatch, String ifNoneMatch) {
        if (!Objects.equals(body.context().commit(), GitHttpPrecondition.expectedHead(ifMatch, ifNoneMatch))) {
            throw new IllegalArgumentException("HTTP precondition must match the editor context");
        }
        Operation operation = switch (body.kind()) {
            case "CREATE_ELEMENT" -> new SemanticCommand(new CreateArchitectureElement("arch-" + body.metadata().commandId(), body.type(), body.properties()));
            case "UPDATE_ELEMENT" -> new SemanticCommand(new UpdateArchitectureElement(body.id(), body.type(), body.properties()));
            case "DELETE_ELEMENT" -> new SemanticCommand(new DeleteArchitectureElement(body.id()));
            case "CREATE_RELATION" -> new SemanticCommand(new CreateArchitectureRelation(relation(body), body.status()));
            case "UPDATE_RELATION" -> new SemanticCommand(new UpdateArchitectureRelation(relation(body), body.status()));
            case "DELETE_RELATION" -> new SemanticCommand(new DeleteArchitectureRelation(relation(body)));
            case "MOVE_ELEMENT" -> new SemanticCommand(new MoveOrGroupElement(body.id(), body.parentId()));
            case "UNDO" -> new UndoArchitectureCommand(requireTarget(body.targetCommit()));
            case "REDO" -> new RedoArchitectureCommand(requireTarget(body.targetCommit()));
            default -> throw new IllegalArgumentException("Unsupported semantic command");
        };
        return new Command(body.context(), body.metadata(), operation);
    }

    private static RelationKey relation(WireCommand body) {
        if (body.sourceId() == null || body.relationType() == null || body.targetId() == null) throw new IllegalArgumentException("Relation identity is required");
        return new RelationKey(body.sourceId(), body.relationType(), body.targetId());
    }

    private static String requireTarget(String target) {
        if (target == null) throw new IllegalArgumentException("Target commit is required");
        return org.eclipse.jgit.lib.ObjectId.fromString(target).name();
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

    private static ResponseEntity.BodyBuilder response(HttpStatus status, String commit) {
        var response = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        return commit == null ? response : response.header(HttpHeaders.ETAG, GitHttpPrecondition.etag(commit));
    }

    @ExceptionHandler(BranchHeadConflictException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> conflict(BranchHeadConflictException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "HEAD_MOVED");
        body.put("detail", "The branch moved. Refresh, compare and preview the command again.");
        body.put("expectedCommit", error.getExpectedHeadCommit());
        body.put("currentCommit", error.getActualHeadCommit());
        return response(HttpStatus.PRECONDITION_FAILED, error.getActualHeadCommit()).body(body);
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
            case "CONTEXT_CHANGED", "UNDO_CONFLICT", "ALREADY_INVERTED", "COMMAND_ID_REUSED", "DEPENDENCIES_EXIST" -> 409;
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
