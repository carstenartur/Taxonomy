package com.taxonomy.portfolio;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetHealth;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetMode;
import com.taxonomy.analysis.dto.AiTargetDtos.PromptBudget;
import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.PromptBudgetExceededException;
import com.taxonomy.portfolio.controller.CopilotAnalysisController;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.AiCostPolicy;
import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.AiAutomationPolicy;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotAnalysisControllerTest {

    @Mock private CopilotAutomationService automationService;
    @Mock private ProjectPortfolioService projectService;
    @Mock private AiAutomationPolicy automationPolicy;
    @Mock private AiPromptBudgetPolicy promptBudgetPolicy;
    @Mock private WorkspaceResolver workspaceResolver;

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");
    private CopilotAnalysisController controller;

    @BeforeEach
    void setUp() {
        controller = new CopilotAnalysisController(
                automationService,
                projectService,
                automationPolicy,
                promptBudgetPolicy,
                workspaceResolver);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
    }

    @Test
    void explicitRunPreflightsTargetAndReturnsAcceptedPersistentOperationLocation() {
        CopilotRunRequest request = new CopilotRunRequest(
                target().targetId(), null, 50, AnalysisAutomationProfile.FULL,
                1, false, true, true);
        AiAutomationPolicy.RunSettings settings = settings();
        when(automationPolicy.manual(request)).thenReturn(settings);
        when(projectService.getRequirement(41L, 7L, context.username(), context))
                .thenReturn(requirement("Need secure communication"));
        when(promptBudgetPolicy.requireWithinBudget(
                "Need secure communication", settings.target())).thenReturn(settings.target());

        CopilotOperationView operation = operation(AnalysisStatus.PENDING);
        when(automationService.enqueueManual(
                41L, 7L, request, context.username(), context)).thenReturn(operation);

        var response = controller.analyzeRequirement(41L, 7L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/projects/41/copilot-operations/" + "a".repeat(64));
        assertThat(response.getBody()).isSameAs(operation);
    }

    @Test
    void oversizedPromptIsRejectedBeforeAJobIsCreated() {
        CopilotRunRequest request = new CopilotRunRequest(
                target().targetId(), null, 50, AnalysisAutomationProfile.FULL,
                1, false, true, true);
        AiAutomationPolicy.RunSettings settings = settings();
        when(automationPolicy.manual(request)).thenReturn(settings);
        when(projectService.getRequirement(41L, 7L, context.username(), context))
                .thenReturn(requirement("x".repeat(101)));
        when(promptBudgetPolicy.requireWithinBudget(
                "x".repeat(101), settings.target()))
                .thenThrow(new PromptBudgetExceededException(
                        settings.target(), 101, 101, 26));

        assertThatThrownBy(() -> controller.analyzeRequirement(41L, 7L, request))
                .isInstanceOfSatisfying(PortfolioException.class, exception -> {
                    assertThat(exception.getKind())
                            .isEqualTo(PortfolioException.Kind.PAYLOAD_TOO_LARGE);
                    assertThat(exception.getCode())
                            .isEqualTo(PromptBudgetExceededException.CODE);
                });
    }

    @Test
    void latestReturnsNoContentWhenRequirementHasNoOperation() {
        when(automationService.latestOperation(
                41L, 7L, context.username(), context)).thenReturn(Optional.empty());

        assertThat(controller.latest(41L, 7L).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private static RequirementView requirement(String text) {
        RequirementVersionView version = new RequirementVersionView(
                11L,
                1,
                text,
                "hash",
                "created for test",
                "architect",
                Instant.now(),
                null);
        return new RequirementView(
                7L,
                41L,
                "REQ-7",
                "Secure communication",
                null,
                50,
                null,
                null,
                null,
                "architect",
                11L,
                null,
                Instant.now(),
                Instant.now(),
                version);
    }

    private static AiTargetDescriptor target() {
        return new AiTargetDescriptor(
                "custom-openai:test",
                "Test AI / test-model",
                "CUSTOM_OPENAI",
                "test-model",
                AiTargetMode.REMOTE,
                AiTargetHealth.READY,
                true,
                false,
                false,
                new PromptBudget(100, 200, 25),
                "fingerprint",
                null);
    }

    private static AiAutomationPolicy.RunSettings settings() {
        return new AiAutomationPolicy.RunSettings(
                false,
                AnalysisAutomationProfile.FULL,
                "CUSTOM_OPENAI",
                target(),
                50,
                1,
                true,
                true,
                false);
    }

    private static CopilotOperationView operation(AnalysisStatus status) {
        return new CopilotOperationView(
                "a".repeat(64),
                41L,
                7L,
                AnalysisAutomationProfile.FULL,
                AiCostPolicy.METERED,
                false,
                "CUSTOM_OPENAI",
                50,
                1,
                0,
                status,
                true,
                true,
                null,
                "queued",
                List.of(),
                List.of("analysis"),
                List.of("review"));
    }
}
