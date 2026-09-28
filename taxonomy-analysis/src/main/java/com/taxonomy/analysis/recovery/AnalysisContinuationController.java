package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Read and cancel only. A decision that executes questions uses the existing POST /api/analyze path. */
@RestController
@RequestMapping("/api/analysis-continuations")
public class AnalysisContinuationController {
    private final AnalysisContinuationService service;
    private final WorkspaceResolver resolver;
    public AnalysisContinuationController(AnalysisContinuationService service, WorkspaceResolver resolver) {
        this.service = service; this.resolver = resolver;
    }
    @GetMapping("/{id}")
    public ResponseEntity<AnalysisContinuationStore.Snapshot> read(@PathVariable String id) {
        return privateResponse(service.read(id, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @PostMapping("/{id}/cancel")
    public ResponseEntity<AnalysisContinuationStore.Snapshot> cancel(@PathVariable String id) {
        return privateResponse(service.cancel(id, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    @GetMapping("/{id}/questions/{key}")
    public ResponseEntity<LlmCallDetail> detail(@PathVariable String id, @PathVariable String key) {
        return privateResponse(service.detail(id, key, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()));
    }
    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
