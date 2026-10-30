package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.RequirementIntakeService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

/** Intake is a read-only review. Applying a choice is a separate architecture command. */
@RestController
@RequestMapping("/api/architecture/lifecycle")
public class RequirementIntakeController {
    private final RequirementIntakeService intake;
    private final WorkspaceResolver resolver;
    public RequirementIntakeController(RequirementIntakeService intake, WorkspaceResolver resolver) {
        this.intake = intake; this.resolver = resolver;
    }
    @PostMapping("/intake-review")
    @Operation(summary="Review requirement text against authorized existing candidates",
            description="Reviews bounded requirement text against captured, authorized candidates and reports match coverage and provider availability. Supplied repository, workspace and branch pins must match the authenticated selected context. The review does not create or change a requirement or architecture.")
    public Response review(@RequestBody Request request,
                           @RequestParam(required = false) String repositoryId,
                           @RequestParam(required = false) String workspaceId,
                           @RequestParam(required = false) String workspaceScopeKey,
                           @RequestParam(required = false) String branch) {
        var context=resolver.resolveCurrentContext();
        // These pins assert the caller's displayed selection; they never grant access to another context.
        if ((repositoryId != null && !repositoryId.equals(context.repositoryId()))
                || (workspaceId != null && !WorkspaceOverlayScope.keyFor(workspaceId).equals(WorkspaceOverlayScope.keyFor(context.workspaceId())))
                || (workspaceScopeKey != null && !workspaceScopeKey.equals(WorkspaceOverlayScope.keyFor(context.workspaceId())))
                || (branch != null && !branch.equals(context.currentBranch())))
            throw PortfolioException.notFound("The exact selected review context is unavailable");
        return intake.review(request, resolver.resolveCurrentUsername(), context);
    }
}
