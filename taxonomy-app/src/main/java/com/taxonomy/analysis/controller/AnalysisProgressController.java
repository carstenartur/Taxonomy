package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only live telemetry plus explicit cooperative cancellation; never starts analysis. */
@RestController
@RequestMapping("/api/analysis-runs")
public class AnalysisProgressController {
    private final AnalysisProgressRegistry registry;
    private final WorkspaceResolver workspaceResolver;

    public AnalysisProgressController(AnalysisProgressRegistry registry, WorkspaceResolver workspaceResolver) {
        this.registry = registry;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping
    public ResponseEntity<List<AnalysisProgressRegistry.Snapshot>> recent(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long requirementId) {
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        return privateResponse(registry.recent(owner, context, projectId, requirementId));
    }

    @GetMapping("/{operationId}")
    public ResponseEntity<AnalysisProgressRegistry.Snapshot> status(@PathVariable String operationId) {
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        return privateResponse(registry.snapshot(operationId, owner, context));
    }

    @GetMapping("/{operationId}/calls/{callId}")
    public ResponseEntity<AnalysisProgressRegistry.CallDetail> detail(
            @PathVariable String operationId, @PathVariable long callId) {
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        return privateResponse(registry.callDetail(operationId, callId, owner, context));
    }

    @PostMapping("/{operationId}/cancel")
    public ResponseEntity<AnalysisProgressRegistry.Snapshot> cancel(@PathVariable String operationId) {
        String owner = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        return privateResponse(registry.cancel(operationId, owner, context));
    }

    private static <T> ResponseEntity<T> privateResponse(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
