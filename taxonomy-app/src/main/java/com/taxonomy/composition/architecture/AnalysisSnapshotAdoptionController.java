package com.taxonomy.composition.architecture;

import com.taxonomy.composition.architecture.AnalysisSnapshotAdoptionService.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.persistence.EditorJournal.RevisionConflict;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio", "analysis"})
@RestController
public class AnalysisSnapshotAdoptionController {
    private final AnalysisSnapshotAdoptionService service;
    private final WorkspaceResolver resolver;
    public AnalysisSnapshotAdoptionController(AnalysisSnapshotAdoptionService service,WorkspaceResolver resolver) {
        this.service = service; this.resolver = resolver;
    }
    @PostMapping("/api/architecture/editor/snapshot-adoptions/preview")
    @Operation(summary = "Preview adoption of exact retained analysis evidence", description = "Read-only preview. Requires the full source tuple, editor Context/Metadata and exact workspace revision If-Match. No graph payload or review decision is accepted.")
    public ResponseEntity<PreviewResult> preview(@RequestBody Request request,
            @RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,
            @RequestHeader(value=HttpHeaders.IF_NONE_MATCH,required=false) String ifNoneMatch) throws IOException {
        requireRevision(request.context(),ifMatch,ifNoneMatch);
        var result = service.preview(resolver.resolveCurrentRepositoryContext(),request);
        return response(result.preview() == null ? request.context() : result.preview().context()).body(result);
    }
    @PostMapping("/api/architecture/editor/snapshot-adoptions")
    @Operation(summary = "Adopt a previewed frozen snapshot into the private editor", description = "Rebuilds the source server-side and checks the preview fingerprint before the existing journal accepts one undoable semantic edit. Repeated source adoption opens its canonical view; Git checkpoints remain explicit.")
    public ResponseEntity<Adopted> adopt(@RequestBody Request request,
            @RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,
            @RequestHeader(value=HttpHeaders.IF_NONE_MATCH,required=false) String ifNoneMatch) throws IOException {
        requireRevision(request.context(),ifMatch,ifNoneMatch);
        var result = service.adopt(resolver.resolveCurrentRepositoryContext(),request);
        return response(result.accepted() == null ? request.context() : result.accepted().context()).body(result);
    }
    private static void requireRevision(Context context,String ifMatch,String ifNoneMatch) {
        if (ifMatch == null) throw new MissingRevision();
        if (ifNoneMatch != null || !("\"workspace-revision-" + context.revision() + "\"").equals(ifMatch))
            throw new IllegalArgumentException("HTTP precondition must match the exact workspace revision");
    }
    private static ResponseEntity.BodyBuilder response(Context context) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"workspace-revision-" + context.revision() + "\"")
                .header("X-Taxonomy-Semantic-Revision",Long.toString(context.revision()));
    }
    private static final class MissingRevision extends IllegalArgumentException {}
    @ExceptionHandler(MissingRevision.class)
    public ResponseEntity<Map<String,String>> missing() {
        return ResponseEntity.status(428).body(Map.of("code","PRECONDITION_REQUIRED","detail","An exact semantic revision If-Match is required"));
    }
    @ExceptionHandler(RevisionConflict.class)
    public ResponseEntity<Map<String,Object>> conflict(RevisionConflict error) {
        return ResponseEntity.status(412).body(Map.of("code","REVISION_MOVED","detail","Refresh the target and preview again",
                "expectedRevision",error.expected(),"currentRevision",error.actual()));
    }
    @ExceptionHandler(CommandProblem.class)
    public ResponseEntity<Map<String,Object>> problem(CommandProblem error) {
        int status = switch(error.code()) {
            case "NOT_FOUND" -> 404;
            case "READ_ONLY" -> 403;
            case "CONTEXT_CHANGED","COMMAND_ID_REUSED","CHECKPOINT_PENDING","VERSION_CHANGED","SNAPSHOT_IDENTITY_CONFLICT","SNAPSHOT_TYPE_CONFLICT","PREVIEW_CHANGED" -> 409;
            default -> 422;
        };
        return ResponseEntity.status(status).body(Map.of("code",error.code(),"field",error.field(),"detail",error.getMessage(),"dependencies",error.dependencies()));
    }
    @ExceptionHandler(com.taxonomy.portfolio.service.PortfolioException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> sourceProblem(com.taxonomy.portfolio.service.PortfolioException error) {
        return new com.taxonomy.portfolio.controller.PortfolioExceptionHandler().handlePortfolioException(error);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_COMMAND","detail",String.valueOf(error.getMessage())));
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code","GIT_UNAVAILABLE","detail","Storage is unavailable; retry the same command identity"));
    }
}
