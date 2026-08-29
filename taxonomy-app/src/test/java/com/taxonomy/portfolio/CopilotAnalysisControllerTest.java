package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.CopilotAnalysisController;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.model.AiCostPolicy;
import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotAnalysisControllerTest {

    @Mock private CopilotAutomationService automationService;
    @Mock private WorkspaceResolver workspaceResolver;

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");
    private CopilotAnalysisController controller;

    @BeforeEach
    void setUp() {
        controller = new CopilotAnalysisController(
                automationService,
                workspaceResolver);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
    }

    @Test
    void explicitRunReturnsAcceptedPersistentOperationLocation() {
        CopilotRunRequest request = new CopilotRunRequest(
                "custom-openai:test", null, 50, AnalysisAutomationProfile.FULL,
                1, false, true, true);
        CopilotOperationView operation = operation(
                AnalysisStatus.PENDING,
                "queued");
        when(automationService.enqueueManual(
                41L, 7L, request, context.username(), context)).thenReturn(operation);

        var response = controller.analyzeRequirement(41L, 7L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/projects/41/copilot-operations/" + "a".repeat(64));
        assertThat(response.getBody()).isSameAs(operation);
        verify(automationService).enqueueManual(
                41L, 7L, request, context.username(), context);
    }

    @Test
    void promptBudgetFailureRemainsAnAddressablePersistentOperation() {
        CopilotRunRequest request = new CopilotRunRequest(
                "mock:taxonomy-deterministic-v1", null, 50,
                AnalysisAutomationProfile.EXHAUSTIVE,
                2, true, true, true);
        CopilotOperationView failed = operation(
                AnalysisStatus.FAILED,
                "PROMPT_BUDGET_EXCEEDED: AI target mock:taxonomy-deterministic-v1 "
                        + "cannot accept the requirement.");
        when(automationService.enqueueManual(
                41L, 7L, request, context.username(), context)).thenReturn(failed);

        var response = controller.analyzeRequirement(41L, 7L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(failed);
        assertThat(response.getBody().status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(response.getBody().message()).contains("PROMPT_BUDGET_EXCEEDED");
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/projects/41/copilot-operations/" + "a".repeat(64));
    }

    @Test
    void latestReturnsNoContentWhenRequirementHasNoOperation() {
        when(automationService.latestOperation(
                41L, 7L, context.username(), context)).thenReturn(Optional.empty());

        assertThat(controller.latest(41L, 7L).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private static CopilotOperationView operation(
            AnalysisStatus status,
            String message) {
        return new CopilotOperationView(
                "a".repeat(64),
                41L,
                7L,
                AnalysisAutomationProfile.FULL,
                AiCostPolicy.METERED,
                false,
                "MOCK",
                50,
                1,
                status == AnalysisStatus.PENDING ? 0 : 1,
                status,
                true,
                true,
                null,
                message,
                List.of(),
                List.of("analysis"),
                List.of("review"));
    }
}
