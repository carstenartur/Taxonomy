package com.taxonomy.composition.analysis;

import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnFeature({"analysis"})
@RequestMapping("/api/analysis-adapters")
public class AnalysisAdapterObservationController {
    private final ScopedAnalysisAdapterExecution operations;
    public AnalysisAdapterObservationController(ScopedAnalysisAdapterExecution operations) { this.operations = operations; }

    @GetMapping("/{id}")
    public ResponseEntity<?> status(@PathVariable("id") String id) { return operations.status(id, false); }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable("id") String id) { return operations.status(id, true); }
}
