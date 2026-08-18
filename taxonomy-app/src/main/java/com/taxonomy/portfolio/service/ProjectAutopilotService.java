package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotRunRequest;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotRunView;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotStatus;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded, exact-tenant project-level dispatch for explicitly enabled Autopilot. */
@Service
public class ProjectAutopilotService {

    private static final int MAX_CONFIGURED_BATCH = 500;

    private final CopilotAutomationService automationService;
    private final ProjectPortfolioService projectService;
    private final int maximumBatchRequirements;

    public ProjectAutopilotService(
            CopilotAutomationService automationService,
            ProjectPortfolioService projectService,
            @Value("${taxonomy.ai.autopilot.max-project-requirements:50}")
            int maximumBatchRequirements) {
        this.automationService = automationService;
        this.projectService = projectService;
        if (maximumBatchRequirements < 1
                || maximumBatchRequirements > MAX_CONFIGURED_BATCH) {
            throw new IllegalArgumentException(
                    "taxonomy.ai.autopilot.max-project-requirements must be between 1 and "
                            + MAX_CONFIGURED_BATCH);
        }
        this.maximumBatchRequirements = maximumBatchRequirements;
    }

    public ProjectAutopilotStatus status(
            Long projectId,
            String username,
            WorkspaceContext context) {
        List<RequirementView> requirements = projectService.listRequirements(
                projectId, username, context);
        AiAutomationStatus automation = automationService.status();
        return new ProjectAutopilotStatus(
                projectId,
                automation.autopilotReady(),
                automation.runAfterRequirementSave(),
                requirements.size(),
                maximumBatchRequirements,
                automation.reason());
    }

    public ProjectAutopilotRunView run(
            Long projectId,
            ProjectAutopilotRunRequest request,
            String username,
            WorkspaceContext context) {
        AiAutomationStatus automation = automationService.status();
        if (!automation.autopilotReady()) {
            throw PortfolioException.validation(automation.reason());
        }

        List<RequirementView> requirements = projectService.listRequirements(
                projectId, username, context);
        List<RequirementView> selected = selectRequirements(requirements, request);
        int requestedLimit = requestedLimit(request);
        if (selected.size() > requestedLimit) {
            throw PortfolioException.validation(
                    "Project Autopilot selected " + selected.size()
                            + " requirements, exceeding the bounded batch limit of "
                            + requestedLimit
                            + ". Supply explicit requirementIds or increase the operator limit.");
        }

        List<String> operationIds = new ArrayList<>(selected.size());
        for (RequirementView requirement : selected) {
            Optional<CopilotOperationView> operation = automationService.tryAutopilot(
                    projectId,
                    requirement.id(),
                    username,
                    context);
            operation.map(CopilotOperationView::operationId)
                    .ifPresentOrElse(
                            operationIds::add,
                            () -> {
                                throw PortfolioException.conflict(
                                        "Autopilot became unavailable during project dispatch");
                            });
        }
        return new ProjectAutopilotRunView(
                projectId,
                selected.size(),
                operationIds.size(),
                List.copyOf(operationIds),
                "Autopilot operations were persisted; every generated decision remains subject to human review.");
    }

    private List<RequirementView> selectRequirements(
            List<RequirementView> requirements,
            ProjectAutopilotRunRequest request) {
        if (request == null
                || request.requirementIds() == null
                || request.requirementIds().isEmpty()) {
            return requirements;
        }

        Set<Long> requestedIds = new LinkedHashSet<>();
        for (Long id : request.requirementIds()) {
            if (id == null) {
                throw PortfolioException.validation(
                        "Project Autopilot requirementIds must not contain null");
            }
            requestedIds.add(id);
        }
        Map<Long, RequirementView> byId = new LinkedHashMap<>();
        for (RequirementView requirement : requirements) {
            byId.put(requirement.id(), requirement);
        }

        List<RequirementView> selected = new ArrayList<>(requestedIds.size());
        for (Long id : requestedIds) {
            RequirementView requirement = byId.get(id);
            if (requirement == null) {
                throw PortfolioException.notFound(
                        "Requirement " + id + " was not found in project Autopilot scope");
            }
            selected.add(requirement);
        }
        return List.copyOf(selected);
    }

    private int requestedLimit(ProjectAutopilotRunRequest request) {
        if (request == null || request.maxRequirements() == null) {
            return maximumBatchRequirements;
        }
        int requested = request.maxRequirements();
        if (requested < 1 || requested > maximumBatchRequirements) {
            throw PortfolioException.validation(
                    "maxRequirements must be between 1 and the operator limit of "
                            + maximumBatchRequirements);
        }
        return requested;
    }
}
