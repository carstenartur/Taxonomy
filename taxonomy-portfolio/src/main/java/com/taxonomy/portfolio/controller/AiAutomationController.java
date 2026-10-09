package com.taxonomy.portfolio.controller;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetCatalogView;
import com.taxonomy.analysis.service.AiTargetCatalogService;
import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operator/user visibility for Copilot, Autopilot and configured AI targets. */
@RestController
@RequestMapping("/api/ai-automation")
@Tag(name = "AI Automation")
public class AiAutomationController {

    private final CopilotAutomationService automationService;
    private final AiTargetCatalogService targetCatalog;

    public AiAutomationController(
            CopilotAutomationService automationService,
            AiTargetCatalogService targetCatalog) {
        this.automationService = automationService;
        this.targetCatalog = targetCatalog;
    }

    @GetMapping
    @Operation(summary = "Read effective Copilot and Autopilot policy",
            description = "Returns the effective Copilot and Autopilot enablement, policy and readiness for this installation. This inspection neither queues an analysis nor invokes a model.")
    public AiAutomationStatus status() {
        return automationService.status();
    }

    @GetMapping("/targets")
    @Operation(summary = "List credential-free, addressable AI targets and prompt budgets",
            description = "Lists addressable AI targets with their capabilities and prompt-budget information, excluding credentials. Availability is configuration-dependent; listing a target does not select it or start an HTTP model request.")
    public AiTargetCatalogView targets() {
        return targetCatalog.catalog();
    }
}
