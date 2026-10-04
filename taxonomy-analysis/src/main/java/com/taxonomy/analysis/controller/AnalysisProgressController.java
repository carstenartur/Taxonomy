package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.cluster.ClusterAnalysisObservation;
import com.taxonomy.analysis.cluster.ClusterAnalysisView;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Read-only live telemetry plus explicit cooperative cancellation; never starts analysis. */
@RestController
@RequestMapping("/api/analysis-runs")
@Tag(name = "Analysis progress", description = "Scoped observation and cancellation of existing analyses")
public class AnalysisProgressController {
    private final AnalysisProgressRegistry registry;
    private final WorkspaceResolver workspaceResolver;
    private final ClusterAnalysisObservation cluster;

    public AnalysisProgressController(AnalysisProgressRegistry registry, WorkspaceResolver workspaceResolver) {
        this(registry, workspaceResolver, (ClusterAnalysisObservation) null);
    }

    @Autowired
    public AnalysisProgressController(AnalysisProgressRegistry registry, WorkspaceResolver workspaceResolver,
                                      ObjectProvider<ClusterAnalysisObservation> cluster) {
        this(registry, workspaceResolver, cluster.getIfAvailable());
    }

    public AnalysisProgressController(AnalysisProgressRegistry registry, WorkspaceResolver workspaceResolver,
                                      ClusterAnalysisObservation cluster) {
        this.registry = registry;
        this.workspaceResolver = workspaceResolver;
        this.cluster = cluster;
    }

    @Operation(summary = "List recent visible analysis runs",
            description = "Returns bounded durable cluster and local observations in the authenticated workspace, repository and branch. Never starts work.")
    @ApiResponse(responseCode = "200", description = "Visible recent runs", content = @Content(
            array = @ArraySchema(schema = @Schema(oneOf = {AnalysisProgressRegistry.Snapshot.class, ClusterAnalysisView.class}))))
    @GetMapping
    public ResponseEntity<List<?>> recent(
            @Parameter(description = "Restrict results to this project") @RequestParam(required = false) Long projectId,
            @Parameter(description = "Restrict results to this requirement") @RequestParam(required = false) Long requirementId) {
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        if (cluster == null) return privateResponse(registry.recent(owner, context, projectId, requirementId));
        var visible = new LinkedHashMap<String, Object>();
        cluster.recent(owner, context, projectId, requirementId)
                .forEach(view -> visible.put(view.snapshot().operationId(), view));
        registry.recent(owner, context, projectId, requirementId)
                .forEach(view -> visible.putIfAbsent(view.operationId(), view));
        return privateResponse(new ArrayList<>(visible.values()));
    }

    @Operation(summary = "Read analysis progress", description = "Prefers authoritative durable cluster state when present. Local observations retain the compatibility response shape.")
    @ApiResponse(responseCode = "200", description = "Current progress", content = @Content(
            schema = @Schema(oneOf = {AnalysisProgressRegistry.Snapshot.class, ClusterAnalysisView.class})))
    @ApiResponse(responseCode = "202", description = "Registration has not yet been observed; no analysis was started")
    @ApiResponse(responseCode = "404", description = "Analysis absent or outside the authenticated scope")
    @GetMapping("/{operationId}")
    public ResponseEntity<?> status(
            @Parameter(description = "Existing analysis operation identifier") @PathVariable String operationId,
            @Parameter(description = "Allow a pending response while the initial analysis request is registering")
            @RequestParam(defaultValue = "false") boolean waitForRegistration) {
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        // A known foreign durable record must remain 404, never pending or a local fallback.
        if (cluster != null) {
            var durable = cluster.snapshot(operationId, owner, context);
            if (durable.isPresent()) return privateResponse(durable.get());
        }
        try {
            return privateResponse(registry.snapshot(operationId, owner, context));
        } catch (ResponseStatusException unavailable) {
            if (!waitForRegistration || unavailable.getStatusCode().value() != 404) throw unavailable;
            // An early poll can precede POST registration. Reveal no foreign run and allocate no work.
            // This acknowledges a pending observation, not acceptance of the analysis request itself.
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                    .header("Retry-After", "1").build();
        }
    }

