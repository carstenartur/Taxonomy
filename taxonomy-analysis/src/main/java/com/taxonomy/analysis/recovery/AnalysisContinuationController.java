package com.taxonomy.analysis.recovery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Read and cancel only. A decision that executes questions uses the existing POST /api/analyze path. */
@RestController
@Tag(name = "Analysis continuations", description = "Owner- and workspace-scoped resumable analysis checkpoints")
@RequestMapping("/api/analysis-continuations")
@ApiResponse(responseCode = "403", description = "An authenticated owned workspace is required", content = @Content)
public class AnalysisContinuationController {
    private final AnalysisContinuationService service;
    private final WorkspaceResolver resolver;
    public AnalysisContinuationController(AnalysisContinuationService service, WorkspaceResolver resolver) {
        this.service = service; this.resolver = resolver;
    }
    @Operation(summary = "Read a saved analysis continuation",
            description = "Returns the existing continuation for the authenticated owner and exact workspace context. Reading never executes questions or starts a provider call. Resume and retry decisions use POST /api/analyze; private results are returned with Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Authorized retained state",
            content = @Content(schema = @Schema(implementation = AnalysisContinuationStore.Snapshot.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "404", description = "Continuation or question absent or outside the authorized scope", content = @Content)
    @GetMapping("/{id}")
    public ResponseEntity<AnalysisContinuationStore.Snapshot> read(@Parameter(description = "Existing saved continuation identifier") @PathVariable String id) {
        return privateResponse(service.read(id, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @Operation(summary = "Cancel a saved analysis continuation",
            description = "Persists cancellation of the existing continuation in the authenticated owner and workspace scope. Does not delete retained question evidence and does not start another analysis. Results are private and not cacheable.")
    @ApiResponse(responseCode = "200", description = "Authorized retained state",
            content = @Content(schema = @Schema(implementation = AnalysisContinuationStore.Snapshot.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "404", description = "Continuation or question absent or outside the authorized scope", content = @Content)
    @PostMapping("/{id}/cancel")
    public ResponseEntity<AnalysisContinuationStore.Snapshot> cancel(@Parameter(description = "Existing saved continuation identifier") @PathVariable String id) {
        return privateResponse(service.cancel(id, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @Operation(summary = "Read retained continuation question evidence",
            description = "Returns the retained question and response evidence for the exact question key in an authorized continuation. Does not repeat the question or call a provider. A missing or foreign continuation or question is not disclosed.")
    @ApiResponse(responseCode = "200", description = "Authorized retained state",
            content = @Content(schema = @Schema(implementation = LlmCallDetail.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "404", description = "Continuation or question absent or outside the authorized scope", content = @Content)
    @GetMapping("/{id}/questions/{key}")
    public ResponseEntity<LlmCallDetail> detail(@Parameter(description = "Existing saved continuation identifier") @PathVariable String id, @Parameter(description = "Exact question key returned by the continuation, not a list index") @PathVariable String key) {
        return privateResponse(service.detail(id, key, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
