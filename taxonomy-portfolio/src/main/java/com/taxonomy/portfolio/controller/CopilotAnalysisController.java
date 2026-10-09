package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Persistent, resumable full-analysis surface for saved project requirements. */
@RestController
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Requirement Copilot")
public class CopilotAnalysisController {

    private final CopilotAutomationService automationService;
    private final WorkspaceResolver workspaceResolver;

    public CopilotAnalysisController(
            CopilotAutomationService automationService,
            WorkspaceResolver workspaceResolver) {
        this.automationService = automationService;
        this.workspaceResolver = workspaceResolver;
    }

    @PostMapping("/requirements/{requirementId}/copilot")
    @Operation(summary = "Start or reuse a full, persisted Copilot analysis",
            description = "Queues a manually requested Copilot analysis of the authorized project requirement using the supplied run options. Returns 202 with a Location for the persisted operation; acceptance is not completed analysis.")
    public ResponseEntity<CopilotOperationView> analyzeRequirement(
            @PathVariable Long projectId,
            @PathVariable Long requirementId,
            @RequestBody(required = false) CopilotRunRequest request) {
        RequestScope scope = scope();
        CopilotOperationView operation = automationService.enqueueManual(
                projectId, requirementId, request, scope.username(), scope.context());
        return ResponseEntity.accepted()
                .location(operationLocation(projectId, operation.operationId()))
                .body(operation);
    }

    @GetMapping("/requirements/{requirementId}/copilot/latest")
    @Operation(summary = "Resume the newest Copilot or Autopilot operation for a requirement",
            description = "Reads the newest retained Copilot operation for the authorized requirement so the client can restore its view. Returns 204 when none exists. Despite the compatibility route name, this GET never executes or resumes model work.")
    public ResponseEntity<CopilotOperationView> latest(
            @PathVariable Long projectId,
            @PathVariable Long requirementId) {
        RequestScope scope = scope();
        return automationService.latestOperation(
                        projectId, requirementId, scope.username(), scope.context())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/copilot-operations/{operationId}")
    @Operation(summary = "Read and resume a persisted Copilot operation",
            description = "Reads the persisted Copilot operation, including its passes and review state, after authorizing the project, requirement and current workspace. This endpoint observes existing work and never starts or resumes a pass.")
    public CopilotOperationView getOperation(
            @PathVariable Long projectId,
            @PathVariable String operationId) {
        RequestScope scope = scope();
        return automationService.getOperation(
                projectId, operationId, scope.username(), scope.context());
    }

    @PostMapping("/copilot-operations/{operationId}/cancel")
    @Operation(summary = "Cancel all active passes of a Copilot operation",
            description = "Requests cooperative cancellation of the existing requirement Copilot operation and its active passes in the authenticated workspace. Returns the recorded operation state; cancellation does not remove previously committed evidence.")
    public CopilotOperationView cancel(
            @PathVariable Long projectId,
            @PathVariable String operationId) {
        RequestScope scope = scope();
        return automationService.cancelOperation(
                projectId, operationId, scope.username(), scope.context());
    }

    private URI operationLocation(Long projectId, String operationId) {
        return URI.create("/api/projects/" + projectId
                + "/copilot-operations/" + operationId);
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