    @Operation(summary = "Read bounded local call diagnostics",
            description = "Loads retained prompt/response diagnostics only after an explicit request in the original authenticated scope.")
    @ApiResponse(responseCode = "200", description = "Retained call diagnostics", content = @Content(
            schema = @Schema(implementation = AnalysisProgressRegistry.CallDetail.class)))
    @ApiResponse(responseCode = "404", description = "Run or diagnostics absent, or outside the authenticated scope")
    @GetMapping("/{operationId}/calls/{callId}")
    public ResponseEntity<AnalysisProgressRegistry.CallDetail> detail(
            @Parameter(description = "Existing analysis operation identifier") @PathVariable String operationId,
            @Parameter(description = "Call identifier from the progress snapshot") @PathVariable long callId) {
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        if (cluster != null && cluster.snapshot(operationId, owner, context).isPresent()) throw notFound();
        return privateResponse(registry.callDetail(operationId, callId, owner, context));
    }

    @Operation(summary = "Cancel an existing analysis", description = "Cluster cancellation is persisted before it is published to workers. Local runs stop cooperatively.")
    @ApiResponse(responseCode = "200", description = "Authoritative cancellation state", content = @Content(
            schema = @Schema(oneOf = {AnalysisProgressRegistry.Snapshot.class, ClusterAnalysisView.class})))
    @ApiResponse(responseCode = "404", description = "Analysis absent or outside the authenticated scope")
    @PostMapping("/{operationId}/cancel")
    public ResponseEntity<?> cancel(
            @Parameter(description = "Existing analysis operation identifier") @PathVariable String operationId) {
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        if (cluster != null) {
            var durable = cluster.cancel(operationId, owner, context);
            if (durable.isPresent()) return privateResponse(durable.get());
        }
        return privateResponse(registry.cancel(operationId, owner, context));
    }

    @Operation(summary = "Observe durable analysis events",
            description = "Replays durable snapshots and then follows event notifications. SSE ids are monotonic operation sequences; reconnect resumes after the greater of afterSequence and Last-Event-ID. Does not start analysis or poll for work.")
    @ApiResponse(responseCode = "200", description = "Scoped snapshot/progress event stream", content = @Content(
            mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(implementation = ClusterAnalysisView.class)))
    @ApiResponse(responseCode = "400", description = "Invalid replay cursor")
    @ApiResponse(responseCode = "404", description = "Cluster analysis absent or outside the authenticated scope")
    @GetMapping(value = "/{operationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @Parameter(description = "Existing cluster analysis operation identifier") @PathVariable String operationId,
            @Parameter(description = "Last accepted sequence from an initial durable snapshot", schema = @Schema(minimum = "0"))
            @RequestParam(defaultValue = "0") long afterSequence,
            @Parameter(description = "Last SSE event identifier automatically supplied during reconnect")
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = replayCursor(afterSequence, lastEventId);
        if (cluster == null) throw notFound();
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(cluster.events(operationId, owner, context, cursor));
    }

    @Operation(summary = "Recover a persisted cluster analysis result",
            description = "Retrieves the existing immutable result after reload or reconnect without submitting another analysis.")
    @ApiResponse(responseCode = "200", description = "Persisted analysis result", content = @Content(
            schema = @Schema(implementation = AnalysisResult.class)))
    @ApiResponse(responseCode = "409", description = "Result is not ready")
    @ApiResponse(responseCode = "404", description = "Cluster analysis absent or outside the authenticated scope")
    @GetMapping("/{operationId}/result")
    public ResponseEntity<AnalysisResult> result(
            @Parameter(description = "Existing cluster analysis operation identifier") @PathVariable String operationId) {
        if (cluster == null) throw notFound();
        return privateResponse(cluster.result(operationId, workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext()));
    }

    @Operation(summary = "Recover the original cluster analysis input",
            description = "Returns the original requirement and selected analysis scope for an explicit recovery action. Excludes credentials and internal command metadata.")
    @ApiResponse(responseCode = "200", description = "Original scoped user input", content = @Content(
            schema = @Schema(implementation = ClusterAnalysisObservation.RecoveryInput.class)))
    @ApiResponse(responseCode = "404", description = "Cluster analysis absent or outside the authenticated scope")
    @GetMapping("/{operationId}/request")
    public ResponseEntity<ClusterAnalysisObservation.RecoveryInput> request(
            @Parameter(description = "Existing cluster analysis operation identifier") @PathVariable String operationId) {
        if (cluster == null) throw notFound();
        return privateResponse(cluster.request(operationId, workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext()));
    }

    private static long replayCursor(long afterSequence, String lastEventId) {
        if (afterSequence < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid replay cursor");
        if (lastEventId == null) return afterSequence;
        if (!lastEventId.matches("[0-9]{1,19}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid replay cursor");
        }
        try { return Math.max(afterSequence, Long.parseLong(lastEventId)); }
        catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid replay cursor");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found");
    }

    private static <T> ResponseEntity<T> privateResponse(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
