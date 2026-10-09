package com.taxonomy.editor;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.persistence.EditorJournal.RevisionConflict;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Controller
@Tag(name = "Architecture editor")
public class ArchitectureEditorController {
    private final ArchitectureEditorService service;
    private final ArchitectureEditorProjection projection;
    private final WorkspaceResolver resolver;

    public ArchitectureEditorController(ArchitectureEditorService service, ArchitectureEditorProjection projection,
                                        WorkspaceResolver resolver) {
        this.service = service;
        this.projection = projection;
        this.resolver = resolver;
    }

    @GetMapping("/architecture/editor")
    public String page() { return "architecture-editor"; }

    @GetMapping("/api/architecture/editor")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read the scoped architecture editor state",
            description = "Reads the authorized repository, workspace and branch at the selected retained semantic revision or Git checkpoint. Supplied scope pins must match the selected context. Returns source and ETag headers; this read does not execute an edit.")
    @ApiResponse(responseCode = "200", description = "Read the scoped architecture editor state response")
    public ResponseEntity<ArchitectureEditorProjection.View> read(
            @Parameter(description = "Optional repository pin; must match the authenticated selected context")
            @RequestParam(required = false) String repositoryId,
            @Parameter(description = "Optional workspace scope pin; must match the authenticated selected context")
            @RequestParam(required = false) String workspaceScopeKey,
            @Parameter(description = "Optional branch pin; must match the authenticated selected context")
            @RequestParam(required = false) String branch,
            @Parameter(description = "Exact retained Git checkpoint SHA; omit to read semantic workspace state")
            @RequestParam(required = false) String commit,
            @Parameter(description = "Retained semantic revision to read; distinct from a Git checkpoint SHA")
            @RequestParam(required = false) Long revision) throws IOException {
        RepositoryContext context = readContext(repositoryId, workspaceScopeKey, branch);
        var document = service.read(context, commit, revision);
        return documentResponse(document).body(projection.project(document, mayEdit()));
    }

    @PostMapping("/api/architecture/editor/preview")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Preview a semantic editor command",
            description = "Validates and previews the bounded typed command in the selected repository without applying the edit. Requires the exact semantic revision If-Match; If-None-Match is not a substitute.")
    @ApiResponse(responseCode = "200", description = "Preview a semantic editor command response")
    @ApiResponse(responseCode = "400", description = "Invalid command or mismatched HTTP revision format")
    @ApiResponse(responseCode = "412", description = "Semantic revision moved")
    @ApiResponse(responseCode = "428", description = "Required semantic revision If-Match is missing")
    @ApiResponse(responseCode = "409", description = "Command, checkpoint or dependencies conflict")
    @ApiResponse(responseCode = "422", description = "Semantic command validation failed")
    @ApiResponse(responseCode = "503", description = "Git storage unavailable")
    public ResponseEntity<Preview> preview(@RequestBody WireCommand body,
            @Parameter(description = "Required ETag of the exact semantic revision, in the form \"workspace-revision-N\"", required = true, schema = @Schema(type = "string", pattern = "\"workspace-revision-[0-9]+\""))
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Parameter(description = "Not supported for semantic editor commands; supplying this header is rejected")
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Command command = command(body, ifMatch, ifNoneMatch);
        Preview result = service.preview(resolver.resolveCurrentRepositoryContext(), command);
        return response(HttpStatus.OK, result.context()).body(result);
    }

    @PostMapping("/api/architecture/editor/commands")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Execute an idempotent semantic editor command",
            description = "Executes the bounded command using its command metadata and exact workspace revision. Returns 200 when the projection is ready or 202 when the command is accepted but projection work remains. Undo/redo use an explicit target operation, not a Git reset.")
    @ApiResponse(responseCode = "200", description = "Execute an idempotent semantic editor command response")
    @ApiResponse(responseCode = "400", description = "Invalid command or mismatched HTTP revision format")
    @ApiResponse(responseCode = "412", description = "Semantic revision moved")
    @ApiResponse(responseCode = "428", description = "Required semantic revision If-Match is missing")
    @ApiResponse(responseCode = "409", description = "Command, checkpoint or dependencies conflict")
    @ApiResponse(responseCode = "422", description = "Semantic command validation failed")
    @ApiResponse(responseCode = "503", description = "Git storage unavailable")
    @ApiResponse(responseCode = "202", description = "Durable command accepted; projection is not yet ready")
    public ResponseEntity<Accepted> execute(@RequestBody WireCommand body,
            @Parameter(description = "Required ETag of the exact semantic revision, in the form \"workspace-revision-N\"", required = true, schema = @Schema(type = "string", pattern = "\"workspace-revision-[0-9]+\""))
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Parameter(description = "Not supported for semantic editor commands; supplying this header is rejected")
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Accepted result = service.execute(resolver.resolveCurrentRepositoryContext(), command(body, ifMatch, ifNoneMatch));
        return response("READY".equals(result.projectionState()) ? HttpStatus.OK : HttpStatus.ACCEPTED,
                result.context()).body(result);
    }

    @PostMapping("/api/architecture/editor/rebuild")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Rebuild the editor projection",
            description = "Rebuilds the projection of the exact authorized semantic workspace revision. If-Match must equal the supplied context's workspace-revision ETag; this does not create a semantic edit.")
    @ApiResponse(responseCode = "200", description = "Rebuild the editor projection response")
    @ApiResponse(responseCode = "400", description = "Invalid command or mismatched HTTP revision format")
    @ApiResponse(responseCode = "412", description = "Semantic revision moved")
    @ApiResponse(responseCode = "428", description = "Required semantic revision If-Match is missing")
    @ApiResponse(responseCode = "409", description = "Command, checkpoint or dependencies conflict")
    @ApiResponse(responseCode = "422", description = "Semantic command validation failed")
    @ApiResponse(responseCode = "503", description = "Git storage unavailable")
    public ResponseEntity<Map<String, String>> rebuild(@RequestBody Context context,
            @Parameter(description = "Required ETag of the exact semantic revision, in the form \"workspace-revision-N\"", required = true, schema = @Schema(type = "string", pattern = "\"workspace-revision-[0-9]+\""))
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws IOException {
        requireRevision(context, ifMatch, null);
        return response(HttpStatus.OK, context).body(Map.of("projectionState",
                service.rebuild(resolver.resolveCurrentRepositoryContext(), context)));
    }

    /** A bounded wire union is converted immediately to typed application/domain commands. No full graph payload exists. */
    public record WireCommand(Context context, Metadata metadata, String kind, String id, String type,
                              Map<String, String> properties, String sourceId, String relationType, String targetId,
                              String status, String parentId, String targetOperationId,
                              List<PackagePlacement> placements, java.util.Set<String> completeParentScopes,
                              PlanningEdit planning) {
        public WireCommand(Context context, Metadata metadata, String kind, String id, String type,
                           Map<String, String> properties, String sourceId, String relationType, String targetId,
                           String status, String parentId, String targetOperationId,
                           List<PackagePlacement> placements, java.util.Set<String> completeParentScopes) {
            this(context, metadata, kind, id, type, properties, sourceId, relationType, targetId,
                    status, parentId, targetOperationId, placements, completeParentScopes, null);
        }
        public WireCommand(Context context, Metadata metadata, String kind, String id, String type,
                           Map<String, String> properties, String sourceId, String relationType, String targetId,
                           String status, String parentId, String targetOperationId) {
            this(context, metadata, kind, id, type, properties, sourceId, relationType, targetId,
                    status, parentId, targetOperationId, List.of(), java.util.Set.of());
        }
        public WireCommand {
            if (context == null || metadata == null || kind == null) throw new IllegalArgumentException("Context, metadata and kind are required");
            if (properties != null && properties.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Property values must be strings");
            }
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            placements = placements == null ? List.of() : List.copyOf(placements);
            completeParentScopes = completeParentScopes == null ? java.util.Set.of() : java.util.Set.copyOf(completeParentScopes);
        }
    }

    public record PlanningEdit(String entryId, String profile, String version, Map<String, String> values) {}

    private static PlanningEdit planning(WireCommand body) {
        if (body.planning() == null) throw new IllegalArgumentException("Planning edit is required");
        return body.planning();
    }

    private static Command command(WireCommand body, String ifMatch, String ifNoneMatch) {
        requireRevision(body.context(), ifMatch, ifNoneMatch);
        Operation operation = switch (body.kind()) {
            case "SET_PLANNING" -> {
                PlanningEdit p = planning(body);
                yield new SemanticCommand(new SetRequirementPlanning(body.id(),
                        new com.taxonomy.dsl.planning.PlanningEntry(p.entryId(), p.profile(), p.version(), "MANUAL", p.values())));
            }
            case "DELETE_PLANNING" -> new SemanticCommand(new DeleteRequirementPlanning(body.id(), planning(body).entryId()));
            case "CREATE_PACKAGE" -> new SemanticCommand(new CreateArchitecturePackage("pkg-" + body.metadata().commandId(), body.properties()));
            case "UPDATE_PACKAGE" -> new SemanticCommand(new UpdateArchitecturePackage(body.id(), body.properties()));
            case "DELETE_PACKAGE" -> new SemanticCommand(new DeleteArchitecturePackage(body.id()));
            case "SET_PACKAGE_PLACEMENTS" -> new SemanticCommand(new SetArchitecturePackagePlacements(body.placements(), body.completeParentScopes()));
            case "UPSERT_REQUIREMENT_MAPPING" -> new SemanticCommand(new UpsertRequirementMapping(body.sourceId(), body.targetId(), body.metadata().rationale(), body.properties()));
            case "DELETE_REQUIREMENT_MAPPING" -> new SemanticCommand(new DeleteRequirementMapping(body.sourceId(), body.targetId()));
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
                    .eTag(commitEtag(document.context().commit()))
                    .header("X-Taxonomy-Source", document.source());
        }
        return response(HttpStatus.OK, document.context()).header("X-Taxonomy-Source", document.source());
    }

    @PostMapping("/api/architecture/editor/versions/recover")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Reconcile editor version state",
            description = "Explicitly reconciles the editor's version state with its retained journal/checkpoint in the authorized repository. Returns 204 without executing an arbitrary new semantic command.")
    @ApiResponse(responseCode = "204", description = "Reconcile editor version state response")
    public ResponseEntity<Void> recoverVersion() throws IOException {
        service.reconcileVersion(resolver.resolveCurrentRepositoryContext());
        return ResponseEntity.noContent().build();
    }

    private static String etag(Context context) { return "\"workspace-revision-" + context.revision() + "\""; }

    private static String commitEtag(String commitId) {
        return '"' + ObjectId.fromString(commitId).name() + '"';
    }

    private static void requireRevision(Context context, String ifMatch, String ifNoneMatch) {
        if (ifMatch == null) {
            throw new PreconditionRequiredException("An exact semantic revision If-Match header is required");
        }
        if (ifNoneMatch != null || !etag(context).equals(ifMatch)) {
            throw new IllegalArgumentException("HTTP precondition must match the semantic workspace revision");
        }
    }

    static final class PreconditionRequiredException extends IllegalArgumentException {
        PreconditionRequiredException(String message) {
            super(message);
        }
    }

    private static ResponseEntity.BodyBuilder response(HttpStatus status, Context context) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, etag(context))
                .header("X-Taxonomy-Semantic-Revision", Long.toString(context.revision()));
    }

    @PostMapping("/api/architecture/editor/checkpoints")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Create an editor Git checkpoint",
            description = "Creates a Git checkpoint for the explicit semantic revision after checking its If-Match ETag. Semantic operation history remains separate from Git checkpoints; returns the checkpoint receipt.")
    @ApiResponse(responseCode = "200", description = "Create an editor Git checkpoint response")
    @ApiResponse(responseCode = "400", description = "Invalid command or mismatched HTTP revision format")
    @ApiResponse(responseCode = "412", description = "Semantic revision moved")
    @ApiResponse(responseCode = "428", description = "Required semantic revision If-Match is missing")
    @ApiResponse(responseCode = "409", description = "Command, checkpoint or dependencies conflict")
    @ApiResponse(responseCode = "422", description = "Semantic command validation failed")
    @ApiResponse(responseCode = "503", description = "Git storage unavailable")
    public ResponseEntity<CheckpointAccepted> checkpoint(@RequestBody CreateCheckpointCommand command,
            @Parameter(description = "Required ETag of the exact semantic revision, in the form \"workspace-revision-N\"", required = true, schema = @Schema(type = "string", pattern = "\"workspace-revision-[0-9]+\""))
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws IOException {
        requireRevision(command.context(), ifMatch, null);
        var result = service.checkpoint(resolver.resolveCurrentRepositoryContext(), command);
        return response(HttpStatus.OK, result.context()).body(result);
    }

    @PostMapping("/api/architecture/editor/checkpoints/resume")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Resume an interrupted editor checkpoint",
            description = "Resumes the retained checkpoint operation in the authorized repository rather than creating a new semantic command. Returns the checkpoint receipt and the current semantic revision headers.")
    @ApiResponse(responseCode = "200", description = "Resume an interrupted editor checkpoint response")
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

    @ExceptionHandler(PreconditionRequiredException.class)
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
