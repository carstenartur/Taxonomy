package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.AutopilotRequirementResponseAdvice;
import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ImportRequirementsResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutopilotRequirementResponseAdviceTest {

    @Mock private CopilotAutomationService automationService;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private MethodParameter returnType;
    @Mock private ServerHttpRequest request;
    @Mock private ServerHttpResponse response;

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");

    @Test
    void multiRequirementImportWritesOnlyABoundedCountHeader() {
        AutopilotRequirementResponseAdvice advice = advice(true, true);
        HttpHeaders headers = requestAndResponse(
                "/api/projects/41/requirements/import");
        CopilotOperationView first = operation("a".repeat(64));
        CopilotOperationView second = operation("b".repeat(64));
        when(automationService.tryAutopilot(
                41L, 7L, context.username(), context)).thenReturn(Optional.of(first));
        when(automationService.tryAutopilot(
                41L, 8L, context.username(), context)).thenReturn(Optional.of(second));

        Object body = new ImportRequirementsResult(
                List.of(requirement(7L), requirement(8L)), null);
        Object returned = advice.beforeBodyWrite(
                body,
                returnType,
                MediaType.APPLICATION_JSON,
                converterType(),
                request,
                response);

        assertThat(returned).isSameAs(body);
        assertThat(headers.getFirst("X-Taxonomy-Autopilot-Operation-Count"))
                .isEqualTo("2");
        assertThat(headers.containsKey("X-Taxonomy-Autopilot-Operation")).isFalse();
    }

    @Test
    void singleRequirementReturnsItsOperationIdAndCount() {
        AutopilotRequirementResponseAdvice advice = advice(true, true);
        HttpHeaders headers = requestAndResponse("/api/projects/41/requirements");
        String operationId = "c".repeat(64);
        when(automationService.tryAutopilot(
                41L, 7L, context.username(), context))
                .thenReturn(Optional.of(operation(operationId)));

        RequirementView body = requirement(7L);
        advice.beforeBodyWrite(
                body,
                returnType,
                MediaType.APPLICATION_JSON,
                converterType(),
                request,
                response);

        assertThat(headers.getFirst("X-Taxonomy-Autopilot-Operation-Count"))
                .isEqualTo("1");
        assertThat(headers.getFirst("X-Taxonomy-Autopilot-Operation"))
                .isEqualTo(operationId);
    }

    @Test
    void disabledSaveHookDoesNotDispatchEvenWhenExplicitAutopilotIsReady() {
        AutopilotRequirementResponseAdvice advice = advice(true, false);
        HttpHeaders headers = requestAndResponse("/api/projects/41/requirements");

        RequirementView body = requirement(7L);
        advice.beforeBodyWrite(
                body,
                returnType,
                MediaType.APPLICATION_JSON,
                converterType(),
                request,
                response);

        verify(automationService, never()).tryAutopilot(
                41L, 7L, context.username(), context);
        assertThat(headers).isEmpty();
    }

    private AutopilotRequirementResponseAdvice advice(
            boolean autopilotReady,
            boolean runAfterSave) {
        AiAutomationStatus status = mock(AiAutomationStatus.class);
        when(status.autopilotReady()).thenReturn(autopilotReady);
        when(status.runAfterRequirementSave()).thenReturn(runAfterSave);
        when(automationService.status()).thenReturn(status);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        return new AutopilotRequirementResponseAdvice(
                automationService, workspaceResolver);
    }

    private HttpHeaders requestAndResponse(String path) {
        HttpHeaders headers = new HttpHeaders();
        when(request.getURI()).thenReturn(URI.create(path));
        when(response.getHeaders()).thenReturn(headers);
        return headers;
    }

    private static RequirementView requirement(Long id) {
        return new RequirementView(
                id,
                41L,
                "REQ-" + id,
                "Requirement " + id,
                RequirementStatus.DRAFT,
                50,
                Criticality.MEDIUM,
                RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED,
                "architect",
                90L + id,
                null,
                Instant.now(),
                Instant.now(),
                null);
    }

    private static CopilotOperationView operation(String operationId) {
        CopilotOperationView operation = mock(CopilotOperationView.class);
        when(operation.operationId()).thenReturn(operationId);
        return operation;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Class<? extends HttpMessageConverter<?>> converterType() {
        return (Class) HttpMessageConverter.class;
    }
}
