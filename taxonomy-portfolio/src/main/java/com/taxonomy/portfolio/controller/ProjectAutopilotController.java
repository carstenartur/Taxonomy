package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotRunRequest;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotRunView;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotStatus;
import com.taxonomy.portfolio.service.ProjectAutopilotService;
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

/** Exact-tenant, bounded project-wide Autopilot surface. */
@RestController
@RequestMapping("/api/projects/{projectId}/autopilot")
@Tag(name = "Project Autopilot")
public class ProjectAutopilotController {

    private final ProjectAutopilotService autopilotService;
    private final WorkspaceResolver workspaceResolver;

    public ProjectAutopilotController(
            ProjectAutopilotService autopilotService,
            WorkspaceResolver workspaceResolver) {
        this.autopilotService = autopilotService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping
    @Operation(summary = "Read effective project Autopilot readiness and batch limit",
            description = "Reports the effective Autopilot policy, readiness and batch limits for this project. This GET does not enqueue any work or invoke a model.")
    public ProjectAutopilotStatus status(@PathVariable Long projectId) {
        RequestScope scope = scope();
        return autopilotService.status(
                projectId, scope.username(), scope.context());
    }

    @PostMapping("/run")
    @Operation(summary = "Start bounded Autopilot operations for project requirements",
            description = "Starts a bounded Autopilot batch for the authorized project according to the effective policy and supplied options. Returns 202 for accepted work; it does not claim that all project requirements have been analyzed.")
    public ResponseEntity<ProjectAutopilotRunView> run(
            @PathVariable Long projectId,
            @RequestBody(required = false) ProjectAutopilotRunRequest request) {
        RequestScope scope = scope();
        return ResponseEntity.accepted().body(autopilotService.run(
                projectId, request, scope.username(), scope.context()));
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
