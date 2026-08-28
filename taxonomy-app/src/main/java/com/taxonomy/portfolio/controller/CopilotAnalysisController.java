package com.taxonomy.portfolio.controller;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.PromptBudgetExceededException;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.service.AiAutomationPolicy;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
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
    private final ProjectPortfolioService projectService;
    private final AiAutomationPolicy automationPolicy;
    private final AiPromptBudgetPolicy promptBudgetPolicy;
    private final WorkspaceResolver workspaceResolver;

    public CopilotAnalysisController(
            CopilotAutomationService automationService,
            ProjectPortfolioService projectService,
            AiAutomationPolicy automationPolicy,
            AiPromptBudgetPolicy promptBudgetPolicy,
            WorkspaceResolver workspaceResolver) {
        this.automationService = automationService;
        this.projectService = projectService;
        this.automationPolicy = automationPolicy;
        this.promptBudgetPolicy = promptBudgetPolicy;
        this.workspaceResolver = workspaceResolver;
    }

    @PostMapping("/requirements/{requirementId}/copilot")
    @Operation(summary = "Start or reuse a full, persisted Copilot analysis")
    public ResponseEntity<CopilotOperationView> analyzeRequirement(
            @PathVariable Long projectId,
            @PathVariable Long requirementId,
            @RequestBody(required = false) CopilotRunRequest request) {
        RequestScope scope = scope();
        preflightPrompt(projectId, requirementId, request, scope);
        CopilotOperationView operation = automationService.enqueueManual(
                projectId, requirementId, request, scope.username(), scope.context());
        return ResponseEntity.accepted()
                .location(operationLocation(projectId, operation.operationId()))
                .body(operation);
    }

    @GetMapping("/requirements/{requirementId}/copilot/latest")
    @Operation(summary = "Resume the newest Copilot or Autopilot operation for a requirement")
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
    @Operation(summary = "Read and resume a persisted Copilot operation")
    public CopilotOperationView getOperation(
            @PathVariable Long projectId,
            @PathVariable String operationId) {
        RequestScope scope = scope();
        return automationService.getOperation(
                projectId, operationId, scope.username(), scope.context());
    }

    @PostMapping("/copilot-operations/{operationId}/cancel")
    @Operation(summary = "Cancel all active passes of a Copilot operation")
    public CopilotOperationView cancel(
            @PathVariable Long projectId,
            @PathVariable String operationId) {
        RequestScope scope = scope();
        return automationService.cancelOperation(
                projectId, operationId, scope.username(), scope.context());
    }

    private void preflightPrompt(
            Long projectId,
            Long requirementId,
            CopilotRunRequest request,
            RequestScope scope) {
        RequirementView requirement = projectService.getRequirement(
                projectId, requirementId, scope.username(), scope.context());
        RequirementVersionView version = requirement.currentVersion();
        if (version == null || version.text() == null) {
            throw PortfolioException.conflict(
                    "Requirement has no immutable current version to analyze");
        }
        AiAutomationPolicy.RunSettings settings = automationPolicy.manual(request);
        try {
            promptBudgetPolicy.requireWithinBudget(version.text(), settings.target());
        } catch (PromptBudgetExceededException exception) {
            throw PortfolioException.promptBudgetExceeded(exception);
        }
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
