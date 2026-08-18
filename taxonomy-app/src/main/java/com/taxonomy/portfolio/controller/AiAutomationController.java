package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operator/user visibility for Copilot and Autopilot policy. */
@RestController
@RequestMapping("/api/ai-automation")
@Tag(name = "AI Automation")
public class AiAutomationController {

    private final CopilotAutomationService automationService;

    public AiAutomationController(CopilotAutomationService automationService) {
        this.automationService = automationService;
    }

    @GetMapping
    @Operation(summary = "Read effective Copilot and Autopilot policy")
    public AiAutomationStatus status() {
        return automationService.status();
    }
}
