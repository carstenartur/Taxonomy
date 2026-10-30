package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.portfolio.service.RequirementIntakeService;
import com.taxonomy.workspace.service.WorkspaceResolver;
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
    public Response review(@RequestBody Request request) {
        return intake.review(request, resolver.resolveCurrentUsername(), resolver.resolveCurrentContext());
    }
}
