package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ImportRequirementsResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts Autopilot only after a requirement write has returned successfully.
 * Failure to queue optional automation never rolls back the authoritative save.
 */
@ControllerAdvice(assignableTypes = ProjectPortfolioController.class)
public class AutopilotRequirementResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AutopilotRequirementResponseAdvice.class);
    private static final Pattern PROJECT_REQUIREMENT_PATH = Pattern.compile(
            "/api/projects/(\\d+)/requirements(?:/(\\d+)(?:/versions)?)?(?:/import)?$");
    private static final String OPERATION_HEADER = "X-Taxonomy-Autopilot-Operations";

    private final CopilotAutomationService automationService;
    private final WorkspaceResolver workspaceResolver;

    public AutopilotRequirementResponseAdvice(
            CopilotAutomationService automationService,
            WorkspaceResolver workspaceResolver) {
        this.automationService = automationService;
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        String method = returnType.getMethod() != null
                ? returnType.getMethod().getName() : "";
        return "createRequirement".equals(method)
                || "addRequirementVersion".equals(method)
                || "importRequirements".equals(method);
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!automationService.status().autopilotReady()) return body;
        try {
            RequestIdentity identity = identity(request.getURI().getPath());
            if (identity == null) return body;
            String username = workspaceResolver.resolveCurrentUsername();
            WorkspaceContext context = workspaceResolver.resolveCurrentContext();
            List<String> operations = new ArrayList<>();

            if (body instanceof RequirementView requirement) {
                enqueue(identity.projectId(), requirement.id(), username, context)
                        .ifPresent(operations::add);
            } else if (body instanceof RequirementVersionView) {
                if (identity.requirementId() != null) {
                    enqueue(identity.projectId(), identity.requirementId(), username, context)
                            .ifPresent(operations::add);
                }
            } else if (body instanceof ImportRequirementsResult imported
                    && imported.analysisJob() == null
                    && imported.requirements() != null) {
                for (RequirementView requirement : imported.requirements()) {
                    enqueue(identity.projectId(), requirement.id(), username, context)
                            .ifPresent(operations::add);
                }
            }

            if (!operations.isEmpty()) {
                response.getHeaders().add(OPERATION_HEADER, String.join(",", operations));
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Requirement save succeeded, but optional Autopilot dispatch failed", failure);
        }
        return body;
    }

    private Optional<String> enqueue(
            Long projectId,
            Long requirementId,
            String username,
            WorkspaceContext context) {
        return automationService.tryAutopilot(
                        projectId, requirementId, username, context)
                .map(CopilotOperationView::operationId);
    }

    private static RequestIdentity identity(String path) {
        Matcher matcher = PROJECT_REQUIREMENT_PATH.matcher(path);
        if (!matcher.matches()) return null;
        Long projectId = Long.valueOf(matcher.group(1));
        Long requirementId = matcher.group(2) != null
                ? Long.valueOf(matcher.group(2)) : null;
        return new RequestIdentity(projectId, requirementId);
    }

    private record RequestIdentity(Long projectId, Long requirementId) {
    }
}
