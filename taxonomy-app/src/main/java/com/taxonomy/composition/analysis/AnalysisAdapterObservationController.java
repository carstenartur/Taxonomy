package com.taxonomy.composition.analysis;

import com.taxonomy.shared.features.ConditionalOnFeature;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnFeature({"analysis"})
@RequestMapping("/api/analysis-adapters")
public class AnalysisAdapterObservationController {
    private final ScopedAnalysisAdapterExecution operations;
    public AnalysisAdapterObservationController(ScopedAnalysisAdapterExecution operations) { this.operations = operations; }

    @Operation(summary = "Read the original scoped analysis result",
            description = "Returns the native result for the initiating account and repository source. A running receipt returns 202 with its sequence, and an expired receipt returns 410.")
    @GetMapping("/{id}")
    public ResponseEntity<?> status(@PathVariable("id") String id) { return operations.status(id, false); }

    @Operation(summary = "Find an admitted native analysis while its original request is running",
            description = "Resolves the initiating browser's scoped observation to the original operation and source without submitting work. An unknown observation or another account's observation returns 404.")
    @GetMapping("/observations/{token}")
    public ResponseEntity<?> observe(@PathVariable("token") String token) { return operations.observe(token); }

    @Operation(summary = "Cancel a scoped native analysis",
            description = "Closes a running receipt for its initiating account and repository. The operation retains its original identifier and prevents a late provider result from replacing its terminal state.")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable("id") String id) { return operations.status(id, true); }
}
